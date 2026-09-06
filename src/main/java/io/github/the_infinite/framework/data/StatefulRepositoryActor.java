package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.hibernate.*;
import org.hibernate.engine.FetchTiming;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

import io.github.the_infinite.framework.data.types.ChangeResultModel;
import io.github.the_infinite.framework.data.types.PaginatedResult;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class StatefulRepositoryActor<TModel extends BaseEntity> extends RepositoryActor<TModel, Session> {
  StatefulRepositoryActor(SessionFactory sessionFactory, Class<TModel> modelType) {
    super(sessionFactory, modelType);
  }

  private void prepareDetached(
    Session session,
    RepositoryOptions<TModel> options,
    List<TModel> entities
  ) {
    for (final var entity : entities) {
      this.prepareDetached(session, options, entity);
    }
  }

  private void prepareDetached(Session session, RepositoryOptions<TModel> options, TModel entity) {
    Hibernate.initialize(entity);
    this.initializeLazyState(entity);
    final var detach = options != null && options.isDetach();
    if (!session.getTransaction().isActive() || detach) {
      session.detach(entity);
    }
  }

  /**
   * Force-loads any lazy (DELAYED) persistent attribute of the entity while still inside the
   * worker-thread session boundary. This guarantees that downstream code and JSON serialization
   * never trigger a blocking JDBC query on the Vert.x event loop, which previously froze the app.
   * <p>
   * In a stateful (attached) session the entity <em>could</em> still lazy-load after it is
   * returned, but that would happen on the Vert.x event loop (blocking), or after the session
   * is closed / the entity is detached (LazyInitializationException). Eagerly initializing
   * here makes the read path safe for detaching and for JSON serialization.
   * <p>
   * This is intentionally best-effort: if the metamodel cannot be resolved (e.g., the entity
   * is a {@code HibernateProxy} subclass such as {@code MoovableUser$HibernateProxy}) or an
   * individual attribute fails to initialize, the exception is swallowed and the entity is still
   * returned. Callers that require a specific lazy graph should use a fetch join / entity graph.
   */
  private void initializeLazyState(TModel entity) {
    initializeLazyState(entity, modelType, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
  }

  private void initializeLazyState(Object entity, Class<?> declaredType, Set<Object> visited, int depth) {
    if (entity == null || depth > 2 || !visited.add(entity))
      return; // depth cap + cycle guard
    try {
      Class<?> effectiveClass = Hibernate.getClass(entity);
      if (effectiveClass == null || effectiveClass == Object.class)
        effectiveClass = declaredType;

      final var metamodel = sessionFactory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
      EntityPersister persister;
      try {
        persister = metamodel.getEntityDescriptor(effectiveClass);
      } catch (UnknownEntityTypeException ex) {
        return; // not an entity type (embeddable/basic) — nothing to recurse into
      }

      final Object target = Hibernate.unproxy(entity);
      persister.forEachAttributeMapping(attribute -> {
        try {
          final var value = attribute.getValue(target);
          if (attribute.getMappedFetchOptions().getTiming() == FetchTiming.DELAYED && value != null) {
            Hibernate.initialize(value);
          }
          // Recurse into to-one associations even if eager — they may carry their own lazy fields.
          if (value != null && attribute instanceof ToOneAttributeMapping) {
            initializeLazyState(value, value.getClass(), visited, depth + 1);
          }
        } catch (Exception ignored) {
        }
      });
    } catch (Exception ignored) {
    }
  }

  @Override
  protected <T> Future<T> getOrCreateSession(@Nullable Session transaction, @NotNull RepositoryOptions<TModel> options, SessionBoundHandler<Session, T> handler) {
    final var context = Vertx.currentContext();
    if (transaction != null) {
      return wrap(() -> handler.handle(transaction, context), context);
    }

    final var correlation = options.getCorrelation();
    if (correlation != null) {
      return wrap(() -> handler.handle(correlation.getSession(sessionFactory), context), context);
    }

    return wrap(() -> sessionFactory.fromSession(session -> {
      session.addEventListeners(new DBSessionListener());
      return handler.handle(session, context);
    }), context);
  }

  private <T> Future<T> getOrCreateTransaction(@Nullable Session transaction, @NotNull RepositoryOptions<TModel> options, SessionBoundHandler<Session, T> handler) {
    if (transaction != null) {
      return this.getOrCreateSession(transaction, options, handler);
    }

    final var context = Vertx.currentContext();
    final var correlation = options.getCorrelation();
    if (correlation != null) {
      return wrap(() -> {
        final var session = correlation.getSession(sessionFactory);
        final var sessionTransaction = session.getTransaction();
        final var ownsTransaction = !sessionTransaction.isActive();
        if (ownsTransaction) {
          sessionTransaction.begin();
        }
        try {
          final var result = handler.handle(session, context);
          if (ownsTransaction) {
            session.flush();
            sessionTransaction.commit();
          }
          return result;
        } catch (Throwable cause) {
          if (ownsTransaction && sessionTransaction.isActive()) {
            try {
              sessionTransaction.rollback();
            } catch (Throwable rollbackFailure) {
              cause.addSuppressed(rollbackFailure);
            }
          }
          throw cause;
        }
      }, context);
    }

    return wrap(() -> sessionFactory.fromTransaction(session -> handler.handle(session, context)), context);
  }

  @Override
  public <ReturnType> Future<ReturnType> transaction(@NotNull RepositoryOptions<TModel> options, Function<Session, Future<ReturnType>> future) {
    final var correlation = options.getCorrelation();

    //? If we are inside a correlation context, reuse its request-scoped session so that
    //? the surrounding request can commit/rollback the work as one unit.
    if (correlation != null) {
      final var session = correlation.getSession(sessionFactory);
      final var transaction = session.getTransaction();
      final var ownsTransaction = !transaction.isActive();
      return this.runInTransaction(session, transaction, ownsTransaction, future);
    }

    //? Otherwise, fall back to a dedicated session owned entirely by this call.
    final var session = sessionFactory.openSession();
    return this.runInTransaction(session, session.getTransaction(), true, future);
  }

  public <ReturnType> Future<ReturnType> transaction(Function<Session, Future<ReturnType>> future) {
    return this.transaction(new RepositoryOptions<>(null), future);
  }

  private <ReturnType> Future<ReturnType> runInTransaction(Session session, Transaction transaction, boolean ownsTransaction, Function<Session, Future<ReturnType>> future) {
    final var promise = Promise.<ReturnType>promise();
    try {
      if (ownsTransaction) {
        transaction.begin();
      }

      future.apply(session)
        .onFailure(cause -> {
          try {
            if (ownsTransaction && transaction.isActive()) {
              transaction.rollback();
            }
          } catch (Throwable rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
          } finally {
            if (ownsTransaction) {
              session.close();
            }
          }
          promise.fail(cause);
        })
        .onSuccess(result -> {
          try {
            if (ownsTransaction) {
              session.flush();
              transaction.commit();
            }
            promise.complete(result);
          } catch (Throwable cause) {
            try {
              if (ownsTransaction && transaction.isActive()) {
                transaction.rollback();
              }
            } catch (Throwable rollbackFailure) {
              cause.addSuppressed(rollbackFailure);
            }
            promise.fail(cause);
          } finally {
            if (ownsTransaction) {
              session.close();
            }
          }
        });
    } catch (Throwable synchronous) {
      try {
        if (ownsTransaction && transaction.isActive()) {
          transaction.rollback();
        }
      } catch (Throwable rollbackFailure) {
        synchronous.addSuppressed(rollbackFailure);
      }
      try {
        if (ownsTransaction) {
          session.close();
        }
      } catch (Throwable closeFailure) {
        synchronous.addSuppressed(closeFailure);
      }
      promise.fail(synchronous);
    }
    return promise.future();
  }


  @Override
  public Future<Long> getCount(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, start()), usedCursor);
      return session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).getSingleResult();
    });
  }

  @Override
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var pureFilter = Objects.requireNonNullElse(filter, this.start());
    final var usedFilters = buildWithCursor(pureFilter, usedCursor);
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      if (usedFilters.query().getOrderList().isEmpty()) {
        final var cb = usedFilters.builder();
        usedFilters.orderBy(cb.asc(usedFilters.root().get("id")));
      }
      final var selectQuery = usedFilters.select().query();
      final var countQuery = pureFilter.count();
      final var count = Objects.requireNonNullElse(
        session.createQuery(countQuery).getSingleResultOrNull(),
        0L
      );
      final var rows = session.createQuery(selectQuery).setMaxResults(usedLimit + 1).getResultList();
      final var hasNext = rows.size() > usedLimit;
      final var data = hasNext ? new ArrayList<>(rows.subList(0, usedLimit)) : rows;

      //? Okay then.
      this.prepareDetached(session, options, data);
      String nextCursor = null;
      try {
        if (hasNext) {
          nextCursor = makeCursor(data, usedLimit, usedCursor == null ? null : parseCursor(usedCursor));
        }
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
      return new PaginatedResult<>(data, usedLimit, count, usedCursor, nextCursor);
    });
  }


  @Override
  public Future<List<TModel>> getAll(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).getResultList();
      this.prepareDetached(session, options, data);
      return data;
    });
  }

  @Override
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList();
      this.prepareDetached(session, options, data);
      return data;
    });
  }

  @Override
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor).distinct();
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList();
      this.prepareDetached(session, options, data);
      return data;
    });
  }

  @Override
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);
    final var q = usedFilters.select().query();
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      try {
        final var entity = session.createQuery(q).setMaxResults(1).getSingleResultOrNull();
        if (entity == null) {
          return Optional.empty();
        }
        this.prepareDetached(session, options, entity);
        return Optional.of(entity);
      } catch (EntityNotFoundException ignored) {
        return Optional.empty();
      }
    });
  }

  @Override
  public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.getOrCreateSession(transaction, options, (session, _) -> {
      try {
        final var entity = session.find(modelType, id, lockMode);
        if (entity == null) {
          return Optional.empty();
        }
        this.prepareDetached(session, options, entity);
        return Optional.of(entity);
      } catch (EntityNotFoundException ignored) {
        return Optional.empty();
      }
    });
  }

  @Override
  public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating many records."));
    }
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      items.forEach(item -> {
        item.setUid(UUID.randomUUID());
        if (item instanceof BaseAuditableEntity<?> ae) {
          ae.setCreatedBy(options.getUser());
          ae.setUpdatedBy(options.getUser());
        }
        session.persist(item);
      });
      return items;
    });
  }

  @Override
  public Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating a record."));
    }
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseAuditableEntity<?> ae) {
        ae.setCreatedBy(options.getUser());
        ae.setUpdatedBy(options.getUser());
      }
      session.persist(item);
      return Optional.of(item);
    });
  }

  @Override
  public Future<List<TModel>> upsertMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when upserting many records."));
    }
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var result = new ArrayList<TModel>(items.size());
      for (final var item : items) {
        result.add(this.upsertEntity(session, options, item));
      }
      session.flush();
      return result;
    });
  }

  @Override
  public Future<Optional<TModel>> upsertOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when upserting a record."));
    }
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var upserted = this.upsertEntity(session, options, item);
      session.flush();
      return Optional.of(upserted);
    });
  }

  private TModel upsertEntity(Session session, RepositoryOptions<TModel> options, TModel item) {
    final var existing = item.getId() == null ? null : session.find(modelType, item.getId());
    if (existing == null) {
      if (item.getUid() == null) {
        item.setUid(UUID.randomUUID());
      }
      if (item instanceof BaseAuditableEntity<?> ae) {
        ae.setCreatedBy(options.getUser());
        ae.setUpdatedBy(options.getUser());
      }
      session.persist(item);
      return item;
    }
    if (item instanceof BaseAuditableEntity<?> ae) {
      ae.setUpdatedBy(options.getUser());
    }
    return session.merge(item);
  }

  @Override
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating many records."));
    }
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList();
      final var changeList = new ArrayList<TModel>();
      for (final var entity : data) {
        if (valueChanger.change(session, entity)) {
          if (entity instanceof BaseAuditableEntity<?> ae) {
            ae.setUpdatedBy(options.getUser());
          }
          changeList.add(entity);
          session.merge(entity);
        }
      }
      session.flush();
      return new ChangeResultModel<>(changeList);
    });
  }

  @Override
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getSingleResultOrNull();
      if (data == null) {
        return Optional.empty();
      }
      if (!valueChanger.change(session, data)) {
        return Optional.of(data);
      }
      if (data instanceof BaseAuditableEntity<?> ae) {
        ae.setUpdatedBy(options.getUser());
      }
      session.merge(data);
      session.flush();
      return Optional.of(data);
    });
  }

  @Override
  public Future<Optional<TModel>> updateById(long id, @NotNull ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var data = session.find(modelType, id);
      if (data == null) {
        return Optional.empty();
      }
      if (!valueChanger.change(session, data)) {
        return Optional.of(data);
      }
      if (data instanceof BaseAuditableEntity<?> ae) {
        ae.setUpdatedBy(options.getUser());
      }
      session.merge(data);
      session.flush();
      return Optional.of(data);
    });
  }

  @Override
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);
      final var result = session.createMutationQuery(usedFilters.query()).executeUpdate();
      session.flush();
      return result;
    });
  }

  @Override
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    final var usedCursor = options.getCursor();
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);
      final var data = session.createMutationQuery(usedFilters.query()).executeUpdate();
      session.flush();
      return data > 0;
    });
  }

  @Override
  public Future<Optional<TModel>> deleteById(long id, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.getOrCreateTransaction(transaction, options, (session, _) -> {
      final var data = session.find(modelType, id);
      if (data == null) {
        return Optional.empty();
      }
      session.remove(data);
      session.flush();
      return Optional.of(data);
    });
  }
}

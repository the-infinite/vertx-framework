package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.hibernate.CacheMode;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
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
import jakarta.persistence.LockModeType;

public final class StatelessRepositoryActor<TModel extends BaseEntity> extends RepositoryActor<TModel, StatelessSession> {
  StatelessRepositoryActor(SessionFactory sessionFactory, Class<TModel> modelType) {
    super(sessionFactory, modelType);
  }

  @Override
  protected <T> Future<T> getOrCreateSession(@Nullable StatelessSession transaction,
                                             SessionBoundHandler<StatelessSession, T> handler) {
    final var context = Vertx.currentContext();
    if (transaction != null) {
      return wrap(() -> handler.handle(transaction, context), context);
    }

    return wrap(() -> sessionFactory.fromStatelessSession(session -> handler.handle(session, context)), context);
  }

  private <T> Future<T> getOrCreateTransaction(@Nullable StatelessSession transaction,
                                               SessionBoundHandler<StatelessSession, T> handler) {
    if (transaction != null) {
      return this.getOrCreateSession(transaction, handler);
    }

    final var context = Vertx.currentContext();
    return wrap(() -> sessionFactory.fromStatelessTransaction(session -> handler.handle(session, context)), context);
  }

  @Override
  public <ReturnType> Future<ReturnType> transaction(Function<StatelessSession, Future<ReturnType>> future) {
    final var session = sessionFactory.openStatelessSession();
    final var transaction = session.beginTransaction();
    final var promise = Promise.<ReturnType>promise();
    try {
      future.apply(session)
        .onFailure(cause -> {
          try {
            if (transaction.isActive()) {
              transaction.rollback();
            }
          } catch (Throwable rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
          } finally {
            session.close();
          }
          promise.fail(cause);
        })
        .onSuccess(result -> {
          try {
            transaction.commit();
            promise.complete(result);
          } catch (Throwable cause) {
            try {
              if (transaction.isActive()) {
                transaction.rollback();
              }
            } catch (Throwable rollbackFailure) {
              cause.addSuppressed(rollbackFailure);
            }
            promise.fail(cause);
          } finally {
            session.close();
          }
        });
    } catch (Throwable synchronous) {
      try {
        if (transaction.isActive()) {
          transaction.rollback();
        }
      } catch (Throwable rollbackFailure) {
        synchronous.addSuppressed(rollbackFailure);
      }
      try {
        session.close();
      } catch (Throwable closeFailure) {
        synchronous.addSuppressed(closeFailure);
      }
      promise.fail(synchronous);
    }
    return promise.future();
  }

  @Override
  public Future<Long> getCount(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    return this.getOrCreateSession(transaction, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, start()), usedCursor);
      return session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).setCacheMode(CacheMode.IGNORE).setCacheable(false).getSingleResult();
    });
  }

  @Override
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);
    return this.getOrCreateSession(transaction, (session, _) -> {
      final var count = session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).setCacheMode(CacheMode.IGNORE).setCacheable(false).getSingleResult();
      final var data = session.createQuery(usedFilters.select().query()).setCacheMode(CacheMode.IGNORE).setCacheable(false).setMaxResults(usedLimit).getResultList();
      String nextCursor = null;
      try {
        if (data.size() == usedLimit) {
          nextCursor = makeCursor(data, usedLimit, usedCursor == null ? null : parseCursor(usedCursor));
        }
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
      return new PaginatedResult<>(data, usedLimit, count, usedCursor, nextCursor);
    });
  }

  @Override
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);
    return this.getOrCreateSession(transaction, (session, _) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList());
  }

  @Override
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor).distinct();
    return this.getOrCreateSession(transaction, (session, _) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList());
  }

  @Override
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);
    final var q = usedFilters.select().query();
    return this.getOrCreateSession(transaction, (session, _) -> {
      final var data = session.createQuery(q).setCacheMode(CacheMode.IGNORE).setCacheable(false).setMaxResults(1).getResultList();
      return data.isEmpty() ? Optional.empty() : Optional.ofNullable(data.getFirst());
    });
  }

  @Override
  public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @Nullable StatelessSession transaction) {
    return this.getOrCreateSession(transaction, (session, _) -> Optional.ofNullable(session.get(modelType, id, org.hibernate.LockMode.fromJpaLockMode(lockMode))));
  }

  @Override
  public Future<List<TModel>> createMany(@NotNull List<TModel> items,
                                         @NotNull RepositoryOptions<TModel> options,
                                         @Nullable StatelessSession transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating many records."));
    }
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      items.forEach(item -> {
        item.setUid(UUID.randomUUID());
        if (item instanceof BaseAuditableEntity<?> ae) {
          ae.setCreatedBy(options.getUser());
          ae.setUpdatedBy(options.getUser());
        }
        session.insert(item);
      });
      return items;
    });
  }

  @Override
  public Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating a record."));
    }
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseAuditableEntity<?> ae) {
        ae.setCreatedBy(options.getUser());
        ae.setUpdatedBy(options.getUser());
      }
      session.insert(item);
      return Optional.of(item);
    });
  }

  @Override
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating many records."));
    }
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).setCacheMode(CacheMode.IGNORE).setCacheable(false).setMaxResults(usedLimit).getResultList();
      final var changeList = new ArrayList<TModel>();
      for (final var entity : data) {
        if (valueChanger.change(session, entity)) {
          if (entity instanceof BaseAuditableEntity<?> ae) {
            ae.setUpdatedBy(options.getUser());
          }
          changeList.add(entity);
          session.update(entity);
        }
      }
      return new ChangeResultModel<>(changeList);
    });
  }

  @Override
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var data = session.createQuery(usedFilters.select().query()).setCacheMode(CacheMode.IGNORE).setCacheable(false).setMaxResults(usedLimit).getResultList();
      if (data.isEmpty()) {
        return Optional.empty();
      }
      final var entity = data.getFirst();
      if (!valueChanger.change(session, entity)) {
        return Optional.of(entity);
      }
      if (entity instanceof BaseAuditableEntity<?> ae) {
        ae.setUpdatedBy(options.getUser());
      }
      session.update(entity);
      return Optional.of(entity);
    });
  }

  @Override
  public Future<Optional<TModel>> updateById(long id, @NotNull ChangeEffectorFunction<TModel, StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var data = session.get(modelType, id);
      if (data == null) {
        return Optional.empty();
      }
      if (!valueChanger.change(session, data)) {
        return Optional.of(data);
      }
      if (data instanceof BaseAuditableEntity<?> ae) {
        ae.setUpdatedBy(options.getUser());
      }
      session.update(data);
      return Optional.of(data);
    });
  }

  @Override
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);
      return session.createMutationQuery(usedFilters.query()).executeUpdate();
    });
  }

  @Override
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                   @Nullable RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);
      final var data = session.createMutationQuery(usedFilters.query()).executeUpdate();
      return data > 0;
    });
  }

  @Override
  public Future<Optional<TModel>> deleteById(long id, @Nullable StatelessSession transaction) {
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var data = session.get(modelType, id);
      if (data == null) {
        return Optional.empty();
      }
      session.delete(data);
      return Optional.of(data);
    });
  }
}

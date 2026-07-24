package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.hibernate.Hibernate;
import org.hibernate.reactive.mutiny.Mutiny;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.data.types.ChangeResultModel;
import io.github.the_infinite.framework.data.types.PaginatedResult;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.github.the_infinite.framework.utils.AtomHolder;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;

public final class StatefulRepositoryActor<TModel extends BaseEntity> extends RepositoryActor<TModel, Mutiny.Session> {
  StatefulRepositoryActor(Mutiny.SessionFactory sessionFactory, Class<TModel> modelType) {

    super(sessionFactory, modelType);
  }

  @Override
  protected <T> Future<T> getOrCreateSession(@Nullable Mutiny.Session transaction, SessionBoundHandler<Mutiny.Session, T> handler) {
    final var globalVertx = ConfigurationRegistrant.vertx();
    final var context = globalVertx.getOrCreateContext();
    final var currentContext = Vertx.currentContext();
    final var promise = Promise.<T>promise();

    if (transaction != null) {
      try {
        wrap(handler.handle(transaction, currentContext), currentContext).onSuccess(promise::succeed).onFailure(promise::fail);
      } catch (Throwable t) {
        promise.fail(t);
      }
      return promise.future();
    }

    if (context == null) {
      return Future.failedFuture(new IllegalStateException("Cannot create a session for a null context"));
    }

    context.runOnContext(ignored -> {
      try {
        wrap(sessionFactory.withSession((session) -> handler.handle(session, context)), context).onSuccess(promise::succeed).onFailure(promise::fail);
      } catch (Throwable t) {
        promise.fail(t);
      }
    });

    return promise.future();
  }

  private <T> Future<T> getOrCreateTransaction(@Nullable Mutiny.Session transaction, SessionBoundHandler<Mutiny.Session, T> handler) {
    if (transaction != null) {
      return this.getOrCreateSession(transaction, handler);
    }

    final var globalVertx = ConfigurationRegistrant.vertx();
    final var context = globalVertx.getOrCreateContext();
    final var promise = Promise.<T>promise();

    if (context == null) {
      return Future.failedFuture(new IllegalStateException("Cannot create a session for a null context"));
    }

    context.runOnContext(ignored -> {
      try {
        wrap(sessionFactory.withTransaction((session, _) -> handler.handle(session, context)), context).onSuccess(promise::succeed).onFailure(promise::fail);
      } catch (Throwable t) {
        promise.fail(t);
      }
    });
    return promise.future();
  }

  @Override
  public <ReturnType> Future<ReturnType> transaction(Function<Mutiny.Session, Future<ReturnType>> future) {
    final Context context = Vertx.currentContext();
    final var uni = sessionFactory.withTransaction((session, tx) -> Uni.createFrom().<ReturnType>emitter(em -> {
      final Future<ReturnType> transactionFuture;
      try {
        transactionFuture = future.apply(session);
      } catch (Throwable t) {
        tx.markForRollback();
        em.fail(t);
        return;
      }

      transactionFuture.andThen(transactionResult -> {
        final Runnable completeTransaction = () -> {
        try {
          if (transactionResult.failed()) {
            tx.markForRollback();
            em.fail(transactionResult.cause());
            return;
          }

          //? Something about this is beautiful.
          em.complete(transactionResult.result());
        } catch (Throwable t) {
          tx.markForRollback();
          em.fail(t);
        }
        };

        if (context != null && Vertx.currentContext() != context) {
          context.runOnContext(_ -> completeTransaction.run());
        } else {
          completeTransaction.run();
        }
      });
    }));
    return wrap(uni, context);
  }

  @Override
  public Future<Long> getCount(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    final var usedCursor = options == null ? null : options.getCursor();

    return this.getOrCreateSession(transaction, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, start()), usedCursor);
      return session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).getSingleResult();
    });
  }

  @Override
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);
    final var countAtom = new AtomHolder<Long>();

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, _) -> session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).getSingleResult().chain(count -> {
      countAtom.set(count);
      return session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList();
    }).map(Unchecked.function(data -> {
      String nextCursor = null;

      try {
        if (data.size() == usedLimit) {
          nextCursor = makeCursor(data, usedLimit, usedCursor == null ? null : parseCursor(usedCursor));
        }
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }

      return new PaginatedResult<>(data, usedLimit, countAtom.get(), usedCursor, nextCursor);
    })));
  }

  @Override
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, _) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList());
  }

  @Override
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor).distinct();

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, _) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList());
  }

  @Override
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = 1;
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);

    //? Now, run a query with that session.
    final var q  = usedFilters.select().query();
    return this.getOrCreateSession(transaction, (session, _) ->
      session.createQuery(q)
        .setMaxResults(usedLimit)
        .getSingleResultOrNull()
        .chain(entity -> {
          if (entity == null) {
            return Uni.createFrom().item(Optional.<TModel>empty());
          }

          // Initialize all associations while session is still active
          return session.flush().map(ignored -> {
            Hibernate.initialize(entity);
            session.detach(entity);
            return Optional.of(entity);
          });
        })
        .onFailure(EntityNotFoundException.class).recoverWithItem(Optional.empty())
    );
  }

  @Override
  public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @Nullable Mutiny.Session transaction) {
    return this.getOrCreateSession(transaction, (session, _) ->
      session.find(modelType, id, lockMode)
        .chain(entity -> {
          if (entity == null) {
            return Uni.createFrom().item(Optional.<TModel>empty());
          }

          // Initialize all associations while session is still active
          return session.flush().map(_ -> {
            Hibernate.initialize(entity);
            session.detach(entity);
            return Optional.of(entity);
          });
        })
        .onFailure(EntityNotFoundException.class).recoverWithItem(Optional.empty())
    );
  }

  @Override
  public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? Options are not allowed to be null here.
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating many records."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, _) -> session.persistAll(items.stream().peek(item -> {
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseAuditableEntity ae) {
        ae.setCreatedBy(options.getUser());
        ae.setUpdatedBy(options.getUser());
      }
    }).toArray()).map(_ -> items));
  }

  @Override
  public Future<Optional<TModel>> createOne(@NotNull TModel item,
                                      @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? If options are not clearly defined...
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating a record."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, _) -> {
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseAuditableEntity ae) {
        ae.setCreatedBy(options.getUser());
        ae.setUpdatedBy(options.getUser());
      }

      //? Moving forward...
      return session.persist(item).map(_ -> Optional.of(item));
    });
  }

  @Override
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, Mutiny.Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? Options are not allowed to be null here.
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating many records."));
    }

    //? This is fine.
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);

    //? Now, run a query with that session.
    return this.getOrCreateTransaction(transaction, (session, _) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList().chain(data -> {
      final var changeList = new ArrayList<TModel>();

      //? Change each entity herein.
      for (final var entity : data) {
        if (valueChanger.change(session, entity)) {
          if (entity instanceof BaseAuditableEntity ae) {
            ae.setUpdatedBy(options.getUser());
          }
          changeList.add(entity);
        }
      }

      //? Since we have changed it...
      if (changeList.isEmpty()) {
        return Uni.createFrom().item(new ChangeResultModel<>(changeList));
      }

      return session.mergeAll(changeList.toArray()).chain(_ -> session.flush()).map(_ -> new ChangeResultModel<>(changeList));
    }));
  }

  @Override
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, Mutiny.Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? This is fine.
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);

    //? Options are not allowed to be null here.
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateTransaction(transaction, (session, _) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getSingleResult().chain(data -> {
      if (data == null) {
        return Uni.createFrom().item(Optional.empty());
      }

      if (!valueChanger.change(session, data)) {
        return Uni.createFrom().item(Optional.of(data));
      }

      if (data instanceof BaseAuditableEntity ae) {
        ae.setUpdatedBy(options.getUser());
      }

      return session.merge(data).chain(_ -> session.flush()).map(_ -> Optional.of(data));
    }));
  }

  @Override
  public Future<Optional<TModel>> updateById(long id, @NotNull ChangeEffectorFunction<TModel, Mutiny.Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? Options are not allowed to be null here.
    if (options.getUser() == null && BaseAuditableEntity.class.isAssignableFrom(this.modelType)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateTransaction(transaction, (session, _) -> session.find(modelType, id).chain(data -> {
      if (data == null) {
        return Uni.createFrom().item(Optional.empty());
      }

      if (!valueChanger.change(session, data)) {
        return Uni.createFrom().item(Optional.of(data));
      }

      if (data instanceof BaseAuditableEntity ae) {
        ae.setUpdatedBy(options.getUser());
      }

      return session.merge(data).chain(_ -> session.flush()).map(_ -> Optional.of(data));
    }));
  }

  @Override
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    final var usedCursor = options == null ? null : options.getCursor();

    //? Now, run a query with that session.
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);

      //? Moving forward...
      return session.createMutationQuery(usedFilters.query()).executeUpdate();
    });
  }

  @Override
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.Session transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();

    //? Now, run a query with that session.
    return this.getOrCreateTransaction(transaction, (session, _) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);

      //? Moving forward...
      return session.createMutationQuery(usedFilters.query()).executeUpdate().map(data -> data != null && data > 0);
    });
  }

  @Override
  public Future<Optional<TModel>> deleteById(long id, @Nullable Mutiny.Session transaction) {
    //? Now, run a query with that session.
    return this.getOrCreateTransaction(transaction, (session, _) -> session.find(modelType, id).chain(data -> {
      if (data == null) {
        return Uni.createFrom().item(Optional.empty());
      }

      return session.remove(data).chain(_ -> session.flush()).map(_ -> Optional.of(data));
    }));
  }
}

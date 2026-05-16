package io.github.the_infinite.core.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.the_infinite.core.ConfigurationRegistrant;
import io.github.the_infinite.core.data.types.ChangeResultModel;
import io.github.the_infinite.core.data.types.PaginatedResult;
import io.github.the_infinite.core.data.types.RepositoryOptions;
import io.github.the_infinite.core.utils.AtomHolder;

import org.hibernate.reactive.mutiny.Mutiny;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import jakarta.persistence.LockModeType;

public final class StatelessRepositoryActor<TModel extends BaseEntity> extends RepositoryActor<TModel, Mutiny.StatelessSession> {
  StatelessRepositoryActor(Mutiny.SessionFactory sessionFactory,
                           Class<TModel> modelType) {
    super(sessionFactory, modelType);
  }

  @Override
  protected <T> Future<T> getOrCreateSession(@Nullable Mutiny.StatelessSession transaction,
                                             SessionBoundHandler<Mutiny.StatelessSession, T> handler) {
    final var globalVertx = ConfigurationRegistrant.vertx();
    final var context = globalVertx.getOrCreateContext();
    final var promise = Promise.<T>promise();

    if (context == null) {
      return Future.failedFuture(new IllegalStateException("Cannot create a session for a null context"));
    }

    context.runOnContext(v -> {
      if (transaction != null) {
        wrap(handler.handle(transaction, context)).onSuccess(promise::succeed).onFailure(promise::fail);
      }

      //? Okay then.
      else {
        wrap(sessionFactory.withStatelessSession((session) -> handler.handle(session, context))).onSuccess(promise::succeed).onFailure(promise::fail);
      }
    });

    return promise.future();
  }

  @Override
  public <ReturnType> Future<ReturnType> transaction(Function<Mutiny.StatelessSession, Future<ReturnType>> future) {
    final var uni = sessionFactory.withStatelessTransaction((session, tx) -> Uni.createFrom().<ReturnType>emitter(em -> future.apply(session).andThen(transactionResult -> {
      if (transactionResult.failed()) {
        tx.markForRollback();
        em.fail(transactionResult.cause());
        return;
      }

      //? Something about this is beautiful.
      em.complete(transactionResult.result());
    })));
    return wrap(uni);
  }

  @Override
  public Future<Long> getCount(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();

    return this.getOrCreateSession(transaction, (session, context) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, start()), usedCursor);
      return session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).getSingleResult();
    });
  }

  @Override
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
//? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);
    final var countAtom = new AtomHolder<Long>();

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> session.createQuery(usedFilters.count(usedFilters.select().query().getRestriction())).getSingleResult().chain(count -> {
      countAtom.set(count);
      return session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList();
    }).map(data -> {
      String nextCursor = null;

      try {
        if (data.size() == usedLimit) {
          nextCursor = makeCursor(data, usedLimit, usedCursor == null ? null : parseCursor(usedCursor));
        }
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }

      return new PaginatedResult<>(data, usedLimit, countAtom.get(), usedCursor, nextCursor);
    }));
  }

  @Override
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor);

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList());
  }

  @Override
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = options == null ? 30 : options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start()), usedCursor).distinct();

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList());
  }

  @Override
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();
    final var usedLimit = 1;
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getSingleResult().map(Optional::ofNullable));
  }

  @Override
  public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @Nullable Mutiny.StatelessSession transaction) {
    return this.getOrCreateSession(transaction,
      (session, context) -> session.get(modelType, id, lockMode).map(Optional::ofNullable));
  }

  @Override
  public Future<List<TModel>> createMany(@NotNull List<TModel> items,
                                         @NotNull RepositoryOptions<TModel> options,
                                         @Nullable Mutiny.StatelessSession transaction) {
    //? Options are not allowed to be null here.
    if (options.getUserId() < 1 && this.modelType.getSuperclass().equals(BaseAuditableEntity.class)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating many records."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction,
      (session, context) -> session.insertAll(items.stream().peek(item -> {
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseAuditableEntity ae) {
        ae.setCreatedById(options.getUserId());
        ae.setUpdatedById(options.getUserId());
      }
    }).toArray()).map(v -> items));
  }

  @Override
  Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? If options are not clearly defined...
    if (options.getUserId() < 1 && this.modelType.getSuperclass().equals(BaseAuditableEntity.class)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when creating a record."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> {
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseAuditableEntity ae) {
        ae.setCreatedById(options.getUserId());
        ae.setUpdatedById(options.getUserId());
      }

      //? Moving forward...
      return session.insert(item).map(v -> Optional.of(item));
    });
  }

  @Override
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, Mutiny.StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? Options are not allowed to be null here.
    if (options.getUserId() < 1 && this.modelType.getSuperclass().equals(BaseAuditableEntity.class)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating many records."));
    }

    //? This is fine.
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getResultList().chain(data -> {
      final var changeList = new ArrayList<TModel>();

      //? Change each entity herein.
      for (final var entity : data) {
        if (valueChanger.change(session, entity)) {
          if (entity instanceof BaseAuditableEntity ae) {
            ae.setUpdatedById(options.getUserId());
          }
          changeList.add(entity);
        }
      }

      //? Since we have changed it...
      return Uni.createFrom().item(changeList);
    }).chain(changeList -> session.updateAll(changeList.toArray()).map(list -> new ChangeResultModel<>(changeList))));
  }

  @Override
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, Mutiny.StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? This is fine.
    final var usedCursor = options.getCursor();
    final var usedLimit = options.getLimit();
    final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.start().where()), usedCursor);

    //? Options are not allowed to be null here.
    if (options.getUserId() < 1 && this.modelType.getSuperclass().equals(BaseAuditableEntity.class)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> session.createQuery(usedFilters.select().query()).setMaxResults(usedLimit).getSingleResult().map(data -> {
      //? This went well.
      if (data != null && valueChanger.change(session, data)) {
        if (data instanceof BaseAuditableEntity ae) {
          ae.setUpdatedById(options.getUserId());
        }
      }

      //? Fair here.
      return Optional.ofNullable(data);
    }).chain(data -> {
      if (data.isPresent()) {
        return session.update(data.get()).map(v -> data);
      }

      return Uni.createFrom().item(data);
    }));
  }

  @Override
  public Future<Optional<TModel>> updateById(long id, @NotNull ChangeEffectorFunction<TModel, Mutiny.StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? Options are not allowed to be null here.
    if (options.getUserId() < 1 && this.modelType.getSuperclass().equals(BaseAuditableEntity.class)) {
      return Future.failedFuture(new IllegalArgumentException("Repository options cannot be null when updating a record."));
    }

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction,
      (session, context) -> session.get(modelType, id,
        LockModeType.OPTIMISTIC_FORCE_INCREMENT).map(data -> {
      if (data instanceof BaseAuditableEntity ae) {
        ae.setUpdatedById(options.getUserId());
      }

      return Optional.ofNullable(data);
    }).chain(data -> {
      if (data.isPresent()) {
        return session.update(data.get()).map(v -> data);
      }

      return Uni.createFrom().item(data);
    }));
  }

  @Override
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    final var usedCursor = options == null ? null : options.getCursor();

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);

      //? Moving forward...
      return session.createMutationQuery(usedFilters.query()).executeUpdate();
    });
  }

  @Override
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                   @Nullable RepositoryOptions<TModel> options, @Nullable Mutiny.StatelessSession transaction) {
    //? This is fine.
    final var usedCursor = options == null ? null : options.getCursor();

    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> {
      final var usedFilters = buildWithCursor(Objects.requireNonNullElse(filter, this.startDelete().where()), usedCursor);

      //? Moving forward...
      return session.createMutationQuery(usedFilters.query()).executeUpdate().chain(data -> {
        if (data == null || data < 1) {
          return Uni.createFrom().item(false);
        }

        return session.delete(data).map(v -> true);
      });
    });
  }

  @Override
  public Future<Optional<TModel>> deleteById(long id, @Nullable Mutiny.StatelessSession transaction) {
    //? Now, run a query with that session.
    return this.getOrCreateSession(transaction, (session, context) -> {
      //? Moving forward...
      if (context != null) {
        return session.get(modelType, id, LockModeType.OPTIMISTIC_FORCE_INCREMENT).chain(data -> {
          if (data == null) {
            return Uni.createFrom().item(Optional.empty());
          }

          return session.delete(data).map(v -> Optional.of(data));
        });
      }

      return session.get(modelType, id, LockModeType.OPTIMISTIC_FORCE_INCREMENT).chain(data -> {
        if (data == null) {
          return Uni.createFrom().item(Optional.empty());
        }

        return session.delete(data).map(v -> Optional.of(data));
      });
    });
  }
}

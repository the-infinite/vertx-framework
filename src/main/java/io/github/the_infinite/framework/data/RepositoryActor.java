package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.hibernate.SessionFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.data.types.ChangeResultModel;
import io.github.the_infinite.framework.data.types.PaginatedResult;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.persistence.LockModeType;

@SuppressWarnings("unused")
public sealed abstract class RepositoryActor<TModel extends BaseEntity, TSession> permits StatefulRepositoryActor, StatelessRepositoryActor {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RepositoryActor.class);
  protected final SessionFactory sessionFactory;
  protected final Class<TModel> modelType;

  protected RepositoryActor(SessionFactory sessionFactory, Class<TModel> modelType) {
    this.sessionFactory = sessionFactory;
    this.modelType = modelType;
  }

  /**
   * Internally used to run blocking Hibernate work in Vert.x while keeping the repository API Future-based.
   */
  static <T> Future<T> wrap(Supplier<T> supplier) {
    return wrap(supplier, Vertx.currentContext());
  }

  static <T> Future<T> wrap(Supplier<T> supplier, @Nullable Context context) {
    final var promise = Promise.<T>promise();
    final Runnable execute = () -> {
      T result;
      try {
        result = supplier.get();
      } catch (Throwable cause) {
        promise.fail(cause);
        return;
      }
      try {
        promise.succeed(result);
      } catch (Throwable listenerFailure) {
        logger.error("Downstream listener failed after promise completed", listenerFailure);
      }
    };

    final var usedContext = context == null ? ConfigurationRegistrant.vertx().getOrCreateContext() : context;
    usedContext.owner().executeBlocking(() -> {
      execute.run();
      return null;
    });
    return promise.future();
  }

  /**
   * Helps to build an updated criteria query using a given cursor. If the cursor is null, it would return the supplied
   * criteria query. However, if it is not, it would attempt to decode the cursor and build a new criteria query around
   * the updated cursor.
   *
   * @param queryData The query to build a cursor filter about.
   * @param cursor    The optional cursor we are building this about.
   * @return A probably updated criteria query.
   */
  protected QueryData<TModel> buildWithCursor(@NotNull QueryData<TModel> queryData, @Nullable String cursor) {
    //? If this is not specified, we just return the original.
    if (cursor == null) {
      return queryData;
    }

    //? Let us try to build this cursor and compound it in.
    try {
      final var query = queryData.query();
      final var original = query.getRestriction();
      final var cursorData = parseCursor(cursor);
      final var cursorId = cursorData.cursor;

      //? If this had a previous query specified, we need to AND it in.
      if (original != null) {
        query.where(queryData.builder().and(original, sessionFactory.getCriteriaBuilder().greaterThan(query.from(modelType).get("id"), cursorId)));
      }

      //? Since there was nothing, we can just set something.
      else {
        query.where(queryData.builder().greaterThan(query.from(modelType).get("id"), cursorId));
      }
      return queryData;
    } catch (Throwable t) {
      return queryData;
    }
  }

  /**
   * Helps to build an updated criteria query using a given cursor. If the cursor is null, it would return the supplied
   * criteria query. However, if it is not, it would attempt to decode the cursor and build a new criteria query around
   * the updated cursor.
   *
   * @param queryData The query to build a cursor filter about.
   * @param cursor    The optional cursor we are building this about.
   * @return A probably updated criteria query.
   */
  protected DeleteQueryData<TModel> buildWithCursor(@NotNull DeleteQueryData<TModel> queryData, @Nullable String cursor) {
    //? If this is not specified, we just return the original.
    if (cursor == null) {
      return queryData;
    }

    //? Let us try to build this cursor and compound it in.
    try {
      final var query = queryData.query();
      final var original = query.getRestriction();
      final var cursorData = parseCursor(cursor);
      final var cursorId = cursorData.cursor;

      //? If this had a previous query specified, we need to AND it in.
      if (original != null) {
        query.where(queryData.builder().and(original, queryData.builder().greaterThan(queryData.from().get("id"), cursorId)));
      }

      //? Since there was nothing, we can just set something.
      else {
        query.where(queryData.builder().greaterThan(queryData.from().get("id"), cursorId));
      }
      return queryData;
    } catch (Throwable t) {
      return queryData;
    }
  }

  protected CursorData parseCursor(String cursor) throws JsonProcessingException {
    return DataHelpers.deserializeObject(DataHelpers.fromBase64(cursor), CursorData.class);
  }

  /**
   * A helper function used to construct a JPA criteria query in the general context of this repository. This query
   * criterion can then be used inside repository functions.
   */
  public QueryData<TModel> start() {
    final var builder = this.sessionFactory.getCriteriaBuilder();
    return new QueryData<>(builder, modelType);
  }

  /**
   * A helper function used to construct a JPA criteria query in the general context of
   * this repository, specifically for delete operations. This query criterion can then
   * be used in combination with repository functions.
   * <p>
   * Note: Delete operations typically require a different handling in JPA, and this
   * method provides a way to build criteria queries that are suitable for delete
   * operations.
   */
  public DeleteQueryData<TModel> startDelete() {
    final var builder = this.sessionFactory.getCriteriaBuilder();
    return new DeleteQueryData<>(builder, modelType);
  }

  protected String makeCursor(Collection<TModel> data, int limit, CursorData previous) throws JsonProcessingException {
    if (data.isEmpty()) {
      throw new IllegalStateException("Cannot make a cursor for an empty set");
    }

    final var usedLimit = previous == null ? limit : previous.limit;
    final var elements = data.stream().toList();
    final var payload = new CursorData(usedLimit, elements.getLast().getId());
    final var returnValue = DataHelpers.serializeObject(payload);
    return DataHelpers.toBase64(returnValue);
  }

  /**
   * Helper function used to get or create a session around the topic of executing a given task. It would always try to
   * either return the session it is given that should typically belong to a transaction, or to create a new session
   * around the task promise.
   *
   * @param transaction The transaction session we are probably calling this for.
   * @param options     The repository options for this operation.
   * @return The transaction session if it exists, or a session built around task promise that would be disposed when
   * it completes.
   */
  abstract protected <T> Future<T> getOrCreateSession(@Nullable TSession transaction, @Nullable RepositoryOptions<TModel> options, SessionBoundHandler<TSession, T> handler);

  abstract public <ReturnType> Future<ReturnType> transaction(@NotNull RepositoryOptions<TModel> options, Function<TSession, Future<ReturnType>> future);

  // Region for get methods.
  abstract public Future<Long> getCount(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for get methods.


  // Region for create methods.
  abstract public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> createOne(@NotNull TModel item,
                                                     @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for create methods.


  // Region for upsert methods.
  abstract public Future<List<TModel>> upsertMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> upsertOne(@NotNull TModel item,
                                                     @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for upsert methods.


  // Region for update methods.
  abstract public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> updateById(long id, @NotNull ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for update methods.

  // Region for delete methods.
  abstract public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                            @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> deleteById(long id, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for delete methods.

  // Region for utility overloads.

  /**
   * @see #getPaginatedView(QueryData, RepositoryOptions, TSession)
   */
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getPaginatedView(filter, options, null);
  }

  /**
   * @see #getMany(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getMany(filter, options, null);
  }

  /**
   * @see #getDistinctRows(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getDistinctRows(filter, options, null);
  }

  /**
   * @see #getOne(QueryData, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getOne(filter, options, null);
  }

  /**
   * @see #getById(long, LockModeType, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> getById(long id, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction) {
    return this.getById(id, LockModeType.OPTIMISTIC, options, transaction);
  }

  /**
   * @see #getById(long, LockModeType, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> getById(long id, @NotNull RepositoryOptions<TModel> options) {
    return this.getById(id, LockModeType.OPTIMISTIC, options, null);
  }

  /**
   * @see #updateMany(QueryData, RepositoryActor.ChangeEffectorFunction, RepositoryOptions, TSession)
   */
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return this.updateMany(filter, valueChanger, options, null);
  }

  /**
   * @see #updateOne(QueryData, RepositoryActor.ChangeEffectorFunction, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return this.updateOne(filter, valueChanger, options, null);
  }

  /**
   * @see #updateById(long, RepositoryActor.ChangeEffectorFunction, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> updateById(long id, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return this.updateById(id, valueChanger, options, null);
  }

  /**
   * @see #createMany(List, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options) {
    return this.createMany(items, options, null);
  }

  /**
   * @see #createOne(BaseEntity, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options) {
    return this.createOne(item, options, null);
  }

  /**
   * @see #upsertMany(List, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> upsertMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options) {
    return this.upsertMany(items, options, null);
  }

  /**
   * @see #upsertOne(BaseEntity, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> upsertOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options) {
    return this.upsertOne(item, options, null);
  }

  /**
   * @see #deleteMany(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter,
                                    @NotNull RepositoryOptions<TModel> options) {
    return this.deleteMany(filter, options, null);
  }

  /**
   * @see #deleteOne(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                   @NotNull RepositoryOptions<TModel> options) {
    return this.deleteOne(filter, options, null);
  }

  /**
   * @see #deleteById(long, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> deleteById(long id, @NotNull RepositoryOptions<TModel> options) {
    return this.deleteById(id, options, null);
  }

  public interface SessionBoundHandler<TSession, T> {
    T handle(TSession session, @Nullable Context context);
  }

  public interface ChangeEffectorFunction<TEntity extends BaseEntity, TSession> {
    boolean change(TSession session, TEntity entity);
  }

  protected record CursorData(int limit, long cursor) {
  }
}

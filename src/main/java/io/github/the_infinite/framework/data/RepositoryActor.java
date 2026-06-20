package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.hibernate.reactive.mutiny.Mutiny;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.github.the_infinite.framework.data.types.ChangeResultModel;
import io.github.the_infinite.framework.data.types.PaginatedResult;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.github.the_infinite.framework.utils.DataHelpers;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import jakarta.persistence.LockModeType;

@SuppressWarnings("unused")
public sealed abstract class RepositoryActor<TModel extends BaseEntity, TSession> permits StatefulRepositoryActor, StatelessRepositoryActor {
  protected final Mutiny.SessionFactory sessionFactory;
  protected final Class<TModel> modelType;

  protected RepositoryActor(Mutiny.SessionFactory sessionFactory, Class<TModel> modelType) {
    this.sessionFactory = sessionFactory;
    this.modelType = modelType;
  }

  /**
   * Internally used to wrap a Mutiny Uni into a Vert.x Future. This is necessary because the repository functions are
   * designed to return Vert.x Futures, while the underlying database operations use Mutiny Unis. This helper function
   * bridges the gap between these two asynchronous paradigms, allowing for seamless integration of Mutiny-based database
   * operations within the Vert.x Future-based repository API.
   */
  @SuppressWarnings("unchecked")
  static <T> Future<T> wrap(Uni<T> uni) {
    final var promise = Promise.<T>promise();
    uni.subscribe().with(promise::succeed, cause -> {
      //? If this is simply that there was no result...
      if (cause.getMessage().toLowerCase().contains("no result found for query")) {
        try {
          promise.succeed((T) Optional.empty());
        } catch (ClassCastException ignored) {
          promise.succeed(null);
        }
      } else {
        promise.fail(cause);
      }
    });
    return promise.future();
  }

  /**
   * Helps to build an updated criteria query using a given cursor. If cursor is null, it would return the supplied
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
   * Helps to build an updated criteria query using a given cursor. If cursor is null, it would return the supplied
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
   * @return The transaction session if it exists, or a session built around task promise that would be disposed when
   * it completes.
   */
  abstract protected <T> Future<T> getOrCreateSession(@Nullable TSession transaction, SessionBoundHandler<TSession, T> handler);

  abstract public <ReturnType> Future<ReturnType> transaction(Function<TSession, Future<ReturnType>> future);

  // Region for get methods.
  abstract public Future<Long> getCount(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @Nullable TSession transaction);
  // End region for get methods.


  // Region for create methods.
  abstract public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> createOne(@NotNull TModel item,
                                               @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for create methods.


  // Region for update methods.
  abstract public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> updateById(long id, @NotNull ChangeEffectorFunction<TModel, TSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable TSession transaction);
  // End region for update methods.

  // Region for delete methods.
  abstract public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                            @Nullable RepositoryOptions<TModel> options, @Nullable TSession transaction);

  abstract public Future<Optional<TModel>> deleteById(long id, @Nullable TSession transaction);
  // End region for delete methods.

  // Region for utility overloads.

  /**
   * @see #getPaginatedView(QueryData, RepositoryOptions, TSession)
   */
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    return this.getPaginatedView(filter, options, null);
  }

  /**
   * @see #getPaginatedView(QueryData, RepositoryOptions, TSession)
   */
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter) {
    return this.getPaginatedView(filter, null);
  }

  /**
   * @see #getPaginatedView(QueryData, RepositoryOptions, TSession)
   */
  public Future<PaginatedResult<TModel>> getPaginatedView() {
    return this.getPaginatedView(null);
  }

  /**
   * @see #getMany(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    return this.getMany(filter, options, null);
  }

  /**
   * @see #getMany(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter) {
    return this.getMany(filter, null);
  }

  /**
   * @see #getMany(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getMany() {
    return this.getMany(null);
  }

  /**
   * @see #getDistinctRows(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    return this.getDistinctRows(filter, options, null);
  }

  /**
   * @see #getDistinctRows(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter) {
    return this.getDistinctRows(filter, null);
  }

  /**
   * @see #getDistinctRows(QueryData, RepositoryOptions, TSession)
   */
  public Future<List<TModel>> getDistinctRows() {
    return this.getDistinctRows(null);
  }


  /**
   * @see #getOne(QueryData, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    return this.getOne(filter, options, null);
  }

  /**
   * @see #getOne(QueryData, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter) {
    return this.getOne(filter, null);
  }

  /**
   * @see #getOne(QueryData, RepositoryOptions, TSession)
   */
  public Future<Optional<TModel>> getOne() {
    return this.getOne(null);
  }

  /**
   * @see #getById(long, LockModeType, TSession)
   */
  public Future<Optional<TModel>> getById(long id, @Nullable TSession transaction) {
    return this.getById(id, LockModeType.OPTIMISTIC, transaction);
  }

  /**
   * @see #getById(long, LockModeType, TSession)
   */
  public Future<Optional<TModel>> getById(long id) {
    return this.getById(id, null);
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
   * @see #deleteMany(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter,
                                    @Nullable RepositoryOptions<TModel> options) {
    return this.deleteMany(filter, options, null);
  }

  /**
   * @see #deleteMany(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter) {
    return this.deleteMany(filter, null);
  }

  /**
   * @see #deleteMany(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Integer> deleteMany() {
    return this.deleteMany(null);
  }


  /**
   * @see #deleteOne(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                   @Nullable RepositoryOptions<TModel> options) {
    return this.deleteOne(filter, options, null);
  }

  /**
   * @see #deleteOne(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter) {
    return this.deleteOne(filter, null);
  }

  /**
   * @see #deleteOne(DeleteQueryData, RepositoryOptions, TSession)
   */
  public Future<Boolean> deleteOne() {
    return this.deleteOne(null);
  }


  /**
   * @see #deleteById(long, TSession)
   */
  public Future<Optional<TModel>> deleteById(long id) {
    return this.deleteById(id, null);
  }

  public interface SessionBoundHandler<TSession, T> {
    Uni<T> handle(TSession session, @Nullable Context context);
  }

  public interface ChangeEffectorFunction<TEntity extends BaseEntity, TSession> {
    boolean change(TSession session, TEntity entity);
  }

  protected record CursorData(int limit, long cursor) {
  }
}

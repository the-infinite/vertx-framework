package io.github.the_infinite.framework.data;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.github.the_infinite.framework.data.types.ChangeResultModel;
import io.github.the_infinite.framework.data.types.PaginatedResult;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.vertx.core.Future;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import lombok.Getter;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class PersistentRepository<TModel extends BaseEntity, TModule extends Enum<?>> {
  static final Map<Class<?>, PersistentRepository<?, ?>> instances = new ConcurrentHashMap<>();
  private static final Logger logger = LoggerFactory.getLogger(PersistentRepository.class);
  private final SessionFactory sessionFactory;
  /**
   * -- GETTER --
   *  Returns the specific data module (unique to the calling application) that this repository originates from.
   */
  @Getter
  private final TModule module;
  private final Class<TModel> modelType;
  private final StatelessRepositoryActor<TModel> statelessRepositoryActor;
  private final StatefulRepositoryActor<TModel> statefulRepositoryActor;

  PersistentRepository(TModule module, Class<TModel> modelType, SessionFactory sessionFactory) {
    this.module = module;
    this.sessionFactory = sessionFactory;
    this.modelType = modelType;
    this.statefulRepositoryActor = new StatefulRepositoryActor<>(sessionFactory, modelType);
    this.statelessRepositoryActor = new StatelessRepositoryActor<>(sessionFactory, modelType);
  }

  /**
   * Initializes the repository for a specific model type and module.
   */
  public static <TModel extends BaseEntity, TModule extends Enum<?>> void initialize(@NotNull TModule module, @NotNull Class<TModel> modelType, @NotNull SessionFactory sessionFactory) {
    if (instances.containsKey(modelType)) {
      throw new IllegalStateException("Repository for model type " + modelType.getName() + " has already been initialized.");
    }

    logger.atInfo()
      .addKeyValue("module", module.name())
      .addKeyValue("entity", modelType.getName())
      .log("Mounted a repository in the permission module '{}' for the entity '{}'", module.name(), modelType.getName());
    instances.put(modelType, new PersistentRepository<>(module, modelType, sessionFactory));
  }

  /**
   * Finds the repository instance for a specific model type.
   * <p>
   * This method retrieves the repository instance associated with the given model type.
   * If the repository has not been initialized for the specified model type, an
   * `IllegalStateException` is thrown.
   * </p>
   *
   * @param <TModel>  The type of the entity managed by the repository.
   * @param <TModule> The type of the module associated with the repository.
   * @param modelType The class type of the entity managed by the repository. Must not be null.
   * @return The repository instance for the specified model type.
   * @throws IllegalStateException If the repository for the given model type has not been initialized.
   */
  public static <TModel extends BaseEntity, TModule extends Enum<?>> PersistentRepository<TModel, TModule> find(@NotNull Class<TModel> modelType) {
    if (!instances.containsKey(modelType)) {
      throw new IllegalStateException("Repository for model type " + modelType.getName() + " has not been initialized.");
    }

    @SuppressWarnings("unchecked") final var instance = (PersistentRepository<TModel, TModule>) instances.get(modelType);
    return instance;
  }

  /**
   * Confirms whether the given resource is owned by the specified user. Useful in situations where ownership is a
   * concern. For instance, when a user is attempting to modify or delete a resource, this method can be used to verify
   * that they have the necessary permissions to do so.
   *
   * @param item   The item to confirm a user's ownership of.
   * @param userId The user ID to check against.
   */
  public boolean isOwner(TModel item, long userId) {
    return this.isOwner(item, Long.valueOf(userId));
  }

  public boolean isOwner(TModel item, Object user) {
    if (item instanceof BaseAuditableEntity<?> ae) {
      return Objects.equals(ae.getCreatedBy(), user);
    }
    return false;
  }

  /**
   * Retrieves the name of the table associated with the model type if the model type
   * is annotated with the {@code Table} annotation. If the {@code Table} annotation
   * is not present on the model type, an empty {@code Optional} is returned.
   *
   * @return an {@code Optional} containing the name of the table if the {@code Table}
   *         annotation is present; otherwise, an empty {@code Optional}.
   */
  public Optional<String> tableName() {
    if (!modelType.isAnnotationPresent(Table.class)) {
      return Optional.empty();
    }

    final var tableAnnotation = modelType.getAnnotation(Table.class);
    final var tableName = tableAnnotation.name();
    return Optional.of(tableName);
  }

  /**
   * A utility function used to check if a database table exists. It is worth noting that although this function is
   * exposed, there are not a lot of use cases I can think of for it. It is primarily used for testing and debugging
   * purposes to ensure that the database schema is correctly set up before performing operations that depend on the
   * existence of certain tables. In a production environment, this function might not be commonly used, as the
   * application would typically assume that the necessary tables are already in place. Which should be a validated
   * notion by the migration pipeline.
   */
  public Future<Boolean> doesTableExist() {
    final var tableData = tableName();
    if (tableData.isEmpty()) {
      return Future.succeededFuture(false);
    }
    final var tableName = tableData.get();
    final var console = ConsoleLogger.getInstance();
    console.debug("Checking if table '%s' exists for model '%s'".formatted(tableName, modelType.getName()));
    final var sqlQuery = "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = :tableName";
    return RepositoryActor.wrap(() -> sessionFactory.fromSession(session -> session.createNativeQuery(sqlQuery, Long.class).setParameter("tableName", tableName).getSingleResult() > 0));
  }

  /**
   * Checks if the specified user has access to the given item. This method is useful for determining whether a user
   * can view or interact with a resource based on their permissions or roles.
   *
   * @param model  The item to check access for.
   * @param userId The user ID to check access against.
   * @return A future that resolves to true if the user has access to the item, false otherwise.
   */
  public boolean isAccessible(TModel model, long userId) {
    return this.isAccessible(model, Long.valueOf(userId));
  }

  public boolean isAccessible(TModel model, Object user) {
    if (model instanceof BaseAuditableEntity<?> item) {
      return Objects.equals(item.getCreatedBy(), user) || Objects.equals(item.getUpdatedBy(), user);
    }
    return false;
  }

  /**
   * Helper function used to construct a stateful transaction around a given function. It
   * would be nice if Java had native support for promises and async/await like other
   * languages, but it doesn't. So this is what we have to do. This method would
   * execute a function and then fail or succeed the transaction based on
   * the result of that function.
   * <br/> <br/> It does not have support for checkpoints or partial rollbacks. It rolls
   * back the entire transaction if any issue was noticed inside the function.
   * Theoretically, you could implement your own checkpointing system using save-points
   * by calling this function inside itself, but that is not very pretty.
   *
   * @param options      The repository options, including the acting user and correlation context for this transaction.
   * @param future       A callback that returns a future to execute inside the transaction. If the future fails, the
   *                     transaction is rolled back. If it succeeds, the transaction is committed. The session provided inside
   *                     the callback is the transactional session. It should be used in any repository APIs inside the function.
   * @param <ReturnType> The result of the future.
   * @return A future that resolves to use a database transaction
   */
  public <ReturnType> Future<ReturnType> transaction(@NotNull RepositoryOptions<TModel> options, Function<Session, Future<ReturnType>> future) {
    return statefulRepositoryActor.transaction(options, future);
  }

  public <ReturnType> Future<ReturnType> transaction(Function<Session, Future<ReturnType>> future) {
    return this.transaction(new RepositoryOptions<>(null), future);
  }

  /**
   * @see RepositoryActor#start()
   */
  public QueryData<TModel> start() {
    return statefulRepositoryActor.start();
  }

  /**
   * @see RepositoryActor#startDelete()
   */
  public DeleteQueryData<TModel> startDelete() {
    return statefulRepositoryActor.startDelete();
  }

  /**
   * Returns the session factory bound to this repository. You can think of it as an abstraction over the
   * database client.
   */
  public SessionFactory database() {
    return this.sessionFactory;
  }

  public Future<Long> getCount(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.getCount(filter, options, transaction);
  }

  /**
   * Retrieves a paginated "view" of entities using cursor-based pagination.
   * <p>
   * This helper computes the total count of rows matching the provided filter(s),
   * delegates to {@link #getMany} to fetch the current page of items, and then
   * returns pagination metadata including the next cursor. This is derived from the last
   * item's `id` when there's more.
   * <p>
   * Notes:
   * - If `options.cursor` is provided, an additional filter `primaryKey > cursor` is applied.
   * - If `orderBy` is not provided, results default to ascending by `table.id`.
   * - `limit` defaults to 30.
   *
   * @param filter      - Optional filter condition(s) to constrain the result set.
   * @param options     - Optional pagination options such as `limit` and `cursor`.
   * @param transaction - Optional transaction/database context. Defaults to the repository database connection.
   * @return A paginated result containing the items, total count, limit, current cursor, and next cursor (if any).
   */
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.getPaginatedView(filter, options, transaction);
  }

  /**
   * @see #getPaginatedView(QueryData, RepositoryOptions, Session)
   */
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getPaginatedView(filter, options, null);
  }

  /**
   * Retrieves a list of entities based on the provided filter and pagination options.
   * <p>
   * This method executes a database query to fetch a list of entities that match the given criteria.
   * It supports cursor-based pagination and allows for optional transaction management.
   * <p>
   * Notes:
   * - If `options.cursor` is provided, an additional filter `primaryKey > cursor` is applied.
   * - If a transaction is provided, it will be used; otherwise, a new session will be created.
   *
   * @param filter      The optional JPA `CriteriaQuery` used to filter the results.
   * @param options     The optional pagination options for the `cursor`.
   * @param transaction The optional transactional session to use for the query. If null, a new session is created.
   * @return A `Future` that resolves to a list of entities matching the criteria.
   */
  public Future<List<TModel>> getAll(
    @Nullable QueryData<TModel> filter,
    @NotNull RepositoryOptions<TModel> options,
    @Nullable Session transaction
  ) {
    return this.statefulRepositoryActor.getAll(filter, options, transaction);
  }

  /**
   * @see #getMany(QueryData, RepositoryOptions, Session)
   */
  public Future<List<TModel>> getAll(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getAll(filter, options, null);
  }

  /**
   * Retrieves a list of entities based on the provided filter and pagination options.
   * <p>
   * This method executes a database query to fetch a list of entities that match the given criteria.
   * It supports cursor-based pagination and allows for optional transaction management.
   * <p>
   * Notes:
   * - If `options.cursor` is provided, an additional filter `primaryKey > cursor` is applied.
   * - If `options.limit` is not provided, the default limit is 30.
   * - If a transaction is provided, it will be used; otherwise, a new session will be created.
   *
   * @param filter      The optional JPA `CriteriaQuery` used to filter the results.
   * @param options     The optional pagination options, such as `limit` and `cursor`.
   * @param transaction The optional transactional session to use for the query. If null, a new session is created.
   * @return A `Future` that resolves to a list of entities matching the criteria.
   */
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.statefulRepositoryActor.getMany(filter, options, transaction);
  }

  /**
   * @see #getMany(QueryData, RepositoryOptions, Session)
   */
  public Future<List<TModel>> getMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getMany(filter, options, null);
  }

  /**
   * Retrieves a distinct, ordered list of values for a single table.
   * <p>
   * This is typically used to build filter dropdowns/autocomplete sources (e.g., unique tags, statuses),
   * while still respecting any provided `filter` constraints and optional cursor-based pagination.
   * <p>
   * Notes:
   * - If `options.cursor` is provided, the query adds `primaryKey > cursor` to the where clause.
   * - Results are ordered ascending by the requested column.
   * - Only the distinct values for `column` are selected and returned.
   *
   * @param filter      - Optional filter condition(s) to constrain which rows contribute to the distinct set.
   * @param options     - Optional repository options. When `cursor` is provided, only rows with primary keys
   *                    greater than the cursor are considered.
   * @param transaction - Optional transaction/database context to execute the query against. Defaults to
   *                    the repository database connection.
   * @return A promise resolving to an array of unique values for the specified column.
   */
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.getDistinctRows(filter, options, transaction);
  }

  /**
   * @see #getDistinctRows(QueryData, RepositoryOptions, Session)
   */
  public Future<List<TModel>> getDistinctRows(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getDistinctRows(filter, options, null);
  }

  /**
   * Retrieves a single entity from the repository based on the provided filter and ordering criteria.
   *
   * @param filter      Optional filter criteria to apply when searching for the entity
   * @param options     Optional repository options for the query
   * @param transaction Optional transaction context for the database operation
   * @return A future that resolves to the found entity or null if no entity matches the criteria
   */
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.statefulRepositoryActor.getOne(filter, options, transaction);
  }

  /**
   * @see #getOne(QueryData, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> getOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryOptions<TModel> options) {
    return this.getOne(filter, options, null);
  }

  /**
   * Retrieves a single entity from the repository by its primary key identifier.
   *
   * @param id          - The primary key value to search for
   * @param lockMode    what kind of lock are we acquiring?
   * @param options     The repository options, including the acting user and correlation context.
   * @param transaction Optional database transaction to execute the query within
   * @return A promise that resolves to the found entity or null if no entity matches the given id
   */
  public Future<Optional<TModel>> getById(long id, LockModeType lockMode, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.statefulRepositoryActor.getById(id, lockMode, options, transaction);
  }

  /**
   * Retrieves a single entity by its primary key identifier.
   *
   * @param id          - The primary key value to search for
   * @param options     The repository options, including the acting user and correlation context.
   * @return A promise that resolves to the found entity or null if no entity matches the given id
   * @see #getById(long, LockModeType, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> getById(long id, @NotNull RepositoryOptions<TModel> options) {
    return this.getById(id, LockModeType.OPTIMISTIC, options, null);
  }


  public Future<Optional<TModel>> getById(long id,
                                          @NotNull RepositoryOptions<TModel> options,
                                          @Nullable Session transaction) {
    return this.getById(id, LockModeType.OPTIMISTIC, options, transaction);
  }

  /**
   * Updates multiple records in the repository that match the provided filter criteria.
   *
   * @param filter       One or more filter conditions used to determine which records to update.
   * @param valueChanger The effector function that is invoked on each record to update it
   *                     and determine a need for updating it.
   * @param options      Optional repository options, such as a cursor for pagination and a limit (defaulting to 50,000).
   * @param transaction  Optional transaction or database connection to execute the update operation.
   * @return A future resolving to a change result containing the count of affected rows and any additional metadata.
   */
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    return this.statelessRepositoryActor.updateMany(filter, valueChanger, options, transaction);
  }

  /**
   * @see #updateMany(QueryData, RepositoryActor.ChangeEffectorFunction, RepositoryOptions, StatelessSession)
   */
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable QueryData<TModel> filter, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, StatelessSession> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return this.updateMany(filter, valueChanger, options, null);
  }

  /**
   * Updates a single entity in the repository that matches the provided filter criteria.
   * <p>
   * This method retrieves an entity based on the given filter and applies the provided value changer function
   * to modify the entity. If the entity is successfully updated, it is merged back into the database.
   * <p>
   * Notes:
   * - If no entity matches the filter, the method resolves to an empty `Optional`.
   * - The `valueChanger` function determines whether the entity should be updated.
   * - If the `valueChanger` does not modify the entity, the method resolves to an empty `Optional`.
   *
   * @param filter       The optional JPA `CriteriaQuery` used to filter the entity to update.
   * @param valueChanger The function that modifies the entity. It returns `true` if the entity was changed, `false` otherwise.
   * @param options      The optional repository options, such as `limit` and `cursor`.
   * @param transaction  The optional transactional session to use for the query. If null, a new session is created.
   * @return A `Future` that resolves to an `Optional` containing the updated entity or an empty `Optional` if no entity was updated.
   */
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.statefulRepositoryActor.updateOne(filter, valueChanger, options, transaction);
  }

  /**
   * @see #updateOne(QueryData, RepositoryActor.ChangeEffectorFunction, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> updateOne(@Nullable QueryData<TModel> filter, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return this.updateOne(filter, valueChanger, options, null);
  }

  /**
   * Updates a single entity in the repository by its primary key identifier.
   * <p>
   * This method retrieves an entity based on the provided `id` and applies the `valueChanger` function
   * to modify the entity. If the entity is successfully updated, it is merged back into the database.
   * <p>
   * Notes:
   * - If no entity matches the `id`, the method resolves to an empty `Optional`.
   * - The `valueChanger` function determines whether the entity should be updated.
   * - If the `valueChanger` does not modify the entity, the method resolves to an empty `Optional`.
   *
   * @param id           The primary key identifier of the entity to update.
   * @param valueChanger The function that modifies the entity. It returns `true` if the entity was changed, `false` otherwise.
   * @param transaction  The optional transactional session to use for the query. If null, a new session is created.
   * @return A `Future` that resolves to an `Optional` containing the updated entity or an empty `Optional` if no entity was updated.
   */
  public Future<Optional<TModel>> updateById(long id, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.updateById(id, valueChanger, options, transaction);
  }

  /**
   * @see #updateById(long, RepositoryActor.ChangeEffectorFunction, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> updateById(long id, @NotNull RepositoryActor.ChangeEffectorFunction<TModel, Session> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return this.updateById(id, valueChanger, options, null);
  }

  /**
   * Persists multiple entities in the repository.
   * <p>
   * This method takes a list of entities and saves them to the database. It uses the provided
   * transaction session if available, or creates a new session for the operation.
   * <p>
   * Notes:
   * - If the transaction session is provided, it will be reused for the operation.
   * - If the operation fails, the promise is marked as failed with the corresponding cause.
   *
   * @param items       The list of entities to persist.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves to the list of persisted entities.
   */
  public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    return statelessRepositoryActor.createMany(items, options, transaction);
  }

  /**
   * @see #createMany(List, RepositoryOptions, StatelessSession)
   */
  public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options) {
    return this.createMany(items, options, null);
  }

  /**
   * Persists a single entity in the repository.
   * <p>
   * This method saves the provided entity to the database. It uses the given transaction session
   * if available, or creates a new session for the operation.
   * <p>
   * Notes:
   * - If the transaction session is provided, it will be reused for the operation.
   * - If the operation fails, the promise is marked as failed with the corresponding cause.
   *
   * @param item        The entity to persist.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves to an `Optional` containing the persisted entity.
   */
  public Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.statefulRepositoryActor.createOne(item, options, transaction);
  }

  /**
   * @see #createOne(BaseEntity, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options) {
    return this.createOne(item, options, null);
  }

  /**
   * Upserts multiple entities in the repository, inserting those without an existing row and updating the rest.
   * <p>
   * This method checks each item's primary key against the database and either inserts it as new or
   * merges the changes over the existing row. It uses the provided transaction session if available,
   * or creates a new session for the operation.
   * <p>
   * Notes:
   * - If the transaction session is provided, it will be reused for the operation.
   * - If the operation fails, the promise is marked as failed with the corresponding cause.
   *
   * @param items       The list of entities to upsert.
   * @param options     The repository options, including the acting user for audit fields.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves to the list of upserted entities.
   */
  public Future<List<TModel>> upsertMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options, @Nullable StatelessSession transaction) {
    return statelessRepositoryActor.upsertMany(items, options, transaction);
  }

  /**
   * @see #upsertMany(List, RepositoryOptions, StatelessSession)
   */
  public Future<List<TModel>> upsertMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options) {
    return this.upsertMany(items, options, null);
  }

  /**
   * Upserts a single entity in the repository, inserting it if no row exists and merging changes otherwise.
   * <p>
   * This method checks the item's primary key against the database and either persists it as new or
   * merges the changes over the existing row. It uses the given transaction session if available,
   * or creates a new session for the operation.
   * <p>
   * Notes:
   * - If the transaction session is provided, it will be reused for the operation.
   * - If the operation fails, the promise is marked as failed with the corresponding cause.
   *
   * @param item        The entity to upsert.
   * @param options     The repository options, including the acting user for audit fields.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves to an `Optional` containing the upserted entity.
   */
  public Future<Optional<TModel>> upsertOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return this.statefulRepositoryActor.upsertOne(item, options, transaction);
  }

  /**
   * @see #upsertOne(BaseEntity, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> upsertOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options) {
    return this.upsertOne(item, options, null);
  }

  /**
   * Deletes multiple entities from the repository that match the provided filter criteria.
   * <p>
   * This method retrieves a list of entities based on the given filter and removes them from the database.
   * It supports cursor-based pagination and allows for optional transaction management.
   * <p>
   * Notes:
   * - If `options.cursor` is provided, an additional filter `primaryKey > cursor` is applied.
   * - If `options.limit` is not provided, the default limit is 30.
   * - If no entities match the filter, the method resolves to a `ChangeResultModel` with a count of 0.
   *
   * @param filter      The optional JPA `CriteriaQuery` used to filter the entities to delete.
   * @param options     The optional pagination options, such as `limit` and `cursor`.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves to an integer containing the count of deleted
   * entities.
   */
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter,
                                    @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.deleteMany(filter, options, transaction);
  }

  /**
   * @see #deleteMany(DeleteQueryData, RepositoryOptions, Session)
   */
  public Future<Integer> deleteMany(@Nullable DeleteQueryData<TModel> filter,
                                    @NotNull RepositoryOptions<TModel> options) {
    return this.deleteMany(filter, options, null);
  }

  /**
   * Deletes a single entity from the repository that matches the provided filter criteria.
   * <p>
   * This method retrieves an entity based on the given filter and removes it from the database.
   * It supports cursor-based pagination and allows for optional transaction management.
   * <p>
   * Notes:
   * - If `options.cursor` is provided, an additional filter `primaryKey > cursor` is applied.
   * - If `options.limit` is not provided, the default limit is 30.
   * - If no entity matches the filter, the method resolves to an empty `Optional`.
   *
   * @param filter      The optional JPA `CriteriaQuery` used to filter the entity to delete.
   * @param options     The optional pagination options, such as `limit` and `cursor`.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves whether this was deleted successfully.
   */
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                   @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.deleteOne(filter, options, transaction);
  }

  /**
   * @see #deleteOne(DeleteQueryData, RepositoryOptions, Session)
   */
  public Future<Boolean> deleteOne(@Nullable DeleteQueryData<TModel> filter,
                                   @NotNull RepositoryOptions<TModel> options) {
    return this.deleteOne(filter, options, null);
  }

  /**
   * Helper function used to retrieve a stateless repository actor for this entire
   * persistent repository. This is useful in situations where you want to perform multiple
   * operations inside the same transaction, or you want to use some of the more advanced
   * features of the stateless repository actor.
   */
  public StatelessRepositoryActor<TModel> stateless() {
    return statelessRepositoryActor;
  }

  /**
   * Helper function used to retrieve a stateful repository actor for this entire
   * persistent repository. This is useful in situations where you want to perform multiple
   * operations inside the same transaction, or you want to use some of the more advanced
   * features of the stateful repository actor.
   */
  public StatefulRepositoryActor<TModel> stateful() {
    return statefulRepositoryActor;
  }

  /**
   * Deletes a single entity from the repository by its primary key identifier.
   * <p>
   * This method retrieves an entity based on the provided `id` and removes it from the database.
   * If the entity is successfully deleted, the method resolves to an `Optional` containing the deleted entity.
   * <p>
   * Notes:
   * - If no entity matches the `id`, the method resolves to an empty `Optional`.
   * - The operation uses `LockModeType.OPTIMISTIC_FORCE_INCREMENT` to ensure optimistic locking.
   *
   * @param id          The primary key identifier of the entity to delete.
   * @param options     The repository options, including the acting user and correlation context.
   * @param transaction The optional transactional session to use for the operation. If null, a new session is created.
   * @return A `Future` that resolves to an `Optional` containing the deleted entity or an empty `Optional` if no entity was deleted.
   */
  public Future<Optional<TModel>> deleteById(long id, @NotNull RepositoryOptions<TModel> options, @Nullable Session transaction) {
    return statefulRepositoryActor.deleteById(id, options, transaction);
  }

  /**
   * @see #deleteById(long, RepositoryOptions, Session)
   */
  public Future<Optional<TModel>> deleteById(long id, @NotNull RepositoryOptions<TModel> options) {
    return this.deleteById(id, options, null);
  }
}

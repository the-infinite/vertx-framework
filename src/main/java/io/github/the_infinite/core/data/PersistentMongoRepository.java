package io.github.the_infinite.core.data;

import io.github.the_infinite.core.data.types.ChangeResultModel;
import io.github.the_infinite.core.data.types.PaginatedResult;
import io.github.the_infinite.core.data.types.RepositoryOptions;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.BulkOperation;
import io.vertx.ext.mongo.BulkWriteOptions;

import jakarta.persistence.Table;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class PersistentMongoRepository<TModel extends BaseMongoEntity, TModule extends Enum<?>> {
  private static final Logger logger = LoggerFactory.getLogger(PersistentMongoRepository.class);
  private static final Map<Class<?>, PersistentMongoRepository<?, ?>> instances = new ConcurrentHashMap<>();

  private final MongoClient mongoClient;
  private final TModule module;
  private final Class<TModel> modelType;
  private final String collectionName;

  PersistentMongoRepository(TModule module, Class<TModel> modelType, MongoClient mongoClient) {
    this.module = module;
    this.mongoClient = mongoClient;
    this.modelType = modelType;
    this.collectionName = getCollectionName(modelType);
  }

  private String getCollectionName(Class<TModel> modelType) {
    if (modelType.isAnnotationPresent(Table.class)) {
      final var table = modelType.getAnnotation(Table.class);
      if (table.name() != null && !table.name().isEmpty()) {
        return table.name();
      }
    }
    return modelType.getSimpleName().toLowerCase();
  }

  /**
   * Initializes the repository for a specific model type and module.
   */
  public static <TModel extends BaseMongoEntity, TModule extends Enum<?>> void initialize(@NotNull TModule module, @NotNull Class<TModel> modelType, @NotNull MongoClient mongoClient) {
    if (instances.containsKey(modelType)) {
      throw new IllegalStateException("Repository for model type " + modelType.getName() + " has already been initialized.");
    }

    logger.atInfo()
          .addKeyValue("module", module.name())
          .addKeyValue("entity", modelType.getName())
          .log("Mounted a mongo repository in the permission module '{}' for the entity '{}'", module.name(), modelType.getName());
    instances.put(modelType, new PersistentMongoRepository<>(module, modelType, mongoClient));
  }

  /**
   * Finds the repository instance for a specific model type.
   */
  public static <TModel extends BaseMongoEntity, TModule extends Enum<?>> PersistentMongoRepository<TModel, TModule> find(@NotNull Class<TModel> modelType) {
    if (!instances.containsKey(modelType)) {
      throw new IllegalStateException("Repository for model type " + modelType.getName() + " has not been initialized.");
    }

    @SuppressWarnings("unchecked") final var instance = (PersistentMongoRepository<TModel, TModule>) instances.get(modelType);
    return instance;
  }

  /**
   * Confirms whether the given resource is owned by the specified user.
   */
  public boolean isOwner(TModel item, long userId) {
    if (item instanceof BaseMongoAuditableEntity ae) {
      return ae.getCreatedById() == userId;
    }
    return false;
  }

  /**
   * Checks if the specified user has access to the given item.
   */
  public boolean isAccessible(TModel model, long userId) {
    if (model instanceof BaseMongoAuditableEntity item) {
      return item.getCreatedById() == userId || item.getUpdatedById() == userId;
    }
    return false;
  }

  /**
   * Returns the mongo client bound to this repository.
   */
  public MongoClient database() {
    return this.mongoClient;
  }

  /**
   * Returns the specific data module that this repository originates from.
   */
  public TModule getModule() {
    return this.module;
  }

  /**
   * A helper function used to construct a mongo query in the general context of this repository.
   */
  public MongoQueryData<TModel> start() {
    return new MongoQueryData<>(modelType);
  }

  /**
   * A helper function used to construct a mongo query for delete operations.
   */
  public MongoQueryData<TModel> startDelete() {
    return new MongoQueryData<>(modelType);
  }

  public Future<Long> getCount(@Nullable MongoQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    JsonObject query = filter != null ? filter.getQuery().copy() : new JsonObject();
    if (options != null && options.getCursor() != null) {
      query.put("_id", new JsonObject().put("$gt", options.getCursor()));
    }
    return mongoClient.count(collectionName, query);
  }

  /**
   * Retrieves a paginated "view" of entities using cursor-based pagination.
   */
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable MongoQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    final var query = filter == null ? new JsonObject() : filter.getQuery().copy();
    final var sort = filter == null ? new JsonObject().put("_id", 1) : filter.getSort().copy();
    if (sort.isEmpty()) {
      sort.put("_id", 1);
    }

    final int limit = options != null && options.getLimit() > 0 ? options.getLimit() : 30;

    if (options != null && options.getCursor() != null) {
      query.put("_id", new JsonObject().put("$gt", options.getCursor()));
    }

    FindOptions findOptions = new FindOptions()
      .setLimit(limit)
      .setSort(sort);

    return mongoClient.findWithOptions(collectionName, query, findOptions)
      .compose(list -> {
        List<TModel> items = list.stream().map(json -> json.mapTo(modelType)).collect(Collectors.toList());
        return mongoClient.count(collectionName, filter != null ? filter.getQuery() : new JsonObject()).map(count -> {
          String nextCursor = null;
          if (items.size() == limit) {
            nextCursor = items.getLast().getId();
          }
          return new PaginatedResult<>(items, limit, count, options != null ? options.getCursor() : null, nextCursor);
        });
      });
  }

  /**
   * Retrieves a list of entities based on the provided filter and pagination options.
   */
  public Future<List<TModel>> getMany(@Nullable MongoQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    final var query = filter == null ? new JsonObject() : filter.getQuery().copy();
    final var sort = filter == null ? new JsonObject() : filter.getSort().copy();

    FindOptions findOptions = new FindOptions();
    if (options != null && options.getLimit() > 0) {
      findOptions.setLimit(options.getLimit());
    }
    if (!sort.isEmpty()) {
      findOptions.setSort(sort);
    }
    if (options != null && options.getCursor() != null) {
      query.put("_id", new JsonObject().put("$gt", options.getCursor()));
    }

    return mongoClient.findWithOptions(collectionName, query, findOptions)
      .map(list -> list.stream().map(json -> json.mapTo(modelType)).collect(Collectors.toList()));
  }

  /**
   * Retrieves a single entity from the repository based on the provided filter and options.
   */
  public Future<Optional<TModel>> getOne(@Nullable MongoQueryData<TModel> filter, @Nullable RepositoryOptions<TModel> options) {
    final var query = filter == null ? new JsonObject() : filter.getQuery().copy();
    if (options != null && options.getCursor() != null) {
      query.put("_id", new JsonObject().put("$gt", options.getCursor()));
    }
    return mongoClient.findOne(collectionName, query, null)
      .map(json -> json == null ? Optional.empty() : Optional.of(json.mapTo(modelType)));
  }

  /**
   * Retrieves a single entity from the repository by its primary key identifier.
   */
  public Future<Optional<TModel>> getById(String id) {
    return mongoClient.findOne(collectionName, new JsonObject().put("_id", id), null)
      .map(json -> json == null ? Optional.empty() : Optional.of(json.mapTo(modelType)));
  }

  /**
   * Persists a single entity in the repository.
   */
  public Future<Optional<TModel>> createOne(@NotNull TModel item, @NotNull RepositoryOptions<TModel> options) {
    item.setUid(UUID.randomUUID());
    if (item instanceof BaseMongoAuditableEntity ae) {
      ae.setCreatedById(options.getUserId());
      ae.setUpdatedById(options.getUserId());
    }
    JsonObject json = JsonObject.mapFrom(item);
    return mongoClient.insert(collectionName, json)
      .map(id -> {
        item.setId(id);
        return Optional.of(item);
      });
  }

  /**
   * Persists multiple entities in the repository.
   */
  public Future<List<TModel>> createMany(@NotNull List<TModel> items, @NotNull RepositoryOptions<TModel> options) {
    if (items.isEmpty()) return Future.succeededFuture(items);

    List<BulkOperation> operations = items.stream().map(item -> {
      if (item.getId() == null) {
        item.setId(new ObjectId().toHexString());
      }
      item.setUid(UUID.randomUUID());
      if (item instanceof BaseMongoAuditableEntity ae) {
        ae.setCreatedById(options.getUserId());
        ae.setUpdatedById(options.getUserId());
      }
      return BulkOperation.createInsert(JsonObject.mapFrom(item));
    }).collect(Collectors.toList());

    return mongoClient.bulkWriteWithOptions(collectionName, operations, new BulkWriteOptions().setOrdered(true))
      .map(v -> items);
  }

  /**
   * Deletes a single entity from the repository by its primary key identifier.
   */
  public Future<Boolean> deleteById(String id) {
    return mongoClient.removeDocument(collectionName, new JsonObject().put("_id", id))
      .map(res -> res.getRemovedCount() > 0);
  }

  /**
   * Deletes multiple entities from the repository that match the provided filter criteria.
   */
  public Future<Integer> deleteMany(@Nullable MongoQueryData<TModel> filter) {
    JsonObject query = filter != null ? filter.getQuery() : new JsonObject();
    return mongoClient.removeDocuments(collectionName, query)
      .map(res -> (int) res.getRemovedCount());
  }

  /**
   * Updates a single entity in the repository by its primary key identifier.
   */
  public Future<Optional<TModel>> updateById(String id, @NotNull ChangeEffectorFunction<TModel> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return getById(id).compose(opt -> {
      if (opt.isEmpty()) return Future.succeededFuture(Optional.empty());
      TModel item = opt.get();
      if (valueChanger.change(mongoClient, item)) {
        if (item instanceof BaseMongoAuditableEntity ae) {
          ae.setUpdatedById(options.getUserId());
        }
        JsonObject json = JsonObject.mapFrom(item);
        json.remove("_id");
        return mongoClient.replaceDocuments(collectionName, new JsonObject().put("_id", id), json)
          .map(res -> Optional.of(item));
      }
      return Future.succeededFuture(Optional.of(item));
    });
  }

  /**
   * Updates multiple records in the repository that match the provided filter criteria.
   */
  public Future<ChangeResultModel<TModel>> updateMany(@Nullable MongoQueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return getMany(filter, options).compose(items -> {
      List<TModel> changedItems = new ArrayList<>();
      for (TModel item : items) {
        if (valueChanger.change(mongoClient, item)) {
          if (item instanceof BaseMongoAuditableEntity ae) {
            ae.setUpdatedById(options.getUserId());
          }
          changedItems.add(item);
        }
      }

      if (changedItems.isEmpty()) {
        return Future.succeededFuture(new ChangeResultModel<>(changedItems));
      }

      List<BulkOperation> operations = changedItems.stream().map(item -> {
        JsonObject json = JsonObject.mapFrom(item);
        json.remove("_id");
        return BulkOperation.createReplace(new JsonObject().put("_id", item.getId()), json);
      }).collect(Collectors.toList());

      return mongoClient.bulkWriteWithOptions(collectionName, operations, new BulkWriteOptions().setOrdered(true))
        .map(v -> new ChangeResultModel<>(changedItems));
    });
  }

  /**
   * Updates a single entity in the repository that matches the provided filter criteria.
   */
  public Future<Optional<TModel>> updateOne(@Nullable MongoQueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel> valueChanger, @NotNull RepositoryOptions<TModel> options) {
    return getOne(filter, options).compose(opt -> {
      if (opt.isEmpty()) return Future.succeededFuture(Optional.empty());
      TModel item = opt.get();
      if (valueChanger.change(mongoClient, item)) {
        if (item instanceof BaseMongoAuditableEntity ae) {
          ae.setUpdatedById(options.getUserId());
        }
        JsonObject json = JsonObject.mapFrom(item);
        json.remove("_id");
        return mongoClient.replaceDocuments(collectionName, new JsonObject().put("_id", item.getId()), json)
          .map(res -> Optional.of(item));
      }
      return Future.succeededFuture(Optional.of(item));
    });
  }

  public interface ChangeEffectorFunction<TEntity extends BaseMongoEntity> {
    boolean change(MongoClient session, TEntity entity);
  }

  // Region for utility overloads
  public Future<PaginatedResult<TModel>> getPaginatedView(@Nullable MongoQueryData<TModel> filter) {
    return this.getPaginatedView(filter, null);
  }

  public Future<PaginatedResult<TModel>> getPaginatedView() {
    return this.getPaginatedView(null);
  }

  public Future<List<TModel>> getMany(@Nullable MongoQueryData<TModel> filter) {
    return this.getMany(filter, null);
  }

  public Future<List<TModel>> getMany() {
    return this.getMany(null);
  }

  public Future<Optional<TModel>> getOne(@Nullable MongoQueryData<TModel> filter) {
    return this.getOne(filter, null);
  }

  public Future<Optional<TModel>> getOne() {
    return this.getOne(null);
  }

  public Future<Long> getCount(@Nullable MongoQueryData<TModel> filter) {
    return this.getCount(filter, null);
  }

  public Future<Long> getCount() {
    return this.getCount(null);
  }

  public Future<Optional<TModel>> createOne(@NotNull TModel item) {
    return this.createOne(item, new RepositoryOptions<>());
  }

  public Future<List<TModel>> createMany(@NotNull List<TModel> items) {
    return this.createMany(items, new RepositoryOptions<>());
  }

  public Future<Optional<TModel>> updateById(String id, @NotNull ChangeEffectorFunction<TModel> valueChanger) {
    return this.updateById(id, valueChanger, new RepositoryOptions<>());
  }

  public Future<ChangeResultModel<TModel>> updateMany(@Nullable MongoQueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel> valueChanger) {
    return this.updateMany(filter, valueChanger, new RepositoryOptions<>());
  }

  public Future<Optional<TModel>> updateOne(@Nullable MongoQueryData<TModel> filter, @NotNull ChangeEffectorFunction<TModel> valueChanger) {
    return this.updateOne(filter, valueChanger, new RepositoryOptions<>());
  }

  public Future<Integer> deleteMany() {
    return this.deleteMany(null);
  }

  /**
   * Retrieves a distinct, ordered list of values for a single field.
   */
  public Future<List<String>> getDistinctRows(@Nullable MongoQueryData<TModel> filter, @NotNull String fieldName) {
    JsonObject query = filter != null ? filter.getQuery() : new JsonObject();
    return mongoClient.distinct(collectionName, fieldName, String.class.getName())
      .map(jsonArray -> jsonArray.stream().map(Object::toString).collect(Collectors.toList()));
  }

  /**
   * Checks if the collection exists in the database.
   */
  public Future<Boolean> doesCollectionExist() {
    return mongoClient.getCollections().map(list -> list.contains(collectionName));
  }
}

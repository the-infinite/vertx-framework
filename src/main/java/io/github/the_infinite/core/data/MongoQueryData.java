package io.github.the_infinite.core.data;

import io.vertx.core.json.JsonObject;
import java.util.Objects;

@SuppressWarnings("unused")
public final class MongoQueryData<T extends BaseMongoEntity> {
  private final JsonObject query;
  private final JsonObject sort;
  private final Class<T> entityClass;

  public MongoQueryData(Class<T> clazz) {
    this.entityClass = clazz;
    this.query = new JsonObject();
    this.sort = new JsonObject();
  }

  public MongoQueryData<T> where(String field, Object value) {
    query.put(field, value);
    return this;
  }

  public MongoQueryData<T> where(JsonObject filter) {
    query.mergeIn(filter);
    return this;
  }

  public MongoQueryData<T> orderBy(String field, boolean ascending) {
    sort.put(field, ascending ? 1 : -1);
    return this;
  }

  public JsonObject getQuery() {
    return query;
  }

  public JsonObject getSort() {
    return sort;
  }

  public Class<T> getEntityClass() {
    return entityClass;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MongoQueryData<?> that = (MongoQueryData<?>) o;
    return Objects.equals(query, that.query) && Objects.equals(sort, that.sort) && Objects.equals(entityClass, that.entityClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(query, sort, entityClass);
  }

  @Override
  public String toString() {
    return "MongoQueryData[" + "query=" + query + ", " + "sort=" + sort + ", " + "entityClass=" + entityClass + ']';
  }
}

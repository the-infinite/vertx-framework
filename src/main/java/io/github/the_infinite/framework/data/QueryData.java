package io.github.the_infinite.framework.data;

import java.util.Objects;

import jakarta.persistence.criteria.*;

@SuppressWarnings("unused")
public final class QueryData<T extends BaseEntity> {
  private final CriteriaBuilder builder;
  private final CriteriaQuery<T> query;
  private final Class<T> entityClass;
  private final Root<T> root;

  QueryData(CriteriaBuilder builder, Class<T> clazz) {
    this.builder = builder;
    this.entityClass = clazz;
    this.query = builder.createQuery(clazz);
    this.root = this.query.from(clazz);
  }

  public QueryData<T> orderBy(Order... orders) {
    query.orderBy(orders);
    return this;
  }

  public QueryData<T> distinct() {
    query.distinct(true);
    return this;
  }

  public Root<T> from() {
    return query.from(query.getResultType());
  }

  public QueryData<T> where(Predicate... predicates) {
    query.where(predicates);
    return this;
  }

  public QueryData<T> select() {
    if (query.getSelection() != null) {
      return this;
    }
    query.select(root);
    return this;
  }

  public <YType> Path<YType> get(String key) {
    return root.get(key);
  }

  public Root<T> root() {
    return root;
  }

  public CriteriaBuilder builder() {
    return builder;
  }

  public CriteriaQuery<Long> count(Predicate... predicates) {
    final var query = builder.createQuery(Long.class);

    //? If there are no predicates...
    if (predicates == null || predicates.length == 0 || predicates.length == 1 && predicates[0] == null) {
      query.select(builder.count(query.from(entityClass)));
    }

    //? If there are predicates...
    else {
      query.select(builder.count(query.from(entityClass))).where(predicates);
    }

    return query;
  }

  public CriteriaQuery<T> query() {
    return query;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    var that = (QueryData<?>) obj;
    return Objects.equals(this.builder, that.builder) && Objects.equals(this.query, that.query) && Objects.equals(this.root, that.root);
  }

  @Override
  public int hashCode() {
    return Objects.hash(builder, query);
  }

  @Override
  public String toString() {
    return "QueryData[" + "builder=" + builder + ", " + "query=" + query + ']';
  }
}

package io.github.the_infinite.framework.data;

import jakarta.persistence.criteria.*;

@SuppressWarnings("unused")
public class DeleteQueryData<T extends BaseEntity> {
  private final CriteriaBuilder builder;
  private final CriteriaDelete<T> query;
  private final Class<T> entityClass;
  private final Root<T> root;

  DeleteQueryData(CriteriaBuilder builder, Class<T> clazz) {
    this.builder = builder;
    this.entityClass = clazz;
    this.query = builder.createCriteriaDelete(clazz);
    this.root = this.query.from(clazz);
  }

  public Root<T> from() {
    return query.from(entityClass);
  }

  public DeleteQueryData<T> where(Predicate... predicates) {
    query.where(predicates);
    return this;
  }

  public <YType> Path<YType> get(String key) {
    return root.get(key);
  }

  public Root<T> root() {
    return root;
  }

  public CriteriaDelete<T> query() {
    return query;
  }

  public CriteriaBuilder builder() {
    return builder;
  }
}

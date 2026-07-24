package io.github.the_infinite.framework.data;

import lombok.Getter;

@Getter
@SuppressWarnings({"unused", "unchecked"})
public class BaseMongoAuditableEntity<TUser> extends BaseMongoEntity {
  private TUser createdBy;
  private TUser updatedBy;

  void setCreatedBy(Object createdBy) {
    this.createdBy = (TUser) createdBy;
  }

  void setUpdatedBy(Object updatedBy) {
    this.updatedBy = (TUser) updatedBy;
  }
}

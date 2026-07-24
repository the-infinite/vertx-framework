package io.github.the_infinite.framework.data;

import lombok.Getter;

@Getter
@SuppressWarnings("unused")
public class BaseMongoAuditableEntity<TUser> extends BaseMongoEntity {
  private TUser createdBy;
  private TUser updatedBy;

  void setCreatedBy(TUser createdBy) {
    if (createdBy instanceof BaseMongoEntity) {
      this.createdBy = createdBy;
      return;
    }

    throw new IllegalArgumentException("Created by user must be an instance of BaseMongoEntity.");
  }

  void setUpdatedBy(TUser updatedBy) {
    if (updatedBy instanceof BaseMongoEntity) {
      this.updatedBy = updatedBy;
      return;
    }

    throw new IllegalArgumentException("Updated by user must be an instance of BaseMongoEntity.");
  }
}

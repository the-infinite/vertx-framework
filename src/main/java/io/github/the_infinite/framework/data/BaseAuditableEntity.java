package io.github.the_infinite.framework.data;

import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@Getter
@MappedSuperclass
@SuppressWarnings("unused")
public class BaseAuditableEntity<TUser> extends BaseEntity {
  @ManyToOne(optional = false)
  @JoinColumn(nullable = false, updatable = false)
  private TUser createdBy;

  @ManyToOne(optional = false)
  @JoinColumn(nullable = false)
  private TUser updatedBy;

  void setCreatedBy(TUser createdBy) {
    if (createdBy instanceof BaseEntity) {
      this.createdBy = createdBy;
      return;
    }

    throw new IllegalArgumentException("Created by must be a BaseEntity");
  }

  void setUpdatedBy(TUser updatedBy) {
    if (updatedBy instanceof BaseEntity) {
      this.updatedBy = updatedBy;
      return;
    }

    throw new IllegalArgumentException("Updated by must be a BaseEntity");
  }
}

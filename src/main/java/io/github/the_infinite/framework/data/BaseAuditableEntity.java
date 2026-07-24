package io.github.the_infinite.framework.data;

import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@Getter
@MappedSuperclass
@SuppressWarnings({"unused", "unchecked"})
public class BaseAuditableEntity<TUser> extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "createdById", nullable = false, updatable = false)
  private TUser createdBy;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "updatedById", nullable = false)
  private TUser updatedBy;

  void setCreatedBy(Object createdBy) {
    if (createdBy instanceof BaseEntity) {
      this.createdBy = (TUser) createdBy;
      return;
    }

    throw new IllegalArgumentException("Created by must be a BaseEntity");
  }

  void setUpdatedBy(Object updatedBy) {
    if (updatedBy instanceof BaseEntity) {
      this.updatedBy = (TUser) updatedBy;
      return;
    }

    throw new IllegalArgumentException("Updated by must be a BaseEntity");
  }
}

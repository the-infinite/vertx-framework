package io.github.the_infinite.framework.data;

@SuppressWarnings("unused")
public class BaseMongoAuditableEntity extends BaseMongoEntity {
  private long createdById;
  private long updatedById;

  public long getCreatedById() {
    return createdById;
  }

  public void setCreatedById(long createdById) {
    this.createdById = createdById;
  }

  public long getUpdatedById() {
    return updatedById;
  }

  public void setUpdatedById(long updatedById) {
    this.updatedById = updatedById;
  }
}

package io.github.the_infinite.framework.data;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
@SuppressWarnings("unused")
public class BaseAuditableEntity extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private long createdById;

    @Column(nullable = false)
    private long updatedById;

    public long getCreatedById() {
        return createdById;
    }

    void setCreatedById(long createdById) {
        this.createdById = createdById;
    }

    public long getUpdatedById() {
        return updatedById;
    }

    void setUpdatedById(long updatedById) {
        this.updatedById = updatedById;
    }
}

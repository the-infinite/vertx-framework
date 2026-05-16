package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.utils.DataHelpers;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.*;

@SuppressWarnings("unused")
@MappedSuperclass
public abstract class BaseEntity implements Serializable {
    static final HashSet<Class<? extends BaseEntity>> ENTITY_CLASSES = new HashSet<>();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "BIGINT", updatable = false, nullable = false, unique = true)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false, columnDefinition = "UUID")
    private UUID uid;

    @CreationTimestamp
    @Column(updatable = false, nullable = false, columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private OffsetDateTime updatedAt;

    //? Okay then.
    public BaseEntity() {
        if (ENTITY_CLASSES.add(this.getClass())) {
            ConsoleLogger.getInstance().debug("Registered entity class %s".formatted(this.getClass().getName()));
        }
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public UUID getUid() {
        return uid;
    }

    void setUid(UUID uid) {
        this.uid = uid;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BaseEntity other = (BaseEntity) obj;
        return Objects.equals(id, other.id) && Objects.equals(uid, other.uid);
    }

    public boolean partiallyEquivalent(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BaseEntity other = (BaseEntity) obj;
        return Objects.equals(id, other.id) || Objects.equals(uid, other.uid);
    }

    @Override
    public String toString() {
        try {
            return DataHelpers.serializeObject(this);
        } catch (JsonProcessingException e) {
            return "BaseEntity{" + "id='" + this.id + "'" + ", uid='" + this.uid + "'" + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}" + super.toString();
        }
    }
}

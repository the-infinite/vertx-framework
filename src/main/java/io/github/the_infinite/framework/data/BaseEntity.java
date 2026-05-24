package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import io.github.the_infinite.framework.utils.DataHelpers;
import jakarta.persistence.*;
import lombok.Getter;

@SuppressWarnings("unused")
@Getter
@MappedSuperclass
public abstract class BaseEntity implements Serializable {
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
  }

  public BaseEntity setId(Long id) {
    this.id = id;
    return this;
  }

  public BaseEntity setUid(UUID uid) {
    this.uid = uid;
    return this;
  }

  public BaseEntity setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
    return this;
  }

  public BaseEntity setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
    return this;
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

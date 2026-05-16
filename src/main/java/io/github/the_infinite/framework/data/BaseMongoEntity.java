package io.github.the_infinite.framework.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.the_infinite.framework.utils.DataHelpers;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

@SuppressWarnings("unused")
public abstract class BaseMongoEntity implements Serializable {
  @JsonProperty("_id")
  private String id;

  private UUID uid;

  private OffsetDateTime createdAt;

  private OffsetDateTime updatedAt;

  public String getId() {
    return id;
  }

  public void setId(@NotNull String id) {
    this.id = id;
  }

  public UUID getUid() {
    return uid;
  }

  public void setUid(UUID uid) {
    this.uid = uid;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public String toString() {
    try {
      return DataHelpers.serializeObject(this);
    } catch (JsonProcessingException e) {
      return "BaseMongoEntity{" + "id='" + this.id + "'" + ", uid='" + this.uid + "'" + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}";
    }
  }
}

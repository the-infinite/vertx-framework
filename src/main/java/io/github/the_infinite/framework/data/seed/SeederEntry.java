package io.github.the_infinite.framework.data.seed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import io.github.the_infinite.framework.data.BaseEntity;

@Entity
@Getter
@Table(name = "__database_seeders")
@SuppressWarnings("unused")
public final class SeederEntry extends BaseEntity {
  @Column(name = "seeder_name", nullable = false, unique = true, updatable = false)
  private String seederName;

  public SeederEntry setSeederName(String seederName) {
    this.seederName = seederName;
    return this;
  }

  public enum Modules {
    SYSTEM
  }
}

package io.github.the_infinite.framework.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Getter
@Table(name = "__database_migrations")
@SuppressWarnings("unused")
final class MigrationEntry extends BaseEntity {
  @Column(name = "migration_name", nullable = false, unique = true, updatable = false)
  private String migrationName;

  @Column(name = "checksum", nullable = false, updatable = false)
  private long checksum;

  @Column(name = "data", columnDefinition = "TEXT")
  private String data;


  public MigrationEntry setData(String data) {
    this.data = data;
    return this;
  }

  public MigrationEntry setChecksum(long checksum) {
    this.checksum = checksum;
    return this;
  }

  public MigrationEntry setMigrationName(String migrationName) {
    this.migrationName = migrationName;
    return this;
  }

  enum Modules {
    SYSTEM
  }
}

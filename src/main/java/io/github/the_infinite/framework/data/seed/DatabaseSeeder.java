package io.github.the_infinite.framework.data.seed;

import io.github.the_infinite.framework.data.PersistentRepository;
import io.vertx.core.Future;

/**
 * Describes a Java-based database seeder.
 * <p>
 * Implementations should use {@link PersistentRepository} instances to create or delete entities instead of executing
 * raw SQL. Seeders are executed in ascending {@link #order()} and deleted in descending {@link #order()}.
 */
public interface DatabaseSeeder {
  /**
   * A stable, human-readable name used for logging.
   */
  default String name() {
    return getClass().getSimpleName();
  }

  /**
   * Determines the execution order when multiple seeders are used.
   */
  default int order() {
    return 0;
  }

  /**
   * Creates the data managed by this seeder.
   */
  Future<Void> seed();

  /**
   * Deletes the data managed by this seeder.
   */
  Future<Void> delete();
}

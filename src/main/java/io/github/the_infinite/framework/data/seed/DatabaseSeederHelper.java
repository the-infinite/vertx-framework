package io.github.the_infinite.framework.data.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;

/**
 * Executes Java-based database seeders in a predictable order.
 */
public final class DatabaseSeederHelper {
  private static final List<DatabaseSeeder> seeders = Collections.synchronizedList(new ArrayList<>());

  private DatabaseSeederHelper() {
  }

  public static void addSeeder(DatabaseSeeder seeder) {
    seeders.add(seeder);
  }

  public static Future<Void> seed() {
    return seed(seeders);
  }

  public static Future<Void> delete() {
    return delete(seeders);
  }

  public static Future<Void> seed(List<? extends DatabaseSeeder> seeders) {
    final var orderedSeeders = seeders.stream().sorted(Comparator.comparingInt(DatabaseSeeder::order)).toList();
    return execute(orderedSeeders, true);
  }

  public static Future<Void> delete(List<? extends DatabaseSeeder> seeders) {
    final var orderedSeeders = seeders.stream().sorted(Comparator.comparingInt(DatabaseSeeder::order).reversed()).toList();
    return execute(orderedSeeders, false);
  }

  private static Future<Void> execute(List<? extends DatabaseSeeder> seeders, boolean seed) {
    final var promise = Promise.<Void>promise();
    final var console = ConsoleLogger.getInstance();
    executeNext(seeders, seed, 0, console, promise);
    return promise.future();
  }

  private static void executeNext(List<? extends DatabaseSeeder> seeders, boolean seed, int index, ConsoleLogger console, Promise<Void> promise) {
    if (index >= seeders.size()) {
      promise.succeed();
      return;
    }

    final var seeder = seeders.get(index);
    final var action = seed ? "Seeding" : "Deleting seeded data for";
    console.info("%s %s...".formatted(action, seeder.name()));

    final var future = seed ? seeder.seed() : seeder.delete();
    future
      .onFailure(promise::fail)
      .onSuccess(ignored -> executeNext(seeders, seed, index + 1, console, promise));
  }
}

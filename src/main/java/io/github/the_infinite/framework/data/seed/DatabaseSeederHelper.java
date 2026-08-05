package io.github.the_infinite.framework.data.seed;

import org.hibernate.StatelessSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.the_infinite.framework.data.PersistentRepository;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;

/**
 * Executes Java-based database seeders in a predictable order.
 */
public final class DatabaseSeederHelper {
  private static final List<DatabaseSeeder> seeders = Collections.synchronizedList(new ArrayList<>());
  private static PersistentRepository<SeederEntry, SeederEntry.Modules> repository;

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
    return repository().doesTableExist().compose(exists -> {
      if (!exists) {
        return Future.failedFuture("Seeders table does not exist. Run migrations before executing seeders.");
      }

      return repository().getMany(null, new RepositoryOptions<SeederEntry>().setLimit(3000), null)
        .compose(entries -> repository().stateless().transaction(new RepositoryOptions<>(), transaction -> execute(
          transaction,
          orderedSeeders,
          entries.stream().map(SeederEntry::getSeederName)
            .collect(Collectors.toSet()),
          true
        )));
    });
  }

  public static Future<Void> delete(List<? extends DatabaseSeeder> seeders) {
    final var orderedSeeders = seeders.stream().sorted(Comparator.comparingInt(DatabaseSeeder::order).reversed()).toList();
    return repository().doesTableExist().compose(exists -> {
      if (!exists) {
        return Future.succeededFuture();
      }

      return repository().getMany(null, new RepositoryOptions<SeederEntry>().setLimit(3000), null)
        .compose(entries -> repository().stateless().transaction(new RepositoryOptions<>(), transaction -> execute(
          transaction,
          orderedSeeders,
          entries.stream().map(SeederEntry::getSeederName)
            .collect(Collectors.toSet()),
          false
        )));
    });
  }

  private static Future<Void> execute(StatelessSession transaction, List<?
                                        extends DatabaseSeeder> seeders,
                                      Set<String> executedSeeders, boolean seed) {
    final var promise = Promise.<Void>promise();
    final var console = ConsoleLogger.getInstance();
    executeNext( transaction, seeders, executedSeeders, seed, 0, console, promise);
    return promise.future();
  }

  private static void executeNext(
    StatelessSession transaction,
    List<? extends DatabaseSeeder> seeders,
    Set<String> executedSeeders,
    boolean seed,
    int index,
    ConsoleLogger console,
    Promise<Void> promise
  ) {
    if (index >= seeders.size()) {
      promise.succeed();
      return;
    }

    final var seeder = seeders.get(index);
    final var seederName = seeder.name();
    final var executed = executedSeeders.contains(seederName);

    if (seed && executed) {
      console.exec("Skipping already executed seeder %s.".formatted(seederName));
      executeNext(transaction, seeders, executedSeeders, true, index + 1, console, promise);
      return;
    }

    if (!seed && !executed) {
      console.exec("Skipping seeder %s because it has not been executed.".formatted(seederName));
      executeNext(transaction, seeders, executedSeeders, false, index + 1, console, promise);
      return;
    }

    final var action = seed ? "Seeding" : "Deleting seeded data for";
    console.info("%s %s...".formatted(action, seederName));

    final var future = seed ? seeder.seed(transaction) : seeder.delete(transaction);
    future
      .onFailure(promise::fail)
      .onSuccess(ignored -> track(seederName, seed)
        .onFailure(promise::fail)
        .onSuccess(v -> {
          if (seed) {
            executedSeeders.add(seederName);
          } else {
            executedSeeders.remove(seederName);
          }
          executeNext(transaction, seeders, executedSeeders, seed, index + 1, console, promise);
        }));
  }

  private static PersistentRepository<SeederEntry, SeederEntry.Modules> repository() {
    if (repository == null) {
      repository = PersistentRepository.find(SeederEntry.class);
    }

    return repository;
  }

  private static Future<?> track(String seederName, boolean seed) {
    if (seed) {
      final var entry = new SeederEntry().setSeederName(seederName);
      entry.setUid(UUID.randomUUID());
      return repository().createOne(entry, new RepositoryOptions<>());
    }

    final var query = repository().startDelete();
    return repository().deleteOne(query.where(query.builder().equal(query.get("seederName"), seederName)), new RepositoryOptions<>());
  }
}

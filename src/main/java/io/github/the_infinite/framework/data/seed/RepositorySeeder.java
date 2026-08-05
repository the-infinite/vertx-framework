package io.github.the_infinite.framework.data.seed;

import org.hibernate.Session;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Function;

import io.github.the_infinite.framework.data.BaseEntity;
import io.github.the_infinite.framework.data.PersistentRepository;
import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.vertx.core.Future;

/**
 * Base class for seeders that manage one entity type through {@link PersistentRepository}.
 */
public abstract class RepositorySeeder<TModel extends BaseEntity, TModule extends Enum<?>> implements DatabaseSeeder {
  protected final PersistentRepository<TModel, TModule> repository;

  protected RepositorySeeder(@NotNull Class<TModel> modelType) {
    this.repository = PersistentRepository.find(modelType);
  }

  protected RepositorySeeder(@NotNull PersistentRepository<TModel, TModule> repository) {
    this.repository = repository;
  }

  protected RepositoryOptions<TModel> options() {
    return new RepositoryOptions<>();
  }

  protected Future<Void> createOne(@NotNull TModel item) {
    return repository.createOne(item, options()).mapEmpty();
  }

  protected Future<Void> createMany(@NotNull List<TModel> items) {
    return repository.createMany(items, options()).mapEmpty();
  }

  protected Future<Boolean> deleteOne() {
    return repository.deleteOne(null, options());
  }

  protected Future<Integer> deleteMany() {
    return repository.deleteMany(null, options());
  }

  protected <T> Future<T> transaction(@NotNull RepositoryOptions<TModel> options, @NotNull Function<Session, Future<T>> future) {
    return repository.transaction(options, future);
  }
}

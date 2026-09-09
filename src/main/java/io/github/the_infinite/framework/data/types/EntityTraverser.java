package io.github.the_infinite.framework.data.types;

/**
 * Functional interface for intentional lazy-graph traversal.
 *
 * <p>Attached to {@link RepositoryOptions} via {@link RepositoryOptions#withTraverser(EntityTraverser)}.
 * The framework calls it while the Hibernate {@code Session} is still open (worker thread),
 * giving the caller a {@link Traverser} to explicitly {@link Traverser#initialize(Object)} only
 * the associations it actually needs. This replaces the legacy auto-recursive
 * {@code initializeLazyState} that walked every {@code FetchTiming.DELAYED} / {@code ToOne}
 * mapping to depth 2 and broke on cyclic graphs like {@code Business ↔ MoovableUser}.</p>
 *
 * @param <T> entity type
 */
@FunctionalInterface
public interface EntityTraverser<T> {
  /**
   * Called once per loaded root entity, still inside the session boundary.
   *
   * @param entity   the managed entity (already {@link org.hibernate.Hibernate#initialize(Object)}'d)
   * @param traverser helper to open child properties; call {@link Traverser#initialize(Object)}
   *                  for each association you need after detach / JSON serialization
   */
  void traverse(T entity, Traverser traverser);
}

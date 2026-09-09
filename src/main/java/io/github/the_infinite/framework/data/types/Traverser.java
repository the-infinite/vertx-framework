package io.github.the_infinite.framework.data.types;

import org.hibernate.Hibernate;

import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explicit helper for opening lazy associations while still inside the worker-thread
 * session boundary. Passed to {@link EntityTraverser} so callers are intentional
 * about which graph edges to initialize.
 *
 * <p>Unlike the legacy auto-recursive initializer, this does not walk the metamodel
 * reflectively nor recurse to depth 2+. The caller decides the precise paths.</p>
 *
 * <p>Example:</p>
 * <pre>
 *   repo.getOne(filter, new RepositoryOptions&lt;MoovableUser&gt;(user, ctx)
 *     .withTraverser((u, t) -&gt; {
 *       t.initialize(u.getBusinessStaffProfile());
 *       t.initialize(u.getBusinessStaffProfile().getBusiness());
 *       t.initialize(u.getRegion());
 *     }))
 * </pre>
 */
@SuppressWarnings("unused")
public final class Traverser {

  /**
   * Force-initializes a possibly lazy proxy/collection. Safe with {@code null}.
   */
  public void initialize(Object proxy) {
    if (proxy != null) {
      Hibernate.initialize(proxy);
    }
  }

  /**
   * Reflectively initializes all direct lazy associations of the entity
   * (fields annotated with {@code @ManyToOne(fetch=LAZY)} etc. and
   * {@code OneToMany(fetch=LAZY)}). Does not recurse — caller must
   * explicitly chain for deeper graphs. Used as a fallback for generic
   * traversers when the caller has not specified precise edges.
   */
  public void initializeAllLazy(Object entity) {
    if (entity == null) return;
    Class<?> clazz = Hibernate.getClass(entity);
    if (clazz == null) clazz = entity.getClass();
    for (var field : clazz.getDeclaredFields()) {
      var annManyOne = field.getAnnotation(jakarta.persistence.ManyToOne.class);
      var annOneOne = field.getAnnotation(jakarta.persistence.OneToOne.class);
      var annOneMany = field.getAnnotation(jakarta.persistence.OneToMany.class);
      var annManyMany = field.getAnnotation(jakarta.persistence.ManyToMany.class);
      boolean lazy;
      if (annManyOne != null) lazy = annManyOne.fetch() == jakarta.persistence.FetchType.LAZY;
      else if (annOneOne != null) lazy = annOneOne.fetch() == jakarta.persistence.FetchType.LAZY;
      else if (annOneMany != null) lazy = annOneMany.fetch() == jakarta.persistence.FetchType.LAZY;
      else if (annManyMany != null) lazy = annManyMany.fetch() == jakarta.persistence.FetchType.LAZY;
      else continue;
      if (!lazy) continue;
      try {
        field.setAccessible(true);
        Object target = field.get(Hibernate.unproxy(entity));
        if (target != null) Hibernate.initialize(target);
      } catch (Exception ignored) {
      }
    }
  }

  /**
   * Fetches via supplier, initializes it, and returns it. Useful for chaining.
   */
  public <U> U fetch(Supplier<U> getter) {
    if (getter == null) return null;
    U value = getter.get();
    initialize(value);
    return value;
  }

  /**
   * Initializes a collection (lazy {@code Set} / {@code List}).
   */
  public <U> Collection<U> fetchCollection(Collection<U> collection) {
    initialize(collection);
    return collection;
  }

  /**
   * Initializes the value returned by {@code getter} applied to {@code entity}.
   * Returns the initialized value.
   */
  public <T, R> R get(T entity, Function<T, R> getter) {
    if (entity == null || getter == null) return null;
    R value = getter.apply(entity);
    initialize(value);
    return value;
  }
}

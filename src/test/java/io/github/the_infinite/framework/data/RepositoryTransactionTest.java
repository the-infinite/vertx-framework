package io.github.the_infinite.framework.data;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.the_infinite.framework.data.types.RepositoryOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryTransactionTest {
  @Test
  void statefulTransactionCompletesAfterCommit() {
    final var commits = new AtomicInteger();
    final var actor = new StatefulRepositoryActor<BaseEntity>(sessionFactory(Session.class, commits, new AtomicInteger(), new AtomicInteger()), BaseEntity.class);
    final var outcome = new AtomicReference<AsyncResult<String>>();

    actor.transaction(new RepositoryOptions<>(), ignored -> Future.succeededFuture("complete")).onComplete(outcome::set);

    assertEquals(1, commits.get());
    assertNotNull(outcome.get());
    assertTrue(outcome.get().succeeded());
    assertEquals("complete", outcome.get().result());
  }

  @Test
  void statefulTransactionRollsBackAndClosesOnFailure() {
    final var commits = new AtomicInteger();
    final var rollbacks = new AtomicInteger();
    final var closes = new AtomicInteger();
    final var actor = new StatefulRepositoryActor<BaseEntity>(sessionFactory(Session.class, commits, rollbacks, closes), BaseEntity.class);
    final var outcome = new AtomicReference<AsyncResult<String>>();

    actor.transaction(new RepositoryOptions<>(), ignored -> Future.<String>failedFuture("boom")).onComplete(outcome::set);

    assertEquals(0, commits.get());
    assertEquals(1, rollbacks.get());
    assertEquals(1, closes.get());
    assertNotNull(outcome.get());
    assertTrue(outcome.get().failed());
  }

  @Test
  void statefulTransactionRollsBackAndClosesOnSynchronousThrow() {
    final var commits = new AtomicInteger();
    final var rollbacks = new AtomicInteger();
    final var closes = new AtomicInteger();
    final var actor = new StatefulRepositoryActor<BaseEntity>(sessionFactory(Session.class, commits, rollbacks, closes), BaseEntity.class);
    final var outcome = new AtomicReference<AsyncResult<String>>();

    actor.transaction(new RepositoryOptions<>(), (java.util.function.Function<Session, Future<String>>) ignored -> {
      throw new IllegalStateException("sync");
    }).onComplete(outcome::set);

    assertEquals(0, commits.get());
    assertEquals(1, rollbacks.get());
    assertEquals(1, closes.get());
    assertNotNull(outcome.get());
    assertTrue(outcome.get().failed());
  }

  @Test
  void statelessTransactionCompletesAfterCommit() {
    final var commits = new AtomicInteger();
    final var actor = new StatelessRepositoryActor<BaseEntity>(sessionFactory(StatelessSession.class, commits, new AtomicInteger(), new AtomicInteger()), BaseEntity.class);
    final var outcome = new AtomicReference<AsyncResult<String>>();

    actor.transaction(new RepositoryOptions<>(), ignored -> Future.succeededFuture("complete")).onComplete(outcome::set);

    assertEquals(1, commits.get());
    assertNotNull(outcome.get());
    assertTrue(outcome.get().succeeded());
    assertEquals("complete", outcome.get().result());
  }

  @Test
  void statelessTransactionRollsBackAndClosesOnFailure() {
    final var commits = new AtomicInteger();
    final var rollbacks = new AtomicInteger();
    final var closes = new AtomicInteger();
    final var actor = new StatelessRepositoryActor<BaseEntity>(sessionFactory(StatelessSession.class, commits, rollbacks, closes), BaseEntity.class);
    final var outcome = new AtomicReference<AsyncResult<String>>();

    actor.transaction(new RepositoryOptions<>(), ignored -> Future.<String>failedFuture("boom")).onComplete(outcome::set);

    assertEquals(0, commits.get());
    assertEquals(1, rollbacks.get());
    assertEquals(1, closes.get());
    assertNotNull(outcome.get());
    assertTrue(outcome.get().failed());
  }

  @Test
  void statelessTransactionRollsBackAndClosesOnSynchronousThrow() {
    final var commits = new AtomicInteger();
    final var rollbacks = new AtomicInteger();
    final var closes = new AtomicInteger();
    final var actor = new StatelessRepositoryActor<BaseEntity>(sessionFactory(StatelessSession.class, commits, rollbacks, closes), BaseEntity.class);
    final var outcome = new AtomicReference<AsyncResult<String>>();

    actor.transaction(new RepositoryOptions<>(), (java.util.function.Function<StatelessSession, Future<String>>) ignored -> {
      throw new IllegalStateException("sync");
    }).onComplete(outcome::set);

    assertEquals(0, commits.get());
    assertEquals(1, rollbacks.get());
    assertEquals(1, closes.get());
    assertNotNull(outcome.get());
    assertTrue(outcome.get().failed());
  }

  private static SessionFactory sessionFactory(Class<?> sessionType, AtomicInteger commits, AtomicInteger rollbacks, AtomicInteger closes) {
    final var transaction = Proxy.newProxyInstance(
      RepositoryTransactionTest.class.getClassLoader(),
      new Class<?>[]{Transaction.class},
      (proxy, method, args) -> {
        switch (method.getName()) {
          case "commit" -> commits.incrementAndGet();
          case "rollback" -> rollbacks.incrementAndGet();
          case "isActive" -> {
            return true;
          }
        }
        return defaultValue(method.getReturnType());
      }
    );
    final var session = Proxy.newProxyInstance(
      RepositoryTransactionTest.class.getClassLoader(),
      new Class<?>[]{sessionType},
      (proxy, method, args) -> {
        switch (method.getName()) {
          case "beginTransaction" -> {
            return transaction;
          }
          case "getTransaction" -> {
            return transaction;
          }
          case "close" -> {
            closes.incrementAndGet();
            return null;
          }
        }
        return defaultValue(method.getReturnType());
      }
    );

    return (SessionFactory) Proxy.newProxyInstance(
      RepositoryTransactionTest.class.getClassLoader(),
      new Class<?>[]{SessionFactory.class},
      (proxy, method, args) -> method.getName().equals("openStatelessSession") || method.getName().equals("openSession")
        ? session
        : defaultValue(method.getReturnType())
    );
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}

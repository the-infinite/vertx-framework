package io.github.the_infinite.framework.logging.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.monitoring.ILogMonitor;
import io.github.the_infinite.framework.monitoring.MonitoringEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

class MonitorLoggerTest {
  private final List<Vertx> vertxInstances = new ArrayList<>();

  @AfterEach
  void tearDown() throws Exception {
    for (final var vertx : vertxInstances) {
      vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    vertxInstances.clear();
  }

  @Test
  void publishesImmediatelyWhenBatchSizeIsOne() throws Exception {
    final var vertx = Vertx.vertx();
    vertxInstances.add(vertx);
    final var monitor = new FakeMonitor();
    final var logger = MonitorLogger.create(
      vertx,
      monitor,
      new MonitorLogger.Options()
        .setShouldBatch(true)
        .setBatchSize(1)
        .setBatchInterval(60)
        .setShouldRetry(false)
    );
    final var context = CorrelationContext.from(vertx.getOrCreateContext());

    logger.info(context, LogEvent.create("ready", "MonitorLoggerTest", Map.of("step", "batch-size-1")))
      .toCompletionStage()
      .toCompletableFuture()
      .get(5, TimeUnit.SECONDS);

    assertEquals(1, monitor.batchedEvents.size());
    assertEquals(1, monitor.batchedEvents.get(0).size());
    assertEquals("ready", monitor.batchedEvents.get(0).get(0).message());

    logger.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  void queuedBatchCompletesCallersAndFlushesRemainingEvents() throws Exception {
    final var vertx = Vertx.vertx();
    vertxInstances.add(vertx);
    final var monitor = new FakeMonitor();
    final var logger = MonitorLogger.create(
      vertx,
      monitor,
      new MonitorLogger.Options()
        .setShouldBatch(true)
        .setBatchSize(5)
        .setBatchInterval(60)
        .setShouldRetry(false)
    );
    final var context = CorrelationContext.from(vertx.getOrCreateContext());

    logger.info(context, LogEvent.create("queued", "MonitorLoggerTest", Map.of("step", "queued")))
      .toCompletionStage()
      .toCompletableFuture()
      .get(5, TimeUnit.SECONDS);

    assertTrue(monitor.batchedEvents.isEmpty(), "queued events should remain buffered until explicitly flushed or a batch fills");

    logger.flush().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertEquals(1, monitor.batchedEvents.size());
    assertEquals(1, monitor.batchedEvents.get(0).size());
    assertEquals("queued", monitor.batchedEvents.get(0).get(0).message());

    logger.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  void nonBatchedLoggerPublishesSinglesAndDelegatesFlushToMonitor() throws Exception {
    final var vertx = Vertx.vertx();
    vertxInstances.add(vertx);
    final var monitor = new FakeMonitor();
    final var logger = MonitorLogger.create(vertx, monitor);
    final var context = CorrelationContext.from(vertx.getOrCreateContext());

    logger.info(context, LogEvent.create("single", "MonitorLoggerTest", Map.of("mode", "single")))
      .toCompletionStage()
      .toCompletableFuture()
      .get(5, TimeUnit.SECONDS);

    assertEquals(1, monitor.singleEvents.size());
    assertEquals("single", monitor.singleEvents.get(0).message());

    logger.flush().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    assertEquals(1, monitor.flushCount.get());
    logger.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static final class FakeMonitor implements ILogMonitor {
    private final AtomicLong ids = new AtomicLong(0);
    private final AtomicInteger flushCount = new AtomicInteger(0);
    private final List<MonitoringEvent> singleEvents = new CopyOnWriteArrayList<>();
    private final List<List<MonitoringEvent>> batchedEvents = new CopyOnWriteArrayList<>();

    @Override
    public Future<Long> getProvisionalId() {
      return Future.succeededFuture(ids.incrementAndGet());
    }

    @Override
    public Future<Collection<Long>> getIdsFor(int count) {
      final var values = new ArrayList<Long>(count);
      for (int i = 0; i < count; i++) {
        values.add(ids.incrementAndGet());
      }
      return Future.succeededFuture(values);
    }

    @Override
    public Future<Void> publishEvent(MonitoringEvent event) {
      singleEvents.add(event);
      return Future.succeededFuture();
    }

    @Override
    public Future<BulkResult> publishEvents(Collection<MonitoringEvent> events) {
      batchedEvents.add(List.copyOf(events));
      return Future.succeededFuture(new BulkResult(events.size(), 0, null));
    }

    @Override
    public Future<BulkResult> streamEvents(Stream<MonitoringEvent> events) {
      final var collected = events.toList();
      batchedEvents.add(collected);
      return Future.succeededFuture(new BulkResult(collected.size(), 0, null));
    }

    @Override
    public Future<BulkResult> flushEvents() {
      flushCount.incrementAndGet();
      return Future.succeededFuture(new BulkResult(0, 0, List.of()));
    }
  }
}

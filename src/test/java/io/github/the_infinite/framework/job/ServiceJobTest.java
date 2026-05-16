package io.github.the_infinite.framework.job;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.github.the_infinite.framework.monitoring.ILogMonitor;
import io.github.the_infinite.framework.monitoring.MonitoringEvent;
import io.github.the_infinite.framework.response.ServiceResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

class ServiceJobTest {
  private Vertx vertx;
  private Vertx secondaryVertx;

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    if (secondaryVertx != null) {
      secondaryVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void scheduledJobsExposeScheduleAndMetadata() {
    vertx = Vertx.vertx();
    final var job = new StubServiceJob(vertx, new NoopMonitor(), new ServiceJob.TimeOfDay(8, 30, 15));

    final var stats = job.getStats();

    assertEquals("EXACT", stats.getString("type"));
    assertEquals("8:30:15", stats.getString("schedule"));
    assertFalse(stats.containsKey("period"));
    assertEquals(ServiceJob.JobType.EXACT, job.getType());
    assertEquals(new ServiceJob.TimeOfDay(8, 30, 15), job.getSchedule());
    assertEquals(0, job.getPeriod());
    assertFalse(job.isDeferred());
    assertFalse(job.isCriticalLoggingEnabled());
    assertTrue(job.shouldConsume());
    assertTrue(job.isConsumptionEnabled());
    assertNull(job.getLastRun());
    assertNull(job.getLastResult());
  }

  @Test
  void periodicJobsTrackMetricsSafely() {
    vertx = Vertx.vertx();
    final var job = new StubServiceJob(vertx, new NoopMonitor(), 30);

    job.saveMetrics("done");
    job.markSuccess();
    job.markFailure();

    final var stats = job.getStats();

    assertEquals("PERIODIC", stats.getString("type"));
    assertEquals(ServiceJob.JobType.PERIODIC, job.getType());
    assertEquals(30, stats.getInteger("period"));
    assertNull(job.getSchedule());
    assertEquals(1, job.getSuccessfulRuns());
    assertEquals(1, job.getFailedRuns());
    assertEquals(2, job.getRuns());
    assertEquals("done", job.getLastResult());
    assertEquals(2, stats.getInteger("totalRuns"));
    assertNotNull(job.getLastRun());
  }

  @Test
  void registriesAreIsolatedPerVertxInstance() {
    vertx = Vertx.vertx();
    secondaryVertx = Vertx.vertx();

    new StubServiceJob(vertx, new NoopMonitor(), 30);
    new AlternateServiceJob(secondaryVertx, new NoopMonitor(), 45);

    assertEquals(1, JobRegistry.getInstance(vertx).jobCount());
    assertEquals(1, JobRegistry.getInstance(secondaryVertx).jobCount());
  }

  private static final class StubServiceJob extends ServiceJob<String> {
    private StubServiceJob(Vertx vertx, ILogMonitor monitor, int period) {
      super(vertx, monitor, period);
    }

    private StubServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule) {
      super(vertx, monitor, schedule);
    }

    @Override
    protected Future<ServiceResult<String>> run() {
      return Future.succeededFuture(new ServiceResult<>("success", "done", "data", 200));
    }

    @Override
    protected Future<Void> stopGracefully() {
      return Future.succeededFuture();
    }
  }

  private static final class AlternateServiceJob extends ServiceJob<String> {
    private AlternateServiceJob(Vertx vertx, ILogMonitor monitor, int period) {
      super(vertx, monitor, period);
    }

    @Override
    protected Future<ServiceResult<String>> run() {
      return Future.succeededFuture(new ServiceResult<>("success", "alternate", "data", 200));
    }

    @Override
    protected Future<Void> stopGracefully() {
      return Future.succeededFuture();
    }
  }

  private static final class NoopMonitor implements ILogMonitor {
    private final AtomicLong ids = new AtomicLong(0);

    @Override
    public Future<Long> getProvisionalId() {
      return Future.succeededFuture(ids.incrementAndGet());
    }

    @Override
    public Future<Collection<Long>> getIdsFor(int count) {
      return Future.succeededFuture(Stream.generate(ids::incrementAndGet).limit(count).toList());
    }

    @Override
    public Future<Void> publishEvent(MonitoringEvent event) {
      return Future.succeededFuture();
    }

    @Override
    public Future<BulkResult> publishEvents(Collection<MonitoringEvent> events) {
      return Future.succeededFuture(new BulkResult(events.size(), 0, null));
    }

    @Override
    public Future<BulkResult> streamEvents(Stream<MonitoringEvent> events) {
      final var collected = events.toList();
      return Future.succeededFuture(new BulkResult(collected.size(), 0, null));
    }

    @Override
    public Future<BulkResult> flushEvents() {
      return Future.succeededFuture(new BulkResult(0, 0, null));
    }
  }
}

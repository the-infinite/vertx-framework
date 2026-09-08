package io.github.the_infinite.framework.job;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.the_infinite.framework.logging.TimeEvent;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import io.github.the_infinite.framework.logging.monitor.LogEvent;
import io.github.the_infinite.framework.response.ErrorResult;
import io.github.the_infinite.framework.response.ServiceResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Registry responsible for tracking and scheduling {@link ServiceJob} instances for a single {@link Vertx} runtime.
 * <p>
 * Improvements over the naive scheduler:
 * <ul>
 *   <li>Per-job overlap guard: a job is never run concurrently with itself. If a tick fires while the previous run
 *       is still in flight, the new run is skipped (rather than piling up behind a blocking/slow handler).</li>
 *   <li>Logging failures are decoupled from run success: a transient failure to emit the run's log does not mark the
 *       job run itself as failed.</li>
 *   <li>Graceful shutdown drains in-flight runs before invoking {@code stopGracefully} on each job.</li>
 *   <li>Daily (EXACT) jobs are keyed on the calendar day, not an arbitrary 24h window, so a late-night run does not
 *       suppress the next day's execution.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class JobRegistry {
  private final static Map<Vertx, JobRegistry> instances = Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<String, ServiceJob<?>> jobs = new ConcurrentHashMap<>();
  private final Map<String, TimeEvent> times = new ConcurrentHashMap<>();
  private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
  private final Queue<Future<?>> inFlight = new ConcurrentLinkedQueue<>();
  private final ArrayList<Long> timers;
  private final CorrelationContext context;
  private final Vertx vertx;
  private volatile boolean shutdownHookRegistered;

  private JobRegistry(Vertx vertx) {
    this.vertx = vertx;
    this.timers = new ArrayList<>();
    this.context = CorrelationContext.from(vertx.getOrCreateContext());
    this.shutdownHookRegistered = false;
  }

  /**
   * Resolved lazily because the console is only guaranteed to exist once the configuration registrant has been
   * set up, which happens after the registry singleton is first requested.
   */
  private ConsoleLogger console() {
    return ConsoleLogger.getInstance(vertx);
  }

  /**
   * Returns the singleton registry associated with the provided Vert.x instance.
   */
  public static JobRegistry getInstance(Vertx vertx) {
    synchronized (instances) {
      var existing = instances.get(vertx);
      if (existing != null) return existing;
      var created = new JobRegistry(vertx);
      instances.put(vertx, created);
      return created;
    }
  }

  private String getRunId(ServiceJob<?> job) {
    return "%s-%d".formatted(job.getClass().getSimpleName(), job.getRuns() + 1);
  }

  private void markStart(ServiceJob<?> job) {
    final var id = getRunId(job);
    final var time = job.logger.time(context, LogEvent.create("Completed run %s".formatted(id), id, job.getStats().getMap()));
    times.put(id, time);
  }

  private void markEnd(ServiceJob<?> job) {
    final var id = getRunId(job);

    if (!times.containsKey(id)) {
      throw new IllegalStateException("No start time recorded for '%s'".formatted(id));
    }

    //? See it to the end.
    final var time = times.get(id);
    time.end();
  }

  private void markEndIfStarted(ServiceJob<?> job) {
    final var id = getRunId(job);
    final var time = times.remove(id);
    if (time != null) {
      time.end();
    }
  }

  private void handleFailure(ServiceJob<?> job, Throwable cause) {
    final var error = ErrorResult.of(cause);
    final var parsed = error.toServiceResult();
    markEndIfStarted(job);
    job.markFailure();
    job.logger.error(context, LogEvent.create(error.getMessage(), getRunId(job), Map.of("data", parsed.getData(), "code", parsed.getCode())));
  }

  private <T> Future<Void> runJob(ServiceJob<T> job) {
    final var runningFlag = running.computeIfAbsent(job.getClass().getName(), k -> new AtomicBoolean(false));

    //? Never run the same job on top of itself. A slow/blocking run is skipped until the next tick.
    if (!runningFlag.compareAndSet(false, true)) {
      console().warn("Skipping overlapping run of job '%s': a previous run is still in progress.".formatted(job.getClass().getSimpleName()));
      return Future.succeededFuture();
    }

    final var id = getRunId(job);
    markStart(job);

    Future<ServiceResult<T>> runFuture;
    try {
      runFuture = job.run();
    } catch (Throwable t) {
      runFuture = Future.failedFuture(t);
    }
    if (runFuture == null) {
      runFuture = Future.failedFuture(new NullPointerException("job.run() returned a null Future"));
    }

    final var tracked = runFuture.andThen(runResult -> {
      try {
        if (runResult.failed()) {
          handleFailure(job, runResult.cause());
        } else {
          final var result = runResult.result();
          job.saveMetrics(result.getData());
          markEnd(job);
          times.remove(id);
          job.markSuccess();

          //? Emit the run log fire-and-forget. A logging failure must never be reported as a job failure.
          job.logger.exec(context, LogEvent.create(result.getMessage(), id, Map.of("data", result.getData())))
            .onFailure(logErr -> console().error("Failed to emit log for job run '%s': %s".formatted(id, logErr.getMessage())));
        }
      } finally {
        runningFlag.set(false);
      }
    });

    inFlight.add(tracked);
    tracked.onComplete(v -> inFlight.remove(tracked));

    return tracked.mapEmpty();
  }

  /**
   * Returns the number of jobs currently registered against this registry.
   */
  public int jobCount() {
    return jobs.size();
  }

  void addJob(ServiceJob<?> job) {
    jobs.put(job.getClass().getName(), job);
  }

  private Map<String, JsonObject> getStatistics() {
    final var stats = new HashMap<String, JsonObject>();
    for (final var job : jobs.values()) {
      stats.put(job.getClass().getName(), job.getStats());
    }
    return stats;
  }

  /**
   * Stops all registered jobs and cancels active timers for this registry.
   * <p>
   * In-flight job runs are awaited before {@code stopGracefully} is invoked on each job, so a shutdown does not
   * abandon work that is mid-execution.
   */
  public Future<Void> stopJobs() {
    final var promise = Promise.<Void>promise();

    for (final var timer : timers) {
      vertx.cancelTimer(timer);
    }
    timers.clear();

    final var draining = new ArrayList<>(inFlight);
    final Future<Void> drainFuture = draining.isEmpty()
      ? Future.succeededFuture()
      : Future.join(draining).mapEmpty();

    drainFuture.onComplete(drainResult -> {
      if (drainResult.failed()) {
        console().warn("Some in-flight jobs failed while draining during shutdown.");
      }

      final var stopFutures = new ArrayList<Future<?>>();
      for (final var job : jobs.values()) {
        try {
          stopFutures.add(job.stopGracefully());
        } catch (Throwable t) {
          console().error("Failed to stop job '%s' gracefully: %s".formatted(job.getClass().getSimpleName(), t.getMessage()));
        }
      }

      if (stopFutures.isEmpty()) {
        promise.succeed();
        return;
      }

      Future.join(stopFutures).onSuccess(v -> promise.succeed()).onFailure(promise::fail);
    });

    return promise.future();
  }

  /**
   * Starts all registered jobs according to their configured execution model.
   */
  public Future<Void> runJobs() {
    final var promise = context.<Void>promise();
    final var promises = new ArrayList<Future<?>>();
    final var timedJobs = new ArrayList<ServiceJob<?>>();

    //? For each job we have here...
    for (final var job : jobs.values()) {
      //? First, if this is a periodic job, schedule it to run after a given timeline...
      if (job.myType == ServiceJob.JobType.PERIODIC) {
        timers.add(vertx.setPeriodic(job.period, timer -> runJob(job)));
      }

      //? Since this is not a periodic job...
      else {
        timedJobs.add(job);
      }

      //? Skip the first-out-of-step run for deferred jobs.
      if (job.deferred) continue;
      promises.add(runJob(job));
    }

    //? Register our on-exit hook to terminate all jobs.
    if (!shutdownHookRegistered) {
      synchronized (this) {
        if (!shutdownHookRegistered) {
          Runtime.getRuntime().addShutdownHook(new Thread(() -> stopJobs().onComplete(stopResult -> {
            if (stopResult.failed()) {
              final var error = ErrorResult.of(stopResult.cause());
              console().error("Failed to stop jobs gracefully: %s".formatted(error.getMessage()));
            }

            try {
              System.exit(0);
            } catch (Throwable e) {
              final var error = ErrorResult.of(e);
              console().error("Failed to exit gracefully: %s".formatted(error.getMessage()));
            }
          })));
          shutdownHookRegistered = true;
        }
      }
    }

    //? Run them the first time if they are all defined here for us.
    final var advanceFuture = promises.isEmpty() ? Future.succeededFuture() : Future.all(promises);

    //? Schedule the time-of-day jobs. This happens regardless of whether the initial (non-deferred) runs succeeded,
    //? because a transient failure on startup must not prevent the recurring schedule from being installed.
    advanceFuture.onComplete(initialRunResult -> {
      if (initialRunResult.failed()) {
        final var error = ErrorResult.of(initialRunResult.cause());
        console().error("One or more initial job runs failed: %s".formatted(error.getMessage()));
      }

      final var calendar = Calendar.getInstance();
      // Check once per minute, not every second, to reduce wake-ups and heap churn
      timers.add(vertx.setPeriodic(60_000, timer -> {
        final var now = new Date();
        for (final var job : timedJobs) {
          final var schedule = job.getSchedule();
          if (schedule == null) {
            console().error("Scheduled job '%s' has no schedule configured".formatted(job.getClass().getSimpleName()));
            continue;
          }

          calendar.setTime(now);
          final var hourMatch = schedule.hour() == calendar.get(Calendar.HOUR_OF_DAY);
          final var minuteMatch = schedule.minute() == calendar.get(Calendar.MINUTE);
          final var secondMatch = calendar.get(Calendar.SECOND) >= schedule.second();

          //? Once per calendar day.
          final var lastRun = job.getLastRun();
          if (lastRun != null && isSameCalendarDay(lastRun, now)) continue;

          if (hourMatch && minuteMatch && secondMatch) {
            runJob(job);
          }
        }
      }));

      //? The promise is then completed...
      promise.succeed();
    });

    //? Return a future that denotes this promise.
    return promise.future();
  }

  /**
   * True when {@code first} and {@code second} fall on the same calendar day (year/month/day). Unlike a naive
   * 24-hour window, this correctly handles jobs that last ran late on the previous day.
   */
  private static boolean isSameCalendarDay(Date first, Date second) {
    final var a = Calendar.getInstance();
    final var b = Calendar.getInstance();
    a.setTime(first);
    b.setTime(second);
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
      && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
      && a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH);
  }
}

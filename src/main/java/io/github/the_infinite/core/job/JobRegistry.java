package io.github.the_infinite.core.job;

import io.github.the_infinite.core.logging.TimeEvent;
import io.github.the_infinite.core.logging.console.ConsoleLogger;
import io.github.the_infinite.core.logging.correlation.CorrelationContext;
import io.github.the_infinite.core.logging.monitor.LogEvent;
import io.github.the_infinite.core.response.ErrorResult;
import io.github.the_infinite.core.utils.DateUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@SuppressWarnings("unused")
public final class JobRegistry {
  private final static Map<String, ServiceJob<?>> jobs = new ConcurrentHashMap<>();
  private final static Map<String, TimeEvent> times = new ConcurrentHashMap<>();
  private static final int batchedResults = 20;
  private final ArrayList<Long> timers;
  private final CorrelationContext context;

  private final Vertx vertx;

  public JobRegistry(Vertx vertx) {
    this.vertx = vertx;
    this.timers = new ArrayList<>();
    this.context = CorrelationContext.from(vertx.getOrCreateContext());
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

  private <T> void runJob(ServiceJob<T> job) {
    final var id = getRunId(job);
    try {
      markStart(job);
      job.run().andThen((runResult) -> {
        if (!runResult.succeeded()) {
          final var error = ErrorResult.of(runResult.cause());
          final var parsed = error.toServiceResult();
          job.logger.error(context, LogEvent.create(error.getMessage(), getRunId(job), Map.of("data", parsed.getData(), "code", parsed.getCode())));
          return;
        }

        final var result = runResult.result();
        job.saveMetrics(result.getData());

        job.logger.exec(context, LogEvent.create(result.getMessage(), id, Map.of("data", result.getData()))).andThen(loggingResult -> {
          if (!loggingResult.succeeded()) {
            final var error = ErrorResult.of(loggingResult.cause());
            final var parsed = error.toServiceResult();
            job.logger.error(context, LogEvent.create(error.getMessage(), getRunId(job), Map.of("data", parsed.getData(), "code", parsed.getCode())));
            return;
          }

          //? Save this.
          this.markEnd(job);
          job.markSuccess();
        });
      });
    } catch (Throwable e) {
      final var error = ErrorResult.of(e);
      final var parsed = error.toServiceResult();
      job.logger.error(context, LogEvent.create(error.getMessage(), getRunId(job), Map.of("data", parsed.getData(), "code", parsed.getCode())));
    }

  }

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

  public Future<Void> stopJobs() {
    final var promise = Promise.<Void>promise();

    for (final var timer : timers) {
      vertx.cancelTimer(timer);
    }

    vertx.executeBlocking(() -> {
      for (final var job : jobs.values()) {
        job.stopGracefully().andThen(result -> {
          if (result.succeeded()) {
            this.markEnd(job);
          }
        });
      }

      return null;
    });

    return promise.future();
  }

  public Future<Void> runJobs() {
    final var promise = context.<Void>promise();
    final var promises = new ArrayList<Future<?>>();
    final var timedJobs = new ArrayList<ServiceJob<?>>();
    final var console = ConsoleLogger.getInstance(vertx);
    final var allJobs = jobs.values();
    Future<?> advanceFuture = Future.succeededFuture();

    //? For each job we have here...
    for (final var job : allJobs) {
      //? First, if this is a periodic job, schedule it to run after a given timeline...
      if (job.myType == ServiceJob.JobType.PERIODIC) {
        timers.add(vertx.setPeriodic(job.period, timer -> runJob(job)));
      }

      //? Since this is not a periodic job...
      else {
        timedJobs.add(job);
      }

      //? Skip the first out of step run for deferred jobs.
      if (job.deferred) continue;
      promises.add(job.run());
    }

    //? Register our on-exit hook to terminate all jobs.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> stopJobs().andThen(stopResult -> {
      if (stopResult.failed()) {
        final var error = ErrorResult.of(stopResult.cause());
        console.error("Failed to stop jobs gracefully: %s".formatted(error.getMessage()));
        return;
      }

      try {
        System.exit(0);
      } catch (Throwable e) {
        final var error = ErrorResult.of(e);
        console.error("Failed to exit gracefully: %s".formatted(error.getMessage()));
      }
    })));

    //? Run them the first time if they are all defined here for us.
    if (!promises.isEmpty()) {
      advanceFuture = Future.all(promises);
    }

    //? If this is a job with an intervally
    advanceFuture.andThen(initialRunResult -> {
      if (initialRunResult.failed()) {
        final var error = ErrorResult.of(initialRunResult.cause());
        console.error("Failed to start jobs: %s".formatted(error.getMessage()));
        return;
      }

      //? Now, run all scheduled jobs.
      final var calendar = Calendar.getInstance();
      timers.add(vertx.setPeriodic(1000, timer -> {
        for (final var job : timedJobs) {
          if (job.schedule == null) {
            console.error("Invalid schedule job found as a scheduled job seems to have no schedule configured");
            continue;
          }

          final var now = new Date();
          final var schedule = job.schedule;
          final var lastRun = job.lastRun;

          //? Update the reference time.
          calendar.setTime(now);

          //? Match the times
          final var hourMatch = schedule.hour() == calendar.get(Calendar.HOUR_OF_DAY);
          final var minuteMatch = calendar.get(Calendar.MINUTE) == schedule.minute();
          final var secondMatch = calendar.get(Calendar.SECOND) >= schedule.second();

          //? If this is redundant....
          if (lastRun != null && DateUtils.isSameDay(lastRun, now)) continue;

          ///? If this is okay...
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
}

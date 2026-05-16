package io.github.the_infinite.core.job;

import org.jetbrains.annotations.NotNull;

import io.github.the_infinite.core.logging.monitor.MonitorLogger;
import io.github.the_infinite.core.monitoring.ILogMonitor;
import io.github.the_infinite.core.response.ServiceResult;

import java.util.Date;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@SuppressWarnings("unused")
public abstract class ServiceJob<T> {
  protected final MonitorLogger logger;
  final JobType myType;
  final boolean deferred;
  final int period;
  final TimeOfDay schedule;
  private final boolean logCritical;
  // Switches.
  protected CloseHandler closeHandler;
  protected boolean shouldConsume;

  // Taking metrics
  Date lastRun;
  private T lastResult;
  private int failedRuns;
  private int successfulRuns;

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, int period, boolean deferred, boolean logCritical) {
    this.period = period;
    this.deferred = deferred;
    this.logCritical = logCritical;
    this.shouldConsume = true;
    this.schedule = null;
    this.logger = MonitorLogger.create(vertx, monitor, null);
    this.myType = JobType.PERIODIC;


  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, int period, boolean deferred) {
    this(vertx, monitor, period, deferred, false);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, int period) {
    this(vertx, monitor, period, false);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule, boolean deferred, boolean logCritical) {
    this.period = 0;
    this.deferred = deferred;
    this.logCritical = logCritical;
    this.shouldConsume = true;
    this.schedule = schedule;
    this.myType = JobType.PERIODIC;
    this.logger = MonitorLogger.create(vertx, monitor, null);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule, boolean deferred) {
    this(vertx, monitor, schedule, deferred, false);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule) {
    this(vertx, monitor, schedule, false);
  }

  public int getRuns() {
    return failedRuns + successfulRuns;
  }

  public JsonObject getStats() {
    final var json = new JsonObject();

    //? Okay then.
    if (myType == JobType.PERIODIC) {
      json.put("period", period);
    }

    //? Put the schedule.
    else if (schedule != null) {
      json.put("schedule", schedule.toString());
    }

    //? Put the others.
    json.put("lastRun", lastRun);
    json.put("lastResult", lastResult);
    json.put("failedRuns", failedRuns);
    json.put("successfulRuns", successfulRuns);
    json.put("totalRuns", this.getRuns());

    //? Return this.
    return json;
  }

  void markSuccess() {
    this.successfulRuns += 1;
    this.lastRun = new Date();
  }

  void markFailure() {
    this.failedRuns += 1;
    this.lastRun = new Date();
  }

  void saveMetrics(T lastResult) {
    this.lastResult = lastResult;
  }

  abstract Future<ServiceResult<T>> run();

  abstract Future<Void> stopGracefully();

  enum JobType {
    PERIODIC, EXACT,
  }

  protected interface CloseHandler {
    void close();
  }

  @SuppressWarnings("NullableProblems")
  protected record TimeOfDay(@NotNull int hour, @NotNull int minute, @NotNull int second) {
    public TimeOfDay(int hour, int minute) {
      this(hour, minute, 0);
    }

    public TimeOfDay(int hour) {
      this(hour, 0);
    }

    @Override
    public String toString() {
      return "%s:%s:%s".formatted(hour, minute, second);
    }
  }
}

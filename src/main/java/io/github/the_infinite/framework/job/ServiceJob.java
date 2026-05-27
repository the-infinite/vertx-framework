package io.github.the_infinite.framework.job;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;
import java.util.Objects;

import io.github.the_infinite.framework.logging.monitor.MonitorLogger;
import io.github.the_infinite.framework.monitoring.ILogMonitor;
import io.github.the_infinite.framework.response.ServiceResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.Getter;

/**
 * Base type for background jobs managed by {@link JobRegistry}.
 * <p>
 * A job may run on a fixed period or at an exact time of day. Subclasses implement the business work in
 * {@link #run()} and provide shutdown behavior in {@link #stopGracefully()}.
 */
@SuppressWarnings("unused")
public abstract class ServiceJob<T> {
  protected final MonitorLogger logger;
  final JobType myType;
  /**
   *  Returns whether the first execution should be deferred until the next scheduled trigger.
   */
  @Getter
  final boolean deferred;
  /**
   *  Returns the fixed schedule period in milliseconds for periodic jobs, or
   *  for exact-time jobs.
   */
  @Getter
  final int period;
  final TimeOfDay schedule;
  private final boolean logCritical;

  // Switches.
  protected CloseHandler closeHandler;
  protected boolean shouldConsume;

  // Taking metrics
  private Date lastRun;
  private T lastResult;
  private int failedRuns;
  private int successfulRuns;

  private ServiceJob(Vertx vertx, ILogMonitor monitor, int period, boolean deferred, boolean logCritical) {
    if (period <= 0) {
      throw new IllegalArgumentException("period must be greater than 0");
    }

    this.period = period;
    this.deferred = deferred;
    this.logCritical = logCritical;
    this.shouldConsume = true;
    this.schedule = null;
    this.logger = MonitorLogger.create(vertx, monitor, null);
    this.myType = JobType.PERIODIC;

    //? Add yourself child.
    JobRegistry.getInstance(vertx).addJob(this);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, int period, boolean deferred) {
    this(vertx, monitor, period, deferred, false);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, int period) {
    this(vertx, monitor, period, false);
  }

  private ServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule, boolean deferred, boolean logCritical) {
    this.schedule = Objects.requireNonNull(schedule, "schedule cannot be null");
    this.period = 0;
    this.deferred = deferred;
    this.logCritical = logCritical;
    this.shouldConsume = true;
    this.myType = JobType.EXACT;
    this.logger = MonitorLogger.create(vertx, monitor, null);

    //? Add yourself child.
    JobRegistry.getInstance(vertx).addJob(this);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule, boolean deferred) {
    this(vertx, monitor, schedule, deferred, false);
  }

  protected ServiceJob(Vertx vertx, ILogMonitor monitor, TimeOfDay schedule) {
    this(vertx, monitor, schedule, false);
  }

  /**
   * Returns the total number of completed executions, successful or failed.
   */
  public synchronized int getRuns() {
    return failedRuns + successfulRuns;
  }

  /**
   * Returns the number of failed executions recorded for this job.
   */
  public synchronized int getFailedRuns() {
    return failedRuns;
  }

  /**
   * Returns the number of successful executions recorded for this job.
   */
  public synchronized int getSuccessfulRuns() {
    return successfulRuns;
  }

  /**
   * Returns the most recent result payload captured for this job, if any.
   */
  public synchronized @Nullable T getLastResult() {
    return lastResult;
  }

  /**
   * Returns the time at which the job most recently completed.
   */
  public synchronized @Nullable Date getLastRun() {
    return lastRun == null ? null : new Date(lastRun.getTime());
  }

  /**
   * Returns the configured execution type for this job.
   */
  public JobType getType() {
    return myType;
  }

  /**
   * Returns the configured time-of-day schedule for exact-time jobs, or {@code null} for periodic jobs.
   */
  public @Nullable TimeOfDay getSchedule() {
    return schedule;
  }

  /**
   * Indicates whether critical failure logging is enabled for this job.
   */
  public boolean isCriticalLoggingEnabled() {
    return logCritical;
  }

  /**
   * Indicates whether the job is currently configured to consume work.
   */
  public boolean shouldConsume() {
    return shouldConsume;
  }

  /**
   * Alias for {@link #shouldConsume()} exposed with conventional boolean naming.
   */
  public boolean isConsumptionEnabled() {
    return shouldConsume();
  }

  /**
   * Returns a snapshot of the current runtime statistics for this job.
   */
  public JsonObject getStats() {
    final var json = new JsonObject();
    final Date recordedLastRun;
    final T recordedLastResult;
    final int recordedFailedRuns;
    final int recordedSuccessfulRuns;

    synchronized (this) {
      recordedLastRun = lastRun == null ? null : new Date(lastRun.getTime());
      recordedLastResult = lastResult;
      recordedFailedRuns = failedRuns;
      recordedSuccessfulRuns = successfulRuns;
    }

    //? Okay then.
    if (myType == JobType.PERIODIC) {
      json.put("period", period);
    }

    //? Put the schedule.
    else if (schedule != null) {
      json.put("schedule", schedule.toString());
    }

    //? Put the others.
    json.put("type", myType.name());
    json.put("deferred", deferred);
    json.put("logCritical", logCritical);
    json.put("shouldConsume", shouldConsume);
    json.put("lastRun", recordedLastRun);
    json.put("lastResult", recordedLastResult);
    json.put("failedRuns", recordedFailedRuns);
    json.put("successfulRuns", recordedSuccessfulRuns);
    json.put("totalRuns", this.getRuns());

    //? Return this.
    return json;
  }

  synchronized void markSuccess() {
    this.successfulRuns += 1;
    this.lastRun = new Date();
  }

  synchronized void markFailure() {
    this.failedRuns += 1;
    this.lastRun = new Date();
  }

  synchronized void saveMetrics(T lastResult) {
    this.lastResult = lastResult;
  }

  /**
   * Executes one logical run of the job.
   */
  protected abstract Future<ServiceResult<T>> run();

  /**
   * Stops the job and releases any resources required by the implementation.
   */
  protected abstract Future<Void> stopGracefully();

  /**
   * Supported execution models for jobs managed by the framework.
   */
  public enum JobType {
    PERIODIC, EXACT,
  }

  @FunctionalInterface
  protected interface CloseHandler {
    void close();
  }

  /**
   * Immutable time-of-day representation for exact-time jobs.
   */
  @SuppressWarnings("NullableProblems")
  public record TimeOfDay(@NotNull int hour, @NotNull int minute,
                          @NotNull int second) {
    public TimeOfDay {
      if (hour < 0 || hour > 23) {
        throw new IllegalArgumentException("hour must be between 0 and 23");
      }

      if (minute < 0 || minute > 59) {
        throw new IllegalArgumentException("minute must be between 0 and 59");
      }

      if (second < 0 || second > 59) {
        throw new IllegalArgumentException("second must be between 0 and 59");
      }
    }

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

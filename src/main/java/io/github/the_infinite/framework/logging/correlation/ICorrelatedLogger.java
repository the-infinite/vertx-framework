package io.github.the_infinite.framework.logging.correlation;

import io.github.the_infinite.framework.logging.TimeEvent;

import java.util.logging.Level;

import io.vertx.core.Future;

@SuppressWarnings("unused")
public interface ICorrelatedLogger<T> {
  /**
   * Marks the execution of a process with a message.
   *
   * @param message The message indicating the execution step.
   */
  Future<Void> exec(CorrelationContext context, T message);

  /**
   * Logs an error message.
   *
   * @param message The error message.
   */
  Future<Void> error(CorrelationContext context, T message);

  /**
   * Logs an informational message.
   *
   * @param message The informational message.
   */
  Future<Void> info(CorrelationContext context, T message);

  /**
   * Logs a debug message.
   *
   * @param message The debug message.
   */
  Future<Void> debug(CorrelationContext context, T message);

  /**
   * Logs a warning message.
   *
   * @param message The warning message.
   */
  Future<Void> warn(CorrelationContext context, T message);

  /**
   * Creates a trace event with the given message.
   *
   * @param message The trace message.
   * @return A Future containing the `TraceEvent`. This does not close the trace event. It is expected that the caller
   * will close it.
   */
  TimeEvent time(CorrelationContext context, T message);

  /**
   * Implements a generic logging function that decides which function to delegate logging to based off of the log
   * type
   */
  default Future<Void> log(Level level, CorrelationContext context, T message) {
    if (level == Level.WARNING) {
      return this.warn(context, message);
    }

    if (level == Level.SEVERE) {
      return this.error(context, message);
    }

    if (level == Level.CONFIG || level == Level.ALL || level == Level.FINE) {
      return this.debug(context, message);
    }

    if (level == Level.INFO) {
      return this.info(context, message);
    }

    return this.exec(context, message);
  }
}

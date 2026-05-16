package io.github.the_infinite.framework.logging;

import java.util.logging.Level;

import io.vertx.core.Future;

@SuppressWarnings({"unused", "UnusedReturnValue"})
public interface ILogger {
  /**
   * Marks the execution of a process with a message.
   *
   * @param message The message indicating the execution step.
   */
  Future<Void> exec(Object message);

  /**
   * Logs an error message.
   *
   * @param message The error message.
   */
  Future<Void> error(Object message);

  /**
   * Logs an informational message.
   *
   * @param message The informational message.
   */
  Future<Void> info(Object message);

  /**
   * Logs a debug message.
   *
   * @param message The debug message.
   */
  Future<Void> debug(Object message);

  /**
   * Logs a warning message.
   *
   * @param message The warning message.
   */
  Future<Void> warn(Object message);

  /**
   * Implements a generic logging function that decides which function to delegate logging to based off of the log
   * type
   */
  default Future<Void> log(Level level, Object message) {
    if (level == Level.WARNING) {
      return this.warn(message);
    }

    if (level == Level.SEVERE) {
      return this.error(message);
    }

    if (level == Level.CONFIG || level == Level.ALL || level == Level.FINE) {
      return this.debug(message);
    }

    if (level == Level.INFO) {
      return this.info(message);
    }

    return this.exec(message);
  }

  /**
   * Creates a timer event with the given message.
   *
   * @param message The trace message.
   * @return A  `TimeEvent`. This does not close the trace event. It is expected that the caller
   * will close it.
   */
  TimeEvent time(Object message);
}

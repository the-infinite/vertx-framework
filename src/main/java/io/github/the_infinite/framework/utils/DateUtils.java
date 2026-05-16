package io.github.the_infinite.framework.utils;

import java.time.Duration;
import java.util.Date;

public final class DateUtils {
  public static boolean isSameDay(Date first, Date second) {
    final var difference = Duration.between(first.toInstant(), second.toInstant()).abs();
    return difference.toHours() < 24;
  }
}

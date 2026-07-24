package io.github.the_infinite.framework.data.types;

import io.github.the_infinite.framework.logging.correlation.CorrelationContext;
import lombok.Getter;

@Getter
@SuppressWarnings("unused")
public final class RepositoryOptions<T> {
  private Object user;
  private long userId;
  private int limit;
  private String cursor;
  private CorrelationContext correlation;
  private Position position;

  public RepositoryOptions(Object user, CorrelationContext context) {
    this.user = user;
    this.userId = user instanceof Number value ? value.longValue() : 0;
    this.correlation = context;
    this.limit = 30;
    this.cursor = null;
    this.position = null;
  }

  public RepositoryOptions(long userId, CorrelationContext context) {
    this(Long.valueOf(userId), context);
  }

  public RepositoryOptions(long userId) {
    this(userId, null);
  }

  public RepositoryOptions(Object user) {
    this(user, null);
  }

  public RepositoryOptions(CorrelationContext context) {
    this(null, context);
  }

  public RepositoryOptions() {
    this(null, null);
  }

  public RepositoryOptions<T> setUser(Object user) {
    this.user = user;
    this.userId = user instanceof Number value ? value.longValue() : 0;
    return this;
  }

  public RepositoryOptions<T> setLimit(int limit) {
    this.limit = limit;
    return this;
  }

  public RepositoryOptions<T> setCursor(String cursor) {
    this.cursor = cursor;
    return this;
  }

  public RepositoryOptions<T> setCorrelation(CorrelationContext correlation) {
    this.correlation = correlation;
    return this;
  }

  public RepositoryOptions<T> setPosition(Position position) {
    this.position = position;
    return this;
  }
}

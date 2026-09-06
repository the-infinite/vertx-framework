package io.github.the_infinite.framework.data;

import org.hibernate.SessionEventListener;

import io.github.the_infinite.framework.logging.console.ConsoleLogger;

public class DBSessionListener implements SessionEventListener {
  @Override
  public void end() {
    ConsoleLogger.getInstance().debug("Hibernate session closed");
  }
}

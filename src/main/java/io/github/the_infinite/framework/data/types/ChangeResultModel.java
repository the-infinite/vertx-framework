package io.github.the_infinite.framework.data.types;

import java.util.List;

@SuppressWarnings("unused")
public record ChangeResultModel<T>(int affectedCount, List<T> affectedEntities) {
  public ChangeResultModel(List<T> affectedEntities) {
    this(affectedEntities.size(), affectedEntities);
  }
}

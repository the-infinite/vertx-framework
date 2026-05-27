package io.github.the_infinite.framework.types;

import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings("unused")
public class RangeMap<T> implements Collection<T> {

  // Key: lower bound of the range
  private final TreeMap<Integer, RangeContainer<T>> treeMap = new TreeMap<>();

  /**
   * Custom method to add a value mapped to a specific range.
   */
  public void addRange(int low, int high, T value) {
    if (low > high) {
      throw new IllegalArgumentException("Low bound cannot be greater than high bound");
    }
    treeMap.put(low, new RangeContainer<>(low, high, value));
  }

  /**
   * Custom lookup method to find the generic value matching a specific key.
   */
  public Optional<T> get(int key) {
    Map.Entry<Integer, RangeContainer<T>> entry = treeMap.floorEntry(key);
    if (entry != null && key <= entry.getValue().high) {
      return Optional.of(entry.getValue().value);
    }
    return Optional.empty();
  }

  @Override
  public int size() {
    return treeMap.size();
  }

  // --- Mandatory Collection Interface Implementations ---

  @Override
  public boolean isEmpty() {
    return treeMap.isEmpty();
  }

  @Override
  public boolean contains(Object o) {
    for (RangeContainer<T> container : treeMap.values()) {
      if (Objects.equals(container.value, o)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public @NotNull Iterator<T> iterator() {
    // Returns an iterator over the generic values stored in the tree
    return treeMap.values().stream().map(c -> c.value).iterator();
  }

  @Override
  public Object @NotNull [] toArray() {
    return treeMap.values().stream().map(c -> c.value).toArray();
  }

  @Override
  public <E> E @NotNull [] toArray(E @NotNull [] a) {
    return treeMap.values().stream().map(c -> c.value).toList().toArray(a);
  }

  @Override
  public boolean add(T t) {
    // Collection requires this, but ranges require bounds.
    // We throw an exception to maintain data integrity.
    throw new UnsupportedOperationException("Use addRange(low, high, value) instead.");
  }

  @Override
  public boolean remove(Object o) {
    // Removes the first range container that matches the target object value
    Integer keyToRemove = null;
    for (Map.Entry<Integer, RangeContainer<T>> entry : treeMap.entrySet()) {
      if (Objects.equals(entry.getValue().value, o)) {
        keyToRemove = entry.getKey();
        break;
      }
    }
    if (keyToRemove != null) {
      treeMap.remove(keyToRemove);
      return true;
    }
    return false;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    for (Object e : c) {
      if (!contains(e)) return false;
    }
    return true;
  }

  @Override
  public boolean addAll(@NotNull Collection<? extends T> c) {
    throw new UnsupportedOperationException("Use addRange(low, high, value) instead.");
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    boolean modified = false;
    for (Object e : c) {
      if (remove(e)) modified = true;
    }
    return modified;
  }

  @Override
  public boolean retainAll(@NotNull Collection<?> c) {
    return treeMap.entrySet().removeIf(entry -> !c.contains(entry.getValue().value));
  }

  @Override
  public void clear() {
    treeMap.clear();
  }

  // Internal class to hold the boundaries and the generic value
  private record RangeContainer<V>(int low, int high, V value) {
  }
}

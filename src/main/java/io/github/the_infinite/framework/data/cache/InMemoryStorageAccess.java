package io.github.the_infinite.framework.data.cache;

import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStorageAccess implements DomainDataStorageAccess {
  private final ConcurrentHashMap<Object, Object> cache;

  public InMemoryStorageAccess(String ignoredRegionName) {
    this.cache = new ConcurrentHashMap<>();
  }

  @Override
  public boolean contains(Object key) {
    return cache.containsKey(key);
  }

  @Override
  public Object getFromCache(Object key, SharedSessionContractImplementor session) {
    return cache.get(key);
  }

  @Override
  public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
    cache.put(key, value);
  }

  @Override
  public void removeFromCache(Object key, SharedSessionContractImplementor session) {
    cache.remove(key);
  }

  @Override
  public void clearCache(SharedSessionContractImplementor session) {
    cache.clear();
  }

  @Override
  public void evictData(Object key) {
    cache.remove(key);
  }

  @Override
  public void evictData() {
    cache.clear();
  }

  @Override
  public void release() {
    cache.clear();
  }
}

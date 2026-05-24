package io.github.the_infinite.framework.data.cache;

import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.io.*;

import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RedisStorageAccess implements DomainDataStorageAccess {
  private final RedisStorage cache;
  private final String prefix;
  private final long ttlSeconds;

  public RedisStorageAccess(String regionName, Vertx vertx) {
    this.cache = new RedisStorage(vertx);
    this.prefix = "hibernate:cache:" + regionName + ":";
    this.ttlSeconds = 3600; // 1-hour default
  }

  @Override
  public boolean contains(Object key) {
    final var value = cache.keyExists(buildKey(key)).await();
    return value != null && value;
  }

  @Override
  public Object getFromCache(Object key, SharedSessionContractImplementor session) {
    final var value = cache.getValue(buildKey(key)).await();
    if (value == null) return null;
    return deserialize(value.getBytes());
  }

  @Override
  public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
    final byte[] serializedValue = serialize(value);
    if (serializedValue == null) return;
    cache.setValueWithExpiration(buildKey(key), new String(serializedValue), ttlSeconds)
      .onFailure(err -> log.error("Failed to put item into cache: {}", err.getMessage()));
  }

  @Override
  public void removeFromCache(Object key, SharedSessionContractImplementor session) {
    cache.unlinkKey(buildKey(key)).onFailure(err -> log.error("Failed to remove item from cache: {}", err.getMessage()));
  }

  @Override
  public void clearCache(SharedSessionContractImplementor session) {
    final var keys = cache.findKeys(prefix + "*").await();
    for (final var key : keys) {
      cache.unlinkKey(key).onFailure(err -> log.error("Failed to delete key: {}", key));
    }
  }

  @Override
  public void evictData(Object key) {
    removeFromCache(key, null);
  }

  @Override
  public void evictData() {
    clearCache(null);
  }

  @Override
  public void release() {
    // Cleanup resources if necessary when the region shuts down
  }

  private String buildKey(Object key) {
    return prefix + key.toString();
  }

  // --- Hibernate Cache Entries are complex Tuples, so we must use Java Serialization ---
  private byte[] serialize(Object object) {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         ObjectOutputStream oos = new ObjectOutputStream(bos)) {
      oos.writeObject(object);
      return bos.toByteArray();
    } catch (IOException e) {
      System.err.println("Failed to serialize cache item: " + e.getMessage());
      return null;
    }
  }

  private Object deserialize(byte[] bytes) {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
         ObjectInputStream ois = new ObjectInputStream(bis)) {
      return ois.readObject();
    } catch (Exception e) {
      System.err.println("Failed to deserialize cache item: " + e.getMessage());
      return null;
    }
  }
}

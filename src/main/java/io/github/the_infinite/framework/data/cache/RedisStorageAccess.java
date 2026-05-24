package io.github.the_infinite.framework.data.cache;

import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;

public class RedisStorageAccess implements DomainDataStorageAccess {
  private final JedisPool jedisPool;
  private final String prefix;
  private final long ttlSeconds;

  public RedisStorageAccess(String regionName, JedisPool jedisPool) {
    this.jedisPool = jedisPool;
    this.prefix = "hibernate:cache:" + regionName + ":";
    this.ttlSeconds = 3600; // 1-hour default
  }

  @Override
  public boolean contains(Object key) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.exists(buildKey(key));
    }
  }

  @Override
  public Object getFromCache(Object key, SharedSessionContractImplementor session) {
    try (Jedis jedis = jedisPool.getResource()) {
      byte[] data = jedis.get(buildKeyBytes(key));
      if (data == null) {
        return null;
      }
      return deserialize(data);
    }
  }

  @Override
  public void putIntoCache(Object key, Object value, SharedSessionContractImplementor session) {
    final byte[] serializedValue = serialize(value);
    if (serializedValue == null) return;

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.setex(buildKeyBytes(key), ttlSeconds, serializedValue);
    }
  }

  @Override
  public void removeFromCache(Object key, SharedSessionContractImplementor session) {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.unlink(buildKeyBytes(key));
    }
  }

  @Override
  public void clearCache(SharedSessionContractImplementor session) {
    try (Jedis jedis = jedisPool.getResource()) {
      final byte[] matchPattern = (prefix + "*").getBytes(StandardCharsets.UTF_8);

      // Configure SCAN to fetch 100 keys per iteration matching our region prefix
      final var params = new ScanParams().match(matchPattern).count(750);

      // Start at the root cursor
      byte[] cursor = ScanParams.SCAN_POINTER_START_BINARY;

      do {
        // Execute the paginated scan
        final var scanResult = jedis.scan(cursor, params);
        final var keys = scanResult.getResult();

        if (keys != null && !keys.isEmpty()) {
          // Iterate and individually UNLINK each key asynchronously
          for (byte[] key : keys) {
            jedis.unlink(key);
          }
        }

        // Update the cursor for the next iteration
        cursor = scanResult.getCursorAsBytes();

      } while (!Arrays.equals(cursor, ScanParams.SCAN_POINTER_START_BINARY));
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
    // Pool is managed by the Factory, nothing to do per region.
  }

  private String buildKey(Object key) {
    return prefix + key.toString();
  }

  private byte[] buildKeyBytes(Object key) {
    return buildKey(key).getBytes(StandardCharsets.UTF_8);
  }

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

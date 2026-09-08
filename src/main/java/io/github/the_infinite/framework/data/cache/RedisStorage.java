package io.github.the_infinite.framework.data.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.the_infinite.framework.data.DatabaseFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.ResponseType;

@SuppressWarnings("unused")
public final class RedisStorage {
  private final RedisAPI api;

  public RedisStorage(Vertx vertx) {
    final var client = DatabaseFactory.getRedisClient(vertx);
    this.api = RedisAPI.api(client);
  }

  public RedisAPI storage() {
    return this.api;
  }

  private <T> Future<T> mapResponse(Future<Response> future, Function<Response, T> mapper) {
    return future.compose(response -> {
      if (response == null) {
        return Future.succeededFuture(null);
      }
      if (response.type() == ResponseType.ERROR) {
        return Future.failedFuture(response.toString());
      }
      return Future.succeededFuture(mapper.apply(response));
    });
  }

  private <T> Future<T> mapResponse(Future<Response> future, Function<Response, T> mapper, T defaultValue) {
    return future.compose(response -> {
      if (response == null) {
        return Future.succeededFuture(defaultValue);
      }
      if (response.type() == ResponseType.ERROR) {
        return Future.failedFuture(response.toString());
      }
      return Future.succeededFuture(mapper.apply(response));
    });
  }

  private Future<Void> mapEmpty(Future<Response> future) {
    return future.compose(response -> {
      if (response != null && response.type() == ResponseType.ERROR) {
        return Future.failedFuture(response.toString());
      }
      return Future.succeededFuture();
    });
  }

  // Key Operations
  public Future<String> getValue(String key) {
    return mapResponse(api.get(key), Response::toString);
  }

  public Future<Void> setValue(String key, String value) {
    return mapEmpty(api.set(List.of(key, value)));
  }

  public Future<Void> setValueWithExpiration(String key, String value, long seconds) {
    return mapEmpty(api.setex(key, String.valueOf(seconds), value));
  }

  public Future<Boolean> setValueIfAbsent(String key, String value) {
    return mapResponse(api.setnx(key, value), response -> response.toLong() == 1L, false);
  }

  public Future<Long> deleteKey(String key) {
    return mapResponse(api.del(List.of(key)), Response::toLong, 0L);
  }

  public Future<Long> unlinkKey(String key) {
    return mapResponse(api.unlink(List.of(key)), Response::toLong, 0L);
  }

  public Future<Long> deleteKeys(List<String> keys) {
    if (keys == null || keys.isEmpty()) return Future.succeededFuture(0L);
    return mapResponse(api.del(keys), Response::toLong, 0L);
  }

  public Future<Boolean> keyExists(String key) {
    return mapResponse(api.exists(List.of(key)), response -> response.toLong() == 1L, false);
  }

  public Future<Long> incrementValue(String key) {
    return mapResponse(api.incr(key), Response::toLong, 0L);
  }

  public Future<Long> incrementValue(String key, int delta) {
    return mapResponse(api.incrby(key, Integer.toString(delta)), Response::toLong, 0L);
  }

  public Future<Long> decrementValue(String key) {
    return mapResponse(api.decr(key), Response::toLong, 0L);
  }

  public Future<Long> decrementValue(String key, int delta) {
    return mapResponse(api.decrby(key, Integer.toString(delta)), Response::toLong, 0L);
  }


  public Future<Long> idleTime(String key) {
    return mapResponse(api.object(List.of("IDLETIME", key)), Response::toLong, 0L);
  }

  public Future<Boolean> setExpiration(String key, long seconds) {
    return mapResponse(api.expire(List.of(key, String.valueOf(seconds))), response -> response.toLong() == 1L, false);
  }

  public Future<Long> getTimeToLive(String key) {
    return mapResponse(api.ttl(key), Response::toLong, -2L);
  }

  public Future<List<String>> getMultipleValues(List<String> keys) {
    if (keys == null || keys.isEmpty()) return Future.succeededFuture(List.of());
    return mapResponse(api.mget(keys), response -> response.stream().map(res -> res == null ? null : res.toString()).toList(), List.of());
  }

  public Future<Void> setMultipleValues(Map<String, String> keyValues) {
    if (keyValues == null || keyValues.isEmpty()) return Future.succeededFuture();
    final var args = new ArrayList<String>();
    keyValues.forEach((k, v) -> {
      args.add(k);
      args.add(v);
    });
    return mapEmpty(api.mset(args));
  }

  // Hash Operations
  public Future<String> getHashValue(String key, String field) {
    return mapResponse(api.hget(key, field), Response::toString);
  }

  public Future<Void> setHashValue(String key, String field, String value) {
    return mapEmpty(api.hset(List.of(key, field, value)));
  }

  public Future<Long> deleteHashField(String key, String field) {
    return mapResponse(api.hdel(List.of(key, field)), Response::toLong, 0L);
  }

  public Future<Void> setHashValues(String key, Map<String, String> fieldValues) {
    if (fieldValues == null || fieldValues.isEmpty()) return Future.succeededFuture();
    final var args = new ArrayList<String>();
    args.add(key);
    fieldValues.forEach((f, v) -> {
      args.add(f);
      args.add(v);
    });
    return mapEmpty(api.hset(args));
  }

  public Future<Boolean> setHashValueIfAbsent(String key, String field, String value) {
    return mapResponse(api.hsetnx(key, field, value), response -> response.toLong() == 1L, false);
  }

  public Future<Long> incrementHashField(String key, String field, int value) {
    return mapResponse(api.hincrby(key, field, Integer.toString(value)),
      Response::toLong,
      0L);
  }

  public Future<Long> decrementHashField(String key, String field, int value) {
    return incrementHashField(key, field, -value);
  }

  public Future<Map<String, String>> getAllHashFields(String key) {
    return mapResponse(api.hgetall(key), response -> {
      final var map = new HashMap<String, String>();
      if (response.isMap()) {
        for (String k : response.getKeys()) {
          map.put(k, response.get(k).toString());
        }
      } else if (response.isArray()) {
        for (int i = 0; i < response.size(); i += 2) {
          map.put(response.get(i).toString(), response.get(i + 1).toString());
        }
      }
      return map;
    }, new HashMap<>());
  }

  public Future<List<String>> getHashKeys(String key) {
    return mapResponse(api.hkeys(key), response -> response.stream().map(Response::toString).toList(), List.of());
  }

  public Future<List<String>> getHashValues(String key) {
    return mapResponse(api.hvals(key), response -> response.stream().map(Response::toString).toList(), List.of());
  }

  public Future<Boolean> hashFieldExists(String key, String field) {
    return mapResponse(api.hexists(key, field), response -> response.toLong() == 1L, false);
  }

  // List Operations
  public Future<Long> pushToListStart(String key, String value) {
    return mapResponse(api.lpush(List.of(key, value)), Response::toLong, 0L);
  }

  public Future<Long> pushToListEnd(String key, String value) {
    return mapResponse(api.rpush(List.of(key, value)), Response::toLong, 0L);
  }

  public Future<String> popFromListStart(String key) {
    return mapResponse(api.lpop(List.of(key)), Response::toString);
  }

  public Future<String> popFromListEnd(String key) {
    return mapResponse(api.rpop(List.of(key)), Response::toString);
  }

  public Future<Long> getListLength(String key) {
    return mapResponse(api.llen(key), Response::toLong, 0L);
  }

  public Future<List<String>> getListRange(String key, long start, long stop) {
    return mapResponse(api.lrange(key, String.valueOf(start), String.valueOf(stop)), response -> response.stream().map(Response::toString).toList(), List.of());
  }

  // Set Operations
  public Future<Long> addToSet(String key, String member) {
    return mapResponse(api.sadd(List.of(key, member)), Response::toLong, 0L);
  }

  public Future<Long> removeFromSet(String key, String member) {
    return mapResponse(api.srem(List.of(key, member)), Response::toLong, 0L);
  }

  public Future<List<String>> getSetMembers(String key) {
    return mapResponse(api.smembers(key), response -> response.stream().map(Response::toString).toList(), List.of());
  }

  public Future<Boolean> isSetMember(String key, String member) {
    return mapResponse(api.sismember(key, member), response -> response.toLong() == 1L, false);
  }

  public Future<Long> getSetSize(String key) {
    return mapResponse(api.scard(key), Response::toLong, 0L);
  }

  // Sorted Set Operations
  public Future<Long> addToSortedSet(String key, double score, String member) {
    return mapResponse(api.zadd(List.of(key, String.valueOf(score), member)), Response::toLong, 0L);
  }

  public Future<List<String>> getSortedSetRange(String key, long start, long stop) {
    return mapResponse(api.zrange(List.of(key, String.valueOf(start), String.valueOf(stop))), response -> response.stream().map(Response::toString).toList(), List.of());
  }

  public Future<Long> removeFromSortedSet(String key, String member) {
    return mapResponse(api.zrem(List.of(key, member)), Response::toLong, 0L);
  }

  public Future<Double> getSortedSetScore(String key, String member) {
    return mapResponse(api.zscore(key, member), response -> response == null ? null : Double.valueOf(response.toString()));
  }

  public Future<Long> getSortedSetSize(String key) {
    return mapResponse(api.zcard(key), Response::toLong, 0L);
  }

  // Other Key Operations
  public Future<Boolean> removeExpiration(String key) {
    return mapResponse(api.persist(key), response -> response.toLong() == 1L, false);
  }

  public Future<Void> renameKey(String oldKey, String newKey) {
    return mapEmpty(api.rename(oldKey, newKey));
  }

  /**
   * Non-blocking incremental scan - preferred over KEYS which blocks Redis.
   * Iterates with COUNT hint (250) to avoid materialising the whole keyspace at once.
   */
  public Future<List<String>> scanKeys(String pattern) {
    return scanKeys(pattern, 250);
  }

  public Future<List<String>> scanKeys(String pattern, int count) {
    final var all = new ArrayList<String>();
    return scanBatch("0", pattern, count, all).map(v -> all);
  }

  private Future<Void> scanBatch(String cursor, String pattern, int count, ArrayList<String> accumulator) {
    final var args = new ArrayList<String>();
    args.add(cursor);
    args.add("MATCH");
    args.add(pattern);
    args.add("COUNT");
    args.add(String.valueOf(count));
    return mapResponse(api.scan(args), response -> {
      if (response == null || !response.isArray() || response.size() < 2) {
        return new ScanResult("0", List.of());
      }
      final var nextCursor = response.get(0).toString();
      final var keysPart = response.get(1);
      final var keys = new ArrayList<String>();
      if (keysPart != null && keysPart.isArray()) {
        for (var r : keysPart) {
          if (r != null) keys.add(r.toString());
        }
      }
      return new ScanResult(nextCursor, keys);
    }, new ScanResult("0", List.of())).compose(result -> {
      accumulator.addAll(result.keys());
      if ("0".equals(result.cursor())) {
        return Future.succeededFuture();
      }
      return scanBatch(result.cursor(), pattern, count, accumulator);
    });
  }

  private record ScanResult(String cursor, List<String> keys) {}

  /** @deprecated Use {@link #scanKeys(String)} to avoid blocking Redis. */
  @Deprecated
  public Future<List<String>> findKeys(String pattern) {
    return scanKeys(pattern);
  }
}

package io.github.the_infinite.framework.data.cache;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.RegionFactoryTemplate;
import org.hibernate.cache.spi.support.StorageAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import java.util.Map;

import io.github.the_infinite.framework.env.AppEnvironment;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisRegionFactory extends RegionFactoryTemplate {
  // Global pool shared across all cache regions
  private static JedisPool globalJedisPool;

  @Override
  protected void prepareForUse(SessionFactoryOptions settings, Map configValues) {
    if (globalJedisPool == null) {
      final var poolConfig = new JedisPoolConfig();
      poolConfig.setMaxTotal(8);
      poolConfig.setMaxIdle(8);
      poolConfig.setMinIdle(0);
      poolConfig.setMaxWaitMillis(5_000);
      poolConfig.setTestOnBorrow(true);
      poolConfig.setTestOnReturn(false);
      globalJedisPool = new JedisPool(poolConfig, AppEnvironment.getInstance().getRedisUrl());
    }
  }

  @Override
  protected void releaseFromUse() {
    if (globalJedisPool != null && !globalJedisPool.isClosed()) {
      globalJedisPool.close();
    }
  }

  @Override
  protected DomainDataStorageAccess createDomainDataStorageAccess(DomainDataRegionConfig regionConfig, DomainDataRegionBuildingContext buildingContext) {
    return new RedisStorageAccess(regionConfig.getRegionName(), globalJedisPool);
  }

  @Override
  protected StorageAccess createQueryResultsRegionStorageAccess(String regionName, SessionFactoryImplementor sessionFactory) {
    return new RedisStorageAccess(regionName, globalJedisPool);
  }

  @Override
  protected StorageAccess createTimestampsRegionStorageAccess(String regionName, SessionFactoryImplementor sessionFactory) {
    return new RedisStorageAccess(regionName, globalJedisPool);
  }
}

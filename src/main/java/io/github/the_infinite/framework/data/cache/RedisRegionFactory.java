package io.github.the_infinite.framework.data.cache;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.RegionFactoryTemplate;
import org.hibernate.cache.spi.support.StorageAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import java.util.Map;

import io.github.the_infinite.framework.ConfigurationRegistrant;

public class RedisRegionFactory extends RegionFactoryTemplate {
  @Override
  protected void prepareForUse(SessionFactoryOptions settings, Map<String, Object> configValues) {
  }

  @Override
  protected void releaseFromUse() {
  }

  @Override
  protected DomainDataStorageAccess createDomainDataStorageAccess(DomainDataRegionConfig regionConfig, DomainDataRegionBuildingContext buildingContext) {
    return new RedisStorageAccess(regionConfig.getRegionName(), ConfigurationRegistrant.global());
  }

  @Override
  protected StorageAccess createQueryResultsRegionStorageAccess(String regionName, SessionFactoryImplementor sessionFactory) {
    return new RedisStorageAccess(regionName, ConfigurationRegistrant.global());
  }

  @Override
  protected StorageAccess createTimestampsRegionStorageAccess(String regionName, SessionFactoryImplementor sessionFactory) {
    return new RedisStorageAccess(regionName, ConfigurationRegistrant.global());
  }
}

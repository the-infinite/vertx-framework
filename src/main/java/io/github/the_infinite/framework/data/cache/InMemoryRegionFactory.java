package io.github.the_infinite.framework.data.cache;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.cfg.spi.DomainDataRegionBuildingContext;
import org.hibernate.cache.cfg.spi.DomainDataRegionConfig;
import org.hibernate.cache.spi.support.DomainDataStorageAccess;
import org.hibernate.cache.spi.support.RegionFactoryTemplate;
import org.hibernate.cache.spi.support.StorageAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRegionFactory extends RegionFactoryTemplate {
  private final ConcurrentHashMap<String, DomainDataStorageAccess> regions = new ConcurrentHashMap<>();

  @Override
  protected void prepareForUse(SessionFactoryOptions settings, Map<String, Object> configValues) {
  }

  @Override
  protected void releaseFromUse() {
    regions.values().forEach(DomainDataStorageAccess::release);
    regions.clear();
  }

  @Override
  protected DomainDataStorageAccess createDomainDataStorageAccess(
    DomainDataRegionConfig regionConfig,
    DomainDataRegionBuildingContext buildingContext) {

    // Create a new map for the region if it doesn't exist, otherwise return the existing one
    return regions.computeIfAbsent(
      regionConfig.getRegionName(),
      InMemoryStorageAccess::new
    );
  }

  @Override
  protected StorageAccess createQueryResultsRegionStorageAccess(String regionName, SessionFactoryImplementor sessionFactory) {
    return regions.computeIfAbsent(
      regionName,
      InMemoryStorageAccess::new
    );
  }

  @Override
  protected StorageAccess createTimestampsRegionStorageAccess(String regionName, SessionFactoryImplementor sessionFactory) {
    return regions.computeIfAbsent(
      regionName,
      InMemoryStorageAccess::new
    );
  }
}

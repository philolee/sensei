package com.senseidb.gateway.kafka.persistent;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import proj.zoie.api.DataConsumer.DataEvent;

import com.senseidb.gateway.kafka.DataPacket;
import com.senseidb.gateway.kafka.KafkaStreamDataProvider;
import com.senseidb.indexing.DataSourceFilter;
import com.senseidb.util.Pair;

public class PersistentKafkaStreamDataProvider extends KafkaStreamDataProvider {
  private static final Logger log = Logger.getLogger(PersistentKafkaStreamDataProvider.class);
  private final PersistentCacheManager cacheManager;
  private final int batchSize;
  private final AtomicInteger currentBatchCounter = new AtomicInteger(0);
  private final long startingOffset;
  private final AtomicLong versionCounter = new AtomicLong();
  private volatile Iterator<Pair<String, String>> eventsFromPersistentCache;

  public PersistentKafkaStreamDataProvider(Comparator<String> versionComparator,
      Map<String, String> config, long offset, DataSourceFilter<DataPacket> dataConverter,
      PersistentCacheManager cacheManager) {
    super(versionComparator, config, dataConverter);
    batchSize = Integer.parseInt(config.get("provider.batchSize"));
    startingOffset = offset;
    versionCounter.set(startingOffset);
    this.cacheManager = cacheManager;
  }

  @Override
  public void start() {
    List<Pair<String, String>> eventsNotAvailableInZoie = cacheManager
        .getEventsNotAvailableInZoie(getStringVersionRepresentation(startingOffset));
    eventsFromPersistentCache = eventsNotAvailableInZoie.iterator();
    super.start();
  }

  @Override
  public DataEvent<JSONObject> next() {
    try {
      if (eventsFromPersistentCache != null && eventsFromPersistentCache.hasNext()) {
        Pair<String, String> next = eventsFromPersistentCache.next();
        return new DataEvent<JSONObject>(new JSONObject(next.getSecond()), next.getFirst());
      }
      DataEvent<JSONObject> next = super.next();
      if (next != null) {
        cacheManager.addEvent(next.getData(), next.getVersion());
        if (batchSize <= currentBatchCounter.incrementAndGet()) {
          cacheManager.commitPengingEvents();
          super.commit();
          currentBatchCounter.set(0);
        }
      }
      return next;
    } catch (Exception ex) {
      log.error(ex.getMessage(), ex);
      return null;
    }
  }

  @Override
  public long getNextVersion() {
    return versionCounter.incrementAndGet();
  }

}

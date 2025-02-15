package tc.oc.pgm.map;

import static tc.oc.pgm.util.Assert.assertNotNull;

import com.google.common.collect.Iterators;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.Nullable;
import tc.oc.pgm.api.map.MapContext;
import tc.oc.pgm.api.map.MapInfo;
import tc.oc.pgm.api.map.MapLibrary;
import tc.oc.pgm.api.map.MapSource;
import tc.oc.pgm.api.map.exception.MapException;
import tc.oc.pgm.api.map.exception.MapMissingException;
import tc.oc.pgm.api.map.factory.MapFactory;
import tc.oc.pgm.api.map.factory.MapSourceFactory;
import tc.oc.pgm.api.map.includes.MapIncludeProcessor;
import tc.oc.pgm.util.LiquidMetal;
import tc.oc.pgm.util.StreamUtils;
import tc.oc.pgm.util.StringUtils;
import tc.oc.pgm.util.UsernameResolver;

public class MapLibraryImpl implements MapLibrary {

  private final Logger logger;
  private final List<MapSourceFactory> factories;
  private final SortedMap<String, MapEntry> maps;
  private final Set<MapSource> failed;
  private final MapIncludeProcessor includes;

  private static class MapEntry {
    private final MapSource source;
    private final MapInfo info;
    private final SoftReference<MapContext> context;

    private MapEntry(MapSource source, MapInfo info, MapContext context) {
      this.source = assertNotNull(source);
      this.info = assertNotNull(info);
      this.context = new SoftReference<>(assertNotNull(context));
    }
  }

  public MapLibraryImpl(
      Logger logger, List<MapSourceFactory> factories, MapIncludeProcessor includes) {
    this.logger = assertNotNull(logger); // Logger should be visible in-game
    this.factories = Collections.synchronizedList(assertNotNull(factories));
    this.maps = Collections.synchronizedSortedMap(new ConcurrentSkipListMap<>());
    this.failed = Collections.synchronizedSet(new HashSet<>());
    this.includes = includes;
  }

  @Override
  public MapInfo getMap(String idOrName) {

    // Exact match
    MapEntry map = maps.get(StringUtils.slugify(idOrName));
    if (map == null) {
      // Fuzzy match
      map =
          StringUtils.bestFuzzyMatch(
              StringUtils.normalize(idOrName), maps.values(), m -> m.info.getNormalizedName());
    }

    return map == null ? null : map.info;
  }

  @Override
  public Stream<MapInfo> getMaps(@Nullable String query) {
    Stream<MapInfo> maps = this.maps.values().stream().map(e -> e.info);
    if (query != null) {
      String normalized = StringUtils.normalize(query);
      maps = maps.filter(mi -> LiquidMetal.match(mi.getNormalizedName(), normalized));
    }
    return maps;
  }

  @Override
  public Iterator<MapInfo> getMaps() {
    return getMaps(null).iterator();
  }

  @Override
  public long getSize() {
    return maps.size();
  }

  @Override
  public MapIncludeProcessor getIncludeProcessor() {
    return includes;
  }

  private void logMapError(MapException err) {
    logger.log(Level.WARNING, err.getMessage(), err);
  }

  private void logMapSuccess(int fail, int ok) {
    fail = failed.size() - fail;
    ok = maps.size() - ok;

    if (fail <= 0 && ok <= 0) {
      logger.info("No new maps found");
    } else if (fail <= 0) {
      logger.info("Loaded " + ChatColor.YELLOW + ok + ChatColor.RESET + " new maps");
    } else if (ok <= 0) {
      logger.info("Failed to load " + ChatColor.YELLOW + fail + ChatColor.RESET + " maps");
    } else {
      logger.info(
          "Loaded "
              + ChatColor.YELLOW
              + ok
              + ChatColor.RESET
              + " new maps, failed to load "
              + ChatColor.YELLOW
              + fail
              + ChatColor.RESET
              + " maps");
    }
  }

  @Override
  public CompletableFuture<?> loadNewMaps(boolean reset) {
    final List<Iterator<? extends MapSource>> sources = new LinkedList<>();

    // Reload failed maps
    if (reset) {
      failed.clear();
    } else {
      sources.add(failed.iterator());
    }

    final int fail = failed.size();
    final int ok = reset ? 0 : maps.size();

    // Discover new maps
    final Iterator<MapSourceFactory> factories = this.factories.listIterator();
    while (factories.hasNext()) {
      final MapSourceFactory factory = factories.next();
      try {
        if (reset) factory.reset();
        sources.add(factory.loadNewSources());
      } catch (MapMissingException e) {
        factories.remove();
        logMapError(e);
      }
    }

    // Reload existing maps that have updates
    final Iterator<Map.Entry<String, MapEntry>> maps = this.maps.entrySet().iterator();
    while (maps.hasNext()) {
      final MapEntry entry = maps.next().getValue();
      try {
        if (reset || entry.source.checkForUpdates()) {
          sources.add(Iterators.singletonIterator(entry.source));
        }
      } catch (MapMissingException e) {
        maps.remove();
        logMapError(e);
      }
    }

    return CompletableFuture.runAsync(
            () ->
                StreamUtils.of(Iterators.concat(sources.iterator()))
                    .parallel()
                    .unordered()
                    .forEach(source -> loadMapSafe(source, null)))
        .thenRunAsync(() -> logMapSuccess(fail, ok))
        .thenRunAsync(UsernameResolver::resolveAll);
  }

  @Override
  public CompletableFuture<MapContext> loadExistingMap(String id) {
    return CompletableFuture.supplyAsync(
        () -> {
          final MapEntry entry = maps.get(id);
          if (entry == null) {
            throw new RuntimeException(
                new MapMissingException(id, "Unable to find map from id (was it deleted?)"));
          }

          final MapContext context = entry.context.get();
          try {
            if (context != null && !entry.source.checkForUpdates()) {
              return context;
            }
          } catch (MapMissingException e) {
            failed.remove(entry.source);
            maps.remove(id);
            throw new RuntimeException(e);
          }

          logger.info(ChatColor.GREEN + "XML changes detected, reloading");
          return loadMapSafe(entry.source, entry.info.getId());
        });
  }

  private MapContext loadMap(MapSource source, @Nullable String mapId) throws MapException {
    final MapContext context;
    try (final MapFactory factory = new MapFactoryImpl(logger, source, includes)) {
      context = factory.load();
    } catch (MapMissingException e) {
      failed.remove(source);
      if (mapId != null) maps.remove(mapId);
      throw e;
    } catch (MapException e) {
      failed.add(source);
      throw e;
    } catch (Throwable t) {
      throw new MapException(
          source,
          null,
          "Unhandled " + t.getClass().getName() + ": " + t.getMessage(),
          t.getCause());
    }

    maps.merge(
        context.getId(),
        new MapEntry(source, context.clone(), context),
        (m1, m2) -> m1.info.getVersion().isOlderThan(m2.info.getVersion()) ? m2 : m1);
    failed.remove(source);

    return context;
  }

  private @Nullable MapContext loadMapSafe(MapSource source, @Nullable String mapId) {
    try {
      return loadMap(source, mapId);
    } catch (MapException e) {
      logMapError(e);
    }
    return null;
  }
}

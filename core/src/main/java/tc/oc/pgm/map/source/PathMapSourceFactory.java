package tc.oc.pgm.map.source;

import static tc.oc.pgm.util.Assert.assertNotNull;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import tc.oc.pgm.api.map.MapSource;
import tc.oc.pgm.api.map.exception.MapMissingException;
import tc.oc.pgm.api.map.factory.MapSourceFactory;

public abstract class PathMapSourceFactory implements MapSourceFactory {

  // Sources are held with weak reference because the callee is
  // responsible for holding a strong reference.
  private final NavigableMap<String, WeakReference<MapSource>> sources;
  private final String path;

  protected PathMapSourceFactory(String path) {
    this.sources = new ConcurrentSkipListMap<>();
    this.path = assertNotNull(path);
  }

  protected abstract MapSource loadSource(String dir);

  private Stream<MapSource> loadNewSource(@Nullable String path) {
    if (path == null || !path.startsWith(this.path) || sources.containsKey(path)) {
      return Stream.empty();
    }

    final int index = path.indexOf(MapSource.FILE);
    if (index < 1) {
      return Stream.empty();
    }

    final MapSource source = loadSource(path.substring(0, index - 1));
    sources.put(path, new WeakReference<>(source));
    return Stream.of(source);
  }

  protected abstract Stream<String> loadAllPaths() throws IOException;

  @Override
  public Iterator<? extends MapSource> loadNewSources() throws MapMissingException {
    final Stream<String> paths;
    try {
      paths = loadAllPaths();
    } catch (IOException e) {
      throw new MapMissingException(path, "Unable to list files", e);
    }

    return paths.parallel().flatMap(this::loadNewSource).iterator();
  }

  @Override
  public void reset() {
    sources.clear();
  }
}

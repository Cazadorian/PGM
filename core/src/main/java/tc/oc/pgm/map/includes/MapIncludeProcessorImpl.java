package tc.oc.pgm.map.includes;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import tc.oc.pgm.api.Config;
import tc.oc.pgm.api.map.exception.MapMissingException;
import tc.oc.pgm.api.map.includes.MapInclude;
import tc.oc.pgm.api.map.includes.MapIncludeProcessor;
import tc.oc.pgm.util.xml.InvalidXMLException;
import tc.oc.pgm.util.xml.Node;
import tc.oc.pgm.util.xml.SAXHandler;
import tc.oc.pgm.util.xml.XMLUtils;

public class MapIncludeProcessorImpl implements MapIncludeProcessor {

  private final Logger logger;
  private final Map<String, MapInclude> includes;

  protected static final ThreadLocal<SAXBuilder> DOCUMENT_FACTORY =
      ThreadLocal.withInitial(
          () -> {
            final SAXBuilder builder = new SAXBuilder();
            builder.setSAXHandlerFactory(SAXHandler.FACTORY);
            return builder;
          });

  public MapIncludeProcessorImpl(Logger logger) {
    this.logger = logger;
    this.includes = Maps.newHashMap();
  }

  public MapInclude getGlobalInclude() {
    return getMapIncludeById("global");
  }

  @Override
  public MapInclude getMapIncludeById(String includeId) {
    return includes.get(includeId);
  }

  @Override
  public Collection<MapInclude> getMapIncludes(Document document) throws InvalidXMLException {
    Set<MapInclude> mapIncludes = Sets.newHashSet();

    // Always add global include if present
    if (getGlobalInclude() != null) {
      mapIncludes.add(getGlobalInclude());
    }

    List<Element> elements = document.getRootElement().getChildren("include");
    for (Element element : elements) {

      if (Node.fromAttr(element, "src") != null) {
        // Send a warning to legacy include statements without preventing them from loading
        logger.warning(
            "["
                + document.getBaseURI()
                + "] "
                + "Legacy include statements are no longer supported, please upgrade to the <include id='name'/> format.");
        continue;
      }

      String id = XMLUtils.getRequiredAttribute(element, "id").getValue();
      MapInclude include = getMapIncludeById(id);
      if (include == null)
        throw new InvalidXMLException(
            "The provided include id '" + id + "' could not be found!", element);

      mapIncludes.add(include);
    }
    return mapIncludes;
  }

  @Override
  public void reload(Config config) {
    this.includes.clear();

    if (config.getIncludesDirectory() == null) return;

    File includeFiles = new File(config.getIncludesDirectory());
    if (!includeFiles.isDirectory()) {
      logger.warning(config.getIncludesDirectory() + " is not a directory!");
      return;
    }
    File[] files = includeFiles.listFiles();
    for (File file : files) {
      if (!file.getName().endsWith(".xml")) continue;
      try {
        MapIncludeImpl include = new MapIncludeImpl(file);
        this.includes.put(include.getId(), include);
      } catch (MapMissingException | JDOMException | IOException error) {
        logger.info("Unable to load " + file.getName() + " include document");
        error.printStackTrace();
      }
    }
  }
}

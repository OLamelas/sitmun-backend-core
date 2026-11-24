package org.sitmun.authorization.editing;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Strict, namespace-aware write envelope. Never derives a target from query parameters. */
public final class WfsTransaction {
  private static final Set<String> WFS =
      Set.of("http://www.opengis.net/wfs", "http://www.opengis.net/wfs/2.0");

  private WfsTransaction() {}

  public static XMLInputFactory xmlFactory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setXMLResolver(
        (publicId, systemId, base, namespace) -> {
          throw new XMLStreamException("External XML resources are forbidden");
        });
    return factory;
  }

  public static Set<QName> types(String xml) throws XMLStreamException {
    if (xml == null || xml.isBlank() || xml.length() > 4_194_304) {
      throw new XMLStreamException("Missing or oversized transaction");
    }
    XMLStreamReader reader = xmlFactory().createXMLStreamReader(new StringReader(xml));
    try {
      Set<QName> types = new LinkedHashSet<>();
      int depth = 0;
      int insertChildren = 0;
      String namespace = null;
      String operation = null;
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
          throw new XMLStreamException("XML declarations/entities are forbidden");
        }
        if (event == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (depth > 128) throw new XMLStreamException("XML nesting limit");
          if (depth == 1) {
            namespace = reader.getNamespaceURI();
            if (namespace == null
                || !WFS.contains(namespace)
                || !"Transaction".equals(reader.getLocalName())) {
              throw new XMLStreamException("Not a WFS transaction");
            }
            String service = reader.getAttributeValue(null, "service");
            if (service != null && !"WFS".equals(service))
              throw new XMLStreamException("Wrong service");
            String version = reader.getAttributeValue(null, "version");
            if (version != null
                && !(namespace.endsWith("/2.0")
                    ? "2.0.0".equals(version)
                    : Set.of("1.0.0", "1.1.0").contains(version))) {
              throw new XMLStreamException("Unsupported WFS version");
            }
          } else if (depth == 2) {
            operation = reader.getLocalName();
            if (!namespace.equals(reader.getNamespaceURI())
                || !Set.of("Insert", "Update", "Delete").contains(operation)) {
              throw new XMLStreamException("Unsupported transaction operation");
            }
            insertChildren = 0;
            if (!"Insert".equals(operation)) {
              String raw = reader.getAttributeValue(null, "typeName");
              types.add(expand(reader, raw));
            }
          } else if (depth == 3 && "Insert".equals(operation)) {
            QName name = reader.getName();
            if (name.getNamespaceURI().isBlank() || WFS.contains(name.getNamespaceURI())) {
              throw new XMLStreamException("Unqualified inserted feature");
            }
            types.add(name);
            insertChildren++;
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (depth == 2 && "Insert".equals(operation) && insertChildren == 0) {
            throw new XMLStreamException("Empty Insert");
          }
          depth--;
        } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
            && depth <= 2
            && !reader.isWhiteSpace()) {
          throw new XMLStreamException("Unexpected transaction text");
        }
      }
      if (types.isEmpty() || depth != 0) throw new XMLStreamException("Empty transaction");
      return Set.copyOf(types);
    } finally {
      reader.close();
    }
  }

  static QName expand(XMLStreamReader reader, String raw) throws XMLStreamException {
    if (raw == null || !raw.matches("[\\p{L}_][\\p{L}\\p{N}_.-]*(:[\\p{L}_][\\p{L}\\p{N}_.-]*)?")) {
      throw new XMLStreamException("Invalid type QName");
    }
    int colon = raw.indexOf(':');
    String namespace = reader.getNamespaceURI(colon < 0 ? "" : raw.substring(0, colon));
    if (namespace == null || namespace.isBlank())
      throw new XMLStreamException("Unbound type QName");
    return new QName(namespace, raw.substring(colon + 1));
  }

  /** Every submitted Update property is a write, even if a client claims it is unchanged. */
  public static Set<String> forbiddenAttributes(String xml, WfsAttributeSchema schema, WfsAttributePolicy policy)
      throws XMLStreamException {
    var root = WfsXml.read(xml.getBytes(StandardCharsets.UTF_8));
    if (root.getNamespaceURI() == null || !WFS.contains(root.getNamespaceURI()) || !"Transaction".equals(root.getLocalName()))
      throw new XMLStreamException("Not a WFS transaction");
    Set<String> forbidden = new LinkedHashSet<>();
    for (var operation : WfsXml.children(root)) {
      if (!root.getNamespaceURI().equals(operation.getNamespaceURI())) throw new XMLStreamException("Unknown operation namespace");
      String kind = operation.getLocalName();
      if ("Insert".equals(kind)) {
        if (operation.hasAttribute("idgen") && !"GenerateNew".equals(operation.getAttribute("idgen")))
          throw new XMLStreamException("Insert cannot reuse or replace existing identifiers");
        for (var feature : WfsXml.children(operation)) {
          if (!WfsXml.name(feature).toString().equals(schema.featureType()))
            throw new XMLStreamException("Wrong inserted feature type");
          for (int i = 0; i < feature.getAttributes().getLength(); i++) {
            if (!javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(feature.getAttributes().item(i).getNamespaceURI()))
              throw new XMLStreamException("Inserted feature attributes/identifiers are unsupported");
          }
          for (var property : WfsXml.children(feature)) {
            var matches = schema.attributes().stream().filter(a -> a.identity().equals(WfsXml.name(property))).toList();
            if (matches.size() != 1) throw new XMLStreamException("Unknown inserted property");
            if (matches.get(0).generated()) forbidden.add(matches.get(0).expandedName());
          }
        }
      } else if ("Update".equals(kind)) {
        if (!WfsXml.expand(operation, operation.getAttribute("typeName")).toString().equals(schema.featureType()))
          throw new XMLStreamException("Wrong updated feature type");
        for (var property : WfsXml.children(operation)) {
          if (isFilter(property)) continue;
          if (!WfsXml.name(property).equals(new QName(root.getNamespaceURI(), "Property")))
            throw new XMLStreamException("Unsupported Update child");
          var children = WfsXml.children(property);
          String reference = root.getNamespaceURI().endsWith("/2.0") ? "ValueReference" : "Name";
          var names = children.stream().filter(e -> WfsXml.name(e).equals(new QName(root.getNamespaceURI(), reference))).toList();
          if (names.size() != 1 || !WfsXml.children(names.get(0)).isEmpty()
              || children.stream().anyMatch(e -> !WfsXml.name(e).equals(new QName(root.getNamespaceURI(), reference))
                  && !WfsXml.name(e).equals(new QName(root.getNamespaceURI(), "Value"))))
            throw new XMLStreamException("Invalid property reference");
          var field = schema.property(names.get(0), names.get(0).getTextContent().trim());
          if (field.generated() || !field.geometry() && !policy.editableAttributes().contains(field.expandedName()))
            forbidden.add(field.expandedName());
        }
      } else if (!"Delete".equals(kind)) throw new XMLStreamException("Unsupported write");
    }
    return Set.copyOf(forbidden);
  }

  public static boolean deletesOnly(String xml) throws XMLStreamException {
    var root = WfsXml.read(xml.getBytes(StandardCharsets.UTF_8));
    return WfsXml.children(root).stream().allMatch(e -> "Delete".equals(e.getLocalName()));
  }

  private static boolean isFilter(org.w3c.dom.Element element) {
    return "Filter".equals(element.getLocalName()) && Set.of("http://www.opengis.net/ogc", "http://www.opengis.net/fes/2.0")
        .contains(element.getNamespaceURI());
  }
}

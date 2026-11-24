package org.sitmun.authorization.editing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import org.w3c.dom.Element;

/** Verifiable top-level properties of one feature type. Never follows schema URLs. */
public record WfsAttributeSchema(String featureType, List<Attribute> attributes) {
  static final String XSD = "http://www.w3.org/2001/XMLSchema";
  static final Set<String> GML = Set.of("http://www.opengis.net/gml", "http://www.opengis.net/gml/3.2");
  private static final Set<String> GEOMETRIES = Set.of("GeometryPropertyType", "PointPropertyType",
      "MultiPointPropertyType", "LineStringPropertyType", "MultiLineStringPropertyType",
      "CurvePropertyType", "MultiCurvePropertyType", "PolygonPropertyType", "MultiPolygonPropertyType",
      "SurfacePropertyType", "MultiSurfacePropertyType", "MultiGeometryPropertyType");
  public record Attribute(String name, String namespace, String type, boolean geometry, boolean generated) {
    public QName identity() { return new QName(namespace, name); }
    /** Policy/wire identity retains the empty namespace, unlike QName.toString(). */
    public String expandedName() { return "{" + namespace + "}" + name; }
  }

  public Attribute property(Element context, String raw) throws XMLStreamException {
    // WFS property references may be bare local names, but only when unique in this schema.
    if (raw == null || !raw.matches("[\\p{L}_][\\p{L}\\p{N}_.-]*(:[\\p{L}_][\\p{L}\\p{N}_.-]*)?"))
      throw new XMLStreamException("Unsupported property path");
    QName qualified = raw.contains(":") ? WfsXml.expand(context, raw) : null;
    var matches = attributes.stream().filter(a -> qualified == null ? a.name().equals(raw) : a.identity().equals(qualified)).toList();
    if (matches.size() != 1) throw new XMLStreamException("Unknown or ambiguous property");
    return matches.get(0);
  }

  public static WfsAttributeSchema parse(byte[] document, QName feature) throws XMLStreamException {
    Element schema = WfsXml.read(document);
    if (!WfsXml.name(schema).equals(new QName(XSD, "schema"))
        || !feature.getNamespaceURI().equals(schema.getAttribute("targetNamespace")))
      throw new XMLStreamException("Feature schema namespace mismatch");
    if (schema.hasAttribute("elementFormDefault") && !Set.of("qualified", "unqualified").contains(schema.getAttribute("elementFormDefault")))
      throw new XMLStreamException("Invalid schema qualification");
    // Standard inherited GML infrastructure is not data. Custom imported definitions
    // must be available in the response; arbitrary imports/includes are not fetched.
    if (WfsXml.children(schema).stream().anyMatch(e -> XSD.equals(e.getNamespaceURI())
        && Set.of("include", "redefine", "override").contains(e.getLocalName())))
      throw new XMLStreamException("External schema definitions are unsupported");
    Element declaration = declaration(schema, "element", feature);
    List<Attribute> fields = new ArrayList<>();
    collect(schema, complexType(schema, declaration), fields, new HashSet<>(), 0);
    if (fields.size() > 4096) throw new XMLStreamException("Feature property count limit");
    if (fields.stream().map(Attribute::identity).distinct().count() != fields.size())
      throw new XMLStreamException("Duplicate feature properties");
    return new WfsAttributeSchema(feature.toString(), List.copyOf(fields));
  }

  private static Element declaration(Element schema, String kind, QName name) throws XMLStreamException {
    if (!schema.getAttribute("targetNamespace").equals(name.getNamespaceURI()))
      throw new XMLStreamException("Unresolved imported schema declaration");
    var matches = WfsXml.children(schema).stream().filter(e -> XSD.equals(e.getNamespaceURI())
        && kind.equals(e.getLocalName()) && name.getLocalPart().equals(e.getAttribute("name"))).toList();
    if (matches.size() != 1) throw new XMLStreamException("Missing or ambiguous schema declaration");
    return matches.get(0);
  }

  private static Element complexType(Element schema, Element element) throws XMLStreamException {
    var inline = WfsXml.children(element).stream().filter(e -> WfsXml.name(e).equals(new QName(XSD, "complexType"))).toList();
    if (inline.size() == 1) return inline.get(0);
    return declaration(schema, "complexType", WfsXml.expand(element, element.getAttribute("type")));
  }

  private static boolean geometryType(Element schema, QName type, Set<QName> active) throws XMLStreamException {
    if (XSD.equals(type.getNamespaceURI())) return false;
    if (GML.contains(type.getNamespaceURI())) {
      if (!GEOMETRIES.contains(type.getLocalPart())) throw new XMLStreamException("Unsupported GML property type");
      return true;
    }
    if (active.size() > 32 || !active.add(type)) throw new XMLStreamException("Recursive property type");
    try {
      var declarations = WfsXml.children(schema).stream().filter(e -> XSD.equals(e.getNamespaceURI())
          && Set.of("complexType", "simpleType").contains(e.getLocalName())
          && schema.getAttribute("targetNamespace").equals(type.getNamespaceURI())
          && type.getLocalPart().equals(e.getAttribute("name"))).toList();
      if (declarations.size() != 1) throw new XMLStreamException("Unresolved property type");
      return geometryDerivation(schema, declarations.get(0), active);
    } finally { active.remove(type); }
  }

  private static boolean geometryDerivation(Element schema, Element node, Set<QName> active) throws XMLStreamException {
    for (Element child : WfsXml.children(node)) {
      if (!XSD.equals(child.getNamespaceURI())) continue;
      if (Set.of("extension", "restriction").contains(child.getLocalName()) && child.hasAttribute("base"))
        return geometryType(schema, WfsXml.expand(child, child.getAttribute("base")), active);
      if (Set.of("complexType", "complexContent", "simpleContent").contains(child.getLocalName())
          && geometryDerivation(schema, child, active)) return true;
    }
    return false;
  }

  private static void collect(Element schema, Element node, List<Attribute> fields, Set<Element> active, int depth)
      throws XMLStreamException {
    if (depth > 32 || !active.add(node)) throw new XMLStreamException("Recursive feature schema");
    try {
      for (Element child : WfsXml.children(node)) {
        if (!XSD.equals(child.getNamespaceURI())) throw new XMLStreamException("Unsupported schema content");
        switch (child.getLocalName()) {
          case "annotation" -> { }
          case "sequence", "all", "choice", "complexContent" -> collect(schema, child, fields, active, depth + 1);
          case "extension" -> {
            QName base = WfsXml.expand(child, child.getAttribute("base"));
            if (!(GML.contains(base.getNamespaceURI()) && "AbstractFeatureType".equals(base.getLocalPart())))
              collect(schema, declaration(schema, "complexType", base), fields, active, depth + 1);
            collect(schema, child, fields, active, depth + 1);
          }
          case "element" -> {
            Element source = child;
            QName identity;
            if (child.hasAttribute("ref")) {
              if (child.hasAttribute("name") || child.hasAttribute("type") || child.hasAttribute("form"))
                throw new XMLStreamException("Ambiguous property declaration");
              identity = WfsXml.expand(child, child.getAttribute("ref"));
              source = declaration(schema, "element", identity);
            } else {
              String name = child.getAttribute("name");
              if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_.-]*")) throw new XMLStreamException("Invalid field name");
              if (child.hasAttribute("form") && !Set.of("qualified", "unqualified").contains(child.getAttribute("form")))
                throw new XMLStreamException("Invalid property qualification");
              boolean qualified = "qualified".equals(child.hasAttribute("form") ? child.getAttribute("form") : schema.getAttribute("elementFormDefault"));
              identity = new QName(qualified ? schema.getAttribute("targetNamespace") : "", name);
            }
            QName type = source.hasAttribute("type") ? WfsXml.expand(source, source.getAttribute("type")) : null;
            boolean geometry = type != null ? geometryType(schema, type, new HashSet<>()) : geometryDerivation(schema, source, new HashSet<>());
            var generation = WfsXml.children(source).stream()
                .filter(e -> WfsXml.name(e).equals(new QName(XSD, "annotation")))
                .flatMap(e -> WfsXml.children(e).stream())
                .filter(e -> WfsXml.name(e).equals(new QName(XSD, "appinfo"))
                    && "urn:sitmun:wfs:server-generated".equals(e.getAttribute("source"))).toList();
            if (generation.size() > 1 || !generation.isEmpty()
                && !Set.of("true", "false").contains(generation.get(0).getTextContent().trim()))
              throw new XMLStreamException("Ambiguous generation metadata");
            boolean generated = generation.size() == 1 && "true".equals(generation.get(0).getTextContent().trim());
            fields.add(new Attribute(identity.getLocalPart(), identity.getNamespaceURI(), type == null ? "complex" : type.toString(), geometry, generated));
          }
          default -> throw new XMLStreamException("Unsupported feature schema structure");
        }
      }
    } finally { active.remove(node); }
  }
}

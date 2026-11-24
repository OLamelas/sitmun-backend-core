package org.sitmun.authorization.editing;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Bounded DOM built from the same non-resolving XML reader as transaction envelopes. */
final class WfsXml {
  private WfsXml() {}

  static Element read(byte[] xml) throws XMLStreamException {
    if (xml.length > 4_194_304) throw new XMLStreamException("XML size limit");
    try {
      Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      var reader = WfsTransaction.xmlFactory().createXMLStreamReader(new ByteArrayInputStream(xml));
      var stack = new ArrayDeque<Element>();
      int nodes = 0;
      try {
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
            throw new XMLStreamException("XML declarations/entities are forbidden");
          if (event == XMLStreamConstants.START_ELEMENT) {
            if (stack.size() >= 128 || ++nodes > 100_000)
              throw new XMLStreamException("XML structure limit");
            String prefix = reader.getPrefix();
            Element element = document.createElementNS(reader.getNamespaceURI(),
                prefix == null || prefix.isEmpty() ? reader.getLocalName() : prefix + ":" + reader.getLocalName());
            for (int i = 0; i < reader.getNamespaceCount(); i++) {
              String p = reader.getNamespacePrefix(i);
              element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                  p == null || p.isEmpty() ? "xmlns" : "xmlns:" + p,
                  reader.getNamespaceURI(i) == null ? "" : reader.getNamespaceURI(i));
            }
            for (int i = 0; i < reader.getAttributeCount(); i++) {
              QName name = reader.getAttributeName(i);
              element.setAttributeNS(name.getNamespaceURI().isEmpty() ? null : name.getNamespaceURI(),
                  name.getPrefix().isEmpty() ? name.getLocalPart() : name.getPrefix() + ":" + name.getLocalPart(),
                  reader.getAttributeValue(i));
            }
            if (stack.isEmpty()) document.appendChild(element);
            else stack.peek().appendChild(element);
            stack.push(element);
          } else if (event == XMLStreamConstants.END_ELEMENT) stack.pop();
          else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
            if (!stack.isEmpty()) stack.peek().appendChild(document.createTextNode(reader.getText()));
          }
        }
        if (document.getDocumentElement() == null) throw new XMLStreamException("Empty XML");
        return document.getDocumentElement();
      } finally { reader.close(); }
    } catch (XMLStreamException ex) { throw ex; }
    catch (Exception ex) { throw new XMLStreamException("Invalid XML", ex); }
  }

  static List<Element> children(Element element) {
    List<Element> result = new ArrayList<>();
    for (var child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element item) result.add(item);
    return result;
  }

  static QName name(Element element) {
    return new QName(element.getNamespaceURI() == null ? "" : element.getNamespaceURI(), element.getLocalName());
  }

  static QName expand(Element element, String raw) throws XMLStreamException {
    if (raw == null || !raw.matches("[\\p{L}_][\\p{L}\\p{N}_.-]*(:[\\p{L}_][\\p{L}\\p{N}_.-]*)?"))
      throw new XMLStreamException("Invalid QName or property path");
    int colon = raw.indexOf(':');
    String namespace = element.lookupNamespaceURI(colon < 0 ? null : raw.substring(0, colon));
    if (namespace == null || namespace.isBlank()) throw new XMLStreamException("Unbound QName");
    return new QName(namespace, raw.substring(colon + 1));
  }
}

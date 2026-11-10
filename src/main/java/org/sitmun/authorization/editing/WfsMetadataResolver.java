package org.sitmun.authorization.editing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Reads only the configured endpoint, never advertised operation URLs or XSD imports. */
@Component
public class WfsMetadataResolver {
  private static final int MAX_BYTES = 2_097_152;
  private static final int TIMEOUT_MS = 3000;

  public Set<QName> resolve(
      String endpoint,
      Map<String, String> fixed,
      String user,
      String password,
      List<String> configured)
      throws IOException, XMLStreamException {
    URI uri = URI.create(endpoint);
    if (uri.getScheme() == null
        || !Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || configured == null
        || configured.isEmpty()) throw new IOException("Unverifiable WFS endpoint");
    // The current proxy rebuilds encoded URLs differently when parameters are
    // present. Do not certify a destination whose wire identity is ambiguous.
    if (!java.util.Objects.equals(uri.getRawPath(), uri.getPath())
        || uri.getRawQuery() != null
            && (uri.getRawQuery().contains("%") || uri.getRawQuery().contains("+"))) {
      throw new IOException("Ambiguous encoded WFS endpoint");
    }
    var builder = UriComponentsBuilder.fromUri(uri);
    // Preserve backend-owned routing parameters, but never request a write as metadata.
    var query = builder.build(true).getQueryParams();
    for (var parameter : query.entrySet()) {
      String key = parameter.getKey();
      if (fixed.keySet().stream().anyMatch(key::equalsIgnoreCase)) {
        throw new IOException("Conflicting endpoint and fixed parameters");
      }
      if ("REQUEST".equalsIgnoreCase(key)
              && parameter.getValue().stream()
                  .anyMatch(value -> !"Transaction".equalsIgnoreCase(value))
          || "SERVICE".equalsIgnoreCase(key)
              && parameter.getValue().stream().anyMatch(value -> !"WFS".equalsIgnoreCase(value))) {
        throw new IOException("Incoherent configured WFS operation");
      }
    }
    for (String key : query.keySet()) {
      if (Set.of("REQUEST", "SERVICE", "TYPENAME", "TYPENAMES")
          .contains(
              URLDecoder.decode(key, StandardCharsets.UTF_8).toUpperCase(java.util.Locale.ROOT))) {
        builder.replaceQueryParam(key);
      }
    }
    fixed.forEach(
        (key, value) -> {
          if (!Set.of("REQUEST", "SERVICE", "TYPENAME", "TYPENAMES")
              .contains(key.toUpperCase(java.util.Locale.ROOT))) {
            query.keySet().stream()
                .filter(key::equalsIgnoreCase)
                .forEach(builder::replaceQueryParam);
            builder.replaceQueryParam(
                URLEncoder.encode(key, StandardCharsets.UTF_8),
                URLEncoder.encode(value, StandardCharsets.UTF_8));
          }
        });
    builder.queryParam("SERVICE", "WFS").queryParam("REQUEST", "GetCapabilities");
    byte[] document = fetch(builder.build(true).toUri(), user, password);
    return resolveNames(document, configured);
  }

  byte[] fetch(URI uri, String user, String password) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(TIMEOUT_MS);
    connection.setReadTimeout(TIMEOUT_MS);
    connection.setRequestProperty("Accept", "application/xml,text/xml");
    if (password != null && !password.isEmpty()) {
      String credentials = (user == null ? "" : user) + ":" + password;
      connection.setRequestProperty(
          "Authorization",
          "Basic "
              + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
    long deadline = System.nanoTime() + Duration.ofMillis(TIMEOUT_MS * 2L).toNanos();
    try {
      if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES) {
        throw new IOException("WFS metadata unavailable");
      }
      try (var input = connection.getInputStream();
          var output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (output.size() + count > MAX_BYTES || System.nanoTime() > deadline) {
            throw new IOException("WFS metadata limit exceeded");
          }
          output.write(buffer, 0, count);
        }
        return output.toByteArray();
      }
    } finally {
      connection.disconnect();
    }
  }

  static Set<QName> resolveNames(byte[] document, List<String> configured)
      throws XMLStreamException {
    XMLStreamReader reader =
        WfsTransaction.xmlFactory().createXMLStreamReader(new ByteArrayInputStream(document));
    record Name(String lexical, QName expanded) {}
    List<Name> names = new ArrayList<>();
    List<QName> path = new ArrayList<>();
    String namespace = null;
    try {
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
          throw new XMLStreamException("XML declarations/entities are forbidden");
        }
        if (event == XMLStreamConstants.START_ELEMENT) {
          path.add(reader.getName());
          if (path.size() > 128) throw new XMLStreamException("XML nesting limit");
          if (path.size() == 1) {
            namespace = reader.getNamespaceURI();
            if (namespace == null
                || !Set.of("http://www.opengis.net/wfs", "http://www.opengis.net/wfs/2.0")
                    .contains(namespace)
                || !"WFS_Capabilities".equals(reader.getLocalName())) {
              throw new XMLStreamException("Not WFS capabilities");
            }
          }
          if (path.size() == 4
              && path.get(1).equals(new QName(namespace, "FeatureTypeList"))
              && path.get(2).equals(new QName(namespace, "FeatureType"))
              && reader.getName().equals(new QName(namespace, "Name"))) {
            String lexical = reader.getElementText().trim();
            QName expanded = WfsTransaction.expand(reader, lexical);
            if (Set.of("http://www.opengis.net/wfs", "http://www.opengis.net/wfs/2.0")
                .contains(expanded.getNamespaceURI())) {
              throw new XMLStreamException("Feature namespace is not established");
            }
            names.add(new Name(lexical, expanded));
            path.remove(path.size() - 1);
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          path.remove(path.size() - 1);
        }
      }
    } finally {
      reader.close();
    }
    Set<QName> result = new LinkedHashSet<>();
    for (String configuredName : configured) {
      if (configuredName == null || configuredName.isBlank())
        throw new XMLStreamException("Missing type");
      String raw = configuredName.trim();
      Set<QName> matches = new LinkedHashSet<>();
      for (Name name : names) {
        if (raw.equals(name.lexical())
            || (!raw.contains(":") && raw.equals(name.expanded().getLocalPart()))) {
          matches.add(name.expanded());
        }
      }
      if (matches.size() != 1)
        throw new XMLStreamException("Unknown or ambiguous configured WFS type");
      result.add(matches.iterator().next());
    }
    if (result.isEmpty()) throw new XMLStreamException("No configured WFS types");
    return Set.copyOf(result);
  }
}

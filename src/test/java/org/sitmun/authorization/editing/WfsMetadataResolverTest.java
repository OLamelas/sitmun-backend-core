package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

class WfsMetadataResolverTest {
  private static byte[] capabilities(String names) {
    return ("<WFS_Capabilities xmlns='http://www.opengis.net/wfs' xmlns:a='urn:a'"
            + " xmlns:b='urn:b'><FeatureTypeList>"
            + names
            + "</FeatureTypeList></WFS_Capabilities>")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void resolvesOnlyAuthoritativeNames() throws Exception {
    assertEquals(
        Set.of(new QName("urn:a", "roads")),
        WfsMetadataResolver.resolveNames(
            capabilities("<FeatureType><Name>a:roads</Name></FeatureType>"), List.of("a:roads")));
  }

  @Test
  void rejectsAmbiguousShortNamesAndUnknownQualifiedNames() {
    byte[] xml =
        capabilities(
            "<FeatureType><Name>a:roads</Name></FeatureType><FeatureType><Name>b:roads</Name></FeatureType>");
    assertThrows(
        XMLStreamException.class, () -> WfsMetadataResolver.resolveNames(xml, List.of("roads")));
    assertThrows(
        XMLStreamException.class, () -> WfsMetadataResolver.resolveNames(xml, List.of("c:roads")));
    assertThrows(
        XMLStreamException.class,
        () ->
            WfsMetadataResolver.resolveNames(
                capabilities("<FeatureType><Name>roads</Name></FeatureType>"), List.of("roads")));
  }

  @Test
  void usesOnlyConfiguredEndpointAndServerParametersWithoutDoubleEncoding() throws Exception {
    var observed = new AtomicReference<URI>();
    var resolver =
        new WfsMetadataResolver() {
          @Override
          byte[] fetch(URI uri, String user, String password) {
            observed.set(uri);
            return capabilities("<FeatureType><Name>a:roads</Name></FeatureType>");
          }
        };
    resolver.resolve(
        "http://configured.test/workspace?REQUEST=Transaction",
        Map.of("token", "a+b c"),
        "server-user",
        "server-password",
        List.of("a:roads"));
    assertEquals("/workspace", observed.get().getRawPath());
    assertFalse(observed.get().getRawQuery().contains("Transaction"));
    assertTrue(observed.get().getRawQuery().contains("REQUEST=GetCapabilities"));
    assertTrue(observed.get().getRawQuery().contains("token=a%2Bb+c"));
    observed.set(null);
    assertThrows(
        java.io.IOException.class,
        () ->
            resolver.resolve(
                "http://configured.test/workspace%2Fname",
                Map.of(), null, null, List.of("a:roads")));
    assertNull(observed.get());
  }

  @Test
  void preservesEncodedPlusAtTheActualMetadataReceiver() throws Exception {
    var requests = new java.util.ArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/wfs", exchange -> {
      String query = exchange.getRequestURI().getRawQuery();
      requests.add(query);
      byte[] response = query.contains("GetCapabilities")
          ? capabilities("<FeatureType><Name>a:roads</Name></FeatureType>")
          : """
              <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:a">
                <x:element name="roads"><x:complexType><x:sequence><x:element name="id" type="x:int"/>
                </x:sequence></x:complexType></x:element>
              </x:schema>
              """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (var body = exchange.getResponseBody()) { body.write(response); }
    });
    server.start();
    try {
      var schema = new WfsMetadataResolver().describe(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/wfs?outputFormat=application/gml%2Bxml",
          Map.of(), null, null, List.of("a:roads"));
      assertEquals("{urn:a}roads", schema.featureType());
      assertEquals(2, requests.size());
      for (String query : requests) {
        assertTrue(query.contains("outputFormat=application/gml%2Bxml"));
        String value = query.split("&")[0].split("=", 2)[1];
        assertEquals("application/gml+xml", URLDecoder.decode(value, StandardCharsets.UTF_8));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsAmbiguousEncodedAndConflictingEndpointsBeforeFetching() {
    var observed = new AtomicReference<URI>();
    var resolver = new WfsMetadataResolver() {
      @Override byte[] fetch(URI uri, String user, String password) {
        observed.set(uri);
        return capabilities("<FeatureType><Name>a:roads</Name></FeatureType>");
      }
    };
    for (String query : List.of("outputFormat=application/gml+xml", "%6futputFormat=x",
        "outputFormat=application%252Bxml", "x=1&X=2", "REQUEST=Transaction&request=Transaction",
        "SERVICE=WMS", "outputFormat=application/gml%2Bxml&OUTPUTFORMAT=other")) {
      assertThrows(java.io.IOException.class, () -> resolver.resolve(
          "http://configured.test/wfs?" + query, Map.of(), null, null, List.of("a:roads")), query);
    }
    assertThrows(java.io.IOException.class, () -> resolver.resolve(
        "http://configured.test/wfs?outputFormat=application/gml%2Bxml",
        Map.of("outputFormat", "application/gml+xml"), null, null, List.of("a:roads")));
    assertNull(observed.get());
  }

  @Test
  void describesOnlyTheConfiguredSingleTypeWithoutFollowingAdvertisedOrImportedUrls() throws Exception {
    var observed = new java.util.ArrayList<URI>();
    var resolver = new WfsMetadataResolver() {
      @Override byte[] fetch(URI uri, String user, String password) {
        observed.add(uri);
        assertEquals("server-user", user);
        return uri.getQuery().contains("GetCapabilities")
            ? capabilities("<FeatureType><Name>a:roads</Name></FeatureType>")
            : """
                <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:a">
                  <x:import namespace="http://www.opengis.net/gml/3.2" schemaLocation="https://never-fetch.test/gml.xsd"/>
                  <x:element name="roads"><x:complexType><x:sequence><x:element name="id" type="x:int"/>
                  </x:sequence></x:complexType></x:element>
                </x:schema>
                """.getBytes(StandardCharsets.UTF_8);
      }
    };
    var schema = resolver.describe("http://configured.test/wfs", Map.of("token", "server token"),
        "server-user", "server-password", List.of("a:roads"));
    assertEquals("{urn:a}roads", schema.featureType());
    assertEquals("id", schema.attributes().get(0).name());
    assertEquals(2, observed.size());
    assertTrue(observed.stream().allMatch(uri -> "configured.test".equals(uri.getHost())));
    assertTrue(observed.get(1).getRawQuery().contains("TYPENAME=a%3Aroads"));
    assertTrue(observed.get(1).getRawQuery().contains("token=server+token"));
    observed.clear();
    assertThrows(XMLStreamException.class, () -> resolver.describe("http://configured.test/wfs", Map.of(),
        "server-user", "server-password", List.of("a:roads", "b:roads")));
    assertTrue(observed.isEmpty());
  }

  @Test
  void distinguishesRequestAndParsingStagesWithoutRetainingRemoteSecrets() {
    for (var stage : List.of(WfsDiscoveryException.Stage.CAPABILITIES_REQUEST,
        WfsDiscoveryException.Stage.CAPABILITIES_PARSE, WfsDiscoveryException.Stage.SCHEMA_REQUEST,
        WfsDiscoveryException.Stage.SCHEMA_PARSE)) {
      var resolver = new WfsMetadataResolver() {
        @Override byte[] fetch(URI uri, String user, String password) throws java.io.IOException {
          boolean capabilitiesRequest = uri.getQuery().contains("GetCapabilities");
          if (stage == WfsDiscoveryException.Stage.CAPABILITIES_REQUEST
              || !capabilitiesRequest && stage == WfsDiscoveryException.Stage.SCHEMA_REQUEST)
            throw new java.net.SocketTimeoutException("https://user:secret@private.test/?token=secret");
          if (capabilitiesRequest && stage != WfsDiscoveryException.Stage.CAPABILITIES_PARSE)
            return capabilities("<FeatureType><Name>a:roads</Name></FeatureType>");
          return "<invalid>secret-body</invalid>".getBytes(StandardCharsets.UTF_8);
        }
      };
      var failure = assertThrows(WfsDiscoveryException.class, () ->
          resolver.describe("http://configured.test/wfs", Map.of(), null, null, List.of("a:roads")));
      assertEquals(stage, failure.stage());
      assertEquals(stage.name().endsWith("REQUEST") ? WfsDiscoveryException.Category.TIMEOUT
          : WfsDiscoveryException.Category.XML, failure.category());
      assertNull(failure.getCause());
      assertNull(failure.getNestedException());
      assertFalse(failure.toString().contains("secret"));
      assertFalse(failure.toString().contains("https"));
    }
  }
}

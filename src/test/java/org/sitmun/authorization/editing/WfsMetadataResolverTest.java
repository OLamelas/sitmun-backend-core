package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
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
}

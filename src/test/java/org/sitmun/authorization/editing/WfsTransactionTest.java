package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;
import org.sitmun.authorization.proxy.ogc.OgcLayerAccess;
import org.sitmun.authorization.proxy.ogc.OgcRequestNames;

class WfsTransactionTest {
  private static String transaction(String operations) {
    return "<w:Transaction xmlns:w='http://www.opengis.net/wfs' xmlns:a='urn:roads'"
        + " xmlns:b='urn:roads' xmlns:x='urn:secret'>"
        + operations
        + "</w:Transaction>";
  }

  @Test
  void collectsEveryInsertChildWithoutTreatingPropertiesAsTypes() throws Exception {
    assertEquals(
        Set.of(new QName("urn:roads", "roads"), new QName("urn:secret", "other")),
        WfsTransaction.types(
            transaction(
                "<w:Insert><a:roads><a:value><x:nested/></a:value></a:roads><x:other/></w:Insert>")));
  }

  @Test
  void equivalentPrefixesAuthorizeButForgedNamespaceDoesNot() {
    var good =
        OgcRequestNames.parse(
            null, transaction("<w:Update typeName='b:roads'/><w:Delete typeName='a:roads'/>"));
    assertTrue(OgcLayerAccess.allowTransaction(good, Set.of(new QName("urn:roads", "roads"))));
    var forged = OgcRequestNames.parse(null, transaction("<w:Delete typeName='x:roads'/>"));
    assertFalse(OgcLayerAccess.allowTransaction(forged, Set.of(new QName("urn:roads", "roads"))));
  }

  @Test
  void rejectsConflictingOperationAliasesInsteadOfChoosingOneQueryValue() {
    assertTrue(
        OgcRequestNames.parse(
                java.util.Map.of("REQUEST", "GetCapabilities", "request", "Transaction"), null)
            .rejected());
    assertTrue(
        OgcRequestNames.parse(
                java.util.Map.of("REQUEST", "unknown"),
                transaction("<w:Delete typeName='a:roads'/>"))
            .rejected());
  }

  @Test
  void queryCannotCompleteAnUnknownInsertOrUnboundQName() {
    for (String operation :
        new String[] {
          "<w:Insert><roads/></w:Insert>",
          "<w:Delete typeName='u:roads'/>",
          "<w:Replace typeName='a:roads'/>",
          "<w:Insert/>",
          "<x:Delete typeName='a:roads'/>"
        }) {
      var view =
          OgcRequestNames.parse(java.util.Map.of("TYPENAME", "a:roads"), transaction(operation));
      assertTrue(view.rejected(), operation);
    }
  }

  @Test
  void rejectsDtdMalformedWrongNamespaceAndUnsupportedVersions() {
    for (String xml :
        new String[] {
          "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>"
              + transaction("<w:Insert><a:roads>&e;</a:roads></w:Insert>"),
          "<Transaction xmlns='urn:fake'><Delete typeName='a:roads'/></Transaction>",
          transaction("<w:Delete typeName='a:roads'>"),
          transaction("<w:Delete typeName='a:roads'/>")
              .replace("<w:Transaction ", "<w:Transaction version='9.0' ")
        }) {
      assertThrows(XMLStreamException.class, () -> WfsTransaction.types(xml));
    }
  }
}

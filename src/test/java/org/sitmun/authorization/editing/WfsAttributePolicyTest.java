package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.service.Service;
import org.sitmun.domain.task.Task;
import org.sitmun.domain.task.type.TaskType;

class WfsAttributePolicyTest {
  static WfsAttributeSchema schema() throws XMLStreamException {
    return schema(true);
  }

  static WfsAttributeSchema schema(boolean qualified) throws XMLStreamException {
    return parse("""
        <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema" xmlns:a="urn:roads"
          xmlns:g="http://www.opengis.net/gml/3.2" targetNamespace="urn:roads" elementFormDefault="%s">
          <x:import namespace="http://www.opengis.net/gml/3.2" schemaLocation="https://never-fetch.test/gml.xsd"/>
          <x:element name="roads" type="a:RoadType"/>
          <x:complexType name="RoadType"><x:complexContent><x:extension base="g:AbstractFeatureType"><x:sequence>
            <x:element name="shape" type="a:CustomGeometry"/>
            <x:element name="name" type="x:string"/>
            <x:element name="id" type="x:int"/>
            <x:element name="gid" type="x:int"><x:annotation>
              <x:appinfo source="urn:sitmun:wfs:server-generated">true</x:appinfo>
            </x:annotation></x:element>
          </x:sequence></x:extension></x:complexContent></x:complexType>
          <x:complexType name="CustomGeometry"><x:complexContent><x:extension base="g:PointPropertyType"/></x:complexContent></x:complexType>
        </x:schema>
        """.formatted(qualified ? "qualified" : "unqualified"));
  }

  private static WfsAttributeSchema parse(String xml) throws XMLStreamException {
    return WfsAttributeSchema.parse(xml.getBytes(StandardCharsets.UTF_8), new QName("urn:roads", "roads"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void publishesOnlyVerifiedPermissionsAndLeavesMobileFieldsUnchanged(boolean qualified) throws Exception {
    var schema = schema(qualified);
    String prefix = qualified ? "{urn:roads}" : "{}";
    var layer = Cartography.builder().id(8).blocked(false).service(Service.builder().id(5).type("WFS").blocked(false).build()).build();
    var task = task(layer, "{urn:roads}roads", List.of(prefix + "id", prefix + "obsolete"));
    var taskMapper = org.mockito.Mockito.mock(org.sitmun.authorization.client.service.TaskMapper.class);
    var mobile = Map.<String, Object>of("id", Map.of("editable", false));
    org.mockito.Mockito.when(taskMapper.accept(task)).thenReturn(true);
    org.mockito.Mockito.when(taskMapper.map(org.mockito.ArgumentMatchers.eq(task), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> org.sitmun.authorization.client.dto.TaskDto.builder().id("task/1").type("edition")
            .layer("layer/8").fields(mobile).build());
    var mapper = org.mapstruct.factory.Mappers.getMapper(org.sitmun.authorization.client.mapper.ProfileMapper.class);
    org.springframework.test.util.ReflectionTestUtils.setField(mapper, "taskMappers", List.of(taskMapper));
    var application = org.sitmun.domain.application.Application.builder().id(1).build();
    var territory = org.sitmun.domain.territory.Territory.builder().id(2).build();
    var profile = org.sitmun.authorization.client.service.Profile.builder().application(application).territory(territory)
        .layers(List.of(layer)).tasks(List.of(task)).services(List.of()).groups(List.of()).backgrounds(List.of())
        .trees(List.of()).treeNodes(Map.of()).global(List.of()).wfsAttributeSchemas(Map.of(8, schema)).build();
    var published = mapper.map(profile, application, territory).getTasks().get(0);
    assertEquals(Set.of(prefix + "id"), published.getWfsAttributePolicy().editableAttributes());
    assertEquals(schema, published.getWfsAttributeSchema());
    assertEquals(mobile, published.getFields());
    profile.setWfsAttributeSchemas(Map.of());
    assertNull(mapper.map(profile, application, territory).getTasks().get(0).getWfsAttributePolicy());
  }

  @Test
  void unionsOnlyCurrentAuthorizedTasksAndKnownDataFields() throws Exception {
    var layer = Cartography.builder().id(8).blocked(false)
        .service(Service.builder().id(5).type("WFS").blocked(false).build()).build();
    Task a = task(layer, "{urn:roads}roads", List.of("{urn:roads}name", "{urn:roads}obsolete", "{urn:roads}gid"));
    Task b = task(layer, "{urn:roads}roads", List.of("{urn:roads}id", "{urn:roads}shape"));
    Task wrongType = task(layer, "{urn:other}roads", List.of("{urn:roads}name"));
    assertEquals(Set.of("{urn:roads}name", "{urn:roads}id"),
        WfsAttributePolicy.effective(layer, List.of(a, b, wrongType), schema()).editableAttributes());
    a.setProperties(Map.of("fields", List.of(Map.of("name", "name", "editable", true))));
    assertTrue(WfsAttributePolicy.effective(layer, List.of(a, wrongType), schema()).editableAttributes().isEmpty());
    b.setProperties(Map.of("scope", "db-edit", WfsAttributePolicy.KEY,
        Map.of("version", 1, "featureType", "{urn:roads}roads", "editableAttributes", List.of("{urn:roads}id"))));
    assertTrue(WfsAttributePolicy.effective(layer, List.of(b), schema()).editableAttributes().isEmpty());
  }

  @Test
  void distinguishesInsertUpdateDeleteAndAllowsNormalIdButNotGeneratedGid() throws Exception {
    var schema = schema();
    var policy = new WfsAttributePolicy(1, schema.featureType(), Set.of("{urn:roads}id"));
    assertTrue(WfsTransaction.forbiddenAttributes(tx("<w:Insert><a:roads><a:name>new</a:name><a:id>9</a:id></a:roads></w:Insert>"), schema, policy).isEmpty());
    assertEquals(Set.of("{urn:roads}gid"), WfsTransaction.forbiddenAttributes(tx("<w:Insert><a:roads><a:gid>9</a:gid></a:roads></w:Insert>"), schema, policy));
    assertThrows(XMLStreamException.class, () -> WfsTransaction.forbiddenAttributes(
        tx("<w:Insert idgen='ReplaceDuplicate'><a:roads><a:name>protected</a:name></a:roads></w:Insert>"), schema, policy));
    assertThrows(XMLStreamException.class, () -> WfsTransaction.forbiddenAttributes(
        tx("<w:Insert><a:roads fid='roads.1'><a:name>protected</a:name></a:roads></w:Insert>"), schema, policy));
    assertTrue(WfsTransaction.forbiddenAttributes(tx(update("a:id") + update("a:shape") + "<w:Delete typeName='a:roads'/>"), schema, policy).isEmpty());
    assertEquals(Set.of("{urn:roads}name"), WfsTransaction.forbiddenAttributes(tx(update("a:name") + update("a:id")), schema, policy));
    assertEquals(Set.of("{urn:roads}id"), WfsTransaction.forbiddenAttributes(tx(update("id")), schema,
        new WfsAttributePolicy(1, schema.featureType(), Set.of())));
  }

  @Test
  void rejectsUnknownNamespacesPathsAndAmbiguousLocalNames() throws Exception {
    var schema = schema();
    var policy = new WfsAttributePolicy(1, schema.featureType(), Set.of("{urn:roads}name"));
    for (String name : List.of("x:name", "a:unknown", "a:name/child", "a:name[1]", "a:name/@value", "a:*"))
      assertThrows(XMLStreamException.class, () -> WfsTransaction.forbiddenAttributes(tx(update(name)), schema, policy));
    var ambiguous = new WfsAttributeSchema("{urn:roads}roads", List.of(
        new WfsAttributeSchema.Attribute("name", "urn:roads", "string", false, false),
        new WfsAttributeSchema.Attribute("name", "urn:other", "string", false, false)));
    assertThrows(XMLStreamException.class, () -> WfsTransaction.forbiddenAttributes(tx(update("name")), ambiguous, policy));
    assertTrue(WfsTransaction.forbiddenAttributes(tx(update("a:name")), ambiguous, policy).isEmpty());
    assertTrue(WfsTransaction.forbiddenAttributes(tx(update("a:id")).replace("http://www.opengis.net/wfs", "http://www.opengis.net/wfs/2.0")
        .replace("w:Name", "w:ValueReference"), schema, new WfsAttributePolicy(1, schema.featureType(), Set.of("{urn:roads}id"))).isEmpty());
  }

  @Test
  void keepsEmptyAndQualifiedNamespacesDistinctAndReportsGeneratedInsertsCanonically() throws Exception {
    var schema = new WfsAttributeSchema("{urn:roads}roads", List.of(
        new WfsAttributeSchema.Attribute("name", "", "string", false, false),
        new WfsAttributeSchema.Attribute("name", "urn:roads", "string", false, false)));
    var policy = new WfsAttributePolicy(1, schema.featureType(), Set.of("{}name"));
    assertThrows(XMLStreamException.class,
        () -> WfsTransaction.forbiddenAttributes(tx(update("name")), schema, policy));
    assertEquals(Set.of("{urn:roads}name"),
        WfsTransaction.forbiddenAttributes(tx(update("a:name")), schema, policy));
    assertEquals(Set.of("{}gid"), WfsTransaction.forbiddenAttributes(
        tx("<w:Insert><a:roads><gid>9</gid></a:roads></w:Insert>"),
        schema(false), new WfsAttributePolicy(1, schema.featureType(), Set.of())));
  }

  @Test
  void doesNotInferGenerationFromNamesOrFetchUnverifiableSchemas() throws Exception {
    var plain = parse("""
        <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:roads">
          <x:element name="roads"><x:complexType><x:sequence><x:element name="gid" type="x:int" minOccurs="0"/>
          </x:sequence></x:complexType></x:element>
        </x:schema>
        """);
    assertFalse(plain.attributes().get(0).generated());
    assertEquals("", plain.attributes().get(0).namespace());
    assertThrows(XMLStreamException.class, () -> parse("""
        <!DOCTYPE schema [<!ENTITY remote SYSTEM "file:///etc/passwd">]>
        <schema xmlns="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:roads">&remote;</schema>
        """));
    assertThrows(XMLStreamException.class, () -> parse("""
        <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:roads">
          <x:include schemaLocation="http://never-fetch.test/remote"/>
        </x:schema>
        """));
  }

  private static Task task(Cartography layer, String type, List<String> fields) {
    return Task.builder().type(TaskType.builder().id(0).build()).cartography(layer)
        .properties(Map.of("scope", "feat-edit", WfsAttributePolicy.KEY,
            Map.of("version", 1, "featureType", type, "editableAttributes", fields))).build();
  }

  private static String tx(String content) {
    return "<w:Transaction xmlns:w='http://www.opengis.net/wfs' xmlns:a='urn:roads'>" + content + "</w:Transaction>";
  }

  private static String update(String name) {
    return "<w:Update typeName='a:roads'><w:Property><w:Name>" + name + "</w:Name><w:Value>value</w:Value></w:Property></w:Update>";
  }
}

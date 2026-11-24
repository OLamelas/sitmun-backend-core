package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.cartography.CartographyRepository;
import org.sitmun.domain.cartography.parameter.CartographyServiceParameter;
import org.sitmun.domain.service.Service;
import org.sitmun.domain.service.ServiceRepository;
import org.sitmun.domain.service.parameter.ServiceParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real persistence and HTTP endpoint; only the remote WFS response bytes are controlled. */
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WfsAttributeSchemaPersistenceTest {

  @Autowired private MockMvc mvc;
  @Autowired private CartographyRepository cartographies;
  @Autowired private ServiceRepository services;
  @Autowired private WfsEditingPolicy policy;
  @Autowired private EntityManager entityManager;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoSpyBean private WfsMetadataResolver metadata;

  private Integer cartographyId;
  private Integer serviceId;
  private boolean withParameters;

  @BeforeEach
  void stubRemoteMetadata() throws Exception {
    doAnswer(
            call -> {
              // Neither a test transaction nor OpenEntityManagerInView may hide the regression.
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.hasResource(entityManagerFactory));
              URI uri = call.getArgument(0);
              assertEquals("configured.test", uri.getHost());
              assertEquals("server-user", call.getArgument(1));
              assertEquals("test-password", call.getArgument(2));
              String query = uri.getRawQuery();
              if (withParameters) {
                assertTrue(query.contains("serviceToken=service-token"));
                assertTrue(query.contains("sharedValue=layer-value"));
                assertTrue(query.contains("layerRoute=layer-route"));
                assertFalse(query.contains("sharedValue=service-value"));
              } else {
                assertFalse(query.contains("serviceToken="));
                assertFalse(query.contains("sharedValue="));
                assertFalse(query.contains("layerRoute="));
              }
              return (query.contains("REQUEST=GetCapabilities")
                      ? """
                      <w:WFS_Capabilities xmlns:w="http://www.opengis.net/wfs" xmlns:a="urn:discovery">
                        <w:FeatureTypeList><w:FeatureType><w:Name>a:roads</w:Name></w:FeatureType></w:FeatureTypeList>
                      </w:WFS_Capabilities>
                      """
                      : """
                      <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema"
                          targetNamespace="urn:discovery" elementFormDefault="qualified">
                        <x:element name="roads"><x:complexType><x:sequence>
                          <x:element name="name" type="x:string"/>
                        </x:sequence></x:complexType></x:element>
                      </x:schema>
                      """)
                  .getBytes(StandardCharsets.UTF_8);
            })
        .when(metadata)
        .fetch(any(), any(), any());
  }

  @AfterEach
  void removeFixture() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              if (cartographyId != null) {
                cartographies.deleteById(cartographyId);
                entityManager.flush();
              }
              if (serviceId != null) {
                services.deleteById(serviceId);
              }
            });
  }

  @Test
  void ordinaryLookupReproducesTheDetachedParameterFailure() throws Exception {
    createLayer(true);
    var detached = cartographies.findById(cartographyId).orElseThrow();
    assertFalse(Hibernate.isInitialized(detached.getService().getParameters()));
    assertFalse(Hibernate.isInitialized(detached.getServiceParameters()));

    var failure = assertThrows(WfsDiscoveryException.class, () -> policy.describe(detached));

    assertEquals(WfsDiscoveryException.Stage.CONFIGURATION, failure.stage());
    assertEquals(WfsDiscoveryException.Category.LAZY_INITIALIZATION, failure.category());
    verify(metadata, never()).fetch(any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @WithMockUser(roles = "ADMIN")
  void endpointDiscoversPersistedConfigurationWithoutAnOpenSession(boolean parameters)
      throws Exception {
    createLayer(parameters);

    mvc.perform(get("/api/cartographies/{id}/wfs-attribute-schema", cartographyId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.featureType").value("{urn:discovery}roads"))
        .andExpect(jsonPath("$.attributes[0].name").value("name"))
        .andExpect(jsonPath("$.attributes[0].namespace").value("urn:discovery"));

    verify(metadata, times(2)).fetch(any(), any(), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void discoveryLookupIsNotExposedAsARepositorySearch() throws Exception {
    mvc.perform(get("/api/cartographies/search/findForWfsDiscoveryById").param("id", "1"))
        .andExpect(status().isNotFound());
    verify(metadata, never()).fetch(any(), any(), any());
  }

  private void createLayer(boolean parameters) {
    withParameters = parameters;
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var service =
                  services.save(
                      Service.builder()
                          .name("WFS discovery persistence fixture")
                          .type("WFS")
                          .serviceURL("http://configured.test/wfs")
                          .blocked(false)
                          .user("server-user")
                          .password("test-password")
                          .build());
              serviceId = service.getId();
              var layer =
                  cartographies.save(
                      Cartography.builder()
                          .name("WFS discovery persistence fixture")
                          .layers(List.of("a:roads"))
                          .queryableFeatureAvailable(false)
                          .queryableFeatureEnabled(false)
                          .blocked(false)
                          .service(service)
                          .build());
              cartographyId = layer.getId();
              if (parameters) {
                entityManager.persist(
                    ServiceParameter.builder()
                        .service(service)
                        .type("WFS")
                        .name("serviceToken")
                        .value("service-token")
                        .build());
                entityManager.persist(
                    ServiceParameter.builder()
                        .service(service)
                        .type("WFS")
                        .name("sharedValue")
                        .value("service-value")
                        .build());
                entityManager.persist(
                    CartographyServiceParameter.builder()
                        .cartography(layer)
                        .type("WFS")
                        .name("sharedValue")
                        .value("layer-value")
                        .build());
                entityManager.persist(
                    CartographyServiceParameter.builder()
                        .cartography(layer)
                        .type("WFS")
                        .name("layerRoute")
                        .value("layer-route")
                        .build());
              }
              entityManager.flush();
            });
  }
}

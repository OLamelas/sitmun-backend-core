package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitmun.administration.controller.WfsAttributeSchemaController;
import org.sitmun.domain.application.ApplicationRepository;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.cartography.CartographyRepository;
import org.sitmun.domain.service.Service;
import org.sitmun.domain.territory.TerritoryRepository;
import org.sitmun.domain.user.UserRepository;
import org.sitmun.infrastructure.variables.SystemVariableResolver;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Real controller, method security, policy and parser; only persistence and remote bytes controlled. */
@SpringJUnitWebConfig(WfsAttributeSchemaControllerTest.Config.class)
class WfsAttributeSchemaControllerTest {
  @Configuration
  @EnableWebSecurity
  @EnableMethodSecurity
  static class Config extends WebMvcConfigurationSupport {
    @Override
    protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
      return new RequestMappingHandlerMapping() {
        @Override
        protected boolean isHandler(Class<?> type) {
          return WfsAttributeSchemaController.class.isAssignableFrom(type) || super.isHandler(type);
        }
      };
    }

    @Override
    protected void configurePathMatch(PathMatchConfigurer paths) {
      paths.addPathPrefix("/api", WfsAttributeSchemaController.class::isAssignableFrom);
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
          .httpBasic(basic -> {}).build();
    }
    @Bean UserDetailsService userDetailsService() { return new InMemoryUserDetailsManager(); }
    @Bean CartographyRepository cartographies() { return mock(CartographyRepository.class); }
    @Bean WfsMetadataResolver metadata() { return spy(new WfsMetadataResolver()); }
    @Bean WfsEditingPolicy policy(WfsMetadataResolver metadata) {
      var variables = mock(SystemVariableResolver.class);
      when(variables.resolve(anyString(), any())).thenAnswer(call -> call.getArgument(0));
      return new WfsEditingPolicy(metadata, variables, mock(UserRepository.class),
          mock(ApplicationRepository.class), mock(TerritoryRepository.class));
    }
    @Bean WfsAttributeSchemaController controller(CartographyRepository cartographies, WfsEditingPolicy policy) {
      return new WfsAttributeSchemaController(cartographies, policy);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired CartographyRepository cartographies;
  @Autowired WfsMetadataResolver metadata;
  private MockMvc mvc;
  private Cartography layer;
  private static final String URL = "/api/cartographies/9404/wfs-attribute-schema";

  @BeforeEach
  void setup() throws Exception {
    reset(cartographies, metadata);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    layer = Cartography.builder().id(9404).blocked(false).layers(List.of("menorca:roads"))
        .service(Service.builder().id(5).type("WFS").blocked(false)
            .serviceURL("http://configured.test/wfs").user("server-user").password("secret-password").build())
        .build();
    when(cartographies.findForWfsDiscoveryById(9404)).thenReturn(Optional.of(layer));
    doAnswer(call -> {
      java.net.URI uri = call.getArgument(0);
      assertEquals("configured.test", uri.getHost());
      assertEquals("server-user", call.getArgument(1));
      return bytes(uri.getQuery().contains("GetCapabilities") ? """
          <w:WFS_Capabilities xmlns:w="http://www.opengis.net/wfs" xmlns:menorca="http://menorca">
            <w:FeatureTypeList><w:FeatureType><w:Name>menorca:roads</w:Name></w:FeatureType></w:FeatureTypeList>
          </w:WFS_Capabilities>
          """ : """
          <x:schema xmlns:x="http://www.w3.org/2001/XMLSchema" targetNamespace="http://menorca" elementFormDefault="qualified">
            <x:element name="roads"><x:complexType><x:sequence>
              <x:element name="name" type="x:string"/>
            </x:sequence></x:complexType></x:element>
          </x:schema>
          """);
    }).when(metadata).fetch(any(), any(), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void returnsVerifiedConfiguredSchemaWithoutWritingConfiguration() throws Exception {
    mvc.perform(get(URL)).andExpect(status().isOk())
        .andExpect(jsonPath("$.featureType").value("{http://menorca}roads"))
        .andExpect(jsonPath("$.attributes[0].name").value("name"))
        .andExpect(jsonPath("$.attributes[0].namespace").value("http://menorca"));
    verify(cartographies).findForWfsDiscoveryById(9404);
    verifyNoMoreInteractions(cartographies);
    verify(metadata, times(2)).fetch(any(), eq("server-user"), eq("secret-password"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void preservesMissingAndUnverifiableResponses() throws Exception {
    when(cartographies.findForWfsDiscoveryById(9404)).thenReturn(Optional.empty());
    mvc.perform(get(URL)).andExpect(status().isNotFound());
    verifyNoInteractions(metadata);
    when(cartographies.findForWfsDiscoveryById(9404)).thenReturn(Optional.of(layer));
    layer.setLayers(List.of("menorca:roads", "menorca:shops"));
    mvc.perform(get(URL)).andExpect(status().isUnprocessableEntity());
    verify(metadata, never()).fetch(any(), any(), any());
    layer.setLayers(List.of("menorca:roads"));
    doReturn(bytes("<invalid/>")).when(metadata).fetch(any(), any(), any());
    mvc.perform(get(URL)).andExpect(status().isUnprocessableEntity());
  }

  @Test
  @WithMockUser(roles = "USER")
  void nonAdministratorCannotDiscoverOrReadConfiguration() throws Exception {
    mvc.perform(get(URL)).andExpect(status().isForbidden());
    verifyNoInteractions(cartographies, metadata);
  }

  @Test
  void anonymousCannotDiscover() throws Exception {
    mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    verifyNoInteractions(cartographies, metadata);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void repositoryFailureKeepsThePublicErrorGeneric() throws Exception {
    when(cartographies.findForWfsDiscoveryById(9404))
        .thenThrow(new DataAccessResourceFailureException("sensitive-database-secret"));
    var response = mvc.perform(get(URL)).andExpect(status().isUnprocessableEntity()).andReturn();
    assertFalse(response.getResponse().getContentAsString().contains("sensitive-database-secret"));
    verifyNoInteractions(metadata);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void logsOnlySafeStageAndCategoryAndKeepsThePublicErrorGeneric() throws Exception {
    doThrow(new UnknownHostException("http://user:secret-password@sensitive.test/?token=secret"))
        .when(metadata).fetch(any(), any(), any());
    var logger = (Logger) LoggerFactory.getLogger(WfsAttributeSchemaController.class);
    var captured = new ListAppender<ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      var response = mvc.perform(get(URL)).andExpect(status().isUnprocessableEntity()).andReturn();
      assertFalse(response.getResponse().getContentAsString().contains("secret"));
      assertTrue(captured.list.stream().anyMatch(event ->
          event.getFormattedMessage().contains("cartography=9404 stage=CAPABILITIES_REQUEST category=DNS")));
      for (var event : captured.list) {
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("http"));
        assertFalse(event.getFormattedMessage().contains("secret"));
      }
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  private static byte[] bytes(String xml) {
    return xml.getBytes(StandardCharsets.UTF_8);
  }
}

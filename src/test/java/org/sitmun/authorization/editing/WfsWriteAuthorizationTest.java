package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitmun.authorization.client.service.AuthorizationService;
import org.sitmun.authorization.proxy.dto.ConfigProxyRequestDto;
import org.sitmun.authorization.proxy.validator.ServiceResourceAccessValidator;
import org.sitmun.domain.application.Application;
import org.sitmun.domain.application.ApplicationRepository;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.cartography.CartographyRepository;
import org.sitmun.domain.cartography.parameter.CartographyServiceParameterRepository;
import org.sitmun.domain.role.Role;
import org.sitmun.domain.role.RoleRepository;
import org.sitmun.domain.service.Service;
import org.sitmun.domain.service.ServiceRepository;
import org.sitmun.domain.task.Task;
import org.sitmun.domain.task.type.TaskType;
import org.sitmun.domain.territory.Territory;
import org.sitmun.domain.territory.TerritoryRepository;
import org.sitmun.domain.user.User;
import org.sitmun.domain.user.UserRepository;
import org.sitmun.infrastructure.variables.SystemVariableResolver;
import org.springframework.security.access.AccessDeniedException;

class WfsWriteAuthorizationTest {
  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final WfsMetadataResolver metadata = mock(WfsMetadataResolver.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ApplicationRepository applications = mock(ApplicationRepository.class);
  private final TerritoryRepository territories = mock(TerritoryRepository.class);
  private final CartographyRepository cartographies = mock(CartographyRepository.class);
  private final ServiceRepository services = mock(ServiceRepository.class);
  private final RoleRepository roles = mock(RoleRepository.class);
  private ServiceResourceAccessValidator validator;
  private Task task;
  private Cartography layer;

  @BeforeEach
  void setup() throws Exception {
    var service =
        Service.builder()
            .id(5)
            .type("WFS")
            .serviceURL("http://configured.test/wfs")
            .blocked(false)
            .build();
    layer =
        Cartography.builder()
            .id(8)
            .service(service)
            .layers(List.of("app:roads"))
            .blocked(false)
            .build();
    task = Task.builder().id(7).type(TaskType.builder().id(0).build()).cartography(layer).build();
    var variables = mock(SystemVariableResolver.class);
    when(variables.resolve(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    var policy = new WfsEditingPolicy(metadata, variables, users, applications, territories);
    validator =
        new ServiceResourceAccessValidator(
            services,
            users,
            applications,
            territories,
            cartographies,
            mock(CartographyServiceParameterRepository.class),
            roles,
            authorization,
            policy);
    when(services.findById(5)).thenReturn(Optional.of(service));
    when(users.findByUsername("alice"))
        .thenReturn(Optional.of(User.builder().username("alice").build()));
    when(applications.existsById(1)).thenReturn(true);
    when(territories.existsById(2)).thenReturn(true);
    when(applications.findById(1)).thenReturn(Optional.of(Application.builder().id(1).build()));
    when(territories.findById(2)).thenReturn(Optional.of(Territory.builder().id(2).build()));
    when(roles.findRolesByApplicationAndUserAndTerritory("alice", 1, 2))
        .thenReturn(List.of(Role.builder().id(9).build()));
    when(cartographies.findByRolesAndTerritory(anyList(), eq(2))).thenReturn(List.of(layer));
    when(authorization.findTasksByUserApplicationAndTerritory("alice", 1, 2))
        .thenReturn(List.of(task));
    when(metadata.resolve(anyString(), anyMap(), any(), any(), anyList()))
        .thenReturn(Set.of(new QName("urn:roads", "roads")));
  }

  @Test
  void authorizedWriteHasVerifiedNamespaceAndRevocationIsRecalculated() {
    assertTrue(
        validator.validate(
            request(
                "<w:Insert><a:roads/><b:roads/></w:Insert><w:Update typeName='a:roads'/><w:Delete"
                    + " typeName='b:roads'/>"),
            "alice"));
    when(authorization.findTasksByUserApplicationAndTerritory("alice", 1, 2)).thenReturn(List.of());
    clearInvocations(metadata);
    assertFalse(validator.validate(request("<w:Delete typeName='a:roads'/>"), "alice"));
    verifyNoInteractions(metadata);
  }

  @Test
  void contextDenialAndWrongScopeNeverFetchMetadata() {
    when(authorization.findTasksByUserApplicationAndTerritory("alice", 1, 2))
        .thenThrow(new AccessDeniedException("context"));
    assertFalse(validator.validate(request("<w:Delete typeName='a:roads'/>"), "alice"));
    doReturn(List.of(task))
        .when(authorization)
        .findTasksByUserApplicationAndTerritory("alice", 1, 2);
    task.setProperties(Map.of("scope", "db-edit"));
    assertFalse(validator.validate(request("<w:Delete typeName='a:roads'/>"), "alice"));
    verifyNoInteractions(metadata);
  }

  @Test
  void deniesEntireMixedTransactionAndBlockedOrUnreadableCartography() {
    assertFalse(validator.validate(request("<w:Insert><a:roads/><x:secret/></w:Insert>"), "alice"));
    layer.setBlocked(true);
    clearInvocations(metadata);
    assertFalse(validator.validate(request("<w:Delete typeName='a:roads'/>"), "alice"));
    verifyNoInteractions(metadata);
    layer.setBlocked(false);
    when(cartographies.findByRolesAndTerritory(anyList(), eq(2))).thenReturn(List.of());
    assertFalse(validator.validate(request("<w:Delete typeName='a:roads'/>"), "alice"));
  }

  @Test
  void rejectsServicePathWrongRouteAndMalformedXmlWithoutMetadata() {
    var request = request("<w:Delete typeName='a:roads'/>");
    request.setCartographyId(null);
    assertFalse(validator.validate(request, "alice"));
    request.setCartographyId(8);
    request.setType("WMS");
    assertFalse(validator.validate(request, "alice"));
    request.setType("WFS");
    request.setRequestBody("<not-xml");
    assertFalse(validator.validate(request, "alice"));
    verifyNoInteractions(metadata);
  }

  private ConfigProxyRequestDto request(String operations) {
    return ConfigProxyRequestDto.builder()
        .appId(1)
        .terId(2)
        .type("WFS")
        .typeId(5)
        .cartographyId(8)
        .method("POST")
        .parameters(Map.of())
        .requestBody(
            "<w:Transaction xmlns:w='http://www.opengis.net/wfs' xmlns:a='urn:roads'"
                + " xmlns:b='urn:roads' xmlns:x='urn:secret'>"
                + operations
                + "</w:Transaction>")
        .build();
  }
}

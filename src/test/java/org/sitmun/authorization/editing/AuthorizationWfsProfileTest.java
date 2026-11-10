package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sitmun.authorization.access.UserApplicationAccessPolicy;
import org.sitmun.authorization.client.service.AuthorizationService;
import org.sitmun.authorization.client.service.ProfileContext;
import org.sitmun.authorization.client.mapper.ProfileMapper;
import org.sitmun.authorization.proxy.dto.ConfigProxyRequestDto;
import org.sitmun.authorization.proxy.validator.ServiceResourceAccessValidator;
import org.mapstruct.factory.Mappers;
import org.sitmun.domain.application.Application;
import org.sitmun.domain.application.ApplicationRepository;
import org.sitmun.domain.application.tree.ApplicationTreeRepository;
import org.sitmun.domain.background.BackgroundRepository;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.cartography.CartographyRepository;
import org.sitmun.domain.cartography.parameter.CartographyServiceParameterRepository;
import org.sitmun.domain.cartography.permission.CartographyPermissionRepository;
import org.sitmun.domain.configuration.ConfigurationParameterRepository;
import org.sitmun.domain.role.Role;
import org.sitmun.domain.role.RoleRepository;
import org.sitmun.domain.service.Service;
import org.sitmun.domain.service.ServiceRepository;
import org.sitmun.domain.service.usage.ViewerUsage;
import org.sitmun.domain.task.Task;
import org.sitmun.domain.task.TaskRepository;
import org.sitmun.domain.task.type.TaskType;
import org.sitmun.domain.territory.Territory;
import org.sitmun.domain.territory.TerritoryRepository;
import org.sitmun.domain.tree.node.TreeNodeRepository;
import org.sitmun.domain.user.User;
import org.sitmun.domain.user.UserRepository;
import org.sitmun.infrastructure.persistence.type.i18n.TranslationService;

@ExtendWith(MockitoExtension.class)
class AuthorizationWfsProfileTest {
  @Mock ApplicationRepository applications;
  @Mock TerritoryRepository territories;
  @Mock RoleRepository roles;
  @Mock ConfigurationParameterRepository configuration;
  @Mock CartographyPermissionRepository groups;
  @Mock CartographyRepository cartographies;
  @Mock TaskRepository tasks;
  @Mock BackgroundRepository backgrounds;
  @Mock ApplicationTreeRepository trees;
  @Mock TreeNodeRepository nodes;
  @Mock TranslationService translations;
  @Mock UserApplicationAccessPolicy access;
  @Mock ViewerUsage usage;
  @Mock WfsEditingPolicy editing;
  @Spy @InjectMocks AuthorizationService authorization;

  @Test
  void unverifiableEditionIsOmittedWithoutBreakingOtherTasksOrReadableLayers() {
    var service = Service.builder().id(5).type("WFS").blocked(false).build();
    var first = Cartography.builder().id(8).service(service).blocked(false).build();
    var queryService = Service.builder().id(6).type("WFS").blocked(false).build();
    var second = Cartography.builder().id(9).service(queryService).blocked(false).build();
    // No node, background or situation-map may mask the candidate-only reference.
    var app = Application.builder().id(1).build();
    var role = Role.builder().id(4).build();
    var type = TaskType.builder().id(0).build();
    var valid = Task.builder().id(7).type(type).cartography(first).build();
    var taskOnlyService = Service.builder().id(7).type("WFS").blocked(false).build();
    var unavailable = Task.builder().id(10).type(type).cartography(second).service(taskOnlyService).build();
    doReturn(Optional.of(app))
        .when(authorization)
        .findApplicationByUserApplicationAndTerritory("alice", 1, 2);
    when(territories.findById(2)).thenReturn(Optional.of(Territory.builder().id(2).build()));
    when(roles.findRolesByApplicationAndUserAndTerritory("alice", 1, 2)).thenReturn(List.of(role));
    when(cartographies.findByRolesAndTerritory(anyList(), eq(2)))
        .thenReturn(List.of(first, second));
    when(tasks.findByRolesAndTerritory(anyList(), eq(2))).thenReturn(List.of(valid, unavailable));
    when(editing.target(eq(first), anyCollection(), anyCollection(), eq("alice"), eq(1), eq(2)))
        .thenReturn(Optional.of(Set.of(new QName("urn:roads", "roads"))));
    when(editing.target(eq(second), anyCollection(), anyCollection(), eq("alice"), eq(1), eq(2)))
        .thenReturn(Optional.empty());
    var profile =
        authorization
            .createProfile(
                ProfileContext.builder()
                    .username("alice")
                    .appId(1)
                    .territoryId(2)
                    .nodeSectionBehaviour(
                        ProfileContext.NodeSectionBehaviour.VIRTUAL_ROOT_ALL_NODES)
                    .build())
            .orElseThrow();
    assertEquals(List.of(valid), profile.getTasks());
    assertEquals(
        Set.of(8, 9),
        profile.getLayers().stream()
            .map(Cartography::getId)
             .collect(java.util.stream.Collectors.toSet()));
    assertEquals(Set.of(service, queryService), Set.copyOf(profile.getServices()));
    assertTrue(profile.getTrees().isEmpty()); assertTrue(profile.getBackgrounds().isEmpty());
    verify(editing, times(1)).target(eq(second), anyCollection(), anyCollection(), eq("alice"), eq(1), eq(2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"blocked-layer", "blocked-service", "unreadable", "unreferenced"})
  void candidateRetentionDoesNotExpandQueryAccessOrKeepUnreferencedServices(String reason) {
    var service = Service.builder().id(5).type("WFS").blocked(reason.equals("blocked-service")).build();
    var layer = Cartography.builder().id(8).service(service).blocked(reason.equals("blocked-layer")).build();
    var candidate = Task.builder().id(7).type(TaskType.builder().id(0).build()).cartography(layer).build();
    doReturn(Optional.of(Application.builder().id(1).build()))
        .when(authorization).findApplicationByUserApplicationAndTerritory("alice", 1, 2);
    when(territories.findById(2)).thenReturn(Optional.of(Territory.builder().id(2).build()));
    when(roles.findRolesByApplicationAndUserAndTerritory("alice", 1, 2)).thenReturn(List.of(Role.builder().id(4).build()));
    when(cartographies.findByRolesAndTerritory(anyList(), eq(2)))
        .thenReturn(reason.equals("unreadable") ? List.of() : List.of(layer));
    when(tasks.findByRolesAndTerritory(anyList(), eq(2)))
        .thenReturn(reason.equals("unreferenced") ? List.of() : List.of(candidate));
    var profile = authorization.createProfile(ProfileContext.builder().username("alice").appId(1).territoryId(2)
        .nodeSectionBehaviour(ProfileContext.NodeSectionBehaviour.VIRTUAL_ROOT_ALL_NODES).build()).orElseThrow();
    assertTrue(profile.getLayers().isEmpty());
    assertTrue(profile.getServices().isEmpty());
    assertTrue(profile.getTasks().isEmpty());
  }

  @Test
  void candidateOnlyLayerSurvivesTheFinalDtoButTheSamePolicyStillDeniesTransactions() {
    var service = Service.builder().id(5).type("WFS").blocked(false).build();
    var layer = Cartography.builder().id(8).layers(List.of("app:roads")).service(service).blocked(false).build();
    var candidate = Task.builder().id(7).type(TaskType.builder().id(0).build()).cartography(layer).build();
    doReturn(Optional.of(Application.builder().id(1).build()))
        .when(authorization).findApplicationByUserApplicationAndTerritory("alice", 1, 2);
    when(territories.findById(2)).thenReturn(Optional.of(Territory.builder().id(2).build()));
    when(roles.findRolesByApplicationAndUserAndTerritory("alice", 1, 2)).thenReturn(List.of(Role.builder().id(4).build()));
    when(cartographies.findByRolesAndTerritory(anyList(), eq(2))).thenReturn(List.of(layer));
    when(tasks.findByRolesAndTerritory(anyList(), eq(2))).thenReturn(List.of(candidate));
    // Mockito's empty Optional represents the same unverifiable target in both consumers.
    var profile = authorization.createProfile(ProfileContext.builder().username("alice").appId(1).territoryId(2)
        .nodeSectionBehaviour(ProfileContext.NodeSectionBehaviour.VIRTUAL_ROOT_ALL_NODES).build()).orElseThrow();
    var dto = Mappers.getMapper(ProfileMapper.class).map(profile, profile.getApplication(), profile.getTerritory());
    assertEquals(List.of("layer/8"), dto.getLayers().stream().map(value -> value.getId()).toList());
    assertEquals(List.of("service/5"), dto.getServices().stream().map(value -> value.getId()).toList());
    assertTrue(dto.getTasks().isEmpty());

    var services = mock(ServiceRepository.class);
    var users = mock(UserRepository.class);
    when(services.findById(5)).thenReturn(Optional.of(service));
    when(users.findByUsername("alice")).thenReturn(Optional.of(User.builder().username("alice").build()));
    when(applications.existsById(1)).thenReturn(true);
    when(territories.existsById(2)).thenReturn(true);
    doReturn(List.of(candidate)).when(authorization).findTasksByUserApplicationAndTerritory("alice", 1, 2);
    var validator = new ServiceResourceAccessValidator(services, users, applications, territories, cartographies,
        mock(CartographyServiceParameterRepository.class), roles, authorization, editing);
    var request = ConfigProxyRequestDto.builder().appId(1).terId(2).type("WFS").typeId(5).cartographyId(8)
        .method("POST").parameters(java.util.Map.of())
        .requestBody("<w:Transaction xmlns:w='http://www.opengis.net/wfs' xmlns:a='urn:roads'><w:Delete typeName='a:roads'/></w:Transaction>")
        .build();
    assertFalse(validator.validate(request, "alice"));
  }
}

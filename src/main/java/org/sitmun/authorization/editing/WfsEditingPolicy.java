package org.sitmun.authorization.editing;

import static org.sitmun.domain.DomainConstants.Tasks.isCartographyEditionTask;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.xml.namespace.QName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sitmun.authorization.proxy.ogc.OgcRequestNames.Operation;
import org.sitmun.authorization.proxy.parameter.NamedParameter;
import org.sitmun.authorization.proxy.parameter.ParameterScopes;
import org.sitmun.authorization.proxy.service.RequestCoordinates;
import org.sitmun.domain.application.ApplicationRepository;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.cartography.CartographyBlockPolicy;
import org.sitmun.domain.service.ServiceBlockPolicy;
import org.sitmun.domain.task.Task;
import org.sitmun.domain.territory.TerritoryRepository;
import org.sitmun.domain.user.UserRepository;
import org.sitmun.infrastructure.variables.SystemVariableResolver;
import org.springframework.stereotype.Component;

/**
 * Shared policy for already-authorized context snapshots. Does not build profiles or resolve roles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WfsEditingPolicy {
  private final WfsMetadataResolver metadata;
  private final SystemVariableResolver variables;
  private final UserRepository users;
  private final ApplicationRepository applications;
  private final TerritoryRepository territories;

  private record Destination(String endpoint, Map<String, String> fixed) {}

  /** Admin discovery has no browser-controlled destination, names or schema URLs. */
  public WfsAttributeSchema describe(Cartography layer) throws Exception {
    try {
      var destination = destination(layer, null);
      return metadata.describe(destination.endpoint(), destination.fixed(), layer.getService().getUser(),
          layer.getService().getPassword(), layer.getLayers());
    } catch (Exception failure) {
      throw WfsDiscoveryException.at(WfsDiscoveryException.Stage.CONFIGURATION, failure);
    }
  }

  public Optional<WfsAttributeSchema> schema(
      Cartography layer, Collection<Task> tasks, Collection<Cartography> readable,
      String username, Integer appId, Integer territoryId) {
    if (!eligible(layer, tasks, readable)) return Optional.empty();
    try {
      var destination = destination(layer, coordinates(username, appId, territoryId));
      return Optional.of(metadata.describe(destination.endpoint(), destination.fixed(),
          layer.getService().getUser(), layer.getService().getPassword(), layer.getLayers()));
    } catch (Exception ex) {
      log.warn("WFS attribute schema unavailable: cartography={}", layer.getId());
      return Optional.empty();
    }
  }

  private RequestCoordinates coordinates(String username, Integer appId, Integer territoryId) {
    RequestCoordinates coordinates = new RequestCoordinates();
    coordinates.setUser(users.findByUsername(username).orElseThrow());
    coordinates.setApplication(applications.findById(appId).orElseThrow());
    coordinates.setTerritory(territories.findById(territoryId).orElseThrow());
    return coordinates;
  }

  public Optional<Set<QName>> target(
      Cartography layer,
      Collection<Task> tasks,
      Collection<Cartography> readable,
      String username,
      Integer appId,
      Integer territoryId) {
    if (!eligible(layer, tasks, readable)) return Optional.empty();
    var service = layer.getService();
    try {
      var destination = destination(layer, coordinates(username, appId, territoryId));
      return Optional.of(metadata.resolve(destination.endpoint(), destination.fixed(),
          service.getUser(), service.getPassword(), layer.getLayers()));
    } catch (Exception ex) {
      // Never log URLs, bodies, credentials, or remote exception messages.
      log.warn("WFS edition unavailable: cartography={} service={}", layer.getId(), service.getId());
      return Optional.empty();
    }
  }

  private Destination destination(Cartography layer, RequestCoordinates coordinates) {
      var service = layer.getService();
      if (!ServiceBlockPolicy.isAccessibleInClientProfile(service) || !"WFS".equalsIgnoreCase(service.getType()))
        throw new IllegalArgumentException("Not an available WFS service");
      List<NamedParameter> serviceRows =
          service.getParameters() == null
              ? List.of()
              : service.getParameters().stream()
                  .map(
                      p ->
                          new NamedParameter(
                              p.getType(),
                              p.getName(),
                              variables.resolve(p.getValue(), coordinates)))
                  .toList();
      List<NamedParameter> layerRows =
          layer.getServiceParameters() == null
              ? List.of()
              : layer.getServiceParameters().stream()
                  .map(
                      p ->
                          new NamedParameter(
                              p.getType(),
                              p.getName(),
                              variables.resolve(p.getValue(), coordinates)))
                  .toList();
      var fixed =
          ParameterScopes.fixedParameters(
              service.getType(), Operation.TRANSACTION, serviceRows, layerRows);
      if (fixed.entrySet().stream()
          .anyMatch(
              entry ->
                  "REQUEST".equalsIgnoreCase(entry.getKey())
                          && !"Transaction".equalsIgnoreCase(entry.getValue())
                      || "SERVICE".equalsIgnoreCase(entry.getKey())
                          && !"WFS".equalsIgnoreCase(entry.getValue()))) {
        throw new IllegalArgumentException("Conflicting operation parameter");
      }
      String endpoint = variables.resolve(service.getServiceURL(), coordinates);
      // Client-expanded endpoint templates cannot establish a server-owned write destination.
      if (endpoint == null || endpoint.contains("{") || endpoint.contains("}")) {
        throw new IllegalArgumentException("Client dependent endpoint");
      }
      if (fixed.values().stream().anyMatch(value -> value == null || value.contains("{") || value.contains("}")))
        throw new IllegalArgumentException("Unresolved fixed parameter");
      return new Destination(endpoint, fixed);
  }

  static boolean eligible(
      Cartography layer, Collection<Task> tasks, Collection<Cartography> readable) {
    return CartographyBlockPolicy.isNotDirectlyBlocked(layer)
        && layer.getId() != null
        && ServiceBlockPolicy.isAccessibleInClientProfile(layer.getService())
        && "WFS".equalsIgnoreCase(layer.getService().getType())
        && readable.stream()
            .anyMatch(
                c ->
                    Objects.equals(c.getId(), layer.getId())
                        && CartographyBlockPolicy.isNotDirectlyBlocked(c)
                        && c.getService() != null
                        && Objects.equals(c.getService().getId(), layer.getService().getId()))
        && tasks.stream()
            .anyMatch(
                task ->
                    isCartographyEditionTask(task)
                        && Objects.equals(task.getCartography().getId(), layer.getId())
                        && CartographyBlockPolicy.isNotDirectlyBlocked(task.getCartography())
                        && ServiceBlockPolicy.isAccessibleInClientProfile(
                            task.getCartography().getService())
                        && Objects.equals(
                            task.getCartography().getService().getId(),
                            layer.getService().getId()));
  }
}

package org.sitmun.administration.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sitmun.authorization.editing.WfsAttributeSchema;
import org.sitmun.authorization.editing.WfsDiscoveryException;
import org.sitmun.authorization.editing.WfsEditingPolicy;
import org.sitmun.domain.cartography.CartographyRepository;
import org.springframework.data.rest.webmvc.BasePathAwareController;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@BasePathAwareController
@RequiredArgsConstructor
@Slf4j
public class WfsAttributeSchemaController {
  private final CartographyRepository cartographies;
  private final WfsEditingPolicy policy;

  @GetMapping("/cartographies/{id}/wfs-attribute-schema")
  @ResponseBody
  @PreAuthorize("hasRole('ADMIN')")
  public WfsAttributeSchema describe(@PathVariable Integer id) {
    try {
      var layer = cartographies.findForWfsDiscoveryById(id)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      return policy.describe(layer);
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      var failure = WfsDiscoveryException.at(WfsDiscoveryException.Stage.CONFIGURATION, ex);
      log.warn("WFS attribute discovery failed: cartography={} stage={} category={}",
          id, failure.stage(), failure.category());
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "The configured layer does not provide a verifiable single WFS feature schema");
    }
  }
}

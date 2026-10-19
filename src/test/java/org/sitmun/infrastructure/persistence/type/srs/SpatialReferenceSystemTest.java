package org.sitmun.infrastructure.persistence.type.srs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sitmun.infrastructure.persistence.type.codelist.CodeListValidator;
import org.sitmun.infrastructure.persistence.type.codelist.CodeListValueRepository;
import org.sitmun.test.BaseTest;
import org.sitmun.test.URIConstants;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("SpatialReferenceSystem validation test")
@Import(CodeListValidator.class)
class SpatialReferenceSystemTest extends BaseTest {

  @MockitoBean private CodeListValueRepository repository;

  @BeforeEach
  void mocks() {
    when(repository.existsByCodeListNameAndValue(any(String.class), any(String.class)))
        .thenReturn(true);
  }

  @Test
  @DisplayName("Single projection pass")
  @WithMockUser(roles = "ADMIN")
  void singleProjectionPass() throws Exception {
    mvc.perform(
            post(URIConstants.SERVICES_URI)
                .contentType(APPLICATION_JSON)
                .content(serviceFixture("EPSG:1")))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  @ParameterizedTest
  @ValueSource(strings = {"other", "urn:ogc:def:crs:OTHER::local-grid"})
  @DisplayName("Single other value fail")
  @WithMockUser(roles = "ADMIN")
  void singleOtherValueFail(String identifier) throws Exception {
    mvc.perform(
            post(URIConstants.SERVICES_URI)
                .contentType(APPLICATION_JSON)
                .content(serviceFixture(identifier)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("supportedSRS"))
        .andExpect(jsonPath("$.errors[0].rejectedValue[0]").value(identifier));
  }

  @Test
  @DisplayName("Multiple projections pass")
  @WithMockUser(roles = "ADMIN")
  void multipleProjectionPass() throws Exception {
    mvc.perform(
            post(URIConstants.SERVICES_URI)
                .contentType(APPLICATION_JSON)
                .content(serviceFixture("EPSG:1", "EPSG:2")))
        .andExpect(status().isCreated());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "urn:ogc:def:crs:EPSG::25831",
        "urn:x-ogc:def:crs:EPSG:6.6:25831",
        "http://www.opengis.net/def/crs/EPSG/0/25831"
      })
  @DisplayName("WFS OGC CRS is normalized before validation and saving")
  @WithMockUser(roles = "ADMIN")
  void ogcUrnIsNormalized(String identifier) throws Exception {
    mvc.perform(
            post(URIConstants.SERVICES_URI)
                .contentType(APPLICATION_JSON)
                .content(new JSONObject(serviceFixture(identifier)).put("type", "WFS").toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.supportedSRS[0]").value("EPSG:25831"));
  }

  @Test
  @DisplayName("Blank projections remain invalid")
  @WithMockUser(roles = "ADMIN")
  void blankProjectionIsRejected() throws Exception {
    mvc.perform(
            post(URIConstants.SERVICES_URI)
                .contentType(APPLICATION_JSON)
                .content(serviceFixture("EPSG:1", "", "EPSG:1")))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Multiple projections with other value fail")
  @WithMockUser(roles = "ADMIN")
  void multipleProjectionsWithOtherValueFail() throws Exception {
    mvc.perform(
            post(URIConstants.SERVICES_URI)
                .contentType(APPLICATION_JSON)
                .content(serviceFixture("EPSG:1", "other")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("supportedSRS"))
        .andExpect(jsonPath("$.errors[0].rejectedValue[0]").value("EPSG:1"))
        .andExpect(jsonPath("$.errors[0].rejectedValue[1]").value("other"));
  }

  String serviceFixture(String... projection) throws JSONException {
    return new JSONObject()
        .put("supportedSRS", new JSONArray(projection))
        .put("type", "WMS")
        .put("blocked", "false")
        .put("name", "any name")
        .put("serviceURL", "http://example.com/")
        .toString();
  }
}

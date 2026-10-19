package org.sitmun.administration.service.extractor.capabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CapabilitiesUrlBuilder")
class CapabilitiesUrlBuilderTest {

  @Test
  @DisplayName("Adds request and service for a bare WMS URL")
  void addsGetCapabilitiesForBareWmsUrl() {
    String result = CapabilitiesUrlBuilder.build("https://example.com/wms", "WMS");

    assertThat(result).contains("request=GetCapabilities");
    assertThat(result).contains("service=WMS");
  }

  @Test
  @DisplayName("Adds request and service for a bare WFS URL")
  void addsGetCapabilitiesForBareWfsUrl() {
    String result =
        CapabilitiesUrlBuilder.build("http://geoserver.local:8080/geoserver/menorca/wfs", "WFS");

    assertThat(result).contains("request=GetCapabilities");
    assertThat(result).contains("service=WFS");
    assertThat(result).doesNotContain("service=WMS");
  }

  @Test
  @DisplayName("Keeps the configured WFS version and other query parameters")
  void keepsWfsVersionAndQueryParameters() {
    String result =
        CapabilitiesUrlBuilder.build("https://example.com/wfs?version=2.0.0&language=ca", "WFS");

    assertThat(result).contains("version=2.0.0", "language=ca");
    assertThat(result).contains("request=GetCapabilities", "service=WFS");
    assertThat(result.split("\\?", -1)).hasSize(2);
  }

  @Test
  @DisplayName("Leaves a complete WFS GetCapabilities URL unchanged")
  void leavesExistingWfsGetCapabilitiesUnchanged() {
    String endpoint = "https://example.com/wfs?service=WFS&version=2.0.0&request=GetCapabilities";

    assertThat(CapabilitiesUrlBuilder.build(endpoint, "WFS")).isEqualTo(endpoint);
  }

  @Test
  @DisplayName("Keeps MapServer map= and does not add a second ?")
  void keepsMapServerQueryAndSingleQuestionMark() {
    String endpoint =
        "https://pcivil.icgc.cat/ogc/geoservei?map=/opt/idec/dades/pcivil/risc_quimic.map";

    String result = CapabilitiesUrlBuilder.build(endpoint, "WMS");

    assertThat(result.split("\\?", -1)).hasSize(2);
    assertThat(result).contains("map=/opt/idec/dades/pcivil/risc_quimic.map");
    assertThat(result).contains("request=GetCapabilities");
    assertThat(result).contains("service=WMS");
  }

  @Test
  @DisplayName("Leaves a URL that already has request=GetCapabilities unchanged")
  void leavesExistingGetCapabilitiesUnchanged() {
    String endpoint = "https://example.com/wms?request=GetCapabilities&service=WMS";

    assertThat(CapabilitiesUrlBuilder.build(endpoint, "WMS")).isEqualTo(endpoint);
  }

  @Test
  @DisplayName("Rejects unsupported service types")
  void rejectsUnsupportedType() {
    assertThatThrownBy(() -> CapabilitiesUrlBuilder.build("https://example.com/wmts", "WMTS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported service type for capabilities: WMTS");
  }

  @Test
  @DisplayName("Rejects blank url or type")
  void rejectsBlankUrlOrType() {
    assertThatThrownBy(() -> CapabilitiesUrlBuilder.build("  ", "WMS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("url is required");
    assertThatThrownBy(() -> CapabilitiesUrlBuilder.build("https://example.com/wms", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("type is required");
  }
}

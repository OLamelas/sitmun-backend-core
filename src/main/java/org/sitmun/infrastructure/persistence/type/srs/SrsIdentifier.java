package org.sitmun.infrastructure.persistence.type.srs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts OGC EPSG identifiers to the EPSG:CODE form stored in SITMUN. */
public final class SrsIdentifier {

  private static final Pattern EPSG_URN =
      Pattern.compile("^urn:(?:x-)?ogc:def:crs:EPSG:(?:[^:]*:)?(\\d+)$", Pattern.CASE_INSENSITIVE);

  private static final Pattern EPSG_HTTP_URI =
      Pattern.compile(
          "^https?://www\\.opengis\\.net/def/crs/EPSG/[^/]*/(\\d+)$", Pattern.CASE_INSENSITIVE);

  private SrsIdentifier() {}

  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String identifier = value.trim();
    Matcher match = EPSG_URN.matcher(identifier);
    if (match.matches()) {
      return "EPSG:" + match.group(1);
    }
    match = EPSG_HTTP_URI.matcher(identifier);
    if (match.matches()) {
      return "EPSG:" + match.group(1);
    }
    return identifier;
  }
}

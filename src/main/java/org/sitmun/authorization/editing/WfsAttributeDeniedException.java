package org.sitmun.authorization.editing;

import java.util.Set;

/** Carries only verified schema identities, never submitted values or upstream URLs. */
public class WfsAttributeDeniedException extends RuntimeException {
  private final Set<String> attributes;

  public WfsAttributeDeniedException(Set<String> attributes) {
    super("WFS attribute writes are not authorized");
    this.attributes = Set.copyOf(attributes);
  }

  public Set<String> attributes() { return attributes; }
}

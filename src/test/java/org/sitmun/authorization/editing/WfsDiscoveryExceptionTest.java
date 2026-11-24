package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class WfsDiscoveryExceptionTest {

  @Test
  void distinguishesPersistenceFailuresWithoutRetainingMessagesOrCauses() {
    String secret = "https://user:secret@private.test/?token=secret";
    var lazy = new LazyInitializationException(secret);
    var data = new DataAccessResourceFailureException(secret, new IllegalStateException(secret));
    var stage = WfsDiscoveryException.Stage.CONFIGURATION;

    var lazyFailure = WfsDiscoveryException.at(stage, lazy);
    var dataFailure = WfsDiscoveryException.at(stage, data);

    assertEquals(WfsDiscoveryException.Category.LAZY_INITIALIZATION, lazyFailure.category());
    assertEquals(WfsDiscoveryException.Category.DATA_ACCESS, dataFailure.category());
    for (var failure : List.of(lazyFailure, dataFailure)) {
      assertEquals(stage, failure.stage());
      assertNull(failure.getCause());
      assertNull(failure.getNestedException());
      assertFalse(failure.toString().contains(secret));
      assertFalse(failure.toString().contains("secret"));
      assertSame(
          failure, WfsDiscoveryException.at(WfsDiscoveryException.Stage.SCHEMA_PARSE, failure));
    }
  }
}

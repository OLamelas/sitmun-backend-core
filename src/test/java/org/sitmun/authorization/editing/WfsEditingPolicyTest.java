package org.sitmun.authorization.editing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sitmun.domain.DomainConstants;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.service.Service;
import org.sitmun.domain.task.Task;
import org.sitmun.domain.task.type.TaskType;

class WfsEditingPolicyTest {
  @Test
  void legacyAndFeatEditOnlyAndReadAccessNeverComesFromTheTask() {
    var service = Service.builder().id(5).type("WFS").blocked(false).build();
    var layer = Cartography.builder().id(8).service(service).blocked(false).build();
    var type = mock(TaskType.class);
    when(type.getId()).thenReturn(0);
    var task = Task.builder().id(3).type(type).cartography(layer).build();
    assertTrue(DomainConstants.Tasks.isCartographyEditionTask(task));
    assertTrue(WfsEditingPolicy.eligible(layer, List.of(task), List.of(layer)));
    assertFalse(WfsEditingPolicy.eligible(layer, List.of(task), List.of()));
    for (String scope : List.of("db-edit", "unknown", "")) {
      task.setProperties(Map.of("scope", scope));
      assertFalse(DomainConstants.Tasks.isCartographyEditionTask(task));
      assertFalse(WfsEditingPolicy.eligible(layer, List.of(task), List.of(layer)));
    }
    task.setProperties(Map.of("scope", "feat-edit"));
    assertTrue(WfsEditingPolicy.eligible(layer, List.of(task), List.of(layer)));
    service.setBlocked(true);
    assertFalse(WfsEditingPolicy.eligible(layer, List.of(task), List.of(layer)));
  }
}

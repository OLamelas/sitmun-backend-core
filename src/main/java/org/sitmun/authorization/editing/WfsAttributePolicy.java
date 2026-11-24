package org.sitmun.authorization.editing;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.sitmun.domain.DomainConstants;
import org.sitmun.domain.cartography.Cartography;
import org.sitmun.domain.task.Task;

/** Separate from legacy mobile fields. Persisted identities are Clark QNames, never labels. */
public record WfsAttributePolicy(int version, String featureType, Set<String> editableAttributes) {
  public static final String KEY = "wfsAttributePolicy";

  public static WfsAttributePolicy read(Map<String, Object> properties) {
    if (properties == null || !(properties.get(KEY) instanceof Map<?, ?> raw)
        || !(raw.get("version") instanceof Number version) || version.doubleValue() != 1
        || !(raw.get("featureType") instanceof String featureType)
        || !(raw.get("editableAttributes") instanceof List<?> fields)
        || fields.size() > 4096 || !identity(featureType)
        || fields.stream().anyMatch(field -> !(field instanceof String name) || !identity(name))) return null;
    Set<String> editable = new LinkedHashSet<>();
    fields.forEach(field -> editable.add((String) field));
    return new WfsAttributePolicy(1, featureType, Set.copyOf(editable));
  }

  private static boolean identity(String name) {
    return name.length() <= 2048 && name.matches("\\{[^{}]*}[^{}:]+")
        && name.substring(name.indexOf('}') + 1).matches("[\\p{L}_][\\p{L}\\p{N}_.-]*");
  }

  public static WfsAttributePolicy effective(Cartography layer, Collection<Task> tasks, WfsAttributeSchema schema) {
    Set<String> editable = new LinkedHashSet<>();
    tasks.stream().filter(DomainConstants.Tasks::isCartographyEditionTask)
        .filter(task -> org.sitmun.domain.cartography.CartographyBlockPolicy.isNotDirectlyBlocked(task.getCartography())
            && org.sitmun.domain.service.ServiceBlockPolicy.isAccessibleInClientProfile(task.getCartography().getService()))
        .filter(task -> Objects.equals(task.getCartography().getId(), layer.getId()))
        .filter(task -> task.getCartography().getService() != null && layer.getService() != null
            && Objects.equals(task.getCartography().getService().getId(), layer.getService().getId()))
        .map(task -> read(task.getProperties())).filter(Objects::nonNull)
        .filter(policy -> schema.featureType().equals(policy.featureType()))
        .forEach(policy -> editable.addAll(policy.editableAttributes()));
    editable.retainAll(schema.attributes().stream().filter(a -> !a.geometry() && !a.generated())
        .map(WfsAttributeSchema.Attribute::expandedName).toList());
    return new WfsAttributePolicy(1, schema.featureType(), Set.copyOf(editable));
  }
}

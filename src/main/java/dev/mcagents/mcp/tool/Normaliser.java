package dev.mcagents.mcp.tool;

import dev.mcagents.mcp.catalog.ToolSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns what MCP received into what a bot receives.
 *
 * <p>The two are deliberately different. A bot gets no {@code bot} argument (it has one game
 * connection and no idea of names), nothing optional (every default is already filled), and
 * nothing out of range (every bound is already applied). That is what stops two kinds of bot from
 * disagreeing about what {@code range} defaults to — neither of them decides.
 */
final class Normaliser {

    private Normaliser() {}

    static Map<String, Object> normalise(ToolSpec spec, Map<String, Object> arguments) {
        Map<String, Object> wire = new LinkedHashMap<>();
        Map<String, Object> properties = propertiesOf(spec.wireSchema());

        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String name = property.getKey();
            Map<String, Object> rules = asMap(property.getValue());

            Object given = arguments == null ? null : arguments.get(name);
            Object value = given != null ? given : defaultFor(rules);

            if (value == null) {
                throw new IllegalArgumentException(
                        "\"%s\" needs \"%s\" and no default is defined for it".formatted(spec.name(), name));
            }
            wire.put(name, clamp(coerce(value, rules), rules));
        }
        return wire;
    }

    static int deadlineOf(ToolSpec spec, Map<String, Object> wire) {
        Object given = wire.get("timeoutMs");
        int asked = given instanceof Number number ? number.intValue() : spec.defaultDeadlineMs();
        return Math.clamp(asked, 1_000, 600_000);
    }

    private static Object defaultFor(Map<String, Object> rules) {
        Object explicit = rules.get("default");
        if (explicit != null) {
            return explicit;
        }
        /*
        A schema written for MCP says a default in prose -- "(default: 5)" -- because that is what
        a reader needs. Parsing prose would be guessing, so a wire property with no machine-readable
        default is a gap in the catalogue rather than something to invent here.
        */
        return null;
    }

    private static Object coerce(Object value, Map<String, Object> rules) {
        String type = (String) rules.get("type");
        if (type == null || !(value instanceof Number number)) {
            return value;
        }
        return "integer".equals(type) ? (Object) number.intValue() : (Object) number.doubleValue();
    }

    private static Object clamp(Object value, Map<String, Object> rules) {
        if (!(value instanceof Number number)) {
            return value;
        }
        double result = number.doubleValue();

        if (rules.get("minimum") instanceof Number min) {
            result = Math.max(result, min.doubleValue());
        }
        if (rules.get("maximum") instanceof Number max) {
            result = Math.min(result, max.doubleValue());
        }
        return "integer".equals(rules.get("type")) ? (Object) (int) result : (Object) result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        if (schema == null) {
            return Map.of();
        }
        Object properties = schema.get("properties");
        return properties instanceof Map ? (Map<String, Object>) properties : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}

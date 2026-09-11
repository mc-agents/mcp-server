package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.catalog.ToolSpec;
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

            if (given == null && !rules.containsKey("default")) {
                throw new IllegalArgumentException(
                        "\"%s\" needs \"%s\", and it was not given".formatted(spec.name(), name));
            }

            Object value = given != null ? given : rules.get("default");

            /*
            A null default is a decision, not a gap: it says "no filter", "no override", "choose
            for me". It crosses the wire as an explicit null so that neither kind of bot has to
            work out what an absent key was supposed to mean.
            */
            wire.put(name, value == null ? null : clamp(coerce(value, rules), rules));
        }
        return wire;
    }

    static int deadlineOf(ToolSpec spec, Map<String, Object> wire) {
        Object given = wire.get("timeoutMs");
        int asked = given instanceof Number number ? number.intValue() : spec.defaultDeadlineMs();
        return Math.clamp(asked, 1_000, 600_000);
    }

    private static Object coerce(Object value, Map<String, Object> rules) {
        Object type = rules.get("type");
        if (!(value instanceof Number number)) {
            return value;
        }
        return isInteger(type) ? (Object) number.intValue() : (Object) number.doubleValue();
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
        return isInteger(rules.get("type")) ? (Object) (int) result : (Object) result;
    }

    /** A nullable property carries its type as {@code ["integer", "null"]}. */
    private static boolean isInteger(Object type) {
        return "integer".equals(type) || type instanceof java.util.List<?> list && list.contains("integer");
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

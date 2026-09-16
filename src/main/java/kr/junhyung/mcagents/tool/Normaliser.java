package kr.junhyung.mcagents.tool;

import kr.junhyung.mcagents.catalog.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Room for a tool to finish answering after its own wait expires. */
    private static final int TOOL_TIMEOUT_MARGIN_MS = 1_000;

    private Normaliser() {}

    static Map<String, Object> normalise(ToolSpec spec, Map<String, Object> arguments) {
        return normaliseObject(spec.name(), propertiesOf(spec.wireSchema()), arguments, "");
    }

    /**
     * The bounds the catalogue advertises, held on a call that never crosses the wire.
     *
     * <p>A local tool read its arguments raw, so a {@code timeoutMs} of two billion pinned a
     * thread for as long and a port of 99999 was handed to a bot. The wire clamps, because a bot
     * must never see a value outside its schema and the descriptions say "clamped"; here the value
     * is refused instead, naming the property and its range. A clamped port is a different server
     * and a clamped pattern is a different pattern, and the endpoint's own validation already
     * refuses the same value in the same terms, so a caller inside the process hears what an agent
     * outside it would.
     */
    static Map<String, Object> bound(ToolSpec spec, Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }

        Map<String, Object> properties = propertiesOf(spec.inputSchema());
        Map<String, Object> bounded = new LinkedHashMap<>(arguments);

        for (Map.Entry<String, Object> argument : arguments.entrySet()) {
            String name = argument.getKey();
            Map<String, Object> rules = asMap(properties.get(name));

            if (argument.getValue() instanceof String text && rules.get("maxLength") instanceof Number max
                    && text.length() > max.intValue()) {
                throw new IllegalArgumentException("\"%s\" %s is %d characters, and the most it takes is %d"
                        .formatted(spec.name(), name, text.length(), max.intValue()));
            }
            if (argument.getValue() instanceof Number number && !rules.isEmpty()) {
                Object coerced = coerce(number, rules);
                if (!coerced.equals(clamp(coerced, rules))) {
                    throw new IllegalArgumentException("\"%s\" %s is %s, and it has to be between %s and %s"
                            .formatted(spec.name(), name, number, rules.get("minimum"), rules.get("maximum")));
                }
                bounded.put(name, coerced);
            }
        }
        return bounded;
    }

    /**
     * One object against the properties that describe it: the call's arguments, or one element of
     * an array of objects inside them. {@code where} names the element for the message.
     */
    private static Map<String, Object> normaliseObject(String tool, Map<String, Object> properties,
            Map<String, Object> given, String where) {
        Map<String, Object> wire = new LinkedHashMap<>();

        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String name = property.getKey();
            Map<String, Object> rules = asMap(property.getValue());

            Object value = given == null ? null : given.get(name);

            if (value == null && !rules.containsKey("default")) {
                throw new IllegalArgumentException(
                        "\"%s\"%s needs \"%s\", and it was not given".formatted(tool, where, name));
            }
            if (value == null) {
                value = rules.get("default");
            }

            /*
            A null default is a decision, not a gap: it says "no filter", "no override", "choose
            for me". It crosses the wire as an explicit null so that neither kind of bot has to
            work out what an absent key was supposed to mean.
            */
            wire.put(name, value == null ? null : normaliseValue(tool, name, value, rules, where));
        }
        return wire;
    }

    /**
     * An array of objects is normalised element by element, so a step inside run-inputs arrives
     * with every default filled the way the call itself does. An array of primitives is passed
     * through: clamping drag-slots' slot numbers would turn a negative one into slot 0 rather than
     * letting the bot refuse it.
     */
    @SuppressWarnings("unchecked")
    private static Object normaliseValue(String tool, String name, Object value, Map<String, Object> rules,
            String where) {
        Map<String, Object> itemProperties = propertiesOf(asMap(rules.get("items")));

        if (!"array".equals(rules.get("type")) || itemProperties.isEmpty() || !(value instanceof List<?> elements)) {
            return clamp(coerce(value, rules), rules);
        }

        List<Object> normalised = new ArrayList<>(elements.size());

        for (int index = 0; index < elements.size(); index++) {
            String element = "%s %s[%d]".formatted(where, name, index);

            if (!(elements.get(index) instanceof Map<?, ?> object)) {
                throw new IllegalArgumentException("\"%s\"%s is not an object".formatted(tool, element));
            }
            normalised.add(normaliseObject(tool, itemProperties, (Map<String, Object>) object, element));
        }
        return normalised;
    }

    /**
     * How long the server gives the bot.
     *
     * <p>A tool that takes a {@code timeoutMs} waits that long itself, so the deadline has to sit
     * above it. Setting them equal makes the two timers race, and the bot's deadline usually wins:
     * wait-for-window answered "did not finish within 10000ms" where it had a far better sentence
     * ready about no window having opened. The margin lets the tool's own answer arrive first.
     */
    static int deadlineOf(ToolSpec spec, Map<String, Object> wire) {
        Object given = wire.get("timeoutMs");

        if (given instanceof Number number) {
            return Math.clamp(number.intValue() + TOOL_TIMEOUT_MARGIN_MS, 1_000, 600_000);
        }
        return Math.clamp(spec.defaultDeadlineMs(), 1_000, 600_000);
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
        return "integer".equals(type) || type instanceof List<?> list && list.contains("integer");
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

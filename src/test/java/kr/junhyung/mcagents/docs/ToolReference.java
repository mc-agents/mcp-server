package kr.junhyung.mcagents.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Renders {@code docs/tools.md} from the catalogue.
 *
 * <p>The catalogue is the one place a tool is described, and it is what an agent reads through
 * {@code tools/list}; a reference written by hand beside it says the same things a second time and
 * drifts. This writes the catalogue out as one page a person can read -- every tool by group, its
 * arguments, what it answers with and which kind of bot runs it -- and {@link ToolReferenceTest}
 * fails the build when the page in the tree is not the page the catalogue renders to, the way the
 * operator's build checks its generated manifests.
 *
 * <p>Run by {@code ./gradlew renderToolReference}. In the test sources rather than the jar: a
 * server has no reason to carry its own manual.
 */
public final class ToolReference {

    static final Path CATALOG = Path.of("catalog/catalog.json");
    static final Path TARGET = Path.of("docs/tools.md");

    /**
     * How deep a result's shape is listed. Below this the leaves are the item fields every renderer
     * already words the same way, and the page would be twice as long for them.
     */
    private static final int RESULT_DEPTH = 3;

    private static final Map<String, String> ROUTES = Map.of(
        "rpc", "the bot answers",
        "compose", "the bot answers and the server adds what its feeds caught meanwhile",
        "local", "the server answers from what it holds",
        "orchestrate", "the server drives the bot's session");

    private ToolReference() {
    }

    public static void main(String[] args) throws IOException {
        JsonNode catalog = new ObjectMapper().readTree(CATALOG.toFile());
        Files.writeString(TARGET, render(catalog), StandardCharsets.UTF_8);
        System.out.printf("wrote %s: %d tools from catalogue %s%n", TARGET, catalog.get("tools").size(),
            catalog.get("catalogVersion").asText());
    }

    static String render(JsonNode catalog) {
        JsonNode tools = catalog.get("tools");
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        Map<String, List<String>> watchedBy = new LinkedHashMap<>();
        for (JsonNode tool : tools) {
            groups.computeIfAbsent(tool.get("group").asText(), ignored -> new ArrayList<>()).add(tool);
            if (tool.hasNonNull("watches")) {
                watchedBy.computeIfAbsent(tool.get("watches").asText(), ignored -> new ArrayList<>())
                    .add(tool.get("name").asText());
            }
        }

        List<String> out = new ArrayList<>();
        out.add("# Tools");
        out.add("");
        out.add("<!-- Rendered from catalog/catalog.json by ./gradlew renderToolReference. Edit the catalogue, not this page. -->");
        out.add("");
        out.add(("Catalogue %s, %d tools. This is what `tools/list` offers an MCP client, one section a tool, "
            + "with the arguments as the client sends them. What a bot receives is the catalogue's `wireSchema`, "
            + "which drops `bot` and fills every default in; the [bot protocol](bot-protocol.md) covers that side.")
            .formatted(catalog.get("catalogVersion").asText(), tools.size()));
        out.add("");
        out.add("Every tool but " + String.join(", ", link("list-bots"), link("ping-server"), link("wait-for-server"))
            + " takes `bot`, the name given to join-server, which may be left out while exactly one "
            + "bot is connected; it is not repeated below. A tool marked **fabric only** is one the headless "
            + "kind of bot does not run. **Exclusive** means one call at a time on a bot, since two walks or "
            + "two clicks at once would fight over the same body. **Untrusted** means the answer carries content "
            + "the server did not write -- chat, item names, signs -- and is marked as data rather than "
            + "instructions. **Read-only** means the call changes nothing in the game or on the server, which "
            + "is what an MCP client's `readOnlyHint` approves without asking; **destructive** is the "
            + "`destructiveHint`, for a call that removes something or runs with the bot's permissions. A tool "
            + "that **needs a world** is refused while the bot is on a title or disconnected screen. The "
            + "deadline is how long the server waits for the bot before giving the call up; a tool with a "
            + "`timeoutMs` argument sets its own inside that.");
        out.add("");
        out.add("| Group | Tools |");
        out.add("| --- | --- |");
        groups.forEach((group, members) -> out.add("| [%s](#%s) | %s |".formatted(group, group,
            members.stream().map(tool -> link(tool.get("name").asText())).collect(Collectors.joining(", ")))));
        out.add("");

        groups.forEach((group, members) -> {
            out.add("## " + group);
            out.add("");
            for (JsonNode tool : members) {
                String name = tool.get("name").asText();
                out.add("### " + name);
                out.add("");
                out.add("*" + facts(tool) + "*");
                out.add("");
                out.add(tool.get("description").asText());
                out.add("");
                if (tool.hasNonNull("watches")) {
                    out.add("Polls %s until its answer matches, so the answer is that tool's.".formatted(link(tool.get("watches").asText())));
                    out.add("");
                }
                if (watchedBy.containsKey(name)) {
                    out.add("Waited on by %s.".formatted(watchedBy.get(name).stream().map(ToolReference::link).collect(Collectors.joining(", "))));
                    out.add("");
                }
                JsonNode schema = tool.get("inputSchema");
                List<String> rows = argumentRows(schema, "", true);
                if (rows.isEmpty()) {
                    out.add(schema.path("properties").has("bot") ? "No arguments beyond `bot`." : "No arguments.");
                } else {
                    out.add("| Argument | Type | Required | What it is | Limits |");
                    out.add("| --- | --- | --- | --- | --- |");
                    out.addAll(rows);
                }
                out.add("");
                if (tool.get("structured").asBoolean()) {
                    out.add("The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:");
                    out.add("");
                    out.addAll(resultLines(tool.get("resultSchema").get("properties"), 1));
                    out.add("");
                }
            }
        });
        return String.join("\n", out).stripTrailing() + "\n";
    }

    private static String facts(JsonNode tool) {
        List<String> facts = new ArrayList<>();
        boolean fabricOnly = tool.get("kinds").size() == 1 && "fabric".equals(tool.get("kinds").get(0).asText());
        facts.add(fabricOnly ? "fabric only" : "fabric and azalea");
        facts.add("deadline " + seconds(tool.get("defaultDeadlineMs").asInt()));
        if (tool.get("exclusive").asBoolean()) {
            facts.add("exclusive");
        }
        if (tool.get("untrusted").asBoolean()) {
            facts.add("untrusted");
        }
        if (tool.get("readOnly").asBoolean()) {
            facts.add("read-only");
        }
        if (tool.path("destructive").asBoolean()) {
            facts.add("destructive");
        }
        /*
        Only a call that reaches a bot can be refused for the lack of a world. The key is scoped to
        those routes, and reading its absence as true put the notice on join-server and list-bots.
        */
        String route = tool.get("route").asText();
        boolean reachesABot = "rpc".equals(route) || "compose".equals(route);
        if (reachesABot && tool.path("needsWorld").asBoolean(true)) {
            facts.add("needs a world");
        }
        facts.add(ROUTES.get(route));
        return String.join(" · ", facts);
    }

    /** One row per argument, with an object's own fields under it as {@code parent.field}. */
    private static List<String> argumentRows(JsonNode schema, String prefix, boolean top) {
        List<String> rows = new ArrayList<>();
        JsonNode properties = schema.get("properties");
        if (properties == null) {
            return rows;
        }
        List<String> required = schema.has("required")
            ? StreamSupport.stream(schema.get("required").spliterator(), false).map(JsonNode::asText).toList()
            : List.of();
        for (Map.Entry<String, JsonNode> field : properties.properties()) {
            String name = field.getKey();
            JsonNode argument = field.getValue();
            if (top && "bot".equals(name)) {
                continue;
            }
            String full = prefix + name;
            String requiredCell = required.contains(name) ? "yes"
                : argument.hasNonNull("default") ? "no, default `" + argument.get("default") + "`" : "no";
            rows.add("| `%s` | %s | %s | %s | %s |".formatted(cell(full), cell(typeOf(argument)), requiredCell,
                cell(argument.path("description").asText("")), cell(bounds(argument))));
            JsonNode items = argument.get("items");
            if (argument.has("properties")) {
                rows.addAll(argumentRows(argument, full + ".", false));
            } else if (items != null && items.has("properties")) {
                rows.addAll(argumentRows(items, full + "[].", false));
            }
        }
        return rows;
    }

    /** The shape of a structured answer as a nested list, to {@link #RESULT_DEPTH}. */
    private static List<String> resultLines(JsonNode properties, int depth) {
        List<String> lines = new ArrayList<>();
        if (properties == null) {
            return lines;
        }
        for (Map.Entry<String, JsonNode> field : properties.properties()) {
            JsonNode schema = field.getValue();
            boolean array = "array".equals(schema.path("type").asText());
            JsonNode items = array && schema.has("items") && schema.get("items").isObject() ? schema.get("items") : null;
            String line = "%s- `%s` (%s)".formatted("  ".repeat(depth - 1), field.getKey() + (array ? "[]" : ""),
                items != null ? typeOf(items) : typeOf(schema));
            if (schema.hasNonNull("description")) {
                line += " -- " + schema.get("description").asText().replace('\n', ' ');
            }
            lines.add(line);
            if (depth >= RESULT_DEPTH) {
                continue;
            }
            JsonNode nested = schema.has("properties") ? schema.get("properties")
                : items != null && items.has("properties") ? items.get("properties") : null;
            if (nested != null) {
                lines.addAll(resultLines(nested, depth + 1));
            }
        }
        return lines;
    }

    private static String typeOf(JsonNode schema) {
        JsonNode type = schema.get("type");
        String kind = type == null ? "any"
            : type.isArray() ? StreamSupport.stream(type.spliterator(), false).map(JsonNode::asText).collect(Collectors.joining(" or "))
            : type.asText();
        if (schema.has("enum")) {
            return kind + ": " + StreamSupport.stream(schema.get("enum").spliterator(), false)
                .map(value -> "`" + value.asText() + "`").collect(Collectors.joining(", "));
        }
        if ("array".equals(kind) && schema.has("items") && schema.get("items").isObject()) {
            return "array of " + typeOf(schema.get("items"));
        }
        return kind;
    }

    /** The numeric and length limits, in the words a caller would search the page for. */
    private static String bounds(JsonNode schema) {
        List<String> parts = new ArrayList<>();
        if (schema.has("minimum") && schema.has("maximum")) {
            parts.add(schema.get("minimum") + " to " + schema.get("maximum"));
        } else if (schema.has("minimum")) {
            parts.add("at least " + schema.get("minimum"));
        } else if (schema.has("maximum")) {
            parts.add("at most " + schema.get("maximum"));
        }
        if (schema.has("minLength") && schema.get("minLength").asInt() > 1) {
            parts.add("at least " + schema.get("minLength") + " characters");
        }
        if (schema.has("maxLength")) {
            parts.add("at most " + schema.get("maxLength") + " characters");
        }
        if (schema.has("minItems") || schema.has("maxItems")) {
            parts.add(schema.path("minItems").asInt(0) + " to "
                + (schema.has("maxItems") ? schema.get("maxItems").asText() : "any number of") + " items");
        }
        if (schema.has("pattern")) {
            parts.add("matching `" + schema.get("pattern").asText() + "`");
        }
        return String.join("; ", parts);
    }

    /** Text inside a table cell: a pipe would end the cell and a newline the row. */
    private static String cell(String text) {
        return text.replace("|", "\\|").replace('\n', ' ');
    }

    private static String link(String tool) {
        return "[`%s`](#%s)".formatted(tool, tool);
    }

    private static String seconds(int ms) {
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0)) + "s";
    }
}

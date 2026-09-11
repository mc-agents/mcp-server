package kr.junhyung.mcagents.render;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue says which tools answer with a DTO; this says what their text looks like. A tool
 * without an entry here answers with {@code result.text} as the bot sent it, which is every tool that
 * reports on an action rather than on state.
 */
public final class Renderers {

    private record Entry<T>(Class<T> view, Renderer<T> renderer) {

        String render(ObjectMapper mapper, JsonNode data) {
            return renderer.render(mapper.convertValue(data, view));
        }
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        /* A bot built against a newer catalogue may send a field this server has never heard of. */
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private static final Map<String, Entry<?>> BY_TOOL = Map.ofEntries(
        Map.entry("can-craft", entry(CanCraftRenderer.View.class, new CanCraftRenderer())),
        Map.entry("complete-command", entry(CompletionsRenderer.View.class, new CompletionsRenderer())),
        Map.entry("find-blocks", entry(FoundBlocksRenderer.View.class, new FoundBlocksRenderer())),
        Map.entry("find-entity", entry(FoundEntitiesRenderer.View.class, new FoundEntitiesRenderer())),
        Map.entry("find-item", entry(FoundItemRenderer.View.class, new FoundItemRenderer())),
        Map.entry("get-block-info", entry(BlockInfoRenderer.View.class, new BlockInfoRenderer())),
        Map.entry("get-player-state", entry(PlayerStateRenderer.View.class, new PlayerStateRenderer())),
        Map.entry("get-position", entry(PositionRenderer.View.class, new PositionRenderer())),
        Map.entry("get-recipe", entry(RecipeListRenderer.View.class, new RecipeListRenderer())),
        Map.entry("get-world-state", entry(WorldStateRenderer.View.class, new WorldStateRenderer())),
        Map.entry("list-inventory", entry(InventoryRenderer.View.class, new InventoryRenderer())),
        Map.entry("list-recipes", entry(RecipeListRenderer.View.class, new RecipeListRenderer())),
        Map.entry("open-container", entry(WindowRenderer.View.class, new WindowRenderer())),
        Map.entry("read-block-entity", entry(BlockEntityRenderer.View.class, new BlockEntityRenderer())),
        Map.entry("read-boss-bars", entry(BossBarsRenderer.View.class, new BossBarsRenderer())),
        Map.entry("read-displays", entry(DisplaysRenderer.View.class, new DisplaysRenderer())),
        Map.entry("read-player-list", entry(PlayerListRenderer.View.class, new PlayerListRenderer())),
        Map.entry("read-scoreboard", entry(ScoreboardRenderer.View.class, new ScoreboardRenderer())),
        Map.entry("read-window", entry(WindowRenderer.View.class, new WindowRenderer())),
        Map.entry("wait-for-window", entry(AwaitedWindowRenderer.View.class, new AwaitedWindowRenderer())));

    private Renderers() {}

    private static <T> Entry<T> entry(Class<T> view, Renderer<T> renderer) {
        return new Entry<>(view, renderer);
    }

    public static boolean handles(String tool) {
        return BY_TOOL.containsKey(tool);
    }

    /**
     * Empty means this tool never sends a DTO, so the caller keeps the bot's own text. A tool that
     * does send one and sent something unreadable is a catalogue disagreement, not a missing answer,
     * and saying so is more use than quietly falling back to a line meant for debugging.
     */
    public static Optional<String> render(String tool, JsonNode data) {
        Entry<?> entry = BY_TOOL.get(tool);

        if (entry == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(entry.render(MAPPER, data));
        } catch (RuntimeException cause) {
            throw new RenderException(tool, tool + " sent data this server cannot read: " + cause.getMessage(), cause);
        }
    }

    public static Set<String> tools() {
        return BY_TOOL.keySet();
    }
}

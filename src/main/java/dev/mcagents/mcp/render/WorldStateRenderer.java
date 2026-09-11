package dev.mcagents.mcp.render;

import java.util.List;

public final class WorldStateRenderer implements Renderer<WorldStateRenderer.View> {

    private static final int TICKS_PER_DAY = 24_000;
    private static final int TICKS_PER_HOUR = 1_000;
    /**
     * Tick 0 is six in the morning, not midnight.
     */
    private static final int SUNRISE_OFFSET = 6_000;

    public record View(int timeOfDay, int day, int moonPhase, boolean isDay, String weather,
        boolean doDaylightCycle) {}

    @Override
    public String render(View view) {
        int clock = (view.timeOfDay() + SUNRISE_OFFSET) % TICKS_PER_DAY;
        int hour = clock / TICKS_PER_HOUR;
        int minute = clock % TICKS_PER_HOUR * 60 / TICKS_PER_HOUR;

        return String.join("\n", List.of(
            "time: " + two(hour) + ":" + two(minute) + " (tick " + view.timeOfDay() + " of the day, "
                + (view.isDay() ? "day" : "night") + ")",
            "day: " + view.day() + ", moon phase " + view.moonPhase(),
            "weather: " + view.weather(),
            "daylight cycle: " + (view.doDaylightCycle() ? "running" : "frozen")));
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}

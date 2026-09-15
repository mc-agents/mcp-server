package kr.junhyung.mcagents.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.platform.commons.annotation.Testable;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * The share of the cases one run goes through, asked for as {@code -Pe2e.shard=2/4}.
 *
 * <p>A fabric bot took a quarter of an hour over every case, one after another in one world, and
 * almost all of it was the cases rather than the start. Split, each shard starts a server and a bot
 * of its own and the shards run side by side.
 *
 * <p>The cases are laid end to end in the order of their names, each as long as it took on a fabric
 * bot, and the line is cut into equal lengths; a case belongs to the shard its middle falls in. By
 * time, because the slowest case takes ten times as long as the quickest. And cut rather than dealt
 * out, because a case added to a dealt hand moves every case dealt after it, and one added to the
 * line moves only a case lying across a cut.
 */
public final class Shard implements PostDiscoveryFilter {

    private static final Pattern ASKED = Pattern.compile("(\\d+)/(\\d+)");

    private final int shard;
    private final int shards;
    private final Map<String, Double> seconds = durations();
    private final double usual = seconds.values().stream().mapToDouble(Double::doubleValue).average().orElse(1);
    private final Map<Class<?>, Map<String, Integer>> cuts = new ConcurrentHashMap<>();

    public Shard() {
        String asked = System.getProperty("e2e.shard", "1/1");
        Matcher shape = ASKED.matcher(asked);

        if (!shape.matches() || Integer.parseInt(shape.group(1)) < 1
            || Integer.parseInt(shape.group(1)) > Integer.parseInt(shape.group(2))) {
            throw new IllegalArgumentException(
                "e2e.shard is \"" + asked + "\"; it names one shard of how many, as 2/4");
        }
        shard = Integer.parseInt(shape.group(1));
        shards = Integer.parseInt(shape.group(2));
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (!(descriptor.getSource().orElse(null) instanceof MethodSource source)) {
            return FilterResult.included("not a case");
        }
        int belongs = cuts.computeIfAbsent(source.getJavaClass(), this::cut).get(source.getMethodName());

        return FilterResult.includedIf(belongs == shard,
            () -> "in shard " + shard + " of " + shards,
            () -> "in shard " + belongs + " of " + shards);
    }

    private Map<String, Integer> cut(Class<?> type) {
        List<String> names = ReflectionSupport.streamMethods(type,
                method -> AnnotationSupport.isAnnotated(method, Testable.class), HierarchyTraversalMode.TOP_DOWN)
            .map(Method::getName)
            .distinct()
            .sorted()
            .toList();
        double total = names.stream().mapToDouble(this::length).sum();
        Map<String, Integer> belongs = new HashMap<>();
        double before = 0;

        for (String name : names) {
            belongs.put(name, (int) ((before + length(name) / 2) / total * shards) + 1);
            before += length(name);
        }
        return belongs;
    }

    private double length(String name) {
        return seconds.getOrDefault(name, usual);
    }

    private static Map<String, Double> durations() {
        Properties listed = new Properties();

        try (InputStream in = Shard.class.getResourceAsStream("/durations.properties")) {
            listed.load(in);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }

        Map<String, Double> seconds = new HashMap<>();
        listed.stringPropertyNames().forEach(name -> seconds.put(name, Double.parseDouble(listed.getProperty(name))));
        return seconds;
    }
}

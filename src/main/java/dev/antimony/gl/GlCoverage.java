package dev.antimony.gl;

import dev.antimony.AntimonyMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-entry-point call counters for the compatibility layer.
 *
 * <p>Nothing in here changes behaviour: it answers "what do mods actually
 * call?", which is the only sound way to decide what the layer has to
 * implement. The dump lands in {@code antimony-gl-coverage.txt} next to the
 * game jar.</p>
 */
public final class GlCoverage {
    private static final Map<String, LongAdder> HITS = new ConcurrentHashMap<>();
    private static final Map<Integer, LongAdder> CAPS_ON = new ConcurrentHashMap<>();
    private static final Map<Integer, LongAdder> CAPS_OFF = new ConcurrentHashMap<>();
    private static volatile long flushes;

    private GlCoverage() {
    }

    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_HIT = 
            new java.util.concurrent.atomic.AtomicBoolean();

    public static void hit(String entryPoint) {
        HITS.computeIfAbsent(entryPoint, k -> new LongAdder()).increment();
        if (FIRST_HIT.compareAndSet(false, true)) {
            AntimonyMod.LOG.info("[Antimony] GL hooks are live: first intercepted call is {}", entryPoint);
        }
    }

    public static void cap(int cap, boolean enabled) {
        (enabled ? CAPS_ON : CAPS_OFF).computeIfAbsent(cap, k -> new LongAdder()).increment();
    }

    public static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(GlCoverage::flush, "antimony-gl-coverage-dump"));
    }

    /** Writes the report; cheap enough to call a few times per session. */
    public static void flush() {
        if (HITS.isEmpty()) {
            return;
        }
        flushes++;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# Antimony OpenGL coverage - mode=").append(GlCompat.mode())
                .append("  flush=").append(flushes).append('\n')
                .append("# entry point                     calls\n");
        List<Map.Entry<String, LongAdder>> entries = new ArrayList<>(HITS.entrySet());
        entries.sort(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> -e.getValue().sum())
                .thenComparing(Map.Entry::getKey));
        long total = 0;
        for (Map.Entry<String, LongAdder> e : entries) {
            long calls = e.getValue().sum();
            total += calls;
            sb.append(String.format("%-34s %10d%n", e.getKey(), calls));
        }
        sb.append('\n').append("total calls: ").append(total)
                .append("   distinct entry points: ").append(entries.size()).append('\n');
        sb.append("state setters absorbed (glcompat=on): ")
                .append(dev.antimony.gl.state.GlState.absorbed).append('\n');
        if (!CAPS_ON.isEmpty() || !CAPS_OFF.isEmpty()) {
            sb.append("\n# glEnable / glDisable capabilities seen\n");
            for (Integer cap : sortedCaps()) {
                long on = CAPS_ON.getOrDefault(cap, new LongAdder()).sum();
                long off = CAPS_OFF.getOrDefault(cap, new LongAdder()).sum();
                sb.append(String.format("  cap 0x%04X  enable=%-8d disable=%d%n", cap, on, off));
            }
        }
        try {
            Files.writeString(path(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AntimonyMod.LOG.debug("[Antimony] could not write GL coverage", e);
        }
    }

    public static Path path() {
        Path dir;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            dir = mc != null && mc.gameDirectory != null
                    ? mc.gameDirectory.toPath()
                    : Path.of(System.getProperty("user.dir", "."));
        } catch (Throwable ignored) {
            dir = Path.of(System.getProperty("user.dir", "."));
        }
        return dir.resolve("antimony-gl-coverage.txt");
    }

    private static List<Integer> sortedCaps() {
        List<Integer> caps = new ArrayList<>(CAPS_ON.keySet());
        for (Integer cap : CAPS_OFF.keySet()) {
            if (!caps.contains(cap)) {
                caps.add(cap);
            }
        }
        caps.sort(Integer::compareTo);
        return caps;
    }
}

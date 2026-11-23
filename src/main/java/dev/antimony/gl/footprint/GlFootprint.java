package dev.antimony.gl.footprint;

import dev.antimony.AntimonyMod;
import dev.antimony.core.guard.ModDisabler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads every mod jar's constant pool and lists the OpenGL entry points each
 * mod actually references.
 *
 * <p>This is the layer's shopping list. Guessing which of the 2253 methods in
 * {@code GL11..GL45} a mod might use is hopeless; parsing the {@code Methodref}
 * entries gives the exact set, per mod, with a usage count. The union across all
 * installed mods is what the compatibility layer has to implement - nothing
 * more, nothing less.</p>
 *
 * <p>Outputs, next to the game jar:</p>
 * <ul>
 *   <li>{@code antimony-gl-footprint.txt} - per mod, the GL surface it uses</li>
 *   <li>{@code antimony-gl-needs.txt} - the union, sorted by demand</li>
 * </ul>
 */
public final class GlFootprint {
    /** Class-name prefixes whose methods we care about. */
    private static final String[] PREFIXES = {
            "org/lwjgl/opengl/",
            "com/mojang/blaze3d/platform/GlStateManager"
    };

    private static final Pattern DISPLAY_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");
    private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;

    private GlFootprint() {
    }

    public static void scanAsync() {
        Thread t = new Thread(GlFootprint::scan, "antimony-gl-footprint");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void scan() {
        Path modsDir = ModDisabler.modsDir();
        Map<String, Map<String, Integer>> perMod = new LinkedHashMap<>();
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (var stream = Files.list(modsDir)) {
            List<Path> jars = stream
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path jar : jars) {
                String name = jar.getFileName().toString();
                if (name.startsWith("antimony")) {
                    continue;
                }
                try {
                    Map<String, Integer> surface = scanJar(jar);
                    if (!surface.isEmpty()) {
                        perMod.put(name + "  [" + displayName(jar) + "]", surface);
                    }
                } catch (RuntimeException e) {
                    AntimonyMod.LOG.debug("[Antimony] footprint: could not read {}", name, e);
                }
            }
        } catch (IOException e) {
            AntimonyMod.LOG.warn("[Antimony] footprint: could not list mods", e);
            return;
        }
        writeFootprint(perMod);
        writeNeeds(perMod);
        AntimonyMod.LOG.info("[Antimony] GL footprint: {} mod(s) reference OpenGL, "
                + "{} distinct entry points - see antimony-gl-footprint.txt", perMod.size(), union(perMod).size());
    }

    private static String displayName(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry meta = zip.getEntry("META-INF/neoforge.mods.toml");
            if (meta == null) {
                meta = zip.getEntry("META-INF/mods.toml");
            }
            if (meta == null) {
                return jar.getFileName().toString();
            }
            try (InputStream in = zip.getInputStream(meta)) {
                Matcher m = DISPLAY_NAME.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                return m.find() ? m.group(1) : jar.getFileName().toString();
            }
        } catch (IOException e) {
            return jar.getFileName().toString();
        }
    }

    /** @return "GL11.glDrawElements" -> number of classes referencing it. */
    private static Map<String, Integer> scanJar(Path jar) throws IOException {
        Map<String, Integer> surface = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                if (entry.getSize() > MAX_CLASS_BYTES) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readNBytes(MAX_CLASS_BYTES);
                } catch (IOException ignored) {
                    continue;
                }
                Set<String> refs = new LinkedHashSet<>();
                try {
                    collectMethodRefs(bytes, refs);
                } catch (RuntimeException ignored) {
                    // malformed or obfuscated class: skip it
                }
                for (String ref : refs) {
                    surface.merge(ref, 1, Integer::sum);
                }
            }
        }
        return surface;
    }

    // ---- constant pool -------------------------------------------------

    /**
     * Walks the constant pool and collects {@code Owner.method} for every
     * Methodref/InterfaceMethodref whose owner starts with one of our prefixes.
     */
    static void collectMethodRefs(byte[] b, Set<String> out) {
        if (b.length < 10 || (b[0] & 0xFF) != 0xCA || (b[1] & 0xFF) != 0xFE) {
            return;
        }
        int count = u2(b, 8);
        if (count <= 1 || count > 0xFFFF) {
            return;
        }
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];  // tag 7 -> Utf8 index
        int[] refClass = new int[count];        // tag 9/10/11 -> Class entry
        int[] refNat = new int[count];          // tag 9/10/11 -> NameAndType entry
        int[] natName = new int[count];         // tag 12 -> Utf8 index
        int[] natDesc = new int[count];         // tag 12 -> Utf8 index

        int p = 10;
        for (int i = 1; i < count; i++) {
            if (p >= b.length) {
                return;
            }
            int tag = b[p++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    int length = u2(b, p);
                    p += 2;
                    if (p + length > b.length) {
                        return;
                    }
                    utf8[i] = new String(b, p, length, StandardCharsets.UTF_8);
                    p += length;
                }
                case 3, 4 -> p += 4;
                case 5, 6 -> {
                    p += 8;
                    i++;
                }
                case 7 -> {
                    classNameIndex[i] = u2(b, p);
                    p += 2;
                }
                case 8, 16, 19, 20 -> p += 2;
                case 9, 10, 11 -> {
                    refClass[i] = u2(b, p);
                    refNat[i] = u2(b, p + 2);
                    p += 4;
                }
                case 12 -> {
                    natName[i] = u2(b, p);
                    natDesc[i] = u2(b, p + 2);
                    p += 4;
                }
                case 15 -> p += 3;
                case 17, 18 -> p += 4;
                default -> {
                    return;
                }
            }
        }

        for (int i = 1; i < count; i++) {
            if (refNat[i] == 0 || refClass[i] <= 0 || refNat[i] <= 0) {
                continue;
            }
            if (refClass[i] >= count || refNat[i] >= count) {
                continue;
            }
            int ownerUtf8 = classNameIndex[refClass[i]];
            int nameUtf8 = natName[refNat[i]];
            if (ownerUtf8 <= 0 || ownerUtf8 >= count || nameUtf8 <= 0 || nameUtf8 >= count) {
                continue;
            }
            String owner = utf8[ownerUtf8];
            String method = utf8[nameUtf8];
            if (owner == null || method == null) {
                continue;
            }
            if (!wanted(owner)) {
                continue;
            }
            int slash = owner.lastIndexOf('/');
            out.add((slash >= 0 ? owner.substring(slash + 1) : owner) + "." + method);
        }
    }

    private static boolean wanted(String owner) {
        for (String prefix : PREFIXES) {
            if (owner.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    // ---- reports -------------------------------------------------------

    private static void writeFootprint(Map<String, Map<String, Integer>> perMod) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# Antimony GL footprint - OpenGL entry points referenced by each mod jar\n")
                .append("# numbers are the count of classes in that jar referencing the entry point\n");
        for (Map.Entry<String, Map<String, Integer>> mod : perMod.entrySet()) {
            sb.append('\n').append("=== ").append(mod.getKey()).append(" ===\n");
            List<Map.Entry<String, Integer>> rows = new ArrayList<>(mod.getValue().entrySet());
            rows.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey));
            for (Map.Entry<String, Integer> row : rows) {
                sb.append(String.format("  %-40s %3d%n", row.getKey(), row.getValue()));
            }
        }
        write("antimony-gl-footprint.txt", sb.toString());
    }

    private static void writeNeeds(Map<String, Map<String, Integer>> perMod) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        Map<String, Set<String>> mods = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> mod : perMod.entrySet()) {
            for (Map.Entry<String, Integer> row : mod.getValue().entrySet()) {
                refs.merge(row.getKey(), row.getValue(), Integer::sum);
                mods.computeIfAbsent(row.getKey(), k -> new LinkedHashSet<>()).add(mod.getKey());
            }
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(refs.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# Antimony GL needs - union of every mod's OpenGL surface, most demanded first\n")
                .append("# this is the exact shopping list for the compatibility layer\n")
                .append("# entry point                              refs  mods\n");
        for (Map.Entry<String, Integer> row : rows) {
            sb.append(String.format("%-42s %6d %5d%n", row.getKey(), row.getValue(),
                    mods.get(row.getKey()).size()));
        }
        sb.append('\n').append("distinct entry points: ").append(rows.size()).append('\n');
        write("antimony-gl-needs.txt", sb.toString());
    }

    private static Map<String, Integer> union(Map<String, Map<String, Integer>> perMod) {
        Map<String, Integer> refs = new LinkedHashMap<>();
        for (Map<String, Integer> surface : perMod.values()) {
            refs.putAll(surface);
        }
        return refs;
    }

    private static void write(String fileName, String text) {
        try {
            Path dir = null;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                dir = mc.gameDirectory.toPath();
            }
            if (dir == null) {
                dir = Path.of(System.getProperty("user.dir", "."));
            }
            Files.writeString(dir.resolve(fileName), text, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            AntimonyMod.LOG.debug("[Antimony] footprint: could not write {}", fileName, e);
        }
    }
}

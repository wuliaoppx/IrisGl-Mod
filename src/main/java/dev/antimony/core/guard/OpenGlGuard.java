package dev.antimony.core.guard;

import dev.antimony.AntimonyMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Finds mods that talk to OpenGL directly.
 *
 * <p>Antimony replaces Minecraft's renderer, so OpenGL no longer draws the game
 * and a mod that issues its own GL draws is drawing into a buffer nobody shows.
 * Instead of failing silently, every jar in {@code mods/} is scanned once at
 * startup; hits are logged, written to {@code antimony-gl-report.txt} and - for
 * real draw/shader usage - summarised in an in-game screen where each offending
 * mod can be disabled with one click (reversibly: the jar is moved aside, never
 * deleted).</p>
 *
 * <p>Same idea as VulkanMod's "incompatible mods" list, but computed from the
 * jars rather than maintained by hand.</p>
 */
public final class OpenGlGuard {
    /**
     * Symbols that mean "this mod issues its own OpenGL draw calls": these can
     * never work, because our frames come from the CPU rasterizer.
     */
    private static final String[] HARD_SYMBOLS = {
            "glDrawArrays", "glDrawElements", "glDrawRangeElements", "glMultiDrawElements",
            "glMultiDrawArrays", "glDrawArraysInstanced", "glDrawElementsInstanced",
            "glDrawPixels", "glBegin", "glCreateShader", "glLinkProgram", "glCreateProgram",
            "glGenVertexArrays", "glBindVertexArray", "glGenBuffers", "glBufferData",
            "glVertexAttribPointer", "glGenerateMipmap", "glGenFramebuffers", "glBindFramebuffer"
    };
    /** Merely references the OpenGL package (often just glGetString / GLFW). Logged only. */
    private static final String SOFT_SYMBOL = "org/lwjgl/opengl/";

    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern DISPLAY_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");

    /** off | log | screen (default) - "dialog" is accepted as a legacy alias for screen. */
    private static final String MODE = System.getProperty("antimony.guard", "screen").toLowerCase(Locale.ROOT);
    private static final int MAX_BYTES_PER_JAR = 96 * 1024 * 1024;
    private static final int MAX_SYMBOLS = 6;

    private static volatile List<GuardFinding> findings = List.of();
    private static volatile boolean done;
    private static boolean shownThisSession;
    private static boolean ignoredThisSession;

    private OpenGlGuard() {
    }

    // ---- state ---------------------------------------------------------

    public static boolean active() {
        return !"off".equals(MODE) && !"false".equals(MODE);
    }

    /** Whether the player wants an in-game screen (log mode only writes the file). */
    public static boolean screenMode() {
        return !"log".equals(MODE);
    }

    public static List<GuardFinding> findings() {
        return findings;
    }

    public static boolean done() {
        return done;
    }

    public static boolean hasHardFindings() {
        for (GuardFinding f : findings) {
            if (f.hard()) {
                return true;
            }
        }
        return false;
    }

    public static void ignoreThisSession() {
        ignoredThisSession = true;
    }

    public static Path reportPath() {
        return gameDir().resolve("antimony-gl-report.txt");
    }

    private static Path gameDir() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath();
            }
        } catch (Throwable ignored) {
            // not initialised yet
        }
        return Path.of(System.getProperty("user.dir", "."));
    }

    // ---- scanning ------------------------------------------------------

    public static void scanAsync() {
        if (!active()) {
            done = true;
            return;
        }
        Thread t = new Thread(OpenGlGuard::scan, "antimony-gl-guard");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void scan() {
        List<GuardFinding> found = new ArrayList<>();
        Path modsDir = ModDisabler.modsDir();
        if (!Files.isDirectory(modsDir)) {
            AntimonyMod.LOG.warn("[Antimony] GL guard: mods directory not found, skipping scan");
            done = true;
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
                    ScanResult result = scanJar(jar);
                    if (result == null) {
                        continue;
                    }
                    boolean needsAdapter = !result.hard.isEmpty();
                    if ("xaerominimap".equals(result.modId) && dev.antimony.gl.GlCompat.intercepting()
                            && dev.antimony.core.render.XaeroCpuCompatibility.supportedInstallation()) needsAdapter = false;
                    found.add(new GuardFinding(name, result.modId, result.displayName,
                            needsAdapter, result.hard));
                } catch (RuntimeException e) {
                    AntimonyMod.LOG.debug("[Antimony] GL guard: could not inspect {}", name, e);
                }
            }
        } catch (IOException e) {
            AntimonyMod.LOG.warn("[Antimony] GL guard: could not list mods directory", e);
        }
        findings = List.copyOf(found);
        done = true;
        writeReport();
        maybeShow();
    }

    private record ScanResult(String modId, String displayName, List<String> hard) {
    }

    /** @return null when the jar is clean. */
    private static ScanResult scanJar(Path jar) {
        Set<String> hard = new LinkedHashSet<>();
        boolean soft = false;
        long budget = MAX_BYTES_PER_JAR;
        String modId = "";
        String displayName = "";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry meta = zip.getEntry("META-INF/neoforge.mods.toml");
            if (meta == null) {
                meta = zip.getEntry("META-INF/mods.toml");
            }
            if (meta != null) {
                try (InputStream in = zip.getInputStream(meta)) {
                    String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = MOD_ID.matcher(toml);
                    if (m.find()) {
                        modId = m.group(1);
                    }
                    Matcher d = DISPLAY_NAME.matcher(toml);
                    if (d.find()) {
                        displayName = d.group(1);
                    }
                } catch (IOException ignored) {
                    // metadata is optional
                }
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                if (budget <= 0) {
                    break;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    String carry = "";
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        budget -= read;
                        // Overlap the previous chunk so a symbol split across a
                        // read boundary is still found.
                        String text = carry + new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                        if (hard.size() < MAX_SYMBOLS) {
                            for (String symbol : HARD_SYMBOLS) {
                                if (hard.size() >= MAX_SYMBOLS) {
                                    break;
                                }
                                if (text.contains(symbol)) {
                                    hard.add(symbol);
                                }
                            }
                        }
                        if (!soft && text.contains(SOFT_SYMBOL)) {
                            soft = true;
                        }
                        carry = text.length() > 64 ? text.substring(text.length() - 64) : text;
                    }
                } catch (IOException ignored) {
                    // unreadable entry: skip it, the rest of the jar still gets scanned
                }
            }
        } catch (IOException e) {
            AntimonyMod.LOG.debug("[Antimony] GL guard: could not read {}", jar.getFileName());
            return null;
        }
        if (!hard.isEmpty()) {
            return new ScanResult(modId, displayName, List.copyOf(hard));
        }
        if (soft) {
            return new ScanResult(modId, displayName, List.of());
        }
        return null;
    }

    // ---- report --------------------------------------------------------

    private static void writeReport() {
        StringBuilder sb = new StringBuilder();
        List<GuardFinding> hard = findings.stream().filter(GuardFinding::hard).toList();
        List<GuardFinding> soft = findings.stream().filter(f -> !f.hard()).toList();
        sb.append("锑 (Antimony) 用 IrisGL 软件光栅化接管了 Minecraft 的渲染，\n")
                .append("这是 OpenGL 入口的静态扫描；已接入 CPU 兼容路径的调用不等于渲染失败。\n\n");
        if (hard.isEmpty() && soft.isEmpty()) {
            sb.append("没有发现直接使用 OpenGL 的模组。\n");
        }
        if (!hard.isEmpty()) {
            sb.append("以下模组自己调用 OpenGL 绘制（游戏内界面会提示禁用，文件不会被删除）：\n");
            for (GuardFinding f : hard) {
                sb.append("  - ").append(f.fileName()).append("  [").append(f.label()).append("]  ")
                        .append(String.join(", ", f.symbols())).append('\n');
            }
        }
        if (!soft.isEmpty()) {
            if (!hard.isEmpty()) {
                sb.append('\n');
            }
            sb.append("以下入口仅记录，或已接入限定范围的 CPU 兼容路径：\n");
            for (GuardFinding f : soft) {
                sb.append("  - ").append(f.fileName()).append("  [").append(f.label()).append("]");
                if ("xaerominimap".equals(f.modId()))
                    sb.append(" — CPU 安全模式：地图、坐标、航点和雷达圆点；3D 雷达头像未兼容；不需因此禁用小地图");
                sb.append('\n');
            }
        }
        sb.append("\n禁用位置: mods/.antimony-disabled（把文件名末尾的 .disabled 去掉再放回 mods 即可恢复）\n")
                .append("Antimony has replaced Minecraft's renderer with the IrisGL software rasterizer.\n");
        String report = sb.toString();
        if (!hard.isEmpty()) {
            AntimonyMod.LOG.warn("[Antimony] GL guard:\n{}", report);
        } else {
            AntimonyMod.LOG.info("[Antimony] GL guard: no mod issues its own OpenGL draw calls");
        }
        try {
            Files.writeString(reportPath(), report, StandardCharsets.UTF_8);
            AntimonyMod.LOG.info("[Antimony] GL guard report written to {}", reportPath().toAbsolutePath());
        } catch (IOException e) {
            AntimonyMod.LOG.debug("[Antimony] GL guard report could not be written", e);
        }
    }

    // ---- in-game screen ------------------------------------------------

    /** Called from the title-screen init event. */
    public static void noteTitleScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> showIfReady(mc));
    }

    private static void maybeShow() {
        Minecraft mc;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable ignored) {
            return;
        }
        if (mc == null) {
            return;
        }
        mc.execute(() -> showIfReady(mc));
    }

    private static void showIfReady(Minecraft mc) {
        if (!active() || !screenMode() || shownThisSession || ignoredThisSession) {
            return;
        }
        if (!done || !hasHardFindings()) {
            return;
        }
        if (!(mc.screen instanceof TitleScreen)) {
            return;
        }
        shownThisSession = true;
        mc.setScreen(new GuardScreen());
    }
}

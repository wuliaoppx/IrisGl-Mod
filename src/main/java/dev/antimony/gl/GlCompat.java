package dev.antimony.gl;

import dev.antimony.AntimonyMod;
import java.util.Locale;

/**
 * Entry point and master switch for the OpenGL compatibility layer.
 *
 * <p>The layer exists so that third-party mods which talk to OpenGL keep
 * working: their calls are translated into the same IrisGL CPU rasterizer
 * Minecraft itself uses. It is strictly <b>not</b> a second rendering pipeline -
 * nothing is ever forwarded to the real driver while interception is active.</p>
 *
 * <p>Modes ({@code -Dantimony.glcompat=...}):</p>
 * <ul>
 *   <li>{@code off} - no hooks at all.</li>
 *   <li>{@code report} (explicit diagnostic option) - hooks count calls and let them
 *       through unchanged; this mode does not capture the programs required for CPU shaders.</li>
 *   <li>{@code on} (default) - hooks take over the call: every pure state setter is
 *       absorbed (the driver never sees it) and the value is mirrored into
 *       {@link dev.antimony.gl.state.GlState}. Resource calls (texture upload,
 *       buffer/FBO objects, shader objects) are still forwarded until the CPU
 *       texture/program layer lands.</li>
 * </ul>
 */
public final class GlCompat {
    public enum Mode {
        OFF, REPORT, ON
    }

    private static final String MODE_OVERRIDE = System.getProperty("antimony.glcompat");
    private static final Mode REQUESTED = parse(MODE_OVERRIDE == null ? "on" : MODE_OVERRIDE);
    private static final Mode EFFECTIVE = REQUESTED;

    private GlCompat() {
    }

    private static Mode parse(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "off", "false", "0" -> Mode.OFF;
            case "on", "true", "1" -> Mode.ON;
            default -> Mode.REPORT;
        };
    }

    public static Mode mode() {
        return EFFECTIVE;
    }

    public static boolean active() {
        return EFFECTIVE != Mode.OFF;
    }

    /** True once the layer actually takes over calls instead of only counting them. */
    public static boolean intercepting() {
        return EFFECTIVE == Mode.ON;
    }

    /** Diagnostics: a class outside the transforming loader can never be hooked. */
    private static void logLoader(String className) {
        try {
            Class<?> type = Class.forName(className);
            AntimonyMod.LOG.info("[Antimony] loader check {}: loader={} module={} transformedByMixin={}",
                    className, type.getClassLoader(), type.getModule().getName(),
                    type.getClassLoader() != null && type.getClassLoader().getClass().getName().contains("Transform"));
        } catch (Throwable t) {
            AntimonyMod.LOG.warn("[Antimony] loader check {} failed", className, t);
        }
    }

    /** Called once from the mod constructor. */
    public static void init() {
        AntimonyMod.LOG.info("[Antimony] CPU shader mode: mode={} source={}", EFFECTIVE,
                MODE_OVERRIDE == null ? "jar-default" : "jvm-override");
        if (!active()) {
            AntimonyMod.LOG.info("[Antimony] OpenGL compatibility layer disabled");
            return;
        }
        logLoader("org.lwjgl.opengl.GL11");
        logLoader("com.mojang.blaze3d.platform.GlStateManager");
        logLoader("net.minecraft.client.Minecraft");
        GlCoverage.installShutdownHook();
        if (intercepting()) {
            AntimonyMod.LOG.info("[Antimony] OpenGL compatibility layer: mode=on (state interception active; "
                    + "texture/buffer/shader objects still go to the driver until phase 4)");
        } else {
            AntimonyMod.LOG.info("[Antimony] OpenGL compatibility layer: mode={} (report only, "
                    + "call counts land in antimony-gl-coverage.txt)", EFFECTIVE);
        }
    }
}

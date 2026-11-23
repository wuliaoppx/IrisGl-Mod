package dev.antimony.core.render;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.antimony.AntimonyMod;

/** Render-thread snapshot of terrain fog, taken before later passes can change it. */
public final class FogFrame {
    public record Snapshot(float start, float end, FogShape shape,
                           float red, float green, float blue, float alpha,
                           Float rawDensity) {
        /** Iris uses a negative captured density to select GL_LINEAR. Zero is valid. */
        public Float density() { return rawDensity == null ? null : Math.max(0.0F, rawDensity); }
        public int shapeId() { return shape == FogShape.CYLINDER ? 1 : 0; }

        public void restoreRenderSystem() {
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
            RenderSystem.setShaderFogShape(shape);
            RenderSystem.setShaderFogColor(red, green, blue, alpha);
        }
    }

    private static Snapshot current;
    private static long frames;
    private static boolean densityCaptureWarned;
    private static final boolean DIAG = "on".equalsIgnoreCase(System.getProperty("antimony.post.diag", "off"));

    private FogFrame() { }

    public static void beginFrame() { current = null; }
    public static Snapshot current() { return current; }

    public static Snapshot captureTerrain() {
        float[] color = RenderSystem.getShaderFogColor();
        Float rawDensity = null;
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object state = type.getField("INSTANCE").get(null);
            rawDensity = (Float) type.getMethod("getFogDensity").invoke(state);
        } catch (ClassNotFoundException absent) {
            // Vanilla fog still works without Iris; there is no legacy density owner.
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            if (!densityCaptureWarned) {
                densityCaptureWarned = true;
                AntimonyMod.LOG.warn("[Antimony] Iris fog density unavailable; retaining current RenderSystem colour/range", failure);
            }
        }
        current = new Snapshot(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                RenderSystem.getShaderFogShape(), color[0], color[1], color[2], color[3], rawDensity);
        if (DIAG && frames++ % 100 == 0) {
            AntimonyMod.LOG.info("[Antimony] fog-frame source=FogRenderer/RenderSystem+IrisMixin rawDensity={} cpuDensity={} start={} end={} shape={} rgba=({},{},{},{})",
                    rawDensity, rawDensity == null ? null : current.density(), current.start(), current.end(),
                    current.shape(), current.red(), current.green(), current.blue(), current.alpha());
        }
        return current;
    }
}

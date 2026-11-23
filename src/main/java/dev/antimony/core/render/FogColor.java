package dev.antimony.core.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;

/** Shares vanilla's current floating-point fog colour with the software renderer. */
public final class FogColor {
    private FogColor() {
    }

    /**
     * The replacement frame skips LevelRenderer's fog lifecycle. Run the real
     * implementation so Iris' capture mixin, water transitions, status effects
     * and NeoForge's ComputeFogColor event all see this frame's camera.
     */
    public static void setup(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorldAmount) {
        FogRenderer.setupColor(camera, partialTick, level, renderDistance, darkenWorldAmount);
        FogRenderer.levelFogColor();
    }

    /** Quantization is only for the software framebuffer; uniforms keep float RGBA. */
    public static int packed() {
        float[] color = RenderSystem.getShaderFogColor();
        int r = Mth.clamp((int) (color[0] * 255.0F), 0, 255);
        int g = Mth.clamp((int) (color[1] * 255.0F), 0, 255);
        int b = Mth.clamp((int) (color[2] * 255.0F), 0, 255);
        return (r << 16) | (g << 8) | b;
    }
}

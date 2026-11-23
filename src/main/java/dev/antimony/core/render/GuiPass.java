package dev.antimony.core.render;

import com.mojang.blaze3d.platform.Window;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws vanilla's GUI/HUD/screens through IRIS GL. The GUI code is untouched:
 * every GuiGraphics draw call ends up in an IrisGlBufferSource.
 *
 * <p>Vanilla's GUI coordinate space is left completely alone: the logical GUI
 * size comes from vanilla's own guiScale and window size. Because our software
 * framebuffer is window-sized (IrisGlRenderer keeps them in lockstep), the
 * logical GUI maps 1:1 onto our canvas — screens lay out, draw and hit-test on
 * identical coordinates, so mouse clicks land exactly on what was drawn. There
 * is deliberately no scale override here anymore.</p>
 */
public final class GuiPass {
    private GuiPass() {
    }

    public static void render(Minecraft mc, DeltaTracker deltaTracker) {
        Window window = mc.getWindow();
        int guiWidth = window.getGuiScaledWidth();
        int guiHeight = window.getGuiScaledHeight();
        if (guiWidth <= 0 || guiHeight <= 0) {
            return;
        }
        IrisGl.viewidentity();
        IrisGl.loadidentity();
        IrisGl.ortho(0.0F, guiWidth, guiHeight, 0.0F, -100000.0F, 100000.0F);
        IrisGl.pushmatrix();
        IrisGl.fog(false);
        IrisGl.tint(1.0F, 1.0F, 1.0F, 1.0F);
        // Vanilla's GUI shader state: LEQUAL depth test enabled (GuiGraphics
        // z-layers and 3D item icons rely on it), blending on.
        IrisGl.zbuffer(true);
        IrisGl.zclear();
        IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
        IrisGl.alphatest(false, 0.0F);

        IrisGlBufferSource buffers = IrisGlBufferSource.shared();
        // Vanilla's gui() RenderType enables a LEQUAL depth test and writes
        // depth (GuiGraphics z-layers and 3D item icons rely on it); only
        // guiOverlay() and friends run without depth. Passing depth=true lets
        // endBatch honour each probed RenderType instead of disabling depth
        // for every GUI batch. Depth WRITES stay probed per RenderType, so
        // overlays remain submission-ordered as vanilla draws them.
        buffers.startFrame(null, true);
        GuiGraphics graphics = new GuiGraphics(mc, buffers);
        // Vanilla installs the fixed GUI 3D item lighting for the whole GUI
        // frame (GameRenderer.render -> Lighting.setupFor3DItems before any
        // GUI draw), so the HUD and screens share one light pair. The world
        // pass reinstalls its own pair on the next frame through the
        // RenderSystem mixin (Lighting.setupLevel).
        float[] guiLights = dev.antimony.core.render.EntityLighting.gui3DLights();
        IrisGl.shaderLights(guiLights[0], guiLights[1], guiLights[2], guiLights[3], guiLights[4], guiLights[5]);
        // While the player logs out, gameMode is nulled while level/player are
        // still set; vanilla's Gui.render would NPE inside renderCrosshair
        // (observed at frame ~1515 of a soak run). Skip the HUD instead.
        if (mc.level != null && mc.gameMode != null) {
            mc.gui.render(graphics, deltaTracker);
        }
        if (mc.screen != null) {
            // Same mapping vanilla's MouseHandler uses for screen events.
            int mouseX = (int) (mc.mouseHandler.xpos() * (double) guiWidth / (double) window.getScreenWidth());
            int mouseY = (int) (mc.mouseHandler.ypos() * (double) guiHeight / (double) window.getScreenHeight());
            mc.screen.renderWithTooltip(graphics, mouseX, mouseY, deltaTracker.getRealtimeDeltaTicks());
        }
        if (mc.level != null && mc.gameMode != null) {
            mc.getToasts().render(graphics);
        }
        graphics.flush();
        IrisGl.popmatrix();
        IrisGl.blendmode(false);
        IrisGl.zbuffer(false);
        IrisGl.tint(1.0F, 1.0F, 1.0F, 1.0F);
    }
}

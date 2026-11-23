package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * First person hand/item pass. Vanilla's {@code ItemInHandRenderer} builds the
 * hand in camera space, which is exactly the space our rotation-only view
 * matrix expects, so the pose stack starts at identity here.
 */
public final class HandPass {
    private static boolean renderHand = true;

    private HandPass() {
    }

    public static void setRenderHand(boolean value) {
        renderHand = value;
    }

    public static void render(Minecraft mc, float partialTick, IrisGlTexture lightmap) {
        LocalPlayer player = mc.player;
        if (player == null || !renderHand) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson() || mc.options.hideGui) {
            return;
        }
        IrisGl.ctx().depthTest = false;
        IrisGl.ctx().depthWrite = false;
        // Vanilla authors the hand directly in camera space (the pose stack is
        // multiplied by the inverse view matrix), so the view must be identity
        // here while the perspective projection stays untouched. Use the
        // context helper so the cached MVP is invalidated - a raw identity()
        // write kept the previous frame's camera rotation and pinned the hand
        // in world space (bug: the held item did not turn with the camera).
        IrisGl.viewidentity();
        IrisGl.loadidentity();
        IrisGl.fog(false);
        IrisGl.alphatest(true, 0.05F);
        // Vanilla's hand shader evaluates minecraft_mix_light with ModelView =
        // camera rotation and lights = raw pair; the hand pose already carries
        // the inverse rotation, so the dots reduce to model-space normals vs
        // the raw pair. With our identity view the same reduction holds when
        // we install the raw pair here.
        float[] handLights = dev.antimony.core.render.EntityLighting.rawLights();
        IrisGl.shaderLights(handLights[0], handLights[1], handLights[2], handLights[3], handLights[4], handLights[5]);
        IrisGlBufferSource buffers = IrisGlBufferSource.shared();
        buffers.startFrame(lightmap, false);
        PoseStack pose = new PoseStack();
        mc.gameRenderer.itemInHandRenderer.renderHandsWithItems(
                partialTick,
                pose,
                buffers,
                player,
                mc.getEntityRenderDispatcher().getPackedLightCoords(player, partialTick));
        buffers.endBatch();
        IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
        IrisGl.blendmode(false);
        IrisGl.alphatest(false, 0.0F);
        IrisGl.ctx().depthTest = true;
        IrisGl.ctx().depthWrite = true;
    }
}

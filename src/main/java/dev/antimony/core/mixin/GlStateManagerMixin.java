package dev.antimony.core.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.antimony.core.render.GlStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the GL state bits vanilla toggles through GlStateManager while
 * replaying RenderType shards ({@code setupRenderState()}), so the software
 * rasterizer can honour the exact per-batch state the GPU pipeline would use:
 * back-face culling, depth test, blending and depth writes.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
    @Inject(method = "_enableCull", at = @At("HEAD"))
    private static void antimony$enableCull(CallbackInfo ci) {
        GlStateTracker.setCull(true);
    }

    @Inject(method = "_disableCull", at = @At("HEAD"))
    private static void antimony$disableCull(CallbackInfo ci) {
        GlStateTracker.setCull(false);
    }

    @Inject(method = "_enableDepthTest", at = @At("HEAD"))
    private static void antimony$enableDepthTest(CallbackInfo ci) {
        GlStateTracker.setDepthTest(true);
    }

    @Inject(method = "_disableDepthTest", at = @At("HEAD"))
    private static void antimony$disableDepthTest(CallbackInfo ci) {
        GlStateTracker.setDepthTest(false);
    }

    @Inject(method = "_enableBlend", at = @At("HEAD"))
    private static void antimony$enableBlend(CallbackInfo ci) {
        GlStateTracker.setBlend(true);
    }

    @Inject(method = "_disableBlend", at = @At("HEAD"))
    private static void antimony$disableBlend(CallbackInfo ci) {
        GlStateTracker.setBlend(false);
    }

    @Inject(method = "_depthMask(Z)V", at = @At("HEAD"))
    private static void antimony$depthMask(boolean value, CallbackInfo ci) {
        GlStateTracker.setDepthWrite(value);
    }

    @Inject(method = "_blendFuncSeparate(IIII)V", at = @At("HEAD"))
    private static void antimony$blendFuncSeparate(int src, int dst, int alphaSrc, int alphaDst, CallbackInfo ci) {
        GlStateTracker.setBlendFuncSeparate(mapFactor(src), mapFactor(dst), mapFactor(alphaSrc), mapFactor(alphaDst));
    }

    @Inject(method = "_blendFunc(II)V", at = @At("HEAD"))
    private static void antimony$blendFunc(int src, int dst, CallbackInfo ci) {
        GlStateTracker.setBlendFunc(mapFactor(src), mapFactor(dst));
    }

    /** GL blend factor enum -> IrisGlContext BF_* constant. */
    private static int mapFactor(int glEnum) {
        return switch (glEnum) {
            case 0 -> dev.antimony.core.irisgl.IrisGlContext.BF_ZERO;            // GL_ZERO
            case 1 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE;             // GL_ONE
            case 0x0300 -> dev.antimony.core.irisgl.IrisGlContext.BF_SRC_COLOR;           // GL_SRC_COLOR
            case 0x0301 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_COLOR; // GL_ONE_MINUS_SRC_COLOR
            case 0x0302 -> dev.antimony.core.irisgl.IrisGlContext.BF_SRC_ALPHA;           // GL_SRC_ALPHA
            case 0x0303 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_SRC_ALPHA; // GL_ONE_MINUS_SRC_ALPHA
            case 0x0304 -> dev.antimony.core.irisgl.IrisGlContext.BF_DST_ALPHA;           // GL_DST_ALPHA
            case 0x0305 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_DST_ALPHA; // GL_ONE_MINUS_DST_ALPHA
            case 0x0306 -> dev.antimony.core.irisgl.IrisGlContext.BF_DST_COLOR;           // GL_DST_COLOR
            case 0x0307 -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE_MINUS_DST_COLOR; // GL_ONE_MINUS_DST_COLOR
            default -> dev.antimony.core.irisgl.IrisGlContext.BF_ONE;
        };
    }
}

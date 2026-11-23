package dev.antimony.gl.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.antimony.gl.hook.GlSmHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Taps {@code GlStateManager}, the one place every blaze3d GL call passes
 * through.
 *
 * <p><b>Why not {@code GL11} directly?</b> Measured at runtime: {@code
 * org.lwjgl.opengl.GL11} is loaded by {@code cpw.mods.cl.ModuleClassLoader} as
 * module {@code org.lwjgl.opengl}, while Minecraft lives in module
 * {@code minecraft} on {@code cpw.mods.modlauncher.TransformingClassLoader}.
 * Only the latter is seen by the mixin transformer, so a mixin on any LWJGL
 * class is silently a no-op. Everything in module {@code minecraft} - and that
 * includes the whole blaze3d GL surface - is transformable.</p>
 *
 * <p>Mods that use {@code GlStateManager} / {@code RenderSystem} / RenderTypes
 * are therefore reachable; a mod that calls {@code GL11.xxx} itself is not, and
 * is what the compatibility guard reports and offers to disable.</p>
 *
 * <p>Every handler is injected at HEAD and cancels only when
 * {@code -Dantimony.glcompat=on}; in the default {@code report} mode the call
 * proceeds to the driver untouched and is only counted.</p>
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerProbeMixin {
    // ---- clears ---------------------------------------------------------

    @Inject(method = "_clear(IZ)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$clear(int mask, boolean checkError, CallbackInfo ci) {
        if (GlSmHooks.clear(mask)) {
            ci.cancel();
        }
    }

    @Inject(method = "_clearColor(FFFF)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$clearColor(float red, float green, float blue, float alpha, CallbackInfo ci) {
        if (GlSmHooks.clearColor(red, green, blue, alpha)) {
            ci.cancel();
        }
    }

    @Inject(method = "_clearDepth(D)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$clearDepth(double depth, CallbackInfo ci) {
        if (GlSmHooks.clearDepth(depth)) {
            ci.cancel();
        }
    }

    @Inject(method = "_clearStencil(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$clearStencil(int value, CallbackInfo ci) {
        if (GlSmHooks.clearStencil(value)) {
            ci.cancel();
        }
    }

    // ---- viewport / scissor ---------------------------------------------

    @Inject(method = "_viewport(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$viewport(int x, int y, int width, int height, CallbackInfo ci) {
        if (GlSmHooks.viewport(x, y, width, height)) {
            ci.cancel();
        }
    }

    @Inject(method = "_scissorBox(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$scissor(int x, int y, int width, int height, CallbackInfo ci) {
        if (GlSmHooks.scissor(x, y, width, height)) {
            ci.cancel();
        }
    }

    @Inject(method = "_enableScissorTest()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$enableScissorTest(CallbackInfo ci) {
        if (GlSmHooks.scissorTest(true)) {
            ci.cancel();
        }
    }

    @Inject(method = "_disableScissorTest()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$disableScissorTest(CallbackInfo ci) {
        if (GlSmHooks.scissorTest(false)) {
            ci.cancel();
        }
    }

    // ---- capabilities ----------------------------------------------------

    @Inject(method = "_enableCull()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$enableCull(CallbackInfo ci) {
        if (GlSmHooks.cull(true)) {
            ci.cancel();
        }
    }

    @Inject(method = "_disableCull()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$disableCull(CallbackInfo ci) {
        if (GlSmHooks.cull(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "_enableDepthTest()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$enableDepthTest(CallbackInfo ci) {
        if (GlSmHooks.depthTest(true)) {
            ci.cancel();
        }
    }

    @Inject(method = "_disableDepthTest()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$disableDepthTest(CallbackInfo ci) {
        if (GlSmHooks.depthTest(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "_enableBlend()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$enableBlend(CallbackInfo ci) {
        if (GlSmHooks.blend(true)) {
            ci.cancel();
        }
    }

    @Inject(method = "_disableBlend()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$disableBlend(CallbackInfo ci) {
        if (GlSmHooks.blend(false)) {
            ci.cancel();
        }
    }

    // ---- depth -----------------------------------------------------------

    @Inject(method = "_depthFunc(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$depthFunc(int func, CallbackInfo ci) {
        if (GlSmHooks.depthFunc(func)) {
            ci.cancel();
        }
    }

    @Inject(method = "_depthMask(Z)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$depthMask(boolean write, CallbackInfo ci) {
        if (GlSmHooks.depthMask(write)) {
            ci.cancel();
        }
    }

    // ---- blending --------------------------------------------------------

    @Inject(method = "_blendFunc(II)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$blendFunc(int source, int destination, CallbackInfo ci) {
        if (GlSmHooks.blendFunc(source, destination)) {
            ci.cancel();
        }
    }

    @Inject(method = "_blendFuncSeparate(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$blendFuncSeparate(int source, int destination,
                                                   int alphaSource, int alphaDestination, CallbackInfo ci) {
        if (GlSmHooks.blendFuncSeparate(source, destination, alphaSource, alphaDestination)) {
            ci.cancel();
        }
    }

    @Inject(method = "_blendEquation(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$blendEquation(int mode, CallbackInfo ci) {
        if (GlSmHooks.blendEquation(mode)) {
            ci.cancel();
        }
    }

    // ---- raster / fragment state ----------------------------------------

    @Inject(method = "_polygonMode(II)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$polygonMode(int face, int mode, CallbackInfo ci) {
        if (GlSmHooks.polygonMode(face, mode)) {
            ci.cancel();
        }
    }

    @Inject(method = "_enablePolygonOffset()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$enablePolygonOffset(CallbackInfo ci) {
        if (GlSmHooks.polygonOffsetEnable(true)) {
            ci.cancel();
        }
    }

    @Inject(method = "_disablePolygonOffset()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$disablePolygonOffset(CallbackInfo ci) {
        if (GlSmHooks.polygonOffsetEnable(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "_polygonOffset(FF)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$polygonOffset(float factor, float units, CallbackInfo ci) {
        if (GlSmHooks.polygonOffset(factor, units)) {
            ci.cancel();
        }
    }

    @Inject(method = "_enableColorLogicOp()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$enableColorLogicOp(CallbackInfo ci) {
        if (GlSmHooks.colorLogicOp(true)) {
            ci.cancel();
        }
    }

    @Inject(method = "_disableColorLogicOp()V", at = @At("HEAD"), cancellable = true)
    private static void antimony$disableColorLogicOp(CallbackInfo ci) {
        if (GlSmHooks.colorLogicOp(false)) {
            ci.cancel();
        }
    }

    @Inject(method = "_logicOp(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$logicOp(int op, CallbackInfo ci) {
        if (GlSmHooks.logicOp(op)) {
            ci.cancel();
        }
    }

    @Inject(method = "_colorMask(ZZZZ)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$colorMask(boolean red, boolean green, boolean blue, boolean alpha, CallbackInfo ci) {
        if (GlSmHooks.colorMask(red, green, blue, alpha)) {
            ci.cancel();
        }
    }

    // ---- stencil ---------------------------------------------------------

    @Inject(method = "_stencilFunc(III)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$stencilFunc(int func, int reference, int mask, CallbackInfo ci) {
        if (GlSmHooks.stencilFunc(func, reference, mask)) {
            ci.cancel();
        }
    }

    @Inject(method = "_stencilMask(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$stencilMask(int mask, CallbackInfo ci) {
        if (GlSmHooks.stencilMask(mask)) {
            ci.cancel();
        }
    }

    @Inject(method = "_stencilOp(III)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$stencilOp(int fail, int zFail, int zPass, CallbackInfo ci) {
        if (GlSmHooks.stencilOp(fail, zFail, zPass)) {
            ci.cancel();
        }
    }

    // ---- queries ---------------------------------------------------------

    @Inject(method = "_getError()I", at = @At("HEAD"), cancellable = true)
    private static void antimony$getError(CallbackInfoReturnable<Integer> cir) {
        if (GlSmHooks.getError()) {
            cir.setReturnValue(0);
        }
    }

    // ---- object calls: observed only (phase 4a mirror) --------------------

    @Inject(method = "_bindTexture(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$bindTexture(int texture, CallbackInfo ci) {
        if (GlSmHooks.bindTexture(texture)) {
            ci.cancel();
        }
    }

    @Inject(method = "_activeTexture(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$activeTexture(int unit, CallbackInfo ci) {
        if (GlSmHooks.activeTexture(unit)) {
            ci.cancel();
        }
    }

    @Inject(method = "_genTexture()I", at = @At("HEAD"), cancellable = true)
    private static void antimony$genTexture(CallbackInfoReturnable<Integer> cir) {
        if (GlSmHooks.genTexture()) {
            cir.setReturnValue(1);
        }
    }

    @Inject(method = "_deleteTexture(I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$deleteTexture(int id, CallbackInfo ci) {
        if (GlSmHooks.deleteTexture(id)) {
            ci.cancel();
        }
    }

    @Inject(method = "_deleteTextures([I)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$deleteTextures(int[] ids, CallbackInfo ci) {
        if (ids != null) {
            for (int id : ids) {
                GlSmHooks.deleteTexture(id);
            }
        }
        if (GlSmHooks.deleteTextures()) {
            ci.cancel();
        }
    }

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$texImage2D(int target, int level, int internalFormat,
                                            int width, int height, int border,
                                            int format, int type,
                                            java.nio.IntBuffer pixels, CallbackInfo ci) {
        if (GlSmHooks.texImage2D(target, level, internalFormat, width, height,
                border, format, type, pixels)) {
            ci.cancel();
        }
    }

    @Inject(method = "_texSubImage2D", at = @At("HEAD"), cancellable = true)
    private static void antimony$texSubImage2D(int target, int level, int x, int y,
                                               int width, int height,
                                               int format, int type, long pixels, CallbackInfo ci) {
        GlSmHooks.texSubImage2D(target, level, x, y, width, height, format, type, pixels);
    }

    @Inject(method = "_glBindFramebuffer(II)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$bindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        if (GlSmHooks.bindFramebuffer(target, framebuffer)) {
            ci.cancel();
        }
    }

    // ---- draw calls (phase 3b) -------------------------------------------

    @Inject(method = "_drawElements(IIIJ)V", at = @At("HEAD"), cancellable = true)
    private static void antimony$drawElements(int mode, int count, int type, long indices, CallbackInfo ci) {
        if (GlSmHooks.drawElements(mode, count, type, indices)) {
            ci.cancel();
        }
    }

    // ---- B4: current-program tracking ------------------------------------

    /**
     * Record-only. Iris binds programs through blaze3d's
     * {@code ProgramManager.glUseProgram -> GlStateManager._glUseProgram},
     * which never passes the rewritten {@code GL20.glUseProgram}, so the shim
     * used to keep whatever {@code bind()} had set. Uniform values are keyed by
     * (program, location), so a stale program id silently filed every upload
     * under the wrong program.
     */
    @Inject(method = "_glUseProgram(I)V", at = @At("HEAD"))
    private static void antimony$useProgram(int program, CallbackInfo ci) {
        dev.antimony.gl.shim.GlShimState.useProgram(program);
    }
}

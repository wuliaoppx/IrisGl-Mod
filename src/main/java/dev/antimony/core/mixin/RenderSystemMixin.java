package dev.antimony.core.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.render.IrisGlTextures;
import dev.antimony.gl.shim.GlShimState;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors the few GL state calls that matter to the software rasterizer. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", at = @At("HEAD"))
    private static void irisgl$rememberTexture(int unit, ResourceLocation location, CallbackInfo ci) {
        IrisGlTextures.rememberShaderTexture(unit, location);
    }

    @Inject(method = "setShaderTexture(II)V", at = @At("HEAD"))
    private static void irisgl$rememberRawTexture(int unit, int texture, CallbackInfo ci) {
        IrisGlTextures.rememberShaderTexture(unit, texture);
    }

    @Inject(method = "setShaderColor(FFFF)V", at = @At("HEAD"))
    private static void irisgl$shaderColor(float r, float g, float b, float a, CallbackInfo ci) {
        IrisGl.tint(r, g, b, a);
    }

    /**
     * Mirrors vanilla's entity shader light directions. Lighting.setupLevel
     * installs the raw camera-space pair for world geometry, and
     * setupGui3DDiffuseLighting the fixed pair for item icons - the rasterizer
     * reproduces minecraft_mix_light with whatever is current, exactly like
     * the vertex shader does.
     */
    @Inject(method = "setShaderLights(Lorg/joml/Vector3f;Lorg/joml/Vector3f;)V", at = @At("HEAD"))
    private static void irisgl$shaderLights(Vector3f light0, Vector3f light1, CallbackInfo ci) {
        IrisGl.shaderLights(light0.x(), light0.y(), light0.z(), light1.x(), light1.y(), light1.z());
    }

    @Inject(method = "enableScissor(IIII)V", at = @At("HEAD"))
    private static void irisgl$enableScissor(int x, int y, int width, int height, CallbackInfo ci) {
        IrisGlFramebuffer fb = IrisGl.framebuffer();
        if (fb == null) {
            return;
        }
        int ww = Minecraft.getInstance().getWindow().getWidth();
        int wh = Minecraft.getInstance().getWindow().getHeight();
        if (ww <= 0 || wh <= 0) {
            return;
        }
        float sx = fb.width / (float) ww;
        float sy = fb.height / (float) wh;
        int px = (int) (x * sx);
        int py = (int) (fb.height - (y + height) * sy);
        int pw = (int) (width * sx);
        int ph = (int) (height * sy);
        IrisGl.scissor(px, py, Math.max(0, pw), Math.max(0, ph));
    }

    @Inject(method = "disableScissor()V", at = @At("HEAD"))
    private static void irisgl$disableScissor(CallbackInfo ci) {
        IrisGl.scissorOff();
    }

    // ------------------------------------------------------------------
    // B4: uniform capture
    // ------------------------------------------------------------------
    // These are RECORD-ONLY injections at HEAD. The original blaze3d method
    // still performs the real GL upload, so nothing here can change how the
    // game renders - the values are only mirrored into GlShimState so the CPU
    // post chain can read what Iris actually uploaded. Before this, the whole
    // chain ran on zeros (no projection/model-view matrices, no sun position,
    // no day/night mixers), which is why the pack's lighting degenerated into
    // a flat colour wash.

    @Inject(method = "glUniform1i(II)V", at = @At("HEAD"))
    private static void antimony$uniform1i(int location, int value, CallbackInfo ci) {
        GlShimState.captureUniform1i(location, value);
    }

    @Inject(method = "glUniform1(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform1f(int location, FloatBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformF(location, "1f", value, 1);
    }

    @Inject(method = "glUniform2(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform2f(int location, FloatBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformF(location, "2f", value, 2);
    }

    @Inject(method = "glUniform3(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform3f(int location, FloatBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformF(location, "3f", value, 3);
    }

    @Inject(method = "glUniform4(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform4f(int location, FloatBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformF(location, "4f", value, 4);
    }

    @Inject(method = "glUniform1(ILjava/nio/IntBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform1iBuf(int location, IntBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformI(location, "1i", value, 1);
    }

    @Inject(method = "glUniform2(ILjava/nio/IntBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform2i(int location, IntBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformI(location, "2i", value, 2);
    }

    @Inject(method = "glUniform3(ILjava/nio/IntBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform3i(int location, IntBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformI(location, "3i", value, 3);
    }

    @Inject(method = "glUniform4(ILjava/nio/IntBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniform4i(int location, IntBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformI(location, "4i", value, 4);
    }

    @Inject(method = "glUniformMatrix3(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniformMatrix3(int location, boolean transpose, FloatBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformMatrix(location, "m3", value);
    }

    @Inject(method = "glUniformMatrix4(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"))
    private static void antimony$uniformMatrix4(int location, boolean transpose, FloatBuffer value, CallbackInfo ci) {
        GlShimState.captureUniformMatrix(location, "m4", value);
    }
}

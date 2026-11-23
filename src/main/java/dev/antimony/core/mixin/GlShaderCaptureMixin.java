package dev.antimony.core.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.antimony.gl.GlCompat;
import dev.antimony.gl.shim.GlShimState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shader lifecycle capture on the blaze3d funnel. Iris' ProgramCreator and
 * GlShader build programs through GlStateManager (bytecode-verified), while
 * only ShaderWorkarounds.safeShaderSource reaches LWJGL directly - so the
 * coremod rewrite sees the source but the create/attach/link lifecycle only
 * passes through here. TAIL injection: the real driver call has completed, so
 * the returned id is the true one, and the GlShimState registry mirrors it.
 * Everything is gated on GlCompat.intercepting() (the glcompat=on switch);
 * report/off sessions leave no registry trace.
 */
@Mixin(GlStateManager.class)
public abstract class GlShaderCaptureMixin {
    @Inject(method = "_glGetUniformLocation(ILjava/lang/CharSequence;)I", at = @At("RETURN"))
    private static void antimony$uniformLocation(int program,CharSequence name,CallbackInfoReturnable<Integer> result){
        if(GlCompat.intercepting())GlShimState.observeUniformLocation(program,name.toString(),result.getReturnValue());
    }
    @Inject(method = "glCreateShader(I)I", at = @At("TAIL"))
    private static void antimony$createShader(int type, CallbackInfoReturnable<Integer> cir) {
        if (GlCompat.intercepting()) {
            GlShimState.createShader(cir.getReturnValue(), type);
        }
    }

    @Inject(method = "glDeleteShader(I)V", at = @At("TAIL"))
    private static void antimony$deleteShader(int shader, CallbackInfo ci) {
        if (GlCompat.intercepting()) {
            GlShimState.deleteShader(shader);
        }
    }

    @Inject(method = "glCreateProgram()I", at = @At("TAIL"))
    private static void antimony$createProgram(CallbackInfoReturnable<Integer> cir) {
        if (GlCompat.intercepting()) {
            GlShimState.createProgram(cir.getReturnValue());
        }
    }

    @Inject(method = "glDeleteProgram(I)V", at = @At("TAIL"))
    private static void antimony$deleteProgram(int program, CallbackInfo ci) {
        if (GlCompat.intercepting()) {
            GlShimState.deleteProgram(program);
        }
    }

    @Inject(method = "glAttachShader(II)V", at = @At("TAIL"))
    private static void antimony$attachShader(int program, int shader, CallbackInfo ci) {
        if (GlCompat.intercepting()) {
            GlShimState.attachShader(program, shader);
        }
    }

    @Inject(method = "glLinkProgram(I)V", at = @At("TAIL"))
    private static void antimony$linkProgram(int program, CallbackInfo ci) {
        if (GlCompat.intercepting()) {
            GlShimState.linkProgram(program);
        }
    }
}

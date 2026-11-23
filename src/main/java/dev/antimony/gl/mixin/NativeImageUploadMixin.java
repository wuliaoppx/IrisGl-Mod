package dev.antimony.gl.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.antimony.gl.hook.GlTexMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps {@code NativeImage._upload} - the funnel for every sprite animation
 * frame, font glyph and dynamic texture upload - and copies the slice into
 * the CPU texture mirror.
 *
 * <p>Phase 5b step 1: when {@link GlTexMirror} reports that it absorbed the
 * upload (software renderer owns the screen and the mirror tracks the bound
 * texture), the whole vanilla body - driver {@code glTexSubImage2D} plus its
 * filter/clamp side effects - is skipped, and the one Java side effect the
 * body would have performed is replayed here: freeing the image when the
 * caller asked for it.</p>
 *
 * <p><b>Boolean order, verified against runtime bytecode</b> (javap of
 * {@code _upload}: {@code setFilter(iload 8, iload 10)}, clamp on
 * {@code iload 9}, {@code close()} under {@code iload 11}): the 10th boolean
 * is the second {@code setFilter} argument and the <b>11th</b> boolean is the
 * close gate. The NeoForge decompile names them "close"/"toMain" in that
 * order, which is misleading - gating close on the 10th slot closed every
 * absorbed mipmapped atlas frame's image and crashed the next animation tick
 * with "Image is not allocated". The block atlas passes
 * {@code hasMips=true, close=false}, so the two must never be confused.</p>
 */
@Mixin(NativeImage.class)
public abstract class NativeImageUploadMixin {
    @Inject(method = "_upload(IIIIIIIZZZZ)V", at = @At("HEAD"), cancellable = true)
    private void antimony$mirrorUpload(int level, int x, int y, int skipPixels,
                                       int skipRows, int width, int height,
                                       boolean blur, boolean clamp, boolean filterArg,
                                       boolean close, CallbackInfo ci) {
        NativeImage self = (NativeImage) (Object) this;
        boolean absorbed = GlTexMirror.observeNativeImageUpload(self, level, x, y,
                skipPixels, skipRows, width, height);
        if (absorbed) {
            if (close) {
                try {
                    self.close();
                } catch (Throwable ignored) {
                    // A double close is harmless; the image is going away.
                }
            }
            ci.cancel();
        }
    }
}

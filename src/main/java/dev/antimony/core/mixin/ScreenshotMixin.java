package dev.antimony.core.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.render.IrisGlRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's F2 screenshot reads the main render target with
 * {@code downloadTexture} - but with the GDI presenter nothing is ever drawn
 * into the GL render target, so every screenshot came out black. Take the
 * pixels from the software framebuffer instead, which is exactly what the
 * layered window shows.
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @Inject(method = "takeScreenshot", at = @At("HEAD"), cancellable = true)
    private static void antimony$takeScreenshot(RenderTarget target, CallbackInfoReturnable<NativeImage> cir) {
        Minecraft mc = Minecraft.getInstance();
        IrisGlFramebuffer fb = IrisGl.framebuffer();
        if (fb == null || mc == null || mc.level == null || IrisGlRenderer.frameCount() <= 0) {
            return;
        }
        NativeImage image = null;
        try {
            IrisGl.flushRaster();
            image = new NativeImage(fb.width, fb.height, false);
            for (int y = 0; y < fb.height; y++) {
                int row = y * fb.width;
                for (int x = 0; x < fb.width; x++) {
                    int argb = fb.color[row + x];
                    // NativeImage stores ABGR in little-endian ints.
                    image.setPixelRGBA(x, y, dev.antimony.core.present.CpuScreenImage.opaqueAbgr(argb));
                }
            }
            cir.setReturnValue(image);
        } catch (Throwable t) {
            if (image != null) image.close();
            throw new IllegalStateException("CPU screenshot capture failed; GL readback is disabled", t);
        }
    }
}

package dev.antimony.core.mixin;

import dev.antimony.core.render.ImmediateDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's GPU post-process blur (pause menu, singleplayer world
 * list, ...). The effect runs the main framebuffer through GPU shader passes
 * and re-uploads intermediate textures; the software renderer owns those
 * pixels, so the pass is meaningless here and its texture churn collides with
 * our per-frame uploads. Vanilla's Screen then draws the (already software-
 * rendered) menu over the un-blurred frame, matching the classic look.
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class GameRendererBlurMixin {
    @Inject(method = "processBlurEffect(F)V", at = @At("HEAD"), cancellable = true)
    private void antimony$skipGpuBlur(float radius, CallbackInfo ci) {
        if (ImmediateDraw.hooked()) {
            ci.cancel();
        }
    }
}

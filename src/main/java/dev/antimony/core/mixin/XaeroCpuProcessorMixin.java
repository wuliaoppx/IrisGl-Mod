package dev.antimony.core.mixin;

import dev.antimony.core.render.XaeroCpuCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Avoids allocating/initialising Xaero's unused GPU FBO path during CPU ownership. */
@Pseudo
@Mixin(targets = "xaero.common.minimap.MinimapProcessor", remap = false)
public abstract class XaeroCpuProcessorMixin {
    @Inject(method = "checkFBO()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void antimony$skipUnusedFbo(CallbackInfo callback) {
        if (XaeroCpuCompatibility.useSafeMode()) callback.cancel();
    }
}

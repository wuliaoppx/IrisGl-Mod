package dev.antimony.core.mixin;

import dev.antimony.core.render.XaeroCpuCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects the mod's original safe-mode renderer without changing any persisted setting. */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.Minimap", remap = false)
public abstract class XaeroCpuMinimapMixin {
    @Inject(method = "usingFBO()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void antimony$cpuMap(CallbackInfoReturnable<Boolean> callback) {
        if (XaeroCpuCompatibility.useSafeMode()) {
            XaeroCpuCompatibility.announce();
            callback.setReturnValue(false);
        }
    }
}

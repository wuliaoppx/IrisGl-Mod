package dev.antimony.core.mixin;

import dev.antimony.core.render.XaeroCpuCompatibility;
import dev.antimony.core.render.CpuMapUpdateBudget;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the original update, so the CPU lifecycle mirror never invokes it twice per frame. */
@Pseudo
@Mixin(targets = "xaero.common.minimap.write.MinimapWriter", remap = false)
public abstract class XaeroCpuWriterMixin {
    @Unique private final CpuMapUpdateBudget antimony$mapBudget = new CpuMapUpdateBudget();

    @Inject(method = "onRender()V", at = @At("HEAD"), remap = false)
    private void antimony$writerObserved(CallbackInfo callback) {
        if (XaeroCpuCompatibility.useSafeMode()) antimony$mapBudget.observe(System.nanoTime());
        XaeroCpuCompatibility.writerObserved();
    }

    @ModifyConstant(method = "onRender()V", constant = @Constant(longValue = 50L), remap = false, require = 1, allow = 1)
    private long antimony$cpuMapBudget(long nativeCap) {
        return XaeroCpuCompatibility.useSafeMode() ? antimony$mapBudget.creditCap(nativeCap) : nativeCap;
    }

    @Inject(method = "onRender()V", at = @At("RETURN"), remap = false)
    private void antimony$writerState(CallbackInfo callback) {
        XaeroCpuCompatibility.writerState(this);
    }
}

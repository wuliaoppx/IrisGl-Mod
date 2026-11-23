package dev.antimony.core.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.render.CpuDynamicTextures;
import dev.antimony.core.render.XaeroCpuCompatibility;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the original map bytes before glTexImage2D; the driver never receives map pixels. */
@Pseudo
@Mixin(targets = "xaero.common.minimap.render.MinimapRendererHelper", remap = false)
public abstract class XaeroCpuTextureMixin {
    @Inject(method = "bindTextureBuffer(Ljava/nio/ByteBuffer;III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void antimony$uploadCpuMap(ByteBuffer pixels, int width, int height, int textureId, CallbackInfo callback) {
        if (!XaeroCpuCompatibility.active()) return;
        IrisGl.flushRaster();
        var texture = CpuDynamicTextures.uploadRgb(textureId, width, height, pixels);
        RenderSystem.setShaderTexture(0, textureId);
        XaeroCpuCompatibility.uploaded(textureId, texture);
        callback.cancel();
    }
}

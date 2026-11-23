package dev.antimony.core.mixin;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import dev.antimony.core.render.ImmediateDraw;
import dev.antimony.core.render.IrisGlRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures every vanilla immediate-mode draw (Tesselator path: toast sprites,
 * screen effects, debug renders) and feeds it into the IRIS GL rasterizer
 * instead of the GPU. Only active while the software renderer owns the frame.
 */
@Mixin(BufferUploader.class)
public abstract class BufferUploaderMixin {
    @Inject(method = "drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V", at = @At("HEAD"), cancellable = true)
    private static void irisgl$immediateDraw(MeshData mesh, CallbackInfo ci) {
        if (IrisGlRenderer.shouldReplace()) {
            ImmediateDraw.hook();
            ImmediateDraw.draw(mesh);
            ci.cancel();
        }
    }
}

package dev.antimony.core.mixin;

import dev.antimony.core.render.SodiumSpriteAnimationBridge;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs once per actual vanilla texture tick, including multiple ticks between slow CPU frames. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasAnimationMixin {
    @Inject(method = "cycleAnimationFrames()V", at = @At("HEAD"))
    private void antimony$cpuSpritesAreActive(CallbackInfo ci) {
        SodiumSpriteAnimationBridge.markAnimatedSprites((TextureAtlas)(Object)this);
    }

    @Inject(method = "clearTextureData()V", at = @At("HEAD"))
    private void antimony$forgetReloadedSprites(CallbackInfo ci) {
        SodiumSpriteAnimationBridge.invalidate((TextureAtlas)(Object)this);
    }
}

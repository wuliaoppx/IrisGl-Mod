package dev.antimony.core.mixin;

import dev.antimony.core.render.ChunkUpdateHooks;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla notifies its own GPU render graph about dirty sections through
 * LevelRenderer.setSectionDirty. The software renderer needs the exact same
 * notifications to re-tessellate its section meshes - without them, newly
 * placed/broken blocks never appear (observed bug).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    // Sodium overwrites these entry points and does not call setSectionDirty.
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void antimony$blockAreaDirty(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo ci) {
        dev.antimony.core.render.SectionDirtyRange.blocks(x0, y0, z0, x1, y1, z1, ChunkUpdateHooks::onSectionDirty);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void antimony$blockDirty(BlockPos pos, boolean immediate, CallbackInfo ci) {
        dev.antimony.core.render.SectionDirtyRange.blocks(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX(), pos.getY(), pos.getZ(), ChunkUpdateHooks::onSectionDirty);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"))
    private void antimony$neighborsDirty(int x, int y, int z, CallbackInfo ci) {
        dev.antimony.core.render.SectionDirtyRange.neighbors(x, y, z, ChunkUpdateHooks::onSectionDirty);
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void antimony$sectionDirty(int x, int y, int z, boolean reRender, CallbackInfo ci) {
        ChunkUpdateHooks.onSectionDirty(x, y, z);
    }
}

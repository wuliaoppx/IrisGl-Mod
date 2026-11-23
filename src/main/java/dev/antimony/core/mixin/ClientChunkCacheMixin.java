package dev.antimony.core.mixin;

import dev.antimony.core.render.ChunkUpdateHooks;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When a chunk (re)arrives from the server, the software meshes built from the
 * stale copy of <em>that chunk</em> must be dropped so they are re-tessellated
 * from the fresh data. Other chunks are untouched: invalidating everything
 * made the entire world visibly rebuild whenever one chunk packet arrived.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "replaceWithPacketData*", at = @At("RETURN"))
    private void antimony$chunkArrived(CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            ChunkUpdateHooks.onChunkReplaced(chunk.getPos().x, chunk.getPos().z);
        }
    }
}

package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.antimony.core.irisgl.IrisGlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Entity + block entity pass, using vanilla renderers with an IRIS GL buffer sink. */
public final class EntityPass {
    private EntityPass() {
    }

    public static void renderEntities(ClientLevel level, Minecraft mc, Vec3 eye, float partialTick, IrisGlTexture lightmap) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.prepare(level, mc.gameRenderer.getMainCamera(), mc.crosshairPickEntity);
        IrisGlBufferSource buffers = IrisGlBufferSource.shared();
        buffers.startFrame(lightmap, true);
        PoseStack pose = new PoseStack();
        double max = 96.0 * 96.0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
                continue;
            }
            double x = Mth.lerp(partialTick, entity.xOld, entity.getX()) - eye.x;
            double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) - eye.y;
            double z = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - eye.z;
            if (x * x + y * y + z * z > max) {
                continue;
            }
            float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            dispatcher.render(entity, x, y, z, yaw, partialTick, pose, buffers,
                    dispatcher.getPackedLightCoords(entity, partialTick));
        }
        buffers.endBatch();
    }

    public static void renderBlockEntities(ClientLevel level, Minecraft mc, Vec3 eye, float partialTick, IrisGlTexture lightmap) {
        BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        dispatcher.prepare(level, mc.gameRenderer.getMainCamera(), mc.hitResult);
        IrisGlBufferSource buffers = IrisGlBufferSource.shared();
        buffers.startFrame(lightmap, true);
        PoseStack pose = new PoseStack();
        double max = 96.0 * 96.0;
        int centerX = (int) Math.floor(eye.x) >> 4;
        int centerZ = (int) Math.floor(eye.z) >> 4;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    double x = pos.getX() - eye.x;
                    double y = pos.getY() - eye.y;
                    double z = pos.getZ() - eye.z;
                    if (x * x + y * y + z * z > max) {
                        continue;
                    }
                    pose.pushPose();
                    pose.translate(x, y, z);
                    buffers.beginBlockEntity(IrisTerrainMaterials.snapshot().materialId(blockEntity.getBlockState()));
                    try { dispatcher.render(blockEntity, partialTick, pose, buffers); }
                    finally {
                        try { buffers.endBlockEntity(); }
                        finally { pose.popPose(); }
                    }
                }
            }
        }
        buffers.endBatch();
    }
}

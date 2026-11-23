package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.antimony.core.irisgl.IrisGlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Vanilla debug overlays: the targeted-block outline, F3+B entity hitboxes
 * (drawn by {@code EntityRenderDispatcher} into {@code RenderType.lines}) and
 * F3+G chunk borders / game-test markers ({@code DebugRenderer}).
 *
 * <p>These used to be invisible because the whole {@code LevelRenderer} frame
 * is replaced and our vertex sink only understood quads: line batches were
 * regrouped into groups of four vertices and dropped. {@link MeshBuffer} now
 * replays vanilla's real primitive mode, so the overlays can simply be called
 * with the same buffers as everything else.</p>
 */
public final class DebugPass {
    private DebugPass() {
    }

    /** Block outline + DebugRenderer, drawn before translucent terrain like vanilla. */
    public static void render(Minecraft mc, PoseStack pose, double camX, double camY, double camZ, IrisGlTexture lightmap) {
        IrisGlBufferSource buffers = IrisGlBufferSource.shared();
        buffers.startFrame(lightmap, true);
        try {
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult blockHit && mc.level != null) {
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = mc.level.getBlockState(pos);
                if (!state.isAir()) {
                    VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
                    VoxelShape shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
                    renderShape(pose, consumer, shape,
                            pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ,
                            0.0F, 0.0F, 0.0F, 0.4F);
                }
            }
            mc.debugRenderer.render(pose, buffers, camX, camY, camZ);
        } catch (Throwable t) {
            dev.antimony.AntimonyMod.LOG.warn("[Antimony] debug overlay pass failed", t);
        } finally {
            buffers.endBatch();
        }
    }

    /** Vanilla's LevelRenderer.renderShape: wireframe of a VoxelShape. */
    private static void renderShape(PoseStack pose, VertexConsumer consumer, VoxelShape shape,
                                    double x, double y, double z,
                                    float red, float green, float blue, float alpha) {
        PoseStack.Pose last = pose.last();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float dx = (float) (x2 - x1);
            float dy = (float) (y2 - y1);
            float dz = (float) (z2 - z1);
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len;
            dy /= len;
            dz /= len;
            consumer.addVertex(last, (float) (x1 + x), (float) (y1 + y), (float) (z1 + z))
                    .setColor(red, green, blue, alpha)
                    .setNormal(last, dx, dy, dz);
            consumer.addVertex(last, (float) (x2 + x), (float) (y2 + y), (float) (z2 + z))
                    .setColor(red, green, blue, alpha)
                    .setNormal(last, dx, dy, dz);
        });
    }
}

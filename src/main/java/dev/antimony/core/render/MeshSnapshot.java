package dev.antimony.core.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * Immutable copy of the 3x3x3 section neighbourhood around one section, taken
 * on the render thread so a worker thread can tessellate against frozen block
 * data. The equivalent of vanilla's {@code RenderChunkRegion}, but only the
 * block-state containers are copied: light, biome colours and shade values are
 * read through the live level, exactly like vanilla's off-thread compile
 * tasks do.
 */
final class MeshSnapshot implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** One section in every direction: AO and face/fluid culling reach 1 block. */
    private static final int RADIUS = 1;

    private final ClientLevel level;
    /** [dy][dx][dz] in 0..2, null = air / out of world / chunk not loaded. */
    private final PalettedContainer<BlockState>[][][] sections;
    private final int baseCx;
    private final int baseCy;
    private final int baseCz;

    private MeshSnapshot(ClientLevel level, PalettedContainer<BlockState>[][][] sections,
                         int baseCx, int baseCy, int baseCz) {
        this.level = level;
        this.sections = sections;
        this.baseCx = baseCx;
        this.baseCy = baseCy;
        this.baseCz = baseCz;
    }

    static MeshSnapshot capture(ClientLevel level, int cx, int cy, int cz) {
        int minSection = level.getMinSection();
        int maxSection = minSection + level.getSectionsCount();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[][][] sections = new PalettedContainer[3][3][3];
        for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            int sy = cy + dy;
            if (sy < minSection || sy >= maxSection) {
                continue;
            }
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(sy - minSection);
                    if (section != null && !section.hasOnlyAir()) {
                        sections[dy + RADIUS][dx + RADIUS][dz + RADIUS] = section.getStates().copy();
                    }
                }
            }
        }
        return new MeshSnapshot(level, sections, cx, cy, cz);
    }

    boolean centerEmpty() {
        return this.sections[RADIUS][RADIUS][RADIUS] == null;
    }

    int sectionX() {
        return this.baseCx;
    }

    int sectionY() {
        return this.baseCy;
    }

    int sectionZ() {
        return this.baseCz;
    }

    BlockPos origin() {
        return SectionPos.of(this.baseCx, this.baseCy, this.baseCz).origin();
    }

    ClientLevel level() {
        return this.level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int dx = (pos.getX() >> 4) - this.baseCx + RADIUS;
        int dy = (pos.getY() >> 4) - this.baseCy + RADIUS;
        int dz = (pos.getZ() >> 4) - this.baseCz + RADIUS;
        if (dx < 0 || dx > 2 * RADIUS || dy < 0 || dy > 2 * RADIUS || dz < 0 || dz > 2 * RADIUS) {
            // The mesher only reaches 1 block outside the section (AO, face
            // culling), which stays inside the snapshot; treat anything else
            // as air rather than touching the live level off-thread.
            return AIR;
        }
        PalettedContainer<BlockState> container = this.sections[dy][dx][dz];
        return container == null ? AIR : container.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return this.level.getShade(direction, shade);
    }

    @Override
    public float getShade(float normalX, float normalY, float normalZ, boolean shade) {
        return this.level.getShade(normalX, normalY, normalZ, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return this.level.getBlockTint(pos, resolver);
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.getMinBuildHeight();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.level.getBlockEntity(pos);
    }

    @Override
    public net.neoforged.neoforge.client.model.data.ModelData getModelData(BlockPos pos) {
        return net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
    }

    @Override
    public net.neoforged.neoforge.common.world.AuxiliaryLightManager getAuxLightManager(net.minecraft.world.level.ChunkPos pos) {
        return null;
    }
}

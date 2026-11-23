package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Rain / snow pass, a port of {@code LevelRenderer.renderSnowAndRain}.
 *
 * <p>Vanilla draws one randomly sized quad per 21x21 column around the player,
 * spanning from the ground to ten blocks above the camera, and scrolls the
 * sheet with a per-column texture-V offset that is driven by the world tick.
 * The old port used a fixed quad and clamped texture sampling, so the rain
 * stood still; the sheets are now flagged GL_REPEAT and the offsets follow the
 * vanilla formula.</p>
 */
public final class WeatherPass {
    private static final ResourceLocation RAIN = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");
    /** Reused across frames: two fresh 57 KB buffers per frame fed the GC. */
    private static final MeshBuffer RAIN_BUFFER = new MeshBuffer();
    private static final MeshBuffer SNOW_BUFFER = new MeshBuffer();
    private static int lastQuads;

    private WeatherPass() {
    }

    public static void render(Minecraft mc, float partialTick, IrisGlTexture lightmap) {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        try {
            render0(mc, level, partialTick, lightmap);
        } catch (Throwable t) {
            // Weather is cosmetic; never let it break the frame.
        }
    }

    private static void render0(Minecraft mc, ClientLevel level, float partialTick, IrisGlTexture lightmap) {
        float rain = level.getRainLevel(partialTick);
        if (rain <= 0.01F) {
            return;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        int centerX = Mth.floor(eye.x);
        int centerY = Mth.floor(eye.y);
        int centerZ = Mth.floor(eye.z);
        int radius = Minecraft.useFancyGraphics() ? 10 : 5;
        int ticks = (int) (level.getGameTime() & 131071L);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        MeshBuffer rainBuffer = RAIN_BUFFER;
        MeshBuffer snowBuffer = SNOW_BUFFER;
        rainBuffer.clear();
        snowBuffer.clear();
        for (int dz = centerZ - radius; dz <= centerZ + radius; dz++) {
            for (int dx = centerX - radius; dx <= centerX + radius; dx++) {
                pos.set(dx, centerY, dz);
                Biome biome = level.getBiome(pos).value();
                if (!biome.hasPrecipitation()) {
                    continue;
                }
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, dx, dz);
                int bottomY = Math.max(centerY - radius, ground);
                int topY = Math.max(centerY + radius, ground);
                if (bottomY == topY) {
                    continue;
                }
                pos.set(dx, Math.max(ground, centerY), dz);
                Biome.Precipitation precipitation = biome.getPrecipitationAt(pos);
                if (precipitation == Biome.Precipitation.NONE) {
                    continue;
                }
                long seed = (long) (dx * dx * 3121 + dx * 45238971 ^ dz * dz * 418711 + dz * 13761);
                RandomSource random = RandomSource.create(seed);
                float sizeX = random.nextFloat();
                float sizeZ = random.nextFloat();
                double d0 = sizeX * 0.5;
                double d1 = sizeZ * 0.5;
                float ex = (float) (dx + 0.5 - eye.x);
                float ez = (float) (dz + 0.5 - eye.z);
                float yBottom = (float) (bottomY - eye.y);
                float yTop = (float) (topY - eye.y);
                float fade = Mth.sqrt(ex * ex + ez * ez) / (float) radius;
                if (fade >= 1.0F) {
                    continue;
                }
                pos.set(dx, Math.max(ground, centerY), dz);
                int light = LevelRenderer.getLightColor(level, pos);
                if (precipitation == Biome.Precipitation.RAIN) {
                    int hash = (dx * dx * 3121 + dx * 45238971 + dz * dz * 418711 + dz * 13761) & 0xFF;
                    float speed = 3.0F + random.nextFloat();
                    float scroll = -((float) (ticks + hash) + partialTick) / 32.0F * speed;
                    float vOffset = scroll % 32.0F;
                    float alpha = ((1.0F - fade * fade) * 0.5F + 0.5F) * rain;
                    float vBottom = bottomY * 0.25F + vOffset;
                    float vTop = topY * 0.25F + vOffset;
                    vertex(rainBuffer, ex - (float) d0, yBottom, ez - (float) d1, 0.0F, vBottom, alpha, light);
                    vertex(rainBuffer, ex + (float) d0, yBottom, ez + (float) d1, 1.0F, vBottom, alpha, light);
                    vertex(rainBuffer, ex + (float) d0, yTop, ez + (float) d1, 1.0F, vTop, alpha, light);
                    vertex(rainBuffer, ex - (float) d0, yTop, ez - (float) d1, 0.0F, vTop, alpha, light);
                } else {
                    float scroll = -((float) (ticks & 511) + partialTick) / 512.0F;
                    float time = (float) level.getGameTime() + partialTick;
                    float uOffset = (float) (random.nextDouble() + (double) time * 0.01 * (double) random.nextGaussian());
                    float vExtra = (float) (random.nextDouble() + (double) (time * (float) random.nextGaussian()) * 0.001);
                    float alpha = ((1.0F - fade * fade) * 0.3F + 0.5F) * rain;
                    float vBottom = bottomY * 0.25F + scroll + vExtra;
                    float vTop = topY * 0.25F + scroll + vExtra;
                    // vanilla brightens snow towards full block light
                    int block = (light >> 16 & 65535) * 3 + 240;
                    int sky = (light & 65535) * 3 + 240;
                    int snowLight = (block / 4) << 16 | sky / 4;
                    vertex(snowBuffer, ex - (float) d0, yTop, ez - (float) d1, uOffset, vBottom, alpha, snowLight);
                    vertex(snowBuffer, ex + (float) d0, yTop, ez + (float) d1, 1.0F + uOffset, vBottom, alpha, snowLight);
                    vertex(snowBuffer, ex + (float) d0, yBottom, ez + (float) d1, 1.0F + uOffset, vTop, alpha, snowLight);
                    vertex(snowBuffer, ex - (float) d0, yBottom, ez - (float) d1, uOffset, vTop, alpha, snowLight);
                }
            }
        }
        lastQuads = rainBuffer.quadCount() + snowBuffer.quadCount();
        draw(mc, rainBuffer, RAIN, lightmap);
        draw(mc, snowBuffer, SNOW, lightmap);
    }

    private static void draw(Minecraft mc, MeshBuffer buffer, ResourceLocation texture, IrisGlTexture lightmap) {
        buffer.finish();
        if (buffer.isEmpty()) {
            return;
        }
        IrisGlTexture tex = IrisGlTextures.texture(texture);
        if (tex == null) {
            return;
        }
        IrisGl.zbuffer(true);
        IrisGl.ctx().depthWrite = false;
        IrisGl.fog(false);
        IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
        IrisGl.alphatest(true, 0.02F);
        buffer.draw(tex, lightmap);
        IrisGl.blendmode(false);
        IrisGl.alphatest(false, 0.0F);
        IrisGl.ctx().depthWrite = true;
    }

    private static void vertex(MeshBuffer buffer, float x, float y, float z, float u, float v, float alpha, int light) {
        buffer.addVertex(x, y, z)
                .setUv(u, v)
                .setColor(1.0F, 1.0F, 1.0F, alpha)
                .setLight(light);
    }

    public static int lastQuads() {
        return lastQuads;
    }
}

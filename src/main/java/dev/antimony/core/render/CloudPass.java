package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla's cloud layer, reimplemented for the software rasterizer.
 *
 * <p>{@code LevelRenderer.renderClouds} builds a 96x96 block cloud sheet from
 * {@code textures/environment/clouds.png} and scrolls it with the world time,
 * drawing it twice for the fancy mode (depth-only pre-pass, then the coloured
 * pass). Replacing the frame skipped all of that, which is why the sky had no
 * clouds at all. This class ports {@code LevelRenderer.buildClouds} one to one
 * so the cloud shape, drift and face shading match vanilla.</p>
 */
public final class CloudPass {
    private static final ResourceLocation CLOUDS = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private static final float UV_SCALE = 0.00390625F;
    /** Reused: clouds rebuild when the cell/colour changes, not every frame. */
    private static final MeshBuffer BUFFER = new MeshBuffer();
    private static int prevI = Integer.MIN_VALUE;
    private static int prevJ = Integer.MIN_VALUE;
    private static int prevK = Integer.MIN_VALUE;
    private static CloudStatus prevStatus;
    private static Vec3 prevColor = Vec3.ZERO;
    private static int lastQuads;

    private CloudPass() {
    }

    public static void render(Minecraft mc, ClientLevel level, float partialTick,
                              double eyeX, double eyeY, double eyeZ) {
        if (level == null || mc.options.getCloudsType() == CloudStatus.OFF) {
            return;
        }
        try {
            render0(mc, level, partialTick, eyeX, eyeY, eyeZ);
        } catch (Throwable t) {
            // clouds are cosmetic, never break the frame
        }
    }

    private static void render0(Minecraft mc, ClientLevel level, float partialTick,
                                double eyeX, double eyeY, double eyeZ) {
        float cloudHeight = level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) {
            return;
        }
        double scroll = ((float) level.getGameTime() + partialTick) * 0.03F;
        double d2 = (eyeX + scroll) / 12.0;
        double d3 = cloudHeight - eyeY + 0.33F;
        double d4 = eyeZ / 12.0 + 0.33F;
        d2 -= (double) (Mth.floor(d2 / 2048.0) * 2048);
        d4 -= (double) (Mth.floor(d4 / 2048.0) * 2048);
        float f3 = (float) (d2 - (double) Mth.floor(d2));
        float f4 = (float) (d3 / 4.0 - (double) Mth.floor(d3 / 4.0)) * 4.0F;
        float f5 = (float) (d4 - (double) Mth.floor(d4));
        Vec3 color = level.getCloudColor(partialTick);
        int i = Mth.floor(d2);
        int j = Mth.floor(d3 / 4.0);
        int k = Mth.floor(d4);
        CloudStatus status = mc.options.getCloudsType();

        if (i != prevI || j != prevJ || k != prevK || status != prevStatus
                || prevColor.distanceToSqr(color) > 2.0E-4) {
            prevI = i;
            prevJ = j;
            prevK = k;
            prevStatus = status;
            prevColor = color;
            build(d2, d3, d4, color, status);
        }
        if (BUFFER.isEmpty()) {
            return;
        }
        IrisGlTexture tex = IrisGlTextures.texture(CLOUDS);
        if (tex == null) {
            return;
        }
        lastQuads = BUFFER.quadCount();

        IrisGl.pushmatrix();
        IrisGl.translate(-f3 * 12.0F, f4, -f5 * 12.0F);
        IrisGl.scale(12.0F, 1.0F, 12.0F);

        IrisGl.zbuffer(true);
        IrisGl.fog(false);
        IrisGl.alphatest(true, 0.1F);
        IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
        IrisGl.lightmap(null);
        if (status == CloudStatus.FANCY) {
            // vanilla: cloudsDepthOnly() then clouds() - the depth pre-pass
            // stops the translucent sides from blending over the top faces.
            IrisGl.colorwrite(false);
            BUFFER.draw(tex, null);
            IrisGl.colorwrite(true);
        }
        BUFFER.draw(tex, null);
        IrisGl.popmatrix();

        IrisGl.ctx().blend = false;
        IrisGl.alphatest(false, 0.0F);
        IrisGl.colorwrite(true);
    }

    private static void build(double d2, double d3, double d4, Vec3 color, CloudStatus status) {
        BUFFER.clear();
        float f3 = (float) Mth.floor(d2) * UV_SCALE;
        float f4 = (float) Mth.floor(d4) * UV_SCALE;
        float cr = (float) color.x;
        float cg = (float) color.y;
        float cb = (float) color.z;
        float bottomR = cr * 0.7F;
        float bottomG = cg * 0.7F;
        float bottomB = cb * 0.7F;
        float sideR = cr * 0.9F;
        float sideG = cg * 0.9F;
        float sideB = cb * 0.9F;
        float northR = cr * 0.8F;
        float northG = cg * 0.8F;
        float northB = cb * 0.8F;
        float f17 = (float) Mth.floor(d3 / 4.0) * 4.0F;
        if (status == CloudStatus.FANCY) {
            for (int k = -3; k <= 4; k++) {
                for (int l = -3; l <= 4; l++) {
                    float f18 = k * 8.0F;
                    float f19 = l * 8.0F;
                    if (f17 > -5.0F) {
                        vertex(f18, f17, f19 + 8.0F, (f18) * UV_SCALE + f3, (f19 + 8.0F) * UV_SCALE + f4, bottomR, bottomG, bottomB, 0.0F, -1.0F, 0.0F);
                        vertex(f18 + 8.0F, f17, f19 + 8.0F, (f18 + 8.0F) * UV_SCALE + f3, (f19 + 8.0F) * UV_SCALE + f4, bottomR, bottomG, bottomB, 0.0F, -1.0F, 0.0F);
                        vertex(f18 + 8.0F, f17, f19, (f18 + 8.0F) * UV_SCALE + f3, (f19) * UV_SCALE + f4, bottomR, bottomG, bottomB, 0.0F, -1.0F, 0.0F);
                        vertex(f18, f17, f19, (f18) * UV_SCALE + f3, (f19) * UV_SCALE + f4, bottomR, bottomG, bottomB, 0.0F, -1.0F, 0.0F);
                    }
                    if (f17 <= 5.0F) {
                        vertex(f18, f17 + 4.0F - 9.765625E-4F, f19 + 8.0F, (f18) * UV_SCALE + f3, (f19 + 8.0F) * UV_SCALE + f4, cr, cg, cb, 0.0F, 1.0F, 0.0F);
                        vertex(f18 + 8.0F, f17 + 4.0F - 9.765625E-4F, f19 + 8.0F, (f18 + 8.0F) * UV_SCALE + f3, (f19 + 8.0F) * UV_SCALE + f4, cr, cg, cb, 0.0F, 1.0F, 0.0F);
                        vertex(f18 + 8.0F, f17 + 4.0F - 9.765625E-4F, f19, (f18 + 8.0F) * UV_SCALE + f3, (f19) * UV_SCALE + f4, cr, cg, cb, 0.0F, 1.0F, 0.0F);
                        vertex(f18, f17 + 4.0F - 9.765625E-4F, f19, (f18) * UV_SCALE + f3, (f19) * UV_SCALE + f4, cr, cg, cb, 0.0F, 1.0F, 0.0F);
                    }
                    if (k > -1) {
                        for (int i1 = 0; i1 < 8; i1++) {
                            float u = (f18 + i1 + 0.5F) * UV_SCALE + f3;
                            vertex(f18 + i1, f17, f19 + 8.0F, u, (f19 + 8.0F) * UV_SCALE + f4, sideR, sideG, sideB, -1.0F, 0.0F, 0.0F);
                            vertex(f18 + i1, f17 + 4.0F, f19 + 8.0F, u, (f19 + 8.0F) * UV_SCALE + f4, sideR, sideG, sideB, -1.0F, 0.0F, 0.0F);
                            vertex(f18 + i1, f17 + 4.0F, f19, u, (f19) * UV_SCALE + f4, sideR, sideG, sideB, -1.0F, 0.0F, 0.0F);
                            vertex(f18 + i1, f17, f19, u, (f19) * UV_SCALE + f4, sideR, sideG, sideB, -1.0F, 0.0F, 0.0F);
                        }
                    }
                    if (k <= 1) {
                        for (int j2 = 0; j2 < 8; j2++) {
                            float u = (f18 + j2 + 0.5F) * UV_SCALE + f3;
                            float x = f18 + j2 + 1.0F - 9.765625E-4F;
                            vertex(x, f17, f19 + 8.0F, u, (f19 + 8.0F) * UV_SCALE + f4, sideR, sideG, sideB, 1.0F, 0.0F, 0.0F);
                            vertex(x, f17 + 4.0F, f19 + 8.0F, u, (f19 + 8.0F) * UV_SCALE + f4, sideR, sideG, sideB, 1.0F, 0.0F, 0.0F);
                            vertex(x, f17 + 4.0F, f19, u, (f19) * UV_SCALE + f4, sideR, sideG, sideB, 1.0F, 0.0F, 0.0F);
                            vertex(x, f17, f19, u, (f19) * UV_SCALE + f4, sideR, sideG, sideB, 1.0F, 0.0F, 0.0F);
                        }
                    }
                    if (l > -1) {
                        for (int k2 = 0; k2 < 8; k2++) {
                            float v = (f19 + k2 + 0.5F) * UV_SCALE + f4;
                            vertex(f18, f17 + 4.0F, f19 + k2, (f18) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, -1.0F);
                            vertex(f18 + 8.0F, f17 + 4.0F, f19 + k2, (f18 + 8.0F) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, -1.0F);
                            vertex(f18 + 8.0F, f17, f19 + k2, (f18 + 8.0F) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, -1.0F);
                            vertex(f18, f17, f19 + k2, (f18) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, -1.0F);
                        }
                    }
                    if (l <= 1) {
                        for (int l2 = 0; l2 < 8; l2++) {
                            float v = (f19 + l2 + 0.5F) * UV_SCALE + f4;
                            float z = f19 + l2 + 1.0F - 9.765625E-4F;
                            vertex(f18, f17 + 4.0F, z, (f18) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, 1.0F);
                            vertex(f18 + 8.0F, f17 + 4.0F, z, (f18 + 8.0F) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, 1.0F);
                            vertex(f18 + 8.0F, f17, z, (f18 + 8.0F) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, 1.0F);
                            vertex(f18, f17, z, (f18) * UV_SCALE + f3, v, northR, northG, northB, 0.0F, 0.0F, 1.0F);
                        }
                    }
                }
            }
        } else {
            for (int l1 = -32; l1 < 32; l1 += 32) {
                for (int i2 = -32; i2 < 32; i2 += 32) {
                    vertex(l1, f17, i2 + 32, l1 * UV_SCALE + f3, (i2 + 32) * UV_SCALE + f4, cr, cg, cb, 0.0F, -1.0F, 0.0F);
                    vertex(l1 + 32, f17, i2 + 32, (l1 + 32) * UV_SCALE + f3, (i2 + 32) * UV_SCALE + f4, cr, cg, cb, 0.0F, -1.0F, 0.0F);
                    vertex(l1 + 32, f17, i2, (l1 + 32) * UV_SCALE + f3, i2 * UV_SCALE + f4, cr, cg, cb, 0.0F, -1.0F, 0.0F);
                    vertex(l1, f17, i2, l1 * UV_SCALE + f3, i2 * UV_SCALE + f4, cr, cg, cb, 0.0F, -1.0F, 0.0F);
                }
            }
        }
        BUFFER.finish();
    }

    private static void vertex(float x, float y, float z, float u, float v,
                               float r, float g, float b, float nx, float ny, float nz) {
        BUFFER.addVertex(x, y, z)
                .setUv(u, v)
                .setColor(r, g, b, 0.8F)
                .setNormal(nx, ny, nz);
    }

    public static int lastQuads() {
        return lastQuads;
    }
}

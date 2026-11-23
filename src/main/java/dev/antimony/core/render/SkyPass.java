package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/**
 * Vanilla's NORMAL sky (LevelRenderer.renderSky) for the software rasterizer:
 * the light sky disc, the sunrise/sunset glow, the sun, the moon with its
 * phases, 1500 seeded stars and the void disc below the horizon - in vanilla's
 * order, with vanilla's matrices and vanilla's blend functions.
 *
 * <p>Everything is authored in <b>world-axis-aligned camera-relative space</b>
 * and goes through the normal camera view matrix, exactly like vanilla's
 * PoseStack (which is pushed with the camera rotation and then rotated by the
 * per-body transforms). Authoring the sky in plain camera space instead - which
 * a previous version did by replacing the view with an identity matrix - nailed
 * the whole sky to the screen: the disc, the stars and the sun/moon never moved
 * when the player looked around, and the sun/moon could not be seen at all
 * because they ended up behind/above the camera while the disc stayed
 * horizontal in view space.</p>
 */
public final class SkyPass {
    private static final ResourceLocation SUN =
            ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON =
            ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    /** Star quad corners (x,y,z * 4 per quad), mirroring LevelRenderer.drawStars. */
    private static float[] stars;
    /**
     * B2: 太阳在相机空间的方向（单位向量）——就是屏幕上那个太阳圆盘的方向。
     * 阴影贴图用它当光轴，保证"影子背对看得见的太阳"。
     */
    public static final Vector3f SUN_CAMERA = new Vector3f(0.0F, 1.0F, 0.0F);
    /** -Dantimony.debug.sky: log the sky body state and screen positions. */
    private static final boolean DEBUG_SKY = dev.antimony.AntimonyMod.flag("antimony.debug.sky");

    private SkyPass() {
    }

    /** One-time star field, identical to vanilla's seeded generator. */
    private static void buildStars() {
        if (stars != null) {
            return;
        }
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(10842L);
        java.util.ArrayList<float[]> quads = new java.util.ArrayList<>();
        for (int j = 0; j < 1500; j++) {
            float f1 = random.nextFloat() * 2.0F - 1.0F;
            float f2 = random.nextFloat() * 2.0F - 1.0F;
            float f3 = random.nextFloat() * 2.0F - 1.0F;
            float f4 = 0.15F + random.nextFloat() * 0.1F;
            float lenSq = Mth.lengthSquared(f1, f2, f3);
            if (!(lenSq <= 0.010000001F) && !(lenSq >= 1.0F)) {
                Vector3f dir = new Vector3f(f1, f2, f3).normalize(100.0F);
                float angle = (float) (random.nextDouble() * (float) Math.PI * 2.0);
                Quaternionf q = new Quaternionf().rotateTo(new Vector3f(0.0F, 0.0F, -1.0F), dir).rotateZ(angle);
                Vector3f a = new Vector3f(dir).add(new Vector3f(f4, -f4, 0.0F).rotate(q));
                Vector3f b = new Vector3f(dir).add(new Vector3f(f4, f4, 0.0F).rotate(q));
                Vector3f c = new Vector3f(dir).add(new Vector3f(-f4, f4, 0.0F).rotate(q));
                Vector3f d = new Vector3f(dir).add(new Vector3f(-f4, -f4, 0.0F).rotate(q));
                quads.add(new float[]{a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, d.x, d.y, d.z});
            }
        }
        stars = new float[quads.size() * 12];
        int i = 0;
        for (float[] q : quads) {
            System.arraycopy(q, 0, stars, i, 12);
            i += 12;
        }
    }

    /**
     * Draws the sky body stack. Call after the projection/view/fog setup and
     * before any world geometry, with the camera rotation in ctx.view.
     *
     * @param fogRgb    vanilla's fog (= clear = horizon) colour, 0xRRGGBB
     * @param skyFogEnd the {@code FogMode.FOG_SKY} end distance:
     *                  {@code renderDistance * 16}
     * @param thickFog  vanilla's "foggy at" / boss-overlay fog flag
     */
    public static void render(ClientLevel level, float partialTick, int fogRgb, float skyFogEnd, boolean thickFog) {
        if (level == null || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        buildStars();

        IrisGlContext ctx = IrisGl.ctx();
        boolean savedDepthWrite = ctx.depthWrite;
        boolean savedCull = ctx.cull;
        boolean savedFog = ctx.fogEnabled;
        boolean savedFogShape = ctx.fogCylindrical;
        int savedFogColor = ctx.fogColor;
        float savedFogStart = ctx.fogStart;
        float savedFogEnd = ctx.fogEnd;
        boolean savedLightmap = ctx.lightmapEnabled;
        IrisGlTexture savedLightmapTex = ctx.lightmap;
        ctx.depthWrite = false;
        ctx.cull = false;
        ctx.lightmapEnabled = false;
        IrisGl.texture(false);
        IrisGl.tint(1.0F, 1.0F, 1.0F, 1.0F);

        Vec3 sky = level.getSkyColor(IrisGlRenderer.lastCameraPos(), partialTick);
        float timeOfDay = level.getTimeOfDay(partialTick);
        float rain = level.getRainLevel(partialTick);
        float visible = 1.0F - rain;

        // Vanilla's FogMode.FOG_SKY: start 0, end = render distance, CYLINDER,
        // fog colour = the level fog colour. The disc is a plain POSITION mesh
        // coloured by ColorModulator and drawn with the "position" shader,
        // whose whole look is fog: 16 blocks away at the zenith (no fog) down
        // to 512 at the rim (fully fogged). That is what makes the sky meet the
        // fogged terrain without a seam.
        float fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        float fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        IrisGl.fog(true);
        IrisGl.fogshape(com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape()
                == com.mojang.blaze3d.shaders.FogShape.CYLINDER);
        IrisGl.fogcolor(fogRgb);
        IrisGl.fogrange(fogStart, fogEnd);

        // 1) Light sky disc (no blend), ColorModulator = level sky colour.
        // Vanilla: TRIANGLE_FAN, centre (0,+16,0), rim radius 512.
        IrisGl.blendmode(false);
        IrisGl.c3f((float) sky.x, (float) sky.y, (float) sky.z);
        discFan(16.0F);

        // 2) Sunrise/sunset glow around the sun's horizon bearing. Vanilla
        // rotates X +90, then Z by 0/180 (sun below the horizon?) and Z +90,
        // and draws it with position_color (fogged, vertex alpha as the fade).
        float[] glow = level.effects().getSunriseColor(timeOfDay, partialTick);
        if (glow != null) {
            IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
            float f3 = Mth.sin(level.getSunAngle(partialTick)) < 0.0F ? 180.0F : 0.0F;
            IrisGl.pushmatrix();
            IrisGl.rot(90.0F, 'x');
            IrisGl.rot(f3, 'z');
            IrisGl.rot(90.0F, 'z');
            IrisGl.bgnpolygon();
            IrisGl.c4f(glow[0], glow[1], glow[2], glow[3]);
            IrisGl.v3f(0.0F, 100.0F, 0.0F);
            for (int j = 0; j <= 16; j++) {
                float t = j * (float) (Math.PI * 2) / 16.0F;
                IrisGl.c4f(glow[0], glow[1], glow[2], 0.0F);
                IrisGl.v3f(Mth.sin(t) * 120.0F, Mth.cos(t) * 120.0F, -Mth.cos(t) * 40.0F * glow[3]);
            }
            IrisGl.endpolygon();
            IrisGl.popmatrix();
        }

        // 3) Sun + moon: Y -90 then X by the day time, additive-ish blend
        // (SRC_ALPHA, ONE / ONE, ZERO). Vanilla's position_tex shader applies
        // no fog, so fog is switched off here.
        IrisGl.fog(false);
        IrisGl.blendfuncseparate(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE,
                IrisGlContext.BF_ONE, IrisGlContext.BF_ZERO);
        IrisGl.pushmatrix();
        IrisGl.rot(-90.0F, 'y');
        IrisGl.rot(timeOfDay * 360.0F, 'x');
        // B2: 太阳在相机空间的方向（世界轴坐标 → 相机旋转后）。阴影贴图拿它当
        // 光轴，否则影子朝向会和屏幕上看得见的太阳圆盘对不上。
        SUN_CAMERA.set(0.0F, 100.0F, 0.0F);
        IrisGl.ctx().model.transformPosition(SUN_CAMERA);
        IrisGl.ctx().view.transformPosition(SUN_CAMERA);
        if (SUN_CAMERA.lengthSquared() > 1.0e-6F) {
            SUN_CAMERA.normalize();
        }

        IrisGlTexture sunTex = IrisGlTextures.texture(SUN);
        if (sunTex != null) {
            IrisGl.texbind(sunTex);
            IrisGl.c4f(1.0F, 1.0F, 1.0F, visible);
            IrisGl.bgnpolygon();
            IrisGl.t2f(0.0F, 0.0F);
            IrisGl.v3f(-30.0F, 100.0F, -30.0F);
            IrisGl.t2f(1.0F, 0.0F);
            IrisGl.v3f(30.0F, 100.0F, -30.0F);
            IrisGl.t2f(1.0F, 1.0F);
            IrisGl.v3f(30.0F, 100.0F, 30.0F);
            IrisGl.t2f(0.0F, 1.0F);
            IrisGl.v3f(-30.0F, 100.0F, 30.0F);
            IrisGl.endpolygon();
        }
        IrisGlTexture moonTex = IrisGlTextures.texture(MOON);
        if (moonTex != null) {
            IrisGl.texbind(moonTex);
            int phase = level.getMoonPhase();
            int l = phase % 4;
            int i1 = phase / 4 % 2;
            float u0 = l / 4.0F;
            float v0 = i1 / 2.0F;
            float u1 = (l + 1) / 4.0F;
            float v1 = (i1 + 1) / 2.0F;
            IrisGl.c4f(1.0F, 1.0F, 1.0F, visible);
            // Vanilla's corner/UV order: the moon sheet is sampled right to
            // left so the lit side faces the sun.
            IrisGl.bgnpolygon();
            IrisGl.t2f(u1, v1);
            IrisGl.v3f(-20.0F, -100.0F, 20.0F);
            IrisGl.t2f(u0, v1);
            IrisGl.v3f(20.0F, -100.0F, 20.0F);
            IrisGl.t2f(u0, v0);
            IrisGl.v3f(20.0F, -100.0F, -20.0F);
            IrisGl.t2f(u1, v0);
            IrisGl.v3f(-20.0F, -100.0F, -20.0F);
            IrisGl.endpolygon();
        }
        IrisGl.texture(false);
        IrisGl.popmatrix();

        // 4) Stars: seeded quads in absolute directions, brightness 0..0.5 by
        // time of night. Vanilla calls setupNoFog() before them.
        float starB = level.getStarBrightness(partialTick) * visible;
        if (starB > 0.0F) {
            IrisGl.c4f(starB, starB, starB, starB);
            for (int i = 0; i < stars.length; i += 12) {
                IrisGl.bgnpolygon();
                for (int k = 0; k < 12; k += 3) {
                    IrisGl.v3f(stars[i + k], stars[i + k + 1], stars[i + k + 2]);
                }
                IrisGl.endpolygon();
            }
        }

        // 5) Void disc: below the horizon height the lower hemisphere gets a
        // black disc at y=-4 (vanilla's darkBuffer, a -16 fan translated up 12).
        // It is drawn with the same fogged "position" shader and a black
        // ColorModulator, so what is actually visible is the fog colour 512
        // blocks away - which FogColor already darkened by the void scale.
        IrisGl.blendmode(false);
        IrisGl.c3f(0.0F, 0.0F, 0.0F);
        IrisGl.fog(true);
        IrisGl.fogshape(true);
        IrisGl.fogcolor(fogRgb);
        IrisGl.fogrange(fogStart, fogEnd);
        double horizon = level.getLevelData().getHorizonHeight(level);
        double eyeY = IrisGlRenderer.lastCameraPos().y;
        if (eyeY < horizon) {
            IrisGl.pushmatrix();
            IrisGl.translate(0.0F, 12.0F, 0.0F);
            discFan(-16.0F);
            IrisGl.popmatrix();
        }

        if (DEBUG_SKY) {
            long frame = IrisGlRenderer.frameCount();
            if (frame % 20 == 0) {
                org.joml.Matrix4f mvp = new Matrix4f(ctx.projection).mul(ctx.view);
                antimony$skyLog(level, partialTick, mvp, sunTex, moonTex, starB);
            }
        }

        ctx.depthWrite = savedDepthWrite;
        ctx.cull = savedCull;
        ctx.fogEnabled = savedFog;
        ctx.fogCylindrical = savedFogShape;
        ctx.fogColor = savedFogColor;
        ctx.fogStart = savedFogStart;
        ctx.fogEnd = savedFogEnd;
        ctx.lightmapEnabled = savedLightmap;
        ctx.lightmap = savedLightmapTex;
        IrisGl.c3f(1.0F, 1.0F, 1.0F);
    }

    private static void antimony$skyLog(ClientLevel level, float partialTick, Matrix4f mvp,
                                 IrisGlTexture sunTex, IrisGlTexture moonTex, float starB) {
        String sun = sunTex == null ? "MISSING" : sunTex.width + "x" + sunTex.height;
        String moon = moonTex == null ? "MISSING" : moonTex.width + "x" + moonTex.height;
        dev.antimony.AntimonyMod.LOG.info(
                "[Antimony] sky frame {}: timeOfDay={} rain={} starB={} sunTex={} moonTex={} sun@{} moon@{} viewport={}x{}",
                IrisGlRenderer.frameCount(),
                String.format("%.3f", level.getTimeOfDay(partialTick)),
                String.format("%.2f", level.getRainLevel(partialTick)),
                String.format("%.3f", starB), sun, moon,
                uvScreen(mvp, -90.0F, level.getTimeOfDay(partialTick), 0.0F, 100.0F, 0.0F),
                uvScreen(mvp, -90.0F, level.getTimeOfDay(partialTick), 0.0F, -100.0F, 0.0F),
                IrisGl.ctx().viewportW, IrisGl.ctx().viewportH);
    }

    /** Screen position of a point after Y -90 / X time, for -Dantimony.debug.sky. */    private static String uvScreen(Matrix4f mvp, float yaw, float timeOfDay, float x, float y, float z) {
        Matrix4f body = new Matrix4f()
                .rotate((float) Math.toRadians(yaw), 0.0F, 1.0F, 0.0F)
                .rotate((float) Math.toRadians(timeOfDay * 360.0F), 1.0F, 0.0F, 0.0F);
        Vector3f p = body.transformPosition(new Vector3f(x, y, z));
        org.joml.Vector4f clip = mvp.transform(new org.joml.Vector4f(p.x, p.y, p.z, 1.0F));
        if (clip.w <= 0.0F) {
            return "behind";
        }
        IrisGlContext ctx = IrisGl.ctx();
        float sx = ctx.viewportX + (clip.x / clip.w * 0.5F + 0.5F) * ctx.viewportW;
        float sy = ctx.viewportY + (1.0F - (clip.y / clip.w * 0.5F + 0.5F)) * ctx.viewportH;
        return String.format("%.0f,%.0f", sx, sy);
    }

    /** TRIANGLE_FAN disc of radius 512 at the given camera-relative height. */
    private static void discFan(float y) {
        float prevX = 512.0F * Mth.cos(-180.0F * (float) (Math.PI / 180.0));
        float prevZ = 512.0F * Mth.sin(-180.0F * (float) (Math.PI / 180.0));
        for (int i = -180 + 45; i <= 180; i += 45) {
            float x = 512.0F * Mth.cos(i * (float) (Math.PI / 180.0));
            float z = 512.0F * Mth.sin(i * (float) (Math.PI / 180.0));
            IrisGl.bgnpolygon();
            IrisGl.v3f(0.0F, y, 0.0F);
            IrisGl.v3f(prevX, y, prevZ);
            IrisGl.v3f(x, y, z);
            IrisGl.endpolygon();
            prevX = x;
            prevZ = z;
        }
    }
}

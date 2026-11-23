package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * Replays every live particle through vanilla's own particle renderer, but the
 * vertices land in an IRIS GL mesh buffer instead of a GPU buffer.
 *
 * <p>The particle engine keeps one queue per {@link ParticleRenderType}; the
 * map is private, so it is read reflectively once and cached.</p>
 */
public final class ParticlePass {
    private static Field particlesField;
    private static boolean lookupFailed;
    /** Reused per render type: allocating a 200 KB buffer per type per frame was
     *  a major source of the garbage-collection stutter. */
    private static final Map<ParticleRenderType, MeshBuffer> BUFFERS = new IdentityHashMap<>();
    private static int lastCount;
    private static long lastLog;

    private ParticlePass() {
    }

    @SuppressWarnings("unchecked")
    private static Map<ParticleRenderType, Queue<Particle>> particles(ParticleEngine engine) {
        if (lookupFailed) {
            return null;
        }
        try {
            if (particlesField == null) {
                particlesField = ParticleEngine.class.getDeclaredField("particles");
                particlesField.setAccessible(true);
            }
            return (Map<ParticleRenderType, Queue<Particle>>) particlesField.get(engine);
        } catch (Throwable t) {
            lookupFailed = true;
            return null;
        }
    }

    public static void render(Minecraft mc, float partialTick, IrisGlTexture lightmap) {
        ParticleEngine engine = mc.particleEngine;
        if (engine == null) {
            return;
        }
        Map<ParticleRenderType, Queue<Particle>> queues = particles(engine);
        if (queues == null || queues.isEmpty()) {
            return;
        }
        var camera = mc.gameRenderer.getMainCamera();
        IrisGlTexture particleAtlas = null;
        boolean atlasLoaded = false;
        int objects = 0;
        // Pass 0: opaque sheets, pass 1: alpha blended sheets.
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<ParticleRenderType, Queue<Particle>> entry : queues.entrySet()) {
                ParticleRenderType type = entry.getKey();
                if (type == ParticleRenderType.NO_RENDER || type == ParticleRenderType.CUSTOM) {
                    continue;
                }
                boolean translucent = type == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                        || type == ParticleRenderType.TERRAIN_SHEET;
                if ((pass == 1) != translucent) {
                    continue;
                }
                Queue<Particle> queue = entry.getValue();
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                IrisGlTexture texture;
                if (type == ParticleRenderType.TERRAIN_SHEET) {
                    texture = IrisGlTextures.blockAtlas();
                } else {
                    if (!atlasLoaded) {
                        particleAtlas = IrisGlTextures.texture(TextureAtlas.LOCATION_PARTICLES);
                        atlasLoaded = true;
                    }
                    texture = particleAtlas;
                }
                if (texture == null) {
                    continue;
                }
                MeshBuffer buffer = BUFFERS.computeIfAbsent(type, t -> new MeshBuffer());
                buffer.clear();
                for (Particle particle : queue) {
                    try {
                        particle.render(buffer, camera, partialTick);
                    } catch (Throwable ignored) {
                        // A single broken particle must not kill the frame.
                    }
                }
                buffer.finish();
                if (buffer.isEmpty()) {
                    continue;
                }
                objects += buffer.quadCount();
                IrisGl.zbuffer(true);
                IrisGl.fog(false);
                if (translucent) {
                    IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
                    IrisGl.alphatest(true, 0.02F);
                    IrisGl.ctx().depthWrite = false;
                } else {
                    IrisGl.blendmode(false);
                    IrisGl.alphatest(true, 0.1F);
                }
                buffer.draw(texture, lightmap);
            }
        }
        IrisGl.blendmode(false);
        IrisGl.alphatest(false, 0.0F);
        IrisGl.ctx().depthWrite = true;
        if (objects > 0) {
            lastCount = objects;
        }
        long now = System.currentTimeMillis();
        if (now - lastLog > 5000L && dev.antimony.AntimonyMod.LOG.isDebugEnabled()) {
            lastLog = now;
            dev.antimony.AntimonyMod.LOG.debug("[Antimony] particles: {} quads in {} render types", objects, queues.size());
        }
    }

    public static int lastCount() {
        return lastCount;
    }
}

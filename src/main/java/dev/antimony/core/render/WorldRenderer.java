package dev.antimony.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGl;
import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Terrain + sky pass. Chunk sections are tessellated by vanilla's block model
 * renderer on background workers ({@link MeshBuildPool}) and replayed through
 * IRIS GL immediate mode.
 */
public final class WorldRenderer {
    /** Current view radius in chunks, refreshed from the video settings each frame. */
    private int renderDistance = 4;
    /** Smooth-lighting (AO) watch: changing it must invalidate every mesh. */
    private boolean aoWatched;
    private boolean lastAo;
    /** Sections submitted to the mesh pool per frame (background tessellation). */
    private static final int MAX_SUBMITS_PER_FRAME = 16;
    /** Rescans the 9x9 chunk area this often even when nothing changed. */
    private static final int RESCAN_INTERVAL = 5;

    private final Map<Long, SectionMesh> meshes = new HashMap<>();
    /** Sections that must be rebuilt next (block changed); submitted with priority. */
    private final Set<Long> forcedKeys = new HashSet<>();
    /** Sections whose latest build came back empty (skip resubmission). */
    private final Set<Long> emptyKeys = new HashSet<>();
    private final Map<Long,SectionMesh> emptyProofs = new HashMap<>();
    private final List<SectionMesh> visible = new ArrayList<>();
    /** Frustum culling: P*V of the current frame + the extractor over it. */
    private final org.joml.Matrix4f frustumMatrix = new org.joml.Matrix4f();
    private final org.joml.FrustumIntersection frustum = new org.joml.FrustumIntersection();
    /** Conservative radius of a 16-block section (half diagonal 8*sqrt(3)). */
    private static final float SECTION_CULL_RADIUS = 14.0F;
    private IrisGlTexture atlas;
    private IrisGlTexture lightmap;
    private long lastPlayerSection = Long.MIN_VALUE;
    private int totalQuads;
    private long lastStatsLog;
    private long frameCounter;
    private int meshOutstanding;
    private int frustumCulled;
    private String shadowPreparationDetail = "准备阴影区块";

    public void render(ClientLevel level, Camera camera, float partialTick, int width, int height) {
        dev.antimony.core.win32.SplashOverlay.updateStage(2, "世界场景载入");
        FogFrame.beginFrame();
        long depthGeneration = dev.antimony.gl.glsl.IrisPostChain.beginDepthFrame(level, IrisGl.framebuffer());
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        this.frameCounter++;
        // Wire the video settings that affect the software renderer: view
        // distance drives the mesh ring and fog, and toggling smooth lighting
        // (AO) changes vertex colours at tessellation time, so every mesh
        // must be rebuilt (vanilla's needsUpdate only invalidates its GPU
        // meshes, not ours).
        this.renderDistance = Mth.clamp(mc.options.getEffectiveRenderDistance(), 2, 32);
        boolean ao = mc.options.ambientOcclusion().get();
        if (!this.aoWatched) {
            this.aoWatched = true;
            this.lastAo = ao;
        } else if (ao != this.lastAo) {
            this.lastAo = ao;
            this.clearMeshes();
            AntimonyMod.LOG.info("[Antimony] smooth lighting changed to {}, rebuilding meshes", ao);
        }
        this.atlas = IrisGlTextures.blockAtlas();
        this.lightmap = IrisGlTextures.lightmap();

        var cameraEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : player;
        camera.setup(level, cameraEntity, !mc.options.getCameraType().isFirstPerson(),
                mc.options.getCameraType().isMirrored(), partialTick);
        Vec3 eye = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        boolean overview = "overview".equals(System.getProperty("antimony.debug.camera"));
        if (overview) {
            eye = new Vec3(0.5, 120.0, 0.5);
            look = new Vector3f(0.0F, -1.0F, 0.0F);
            up = new Vector3f(0.0F, 0.0F, 1.0F);
        }
        final Vec3 eyeFinal = eye;
        IrisGlRenderer.cameraPosField(eye);
        // Fog colour, clear colour and the sky disc's horizon are ONE value in
        // vanilla (FogRenderer.setupColor -> levelFogColor -> clearColor), which
        // is exactly why the sky, the fog and the terrain edge line up with no
        // seam. Ported whole, including the water/lava/powder-snow branches,
        // the void darkening and the rain/thunder/night-vision modifiers.
        boolean underwater = camera.getFluidInCamera() == FogType.WATER;
        FogColor.setup(camera, partialTick, level, mc.options.getEffectiveRenderDistance(),
                mc.gameRenderer.getDarkenWorldAmount(partialTick));
        int fogRgb = FogColor.packed();
        // Vanilla FogRenderer.setupFog: FogMode.FOG_SKY ends at
        // GameRenderer.renderDistance (effective render distance * 16 blocks),
        // FOG_TERRAIN at max(that, 32); both use FogShape.CYLINDER in air.
        // GameRenderer only assigns that field inside vanilla's renderLevel(),
        // which this mod replaces, so it must be derived here - reading it gave
        // 0, i.e. a fog end of 0 blocks and a completely fogged-out sky.
        float skyFar = this.renderDistance * 16.0F;
        float terrainFar = Math.max(skyFar, 32.0F);
        boolean thickFog = level.effects().isFoggyAt(Mth.floor(eye.x), Mth.floor(eye.y))
                || mc.gui.getBossOverlay().shouldCreateWorldFog();
        this.drawSky(fogRgb);

        float aspect = width / (float) height;
        double fov = mc.options.fov().get();
        float projectionNear = 0.05F;
        float projectionFar = this.renderDistance * 16.0F * 4.0F;
        IrisGl.perspective((float) fov, aspect, projectionNear, projectionFar);
        dev.antimony.gl.glsl.IrisPostChain.captureProjection(projectionNear, projectionFar);
        // Camera-relative vertices: the view matrix is rotation-only.
        IrisGl.lookat(0.0F, 0.0F, 0.0F, look.x, look.y, look.z, up.x, up.y, up.z);
        // Remember the rotation for passes that author geometry in
        // camera/world-direction space (first-person hand lighting), and
        // install the world entity light pair (vanilla Lighting.setupLevel).
        IrisGlRenderer.lastCameraRotation().set(IrisGl.ctx().view);
        // Frustum planes for section culling: P * V of the camera matrix pair
        // that IrisGl just built (matches JOML FrustumIntersection conventions).
        this.frustumMatrix.set(IrisGl.ctx().projection).mul(IrisGl.ctx().view);
        this.frustum.set(this.frustumMatrix);
        this.frustumCulled = 0;
        // Vanilla's sky body stack (sky disc, sunrise glow, sun, moon, stars,
        // void disc) in camera space, before any world geometry. Skipped
        // underwater: vanilla's sky is hidden behind the water fog anyway.
        if (!underwater) {
            FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_SKY, skyFar, thickFog, partialTick);
            SkyPass.render(level, partialTick, fogRgb, skyFar, thickFog);
        }
        Vector3f l0 = dev.antimony.core.render.EntityLighting.DIFFUSE_0;
        Vector3f l1 = dev.antimony.core.render.EntityLighting.DIFFUSE_1;
        IrisGl.shaderLights(l0.x, l0.y, l0.z, l1.x, l1.y, l1.z);

        IrisGl.fog(!overview);
        // Fog colour = vanilla's FogRenderer.setupColor value, i.e. the same
        // colour as the clear and the sky disc's horizon (see FogColor). The
        // old hard-coded range plus a raw sky colour never matched the sky.
        IrisGl.fogcolor(fogRgb);
        // The real call runs Iris' legacy-density capture and NeoForge's
        // RenderFog event, including water biome tags, lava/snow and effects.
        // Snapshot here: hand/GUI passes may overwrite the live state later.
        FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_TERRAIN, terrainFar, thickFog, partialTick);
        FogFrame.Snapshot fog = FogFrame.captureTerrain();
        IrisGl.fogrange(fog.start(), fog.end());
        IrisGl.fogshape(fog.shapeId() == 1);
        IrisGl.zbuffer(true);
        IrisGl.ctx().blend = false;
        IrisGl.alphatest(false, 0.0F);

        ChunkUpdateHooks.drain(this);
        // Meshes the background workers finished since last frame go live now.
        if (IrisTerrainMaterials.refresh()) this.clearMeshes();
        this.pickupFinishedMeshes();
        this.refreshVisible(level, player, eyeFinal);
        IrisGlRenderer.cameraPosField(eyeFinal);
        dev.antimony.gl.glsl.IrisPostChain.prepareFrameState(partialTick);

        int cx = SectionPos.blockToSectionCoord(player.getBlockX());
        int cz = SectionPos.blockToSectionCoord(player.getBlockZ());
        int[] voxelDimensions = dev.antimony.gl.glsl.IrisPostChain.cpuImageDimensions("voxel_img");
        boolean shadowPrepared=dev.antimony.gl.glsl.IrisPostChain.preparedShadowFrame();
        if (shadowPrepared) this.submitVoxelMissing(level, eyeFinal, voxelDimensions);
        long playerSection = SectionPos.asLong(cx, player.getBlockY() >> 4, cz);
        if (playerSection != this.lastPlayerSection) {
            this.lastPlayerSection = playerSection;
        } else if (this.frameCounter % RESCAN_INTERVAL == 0) {
            this.submitMissing(level, cx, cz, false);
        }
        // Forced rebuilds (block changes) go first, the regular distance scan
        // fills the rest of the submission budget.
        this.submitMissing(level, cx, cz, true);
        if (shadowPrepared) {
            try {
                if(voxelDimensions!=null){
                    var receipt=SolasVoxelProducer.produce(this,
                        dev.antimony.gl.glsl.IrisPostChain.cpuImageFrame(),eyeFinal,
                        dev.antimony.gl.glsl.IrisPostChain.shadowFrame());
                    dev.antimony.gl.glsl.IrisPostChain.afterShadowProducer(receipt);
                }else{
                    var receipt=IrisShadowProducer.produce(this,eyeFinal,
                        dev.antimony.gl.glsl.IrisPostChain.shadowFrame(),dev.antimony.gl.glsl.IrisPostChain.currentShadowOrdinal());
                    dev.antimony.gl.glsl.IrisPostChain.afterShadowProducer(receipt);
                }
            } catch (ReflectiveOperationException|RuntimeException producerFailure) {
                dev.antimony.gl.glsl.IrisPostChain.cpuImageProducerFailure("CPU shadow producer: "+producerFailure);
            }
        }

        // Opaque + cutout terrain, nearest first (early-z style overdraw
        // reduction for the software rasterizer).
        this.visible.sort(Comparator.comparingDouble(m -> this.distanceSq(m, eyeFinal)));
        int drawn = 0;
        int quads = 0;
        // B2: 阴影贴图。光影包自己画 shadow.vsh 那遍阴影，但我们的地形不经过 GL，
        // Iris 那遍永远是空的 → shadow2D() 恒读到 1.0（全亮、没有影子）。
        // 这里用同一套 shadowProjection/shadowModelView 把地形光栅化成深度图。
        //
        // B3: 这一步必须在<地形绘制之前>跑完。深度图本身可以复用（吸附后只在跨格
        // 时重建），但 modelView 的平移列 R*(eye-base) 每帧都要重算：顶点侧的
        // 查询是 mat3(m)*p + m[3]，p 用的是<本帧>的相机相对坐标，平移列若停在上
        // 一帧的 eye 上，走动时影子会整体滞后一帧的位移。
        if (dev.antimony.gl.glsl.IrisPostChain.enabled() && ShadowMap.ENABLED && !shadowPrepared) {
            Vector3f sun = new Vector3f(SkyPass.SUN_CAMERA);
            new org.joml.Matrix3f(IrisGlRenderer.lastCameraRotation()).invert().transform(sun);
            ShadowMap.update(this.meshes.values(), eyeFinal.x, eyeFinal.y, eyeFinal.z,
                    sun.x, sun.y, sun.z);
        }
        dev.antimony.gl.glsl.IrisPostChain.prepareFrameState(partialTick);
        // B3：地形批次打开 shadowPos varying（顶点算一次，光栅器逐像素采样
        // B2 那张深度图）。只有真跑光影包且深度图已建好时才打开，其它几何
        // （实体、粒子、GUI）走完全一样的旧路径，不多一次矩阵乘法。
        boolean castShadows = dev.antimony.gl.glsl.IrisPostChain.enabled()
                && ShadowMap.ENABLED && ShadowMap.TERRAIN && !shadowPrepared;
        if (castShadows) {
            IrisGl.shadow(true);
        }
        try {
            for (SectionMesh mesh : this.visible) {
                IrisGl.pushmatrix();
                IrisGl.translate(
                        mesh.sectionX * 16.0F - (float) eye.x,
                        mesh.sectionY * 16.0F - (float) eye.y,
                        mesh.sectionZ * 16.0F - (float) eye.z);
                dev.antimony.gl.glsl.IrisTerrainVertex.beginScope("TERRAIN_SOLID", IrisGl.ctx());
                try { mesh.solid.draw(this.atlas, this.lightmap, true); }
                finally { dev.antimony.gl.glsl.IrisTerrainVertex.endScope(); }
                IrisGl.alphatest(true, 0.1F);
                dev.antimony.gl.glsl.IrisTerrainVertex.beginScope("TERRAIN_CUTOUT_MIPPED", IrisGl.ctx());
                try { mesh.cutoutMipped.draw(this.atlas, this.lightmap, true); }
                finally { dev.antimony.gl.glsl.IrisTerrainVertex.endScope(); }
                dev.antimony.gl.glsl.IrisTerrainVertex.beginScope("TERRAIN_CUTOUT", IrisGl.ctx());
                try { mesh.cutout.draw(this.atlas, this.lightmap, true); }
                finally { dev.antimony.gl.glsl.IrisTerrainVertex.endScope(); }
                IrisGl.alphatest(false, 0.0F);
                IrisGl.popmatrix();
                drawn++;
                quads += mesh.quadCount();
            }
        } finally {
            dev.antimony.gl.glsl.IrisTerrainVertex.endScope();
            // The flag must never leak into entities/particles/GUI: a stuck
            // `shadowVarying` would make them sample the sun's depth map.
            if (castShadows) {
                IrisGl.shadow(false);
            }
        }

        // Entities + block entities: vanilla draws them between the opaque
        // terrain and the translucent terrain, with depth writes on, so both
        // translucency sorting and entity-vs-entity occlusion match.
        EntityPass.renderEntities(level, mc, eyeFinal, partialTick, this.lightmap);
        EntityPass.renderBlockEntities(level, mc, eyeFinal, partialTick, this.lightmap);
        // Targeted-block outline + F3+B/F3+G overlays sit between block
        // entities and translucent terrain in vanilla's frame.
        DebugPass.render(mc, new PoseStack(), eyeFinal.x, eyeFinal.y, eyeFinal.z, this.lightmap);

        // Iris copies depthtex2 before its opaque-hand pass, then depthtex1 before
        // terrain translucency. Our HandPass is still a single non-depth-writing
        // end-of-world pass: no opaque-hand geometry exists between these boundaries
        // yet, so equal copies here are expected, not evidence of stage equivalence.
        if (depthGeneration != 0) {
            IrisGl.flushRaster();
            dev.antimony.gl.glsl.IrisPostChain.captureDepth(depthGeneration,
                dev.antimony.gl.glsl.DepthFrame.Stage.PRE_HAND, level, IrisGl.framebuffer());
            dev.antimony.gl.glsl.IrisPostChain.captureDepth(depthGeneration,
                dev.antimony.gl.glsl.DepthFrame.Stage.PRE_TRANSLUCENT, level, IrisGl.framebuffer());
        }

        dev.antimony.gl.glsl.IrisPostChain.beforeTranslucents(IrisGl.framebuffer());
        // Translucent terrain, back to front.
        this.visible.sort(Comparator.comparingDouble(m -> -this.distanceSq(m, eyeFinal)));
        IrisGl.blendfunction(IrisGlContext.BF_SRC_ALPHA, IrisGlContext.BF_ONE_MINUS_SRC_ALPHA);
        IrisGl.ctx().depthWrite = true;
        IrisGl.alphatest(true, 0.05F);
        for (SectionMesh mesh : this.visible) {
            if (mesh.translucent.isEmpty()) {
                continue;
            }
            IrisGl.pushmatrix();
            IrisGl.translate(
                    mesh.sectionX * 16.0F - (float) eye.x,
                    mesh.sectionY * 16.0F - (float) eye.y,
                    mesh.sectionZ * 16.0F - (float) eye.z);
            dev.antimony.gl.glsl.IrisTerrainVertex.beginScope("TERRAIN_TRANSLUCENT",IrisGl.ctx());
            try {mesh.translucent.draw(this.atlas, this.lightmap, true);}
            finally {dev.antimony.gl.glsl.IrisTerrainVertex.endScope();IrisGl.popmatrix();}
        }
        IrisGl.ctx().depthWrite = true;
        IrisGl.ctx().blend = false;
        IrisGl.alphatest(false, 0.0F);

        // Cloud layer: vanilla renders it after the translucent chunk layer.
        // Skipped while a shader pack runs: a pack with V_CLOUDS > 0 declares
        // "clouds = off" and raymarches its own clouds in deferred, but the
        // post chain sees leftover vanilla cloud pixels as opaque geometry
        // (depth < 1), so prepare cannot paint sky over them and they survive
        // as grey slabs in the final image.
        if (!dev.antimony.gl.glsl.IrisPostChain.enabled()) {
            CloudPass.render(mc, level, partialTick, eyeFinal.x, eyeFinal.y, eyeFinal.z);
        }

        ParticlePass.render(mc, partialTick, this.lightmap);
        WeatherPass.render(mc, partialTick, this.lightmap);
        HandPass.render(mc, partialTick, this.lightmap);
        if (depthGeneration != 0) {
            IrisGl.flushRaster();
            dev.antimony.gl.glsl.IrisPostChain.captureDepth(depthGeneration,
                dev.antimony.gl.glsl.DepthFrame.Stage.FINAL, level, IrisGl.framebuffer());
        }

        dev.antimony.gl.glsl.IrisPostChain.finishGbuffers();
        this.totalQuads = quads;
        this.meshOutstanding = MeshBuildPool.outstanding();
        if (this.frameCounter - this.lastStatsLog > 100) {
            this.lastStatsLog = this.frameCounter;
            AntimonyMod.LOG.info("[Antimony] frame {} sections={} quads={} meshes={} meshQueue={} forced={} culled={}",
                    this.frameCounter, drawn, quads, this.meshes.size(), this.meshOutstanding,
                    this.forcedKeys.size(), this.frustumCulled);
        }
    }

    /** Installs meshes finished by the pool; empty results mark the section as empty. */
    private void pickupFinishedMeshes() {
        List<SectionMesh> done = MeshBuildPool.pickup();
        for (SectionMesh mesh : done) {
            long key = SectionPos.asLong(mesh.sectionX, mesh.sectionY, mesh.sectionZ);
            if(!MeshBuildPool.current(mesh,key)||ChunkUpdateHooks.isPending(key)){mesh.recycle();continue;}
            this.forcedKeys.remove(key);
            SectionMesh previous = this.meshes.remove(key);
            // Recycle the replaced section's backing arrays - without this the
            // old mesh's three float[] (up to 240 KB each) were retained by
            // nothing and GC'd, while fresh ones were allocated per rebuild.
            if (previous != null) {
                previous.recycle();
            }
            if (!mesh.isEmpty()) {
                this.meshes.put(key, mesh);
                this.emptyKeys.remove(key);
                this.emptyProofs.remove(key);
            } else {
                // The snapshot says this section is air; remember that so the
                // periodic rescan does not resubmit it every RESCAN_INTERVAL.
                this.emptyKeys.add(key);
                this.emptyProofs.put(key,mesh);
                mesh.recycle();
            }
        }
    }

    /**
     * Snapshots and submits missing sections around the player. With
     * {@code forcedOnly} only rebuilds for changed blocks are submitted (they
     * must jump the queue); otherwise the regular ring scan fills the budget.
     */
    private void submitMissing(ClientLevel level, int centerX, int centerZ, boolean forcedOnly) {
        int minSection = level.getMinSection();
        int sections = level.getSectionsCount();
        int py = playerSectionY();
        int budget = MAX_SUBMITS_PER_FRAME;
        // Forced keys first: block changes must appear within a frame or two.
        // A world join can park 10k+ keys here; instead of sorting every key
        // each frame, repeatedly pick the nearest one (O(budget * n)).
        while (budget > 0 && !this.forcedKeys.isEmpty()) {
            long best = Long.MIN_VALUE;
            long bestDist = Long.MAX_VALUE;
            for (long key : this.forcedKeys) {
                if (MeshBuildPool.inFlight(key)) continue;
                long d = dist2(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), centerX, py, centerZ);
                if (d < bestDist) {
                    bestDist = d;
                    best = key;
                }
            }
            if (best == Long.MIN_VALUE || MeshBuildPool.inFlight(best)) {
                // Everything nearest is already being built; wait for the
                // pool window instead of submitting duplicates.
                break;
            }
            MeshSnapshot snap = MeshSnapshot.capture(level,
                    SectionPos.x(best), SectionPos.y(best), SectionPos.z(best));
            if (MeshBuildPool.submit(snap, best)) {
                ChunkUpdateHooks.consumeCurrent(best, MeshBuildPool.submittedRevision(best));
                this.forcedKeys.remove(best);
                budget--;
            } else {
                break;
            }
        }
        if (forcedOnly || budget <= 0) {
            return;
        }
        // Regular ring scan, nearest first.
        List<long[]> candidates = new ArrayList<>();
        for (int dx = -this.renderDistance; dx <= this.renderDistance; dx++) {
            for (int dz = -this.renderDistance; dz <= this.renderDistance; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (int i = 0; i < sections; i++) {
                    LevelChunkSection section = chunk.getSection(i);
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int sectionY = minSection + i;
                    long key = SectionPos.asLong(chunk.getPos().x, sectionY, chunk.getPos().z);
                    if (!this.meshes.containsKey(key) && !this.emptyKeys.contains(key)
                            && !MeshBuildPool.inFlight(key)) {
                        candidates.add(new long[]{key,
                                dist2(chunk.getPos().x, sectionY, chunk.getPos().z, centerX, py, centerZ)});
                    }
                }
            }
        }
        candidates.sort((a, b) -> Long.compare(a[1], b[1]));
        for (long[] c : candidates) {
            if (budget <= 0) {
                break;
            }
            long key = c[0];
            MeshSnapshot snap = MeshSnapshot.capture(level,
                    SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
            if (MeshBuildPool.submit(snap, key)) {
                ChunkUpdateHooks.consumeCurrent(key, MeshBuildPool.submittedRevision(key));
                budget--;
            }
        }
    }

    private int playerSectionY() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getBlockY() >> 4;
    }

    private List<Long> shadowSourceKeys(ClientLevel level,Vec3 camera,dev.antimony.gl.glsl.IrisShadowFrame shadow){
        List<Long> keys=new ArrayList<>();
        if(shadow==null||!shadow.terrain()&&!shadow.translucent())return keys;
        int centerX=Mth.floor(camera.x)>>4,centerZ=Mth.floor(camera.z)>>4;
        int radius=Math.min(this.renderDistance,shadow.renderDistance());
        for(int cx=centerX-radius;cx<=centerX+radius;cx++)for(int cz=centerZ-radius;cz<=centerZ+radius;cz++){
            LevelChunk chunk=level.getChunkSource().getChunk(cx,cz,ChunkStatus.FULL,false);
            // Iris cannot submit an unloaded section; it is outside this frame's source set.
            if(chunk==null)continue;
            for(int sy=level.getMinSection();sy<level.getMaxSection();sy++){
                if(!shadow.includes(cx,sy,cz))continue;
                LevelChunkSection section=chunk.getSection(sy-level.getMinSection());
                if(section!=null&&!section.hasOnlyAir())keys.add(SectionPos.asLong(cx,sy,cz));
            }
        }
        keys.sort(Comparator.comparingDouble(key->dist2(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key),centerX,playerSectionY(),centerZ)));
        return keys;
    }
    private SectionMesh sourceProof(long key){SectionMesh mesh=this.meshes.get(key);return mesh!=null?mesh:this.emptyProofs.get(key);}
    private boolean freshSource(long key){return !ChunkUpdateHooks.isPending(key)&&!this.forcedKeys.contains(key)
        &&!MeshBuildPool.inFlight(key)&&!MeshBuildPool.failed(key)&&MeshBuildPool.current(sourceProof(key),key);}
    private void submitVoxelMissing(ClientLevel level,Vec3 camera,int[] dimensions){
        int budget=MAX_SUBMITS_PER_FRAME;
        for(long key:shadowSourceKeys(level,camera,dev.antimony.gl.glsl.IrisPostChain.shadowFrame())){
            if(freshSource(key)||MeshBuildPool.inFlight(key))continue;
            if(budget==0)break;
            if(MeshBuildPool.submit(MeshSnapshot.capture(level,SectionPos.x(key),SectionPos.y(key),SectionPos.z(key)),key)){
                ChunkUpdateHooks.consumeCurrent(key, MeshBuildPool.submittedRevision(key));
                this.forcedKeys.remove(key);budget--;
            }else break;
        }
    }
    List<SectionMesh> shadowSources(Vec3 camera,dev.antimony.gl.glsl.IrisShadowFrame shadow){
        return voxelSources(camera,null,shadow);
    }
    List<SectionMesh> voxelSources(Vec3 camera,int[] dimensions,dev.antimony.gl.glsl.IrisShadowFrame shadow){
        ClientLevel level=Minecraft.getInstance().level;
        if(level==null)throw new IllegalStateException("No world for shadow sources");
        List<SectionMesh> selected=new ArrayList<>();
        List<Long> keys=shadowSourceKeys(level,camera,shadow);
        Long firstPending=null;
        int pending=0, building=0, failed=0;
        for(long key:keys){
            if(!freshSource(key)){
                if(firstPending==null)firstPending=key;
                pending++;
                if(MeshBuildPool.inFlight(key))building++;
                if(MeshBuildPool.failed(key))failed++;
                continue;
            }
            SectionMesh mesh=sourceProof(key);
            if(!mesh.solid.hasSolasVoxelInputs()||!mesh.cutout.hasSolasVoxelInputs()||!mesh.cutoutMipped.hasSolasVoxelInputs()||!mesh.translucent.hasSolasVoxelInputs())
                throw new IllegalStateException("Shadow section has unsupported source vertices: "+SectionPos.of(key));
            selected.add(mesh);
        }
        this.shadowPreparationDetail="阴影区块 "+selected.size()+"/"+keys.size()+" · 构建中 "+building+" · 待就绪 "+pending
                +(failed==0?"":" · 失败 "+failed);
        if(firstPending!=null)throw new IllegalStateException("Shadow section is pending, stale, or failed: "+SectionPos.of(firstPending)
                +"; "+this.shadowPreparationDetail);
        return List.copyOf(selected);
    }
    void verifyVoxelSources(List<SectionMesh> sources){
        for(SectionMesh mesh:sources){long key=SectionPos.asLong(mesh.sectionX,mesh.sectionY,mesh.sectionZ);
            if(sourceProof(key)!=mesh||!freshSource(key))throw new IllegalStateException("Shadow source changed during vertex replay");}
    }

    private static long dist2(int x, int y, int z, int px, int py, int pz) {
        long dx = x - px;
        long dy = y - py;
        long dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Vanilla clears the framebuffer to the fog colour and lets the fogged sky
     * disc (plus the fogged terrain) cover everything. The old implementation
     * painted a screen-space horizon->zenith gradient instead, which showed as
     * a hard band wherever the disc's rim did not reach it.
     */
    private void drawSky(int fogRgb) {
        IrisGl.zbuffer(false);
        IrisGl.texture(false);
        IrisGl.blendmode(false);
        IrisGl.fog(false);
        IrisGl.setClearColor(0xFF000000 | fogRgb);
        IrisGl.clear();
        IrisGl.zclear();
    }

    /** Frustum cull test against the section's bounding sphere (conservative). */
    private boolean inFrustum(SectionMesh mesh) {
        // Sphere centre in camera-relative world space. IrisGl.lookat uses a
        // rotation-only view (eye at origin), so world direction = camera space.
        float cxw = (mesh.sectionX + 0.5F) * 16.0F - (float) this.eyeX;
        float cyw = (mesh.sectionY + 0.5F) * 16.0F - (float) this.eyeY;
        float czw = (mesh.sectionZ + 0.5F) * 16.0F - (float) this.eyeZ;
        int res = this.frustum.intersectSphere(cxw, cyw, czw, SECTION_CULL_RADIUS);
        if (res == org.joml.FrustumIntersection.OUTSIDE) {
            this.frustumCulled++;
            return false;
        }
        return true;
    }

    private double eyeX;
    private double eyeY;
    private double eyeZ;

    private void refreshVisible(ClientLevel level, LocalPlayer player, Vec3 eye) {
        this.visible.clear();
        this.eyeX = eye.x;
        this.eyeY = eye.y;
        this.eyeZ = eye.z;
        double maxDist = (this.renderDistance + 1) * 16.0;
        for (SectionMesh mesh : this.meshes.values()) {
            double dx = (mesh.sectionX + 0.5) * 16.0 - eye.x;
            double dy = (mesh.sectionY + 0.5) * 16.0 - eye.y;
            double dz = (mesh.sectionZ + 0.5) * 16.0 - eye.z;
            if (dx * dx + dy * dy + dz * dz <= maxDist * maxDist && this.inFrustum(mesh)) {
                this.visible.add(mesh);
            }
        }
    }

    /** Notification entry points already expand the vanilla dependency halo and invalidate
     * revisions immediately. Draining a notification only schedules its own section; it must
     * not invalidate finished neighbours again, or a finite dirty batch never settles. */
    public void remeshSection(int x, int y, int z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || y < level.getMinSection() || y >= level.getMaxSection()) return;
        scheduleRemesh(SectionPos.asLong(x, y, z));
    }

    void scheduleRemesh(long key) {
        this.forcedKeys.add(key);
    }

    boolean scheduledForRebuild(long key) {
        return this.forcedKeys.contains(key);
    }

    /** Drops every software mesh (used when the whole chunk data changed). */
    public int clearMeshes() {
        int n = this.meshes.size();
        this.meshes.clear();
        this.visible.clear();
        this.forcedKeys.clear();
        this.emptyKeys.clear();
        this.emptyProofs.clear();
        this.lastPlayerSection = Long.MIN_VALUE;
        ChunkUpdateHooks.reset();
        MeshBuildPool.reset();
        return n;
    }

    /**
     * Drops only the meshes of one chunk. A new chunk packet used to clear the
     * entire mesh map, which made every loaded section disappear and rebuild
     * whenever the server sent a single chunk - the "the world looks like it
     * refreshes" symptom.
     */
    public int dropChunk(int chunkX, int chunkZ) {
        int before = this.meshes.size();
        MeshBuildPool.invalidateChunk(chunkX,chunkZ);
        this.meshes.keySet().removeIf(key -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
        this.forcedKeys.removeIf(key -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
        this.emptyKeys.removeIf(key -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
        this.emptyProofs.keySet().removeIf(key -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
        this.visible.clear();
        return before - this.meshes.size();
    }

    private double distanceSq(SectionMesh mesh, Vec3 eye) {
        double dx = (mesh.sectionX + 0.5) * 16.0 - eye.x;
        double dy = (mesh.sectionY + 0.5) * 16.0 - eye.y;
        double dz = (mesh.sectionZ + 0.5) * 16.0 - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Vanilla's world-icon gate is {@code LevelRenderer.countRenderedSections()
     * > 10 && LevelRenderer.hasRenderedAllSections()}: enough of the world on
     * screen and no section still waiting to be (re)built. The software
     * renderer never calls LevelRenderer, so both are mirrored here.
     */
    public boolean settled(int minSections) {
        return this.visible.size() >= minSections
                && this.meshOutstanding == 0
                && MeshBuildPool.outstanding() == 0
                && this.visible.isEmpty() == false;
    }

    public int totalQuads() {
        return this.totalQuads;
    }

    public int meshCount() {
        return this.meshes.size();
    }

    /** Sections that passed the frustum test in the last frame. */
    public int visibleSections() {
        return this.visible.size();
    }

    public int meshPoolOutstanding() {
        return this.meshOutstanding;
    }

    public String startupSceneDetail() {
        return this.shadowPreparationDetail;
    }

    public long meshBuildsCompleted() {
        return MeshBuildPool.completedCount();
    }

    public void clear() {
        dev.antimony.gl.glsl.IrisPostChain.invalidateDepthFrame();
        this.meshes.clear();
        this.visible.clear();
        this.forcedKeys.clear();
        this.emptyKeys.clear();
        this.emptyProofs.clear();
        this.lastPlayerSection = Long.MIN_VALUE;
        ChunkUpdateHooks.reset();
        MeshBuildPool.reset();
    }
}

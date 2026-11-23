package dev.antimony.core.render;

import dev.antimony.AntimonyMod;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

/** Render-thread Iris lookup; workers receive only detached immutable values. */
public final class IrisTerrainMaterials {
    private static final String ROOT = "net.irisshaders.iris.";
    private static Object mapIdentity, packIdentity, pipelineIdentity;
    private static volatile Snapshot current = new Snapshot(0, false, Map.of());
    private static boolean warned;
    private static volatile boolean voxelizeLightBlocks;
    public static boolean voxelizeLightBlocks(){return voxelizeLightBlocks;}

    private IrisTerrainMaterials() { }

    public record Snapshot(long generation, boolean enabled, Map<BlockState, Integer> ids) {
        public Snapshot { ids = Map.copyOf(ids); }
        /** Iris BlockMaterialMapping sets its default return value to -1. */
        public int materialId(BlockState state) { return ids.getOrDefault(state, -1); }
    }

    public static Snapshot snapshot() { return current; }

    /**
     * Call once per world frame before mesh pickup/submission. A true result
     * requires clearing already installed section meshes. Pack and pipeline
     * identity also invalidate an equal mapping after reload/dimension switch.
     */
    public static boolean refresh() {
        if (!Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("Iris material capture must run on the render thread");
        Object mapping = null, pack = null, pipeline = null;
        try {
            Class<?> iris = Class.forName(ROOT + "Iris");
            Object selected = iris.getMethod("getCurrentPack").invoke(null);
            pack = selected instanceof Optional<?> optional ? optional.orElse(null) : null;
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            if (manager != null) pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            // CPU takeover skips beginLevelRendering(), where Iris lazily constructs
            // block/tag IDs. Reading WorldRenderingSettings alone therefore returns null
            // (or a previous pipeline's map). Mirror that exact CPU builder per identity;
            // do not invoke the native entry, which also clears/renders GL resources.
            if (pack == packIdentity && pipeline == pipelineIdentity) return false;
            if (pack != null && pipeline != null
                    && Class.forName(ROOT + "pipeline.IrisRenderingPipeline").isInstance(pipeline)) {
                mapping = buildBlockStateIds(pack);
            }
            if (mapping != null && !(mapping instanceof Map<?, ?>))
                throw new IllegalStateException("Iris block-state mapping is not a map");
            Map<BlockState, Integer> copy = new HashMap<>();
            if (mapping instanceof Map<?, ?> values) {
                for (var entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof BlockState state) || !(entry.getValue() instanceof Integer id))
                        throw new IllegalStateException("Unexpected Iris block-state mapping entry");
                    copy.put(state, id);
                }
            }
            voxelizeLightBlocks=false;
            if(pack!=null&&pipeline!=null){
                Object dimension=Class.forName(ROOT+"Iris").getMethod("getCurrentDimension").invoke(null);
                Object programs=pack.getClass().getMethod("getProgramSet",Class.forName(ROOT+"shaderpack.materialmap.NamespacedId")).invoke(pack,dimension);
                Object directives=programs.getClass().getMethod("getPackDirectives").invoke(programs);
                voxelizeLightBlocks=(Boolean)directives.getClass().getMethod("shouldVoxelizeLightBlocks").invoke(directives);
            }
            mapIdentity = mapping;
            packIdentity = pack;
            pipelineIdentity = pipeline;
            current = new Snapshot(current.generation() + 1, mapping != null, copy);
            AntimonyMod.LOG.info("[Antimony] Iris terrain materials captured generation={} enabled={} states={} source=CPU mirror of Iris beginLevelRendering block/tag mapping",
                    current.generation(), current.enabled(), current.ids().size());
            return true;
        } catch (ClassNotFoundException absent) {
            return disable();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            if (!warned) {
                warned = true;
                AntimonyMod.LOG.warn("[Antimony] Iris terrain material snapshot unavailable", failure);
            }
            return disable();
        }
    }

    /** Exact CPU-only mapping construction used by IrisRenderingPipeline.beginLevelRendering. */
    private static Object buildBlockStateIds(Object pack) throws ReflectiveOperationException {
        Object idMap = pack.getClass().getMethod("getIdMap").invoke(pack);
        if (idMap == null) throw new IllegalStateException("Selected Iris pack has no IdMap");
        java.lang.reflect.Method blocks = idMap.getClass().getMethod("getBlockProperties");
        java.lang.reflect.Method tags = idMap.getClass().getMethod("getTagEntries");
        Class<?> builder = Class.forName(ROOT + "shaderpack.materialmap.BlockMaterialMapping");
        Object mapping = builder.getMethod("createBlockStateIdMap", blocks.getReturnType(), tags.getReturnType())
                .invoke(null, blocks.invoke(idMap), tags.invoke(idMap));
        if (!(mapping instanceof Map<?, ?>))
            throw new IllegalStateException("Iris CPU block/tag builder did not return a map");
        return mapping;
    }

    private static boolean disable() {
        if (!current.enabled() && mapIdentity == null && packIdentity == null && pipelineIdentity == null) return false;
        voxelizeLightBlocks=false;
        mapIdentity = packIdentity = pipelineIdentity = null;
        current = new Snapshot(current.generation() + 1, false, Map.of());
        return true;
    }
}

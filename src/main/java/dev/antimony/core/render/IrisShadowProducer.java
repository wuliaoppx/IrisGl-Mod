package dev.antimony.core.render;

import dev.antimony.gl.glsl.*;
import dev.antimony.core.irisgl.IrisGlContext;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Executes the current pack's ordinary shadow VS/FS without inventing a Solas voxel image contract. */
public final class IrisShadowProducer {
    private IrisShadowProducer(){}
    private static Object preparedPipeline;
    private static final Map<String,GlslProgramLoader.Loaded> vertices=new LinkedHashMap<>(),fragments=new LinkedHashMap<>();
    private static final Map<String,IrisTerrainSources.Source> sources=new LinkedHashMap<>();
    private static final Map<String,ShaderVaryings> layouts=new LinkedHashMap<>();
    private static void prepare(Object pipeline)throws ReflectiveOperationException{
        if(preparedPipeline==pipeline)return;
        preparedPipeline=null;vertices.clear();fragments.clear();sources.clear();layouts.clear();
        var capture=IrisTerrainSources.captureShadowCurrent().orElseThrow(()->new IllegalStateException("No current shadow shader source"));
        String identity=pipeline.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(pipeline));
        if(!identity.equals(capture.pipelineIdentity()))throw new IllegalStateException("Current shadow source/pipeline mismatch");
        int index=0;
        for(var source:capture.sources()){
            String key=source.identity().shaderKey();
            if(source.hasExtraVertexStages())throw new IllegalStateException("Extra current shadow vertex stages: "+key);
            int vertexId=1_610_000_001+index,fragmentId=1_660_000_001+index++;
            var vertex=GlslProgramLoader.loadVertex(vertexId,source.vertex());
            if(vertex==null)throw new IllegalStateException("Current shadow VS: "+GlslProgramLoader.vertexFailure(vertexId));
            if(!vertex.meta.usedImages.isEmpty()||!vertex.meta.usedSamplers.isEmpty())
                throw new IllegalStateException("Ordinary shadow VS requires an explicit image/sampler producer contract: "+key);
            var fragment=GlslProgramLoader.load(fragmentId,source.fragment(),source.vertex());
            if(fragment==null)throw new IllegalStateException("Current shadow FS: "+GlslProgramLoader.failure(fragmentId));
            if(!fragment.meta.usedImages.isEmpty())throw new IllegalStateException("Ordinary shadow FS image side effects unavailable: "+key);
            vertices.put(key,vertex);fragments.put(key,fragment);sources.put(key,source);layouts.put(key,ShaderVaryings.link(vertex.meta,fragment.meta));
        }
        if(!vertices.keySet().equals(Set.of("SHADOW_TERRAIN_CUTOUT","SHADOW_TRANSLUCENT")))
            throw new IllegalStateException("Incomplete current shadow terrain source set: "+vertices.keySet());
        preparedPipeline=pipeline;
    }
    public static Set<String> requestedUniforms(Object pipeline)throws ReflectiveOperationException{
        prepare(pipeline);Set<String> result=new HashSet<>();
        for(var p:vertices.values())result.addAll(p.meta.usedUniforms);
        for(var p:fragments.values())result.addAll(p.meta.usedUniforms);
        result.remove("renderStage");return Set.copyOf(result);
    }
    public static final class Receipt {
        private final Object pipeline;private final long ordinal,nanos,vertexCalls;private final int sections;private final IrisGlContext.RasterStats raster;
        private Receipt(Object pipeline,long ordinal,long nanos,long vertexCalls,int sections,IrisGlContext.RasterStats raster){this.pipeline=pipeline;this.ordinal=ordinal;this.nanos=nanos;this.vertexCalls=vertexCalls;this.sections=sections;this.raster=raster;}
        public void verify(Object expected,long currentOrdinal){if(pipeline!=expected||ordinal!=currentOrdinal||!IrisCpuShadowTargets.ready(expected,currentOrdinal))throw new IllegalStateException("Stale/mismatched ordinary shadow receipt");}
        public long nanos(){return nanos;}public long vertices(){return vertexCalls;}public int sections(){return sections;}public IrisGlContext.RasterStats raster(){return raster;}
    }
    static Receipt produce(WorldRenderer world,Vec3 camera,IrisShadowFrame shadow,long ordinal)throws ReflectiveOperationException{
        long start=System.nanoTime();Object pipeline=shadow.pipeline();prepare(pipeline);
        List<SectionMesh> meshes=world.shadowSources(camera,shadow);
        IrisGlContext context=IrisCpuShadowTargets.begin(pipeline,shadow);
        Map<String,TerrainFragmentDraw> draws=new LinkedHashMap<>();
        for(var entry:fragments.entrySet()){
            String key=entry.getKey();var loaded=entry.getValue();var source=sources.get(key);Object shader=IrisCpuShadowTargets.shader(pipeline,key);
            var bindings=IrisCpuShadowTargets.bindings(shader,loaded.meta.outSlots,source.drawDirectives());
            var overrides=SolasVoxelProducer.matrices(new SectionMesh(0,0,0),camera,shadow,8);
            overrides.put("iris_currentAlphaTest",new float[]{source.drawDirectives().alphaReference()});
            draws.put(key,new TerrainFragmentDraw(loaded.program,loaded.meta,layouts.get(key),IrisPostChain.currentUniformValues(loaded,overrides),
                IrisPostChain.shadowTextures(shader,loaded.meta),bindings.drawBuffers(),bindings.attachments(),shadow.resolution(),shadow.resolution(),null));
        }
        long calls=0;
        if(shadow.terrain()){
            for(var mesh:meshes)calls+=run(context,mesh,mesh.solid,camera,shadow,8,false,draws);
            for(var mesh:meshes)calls+=run(context,mesh,mesh.cutout,camera,shadow,10,false,draws);
            for(var mesh:meshes)calls+=run(context,mesh,mesh.cutoutMipped,camera,shadow,9,false,draws);
        }
        IrisCpuShadowTargets.captureOpaque();
        if(shadow.translucent())for(var mesh:meshes)calls+=run(context,mesh,mesh.translucent,camera,shadow,17,true,draws);
        world.verifyVoxelSources(meshes);IrisCpuShadowTargets.complete(ordinal);
        return new Receipt(pipeline,ordinal,System.nanoTime()-start,calls,meshes.size(),context.rasterStatistics());
    }
    private static long run(IrisGlContext context,SectionMesh mesh,MeshBuffer buffer,Vec3 camera,IrisShadowFrame shadow,int phase,boolean translucent,Map<String,TerrainFragmentDraw> draws){
        String key=translucent?"SHADOW_TRANSLUCENT":"SHADOW_TERRAIN_CUTOUT";
        var loaded=vertices.get(key);var layout=layouts.get(key);var output=layout.vertexAccess(loaded.program);
        IrisPostChain.applyTerrainUniforms(loaded,SolasVoxelProducer.matrices(mesh,camera,shadow,phase));
        float[] data=buffer.rawData();int stride=17+layout.components();float[] quad=new float[stride*4];
        for(int i=0;i<buffer.rawVertices();i+=4){
            for(int q=0;q<4;q++){
                int vertex=i+q,o=vertex*MeshBuffer.STRIDE;
                var attributes=new VertexState.Attributes(new float[]{data[o],data[o+1],data[o+2]},new float[]{data[o+5],data[o+6],data[o+7],data[o+8]},
                    new float[]{data[o+3],data[o+4]},new int[]{(int)data[o+9],(int)data[o+10]},new float[]{data[o+11],data[o+12],data[o+13]},
                    buffer.terrainMaterialId(vertex),new float[]{buffer.terrainMidU(vertex),buffer.terrainMidV(vertex)});
                loaded.program.runVertex(new VertexState(new CpuImage[0],shadow.resolution(),shadow.resolution(),0,0,attributes::value).sourceVertexId(vertex));
                System.arraycopy(loaded.program.vertexPosition(),0,quad,q*stride,4);output.capture(quad,q*stride+17);
            }
            layout.flatten(quad,4,stride,17,3);context.submitShaderQuad(quad,stride,draws.get(key));
        }
        return buffer.rawVertices();
    }
}

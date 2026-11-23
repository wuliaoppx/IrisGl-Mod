package dev.antimony.core.render;

import dev.antimony.gl.glsl.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Runs Iris' complete current shadow vertex source, including its imageStore side effect. */
public final class SolasVoxelProducer {
    private SolasVoxelProducer() { }
    public static final class Receipt {
        private final Object pipeline;
        private final long generation,ordinal,writes;
        private long nanos,vertices;
        private int sections;
        private dev.antimony.core.irisgl.IrisGlContext.RasterStats raster;
        private Receipt(IrisCpuImageResources.Frame frame,long writes){pipeline=frame.pipeline();generation=frame.generation();ordinal=frame.frameOrdinal();this.writes=writes;}
        public long uniqueWrites(){return writes;}
        public long nanos(){return nanos;}
        public long vertices(){return vertices;}
        public int sections(){return sections;}
        public dev.antimony.core.irisgl.IrisGlContext.RasterStats raster(){return raster;}
        public void verify(Object pipeline,long generation,long ordinal){
            if(this.pipeline!=pipeline||this.generation!=generation||this.ordinal!=ordinal)throw new IllegalStateException("Stale/mismatched shadow producer proof");
        }
    }
    private static final Map<String,String> EXPECTED=Map.of(
        "SHADOW_TERRAIN_CUTOUT","482fa5ed48b9ae71fee5dc42cbfc890a09ad296b05813c3d18c417318e4ed76c",
        "SHADOW_TRANSLUCENT","482fa5ed48b9ae71fee5dc42cbfc890a09ad296b05813c3d18c417318e4ed76c");
    private static Object verifiedPipeline;
    private static final Map<String,GlslProgramLoader.Loaded> programs=new HashMap<>(),fragments=new HashMap<>();
    private static final Map<String,IrisTerrainSources.Source> sourcesByKey=new HashMap<>();
    private static final Map<String,ShaderVaryings> layouts=new HashMap<>();
    private static final Map<String,TerrainFragmentDraw> frameDraws=new HashMap<>();
    private static dev.antimony.core.irisgl.IrisGlContext shadowContext;
    private static void prepare(Object pipeline)throws ReflectiveOperationException{
        if(verifiedPipeline==pipeline)return;
        programs.clear();fragments.clear();sourcesByKey.clear();layouts.clear();
        var capture=IrisTerrainSources.captureShadowCurrent().orElseThrow(()->new IllegalStateException("No current Iris shadow source"));
        String identity=pipeline.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(pipeline));
        if(!capture.pipelineIdentity().equals(identity))throw new IllegalStateException("Shadow source/image owner pipeline mismatch");
        int index=0;
        for(var source:capture.sources()){
            String key=source.identity().shaderKey();
            if(!Objects.equals(EXPECTED.get(key),source.identity().vertexSha256())||source.hasExtraVertexStages())
                throw new IllegalStateException("Unverified current shadow source "+source.identity());
            var loaded=GlslProgramLoader.loadVertex(1_600_000_001+index++,source.vertex());
            dev.antimony.AntimonyMod.LOG.info("[Antimony] shadow source directives key={} values={}",key,source.drawDirectives());
            if(loaded==null||!loaded.meta.usedImages.equals(Set.of("voxel_img"))||!loaded.meta.usedSamplers.isEmpty())
                throw new IllegalStateException("Unsupported current shadow vertex image contract "+key);
            var fragment=GlslProgramLoader.load(1_650_000_000+index,source.fragment(),source.vertex());
            if(fragment==null)throw new IllegalStateException("Current shadow FS failed to compile: "+key);
            programs.put(key,loaded);fragments.put(key,fragment);sourcesByKey.put(key,source);
            layouts.put(key,ShaderVaryings.link(loaded.meta,fragment.meta));
        }
        if(!programs.keySet().equals(EXPECTED.keySet()))throw new IllegalStateException("Incomplete current shadow sources");
        verifiedPipeline=pipeline;
    }
    public static Set<String> requestedUniforms(Object pipeline)throws ReflectiveOperationException{
        prepare(pipeline);Set<String> names=new HashSet<>();for(var p:programs.values())names.addAll(p.meta.usedUniforms);
        for(var p:fragments.values())names.addAll(p.meta.usedUniforms);names.remove("renderStage");return Set.copyOf(names);
    }
    /** Source selection/freshness is owned by WorldRenderer and checked again before issuing the private proof. */
    static Receipt produce(WorldRenderer world,IrisCpuImageResources.Frame frame,Vec3 camera,IrisShadowFrame shadow)throws ReflectiveOperationException{
        long start=System.nanoTime();
        prepare(frame.pipeline());
        if(shadow==null||shadow.pipeline()!=frame.pipeline())throw new IllegalStateException("Missing current Iris shadow geometry");
        List<SectionMesh> sources=world.voxelSources(camera,frame.dimensions("voxel_img"),shadow);
        shadowContext=IrisCpuShadowTargets.begin(frame.pipeline(),shadow);
        frameDraws.clear();
        for(String key:programs.keySet()){
            var fragment=fragments.get(key);var source=sourcesByKey.get(key);var directives=source.drawDirectives();
            Object shader=IrisCpuShadowTargets.shader(frame.pipeline(),key);
            var bindings=IrisCpuShadowTargets.bindings(shader,fragment.meta.outSlots,directives);
            int[] buffers=bindings.drawBuffers();
            Map<String,float[]> overrides=matrices(new SectionMesh(0,0,0),camera,shadow,8);
            overrides.put("iris_currentAlphaTest",new float[]{directives.alphaReference()});
            frameDraws.put(key,new TerrainFragmentDraw(fragment.program,fragment.meta,layouts.get(key),
                IrisPostChain.currentUniformValues(fragment,overrides),IrisPostChain.shadowTextures(shader,fragment.meta),
                buffers,bindings.attachments(),shadow.resolution(),shadow.resolution(),null));
        }
        long writes=0;
        // Iris submits all solids, then cutout, cutout-mipped, and finally translucent terrain.
        if(shadow.terrain()){
            for(var mesh:sources)writes+=run(mesh,mesh.solid,frame,camera,shadow,8,false);
            for(var mesh:sources)writes+=run(mesh,mesh.cutout,frame,camera,shadow,10,false);
            for(var mesh:sources){writes+=run(mesh,mesh.cutoutMipped,frame,camera,shadow,9,false);writes+=runLights(mesh,frame,camera,shadow);}
        }
        IrisCpuShadowTargets.captureOpaque();
        if(shadow.translucent())for(var mesh:sources)writes+=run(mesh,mesh.translucent,frame,camera,shadow,17,true);
        world.verifyVoxelSources(sources);
        IrisCpuShadowTargets.complete(frame.frameOrdinal());
        long vertices=0;
        for(var mesh:sources){
            if(shadow.terrain())vertices+=(long)mesh.solid.rawVertices()+mesh.cutout.rawVertices()+mesh.cutoutMipped.rawVertices()+mesh.lightVoxels.size();
            if(shadow.translucent())vertices+=mesh.translucent.rawVertices();
        }
        Receipt receipt=new Receipt(frame,writes);
        receipt.nanos=System.nanoTime()-start;receipt.vertices=vertices;receipt.sections=sources.size();
        receipt.raster=shadowContext.rasterStatistics();
        frame.completeShadowProducer(receipt);return receipt;
    }
    static Map<String,float[]> matrices(SectionMesh mesh,Vec3 camera,IrisShadowFrame shadow,int phase){
        Matrix4f view=shadow.view(),projection=shadow.projection();
        Map<String,float[]> values=new HashMap<>();
        values.put("iris_ChunkOffset",new float[]{(float)(mesh.sectionX*16.0-camera.x),(float)(mesh.sectionY*16.0-camera.y),(float)(mesh.sectionZ*16.0-camera.z)});
        values.put("iris_ModelViewMat",view.get(new float[16]));values.put("iris_ModelViewMatInverse",new Matrix4f(view).invert().get(new float[16]));
        values.put("iris_ProjMat",projection.get(new float[16]));values.put("iris_ProjMatInverse",new Matrix4f(projection).invert().get(new float[16]));
        values.put("iris_NormalMat",new Matrix3f(view).invert().transpose().get(new float[9]));
        values.put("iris_TextureMat",new Matrix4f().get(new float[16]));
        values.put("iris_LightmapTextureMatrix",new Matrix4f().translation(1f/32,1f/32,0).scale(1f/256,1f/256,1).get(new float[16]));
        values.put("iris_ColorModulator",new float[]{1,1,1,1});
        values.put("renderStage",new float[]{phase});
        return values;
    }
    private static final class Stores implements FragState {
        final IrisCpuImageResources.Frame frame;final int unit;long writes;
        Stores(IrisCpuImageResources.Frame frame,GlslProgramLoader.Loaded loaded)throws ReflectiveOperationException{
            this.frame=frame;unit=loaded.meta.imageUnits.get("voxel_img");loaded.program.getClass().getField("i$"+unit).setInt(loaded.program,unit);
        }
        public float width(){return 1;}public float height(){return 1;}
        public float[] sample(int unit,float u,float v){throw new IllegalStateException("Unbound shadow vertex sampler");}
        public void storeUnsignedImage3D(int actual,int[] p,int[] value){
            if(actual!=unit||p.length!=3||value.length!=4)throw new IllegalStateException("Shadow image binding mismatch");
            writes+=frame.writeUnsigned("voxel_img",p[0],p[1],p[2],value[0]);
        }
    }
    private static long run(SectionMesh mesh,MeshBuffer buffer,IrisCpuImageResources.Frame frame,Vec3 camera,IrisShadowFrame shadow,int phase,boolean translucent)throws ReflectiveOperationException{
        if(!buffer.hasSolasVoxelInputs())throw new IllegalStateException("Missing original shadow vertex context");
        String key=translucent?"SHADOW_TRANSLUCENT":"SHADOW_TERRAIN_CUTOUT";
        var loaded=programs.get(key);var draw=frameDraws.get(key);var layout=layouts.get(key);
        IrisPostChain.applyTerrainUniforms(loaded,matrices(mesh,camera,shadow,phase));
        Stores stores=new Stores(frame,loaded);float[] data=buffer.rawData();
        int stride=17+layout.components();float[] quad=new float[stride*4];var output=layout.vertexAccess(loaded.program);
        for(int i=0;i<buffer.rawVertices();i+=4){
            for(int q=0;q<4;q++){
                int vertex=i+q,o=vertex*MeshBuffer.STRIDE;var source=buffer.terrainVoxelVertex(vertex);
                float[] mid=TerrainVoxelAttributes.midBlock(source,data[o],data[o+1],data[o+2]);
                var attributes=new VertexState.Attributes(new float[]{data[o],data[o+1],data[o+2]},
                    new float[]{data[o+5],data[o+6],data[o+7],data[o+8]},new float[]{data[o+3],data[o+4]},
                    new int[]{(int)data[o+9],(int)data[o+10]},new float[]{data[o+11],data[o+12],data[o+13]},
                    buffer.terrainMaterialId(vertex),new float[]{buffer.terrainMidU(vertex),buffer.terrainMidV(vertex)});
                loaded.program.runVertex(new VertexState(new CpuImage[0],shadow.resolution(),shadow.resolution(),0,0,
                    input->input.name().equals("at_midBlock")?mid:attributes.value(input)).sourceVertexId(vertex).imageServices(stores));
                System.arraycopy(loaded.program.vertexPosition(),0,quad,q*stride,4);output.capture(quad,q*stride+17);
            }
            layout.flatten(quad,4,stride,17,3);shadowContext.submitShaderQuad(quad,stride,draw);
        }
        return stores.writes;
    }
    private static long runLights(SectionMesh mesh,IrisCpuImageResources.Frame frame,Vec3 camera,IrisShadowFrame shadow)throws ReflectiveOperationException{
        if(mesh.lightVoxels.isEmpty())return 0;
        var loaded=programs.get("SHADOW_TERRAIN_CUTOUT");IrisPostChain.applyTerrainUniforms(loaded,matrices(mesh,camera,shadow,9));
        Stores stores=new Stores(frame,loaded);
        for(var light:mesh.lightVoxels){
            // Iris' LightBlock path emits four coincident vertices at +.25, with ignored midBlock.
            var attributes=new VertexState.Attributes(new float[]{light.x()+.25f,light.y()+.25f,light.z()+.25f},
                new float[4],new float[2],new int[2],new float[3],light.material(),new float[2]);
            loaded.program.runVertex(new VertexState(new CpuImage[0],shadow.resolution(),shadow.resolution(),0,0,
                input->input.name().equals("at_midBlock")?new float[3]:attributes.value(input)).sourceVertexId(0).imageServices(stores));
        }
        return stores.writes;
    }
}

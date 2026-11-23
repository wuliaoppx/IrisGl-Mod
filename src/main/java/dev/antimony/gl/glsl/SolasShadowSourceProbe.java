package dev.antimony.gl.glsl;

import dev.antimony.core.render.TerrainVoxelAttributes;
import java.nio.file.*;
import java.util.*;
import org.joml.*;

/** Executes the entire captured Iris shadow VS with labeled synthetic vertex/matrix inputs. */
public final class SolasShadowSourceProbe {
    public static void main(String[] args)throws Exception{
        String source=Files.readString(Path.of(args[0]));
        var loaded=GlslProgramLoader.loadVertex(1720000001,source);
        check(loaded!=null,"full captured shadow compile: "+GlslProgramLoader.vertexFailure(1720000001));
        check(loaded.meta.usedImages.equals(Set.of("voxel_img")),"actual image side effect retained");
        Matrix4f view=new Matrix4f().rotateX(.7f).rotateZ(-1.1f).translate(.25f,-.5f,.75f);
        Matrix4f projection=new Matrix4f().ortho(-192,192,-192,192,-256,256);
        for(String name:loaded.meta.usedUniforms){
            Object value=switch(name){
                case "iris_ModelViewMat","shadowModelView"->view.get(new float[16]);
                case "iris_ModelViewMatInverse","shadowModelViewInverse"->new Matrix4f(view).invert().get(new float[16]);
                case "iris_ProjMat","shadowProjection"->projection.get(new float[16]);
                case "iris_ProjMatInverse","shadowProjectionInverse"->new Matrix4f(projection).invert().get(new float[16]);
                case "iris_NormalMat"->new Matrix3f(view).invert().transpose().get(new float[9]);
                case "iris_LightmapTextureMatrix"->new Matrix4f().translation(1f/32,1f/32,0).scale(1f/256,1f/256,1).get(new float[16]);
                case "iris_TextureMat"->new Matrix4f().get(new float[16]);
                case "iris_ColorModulator"->new float[]{1,1,1,1};
                case "cameraPosition"->new float[]{33.25f,-15.5f,48.75f};
                case "iris_ChunkOffset"->new float[]{-1.25f,-.5f,-.75f};
                case "renderStage"->8;
                case "frameTimeCounter"->.25f;
                default->throw new AssertionError("Explicit LAB value missing: "+name);
            };
            loaded.program.getClass().getField(loaded.meta.uniformFields.get(name)).set(loaded.program,value);
        }
        List<int[]> stores=new ArrayList<>();int unit=loaded.meta.imageUnits.get("voxel_img");
        loaded.program.getClass().getField("i$"+unit).setInt(loaded.program,unit);
        FragState resources=new FragState(){
            public float width(){return 2048;}public float height(){return 2048;}
            public float[] sample(int u,float x,float y){throw new AssertionError("No shadow vertex sampler expected");}
            public void storeUnsignedImage3D(int u,int[] p,int[] v){check(u==unit,"correct actual image unit");stores.add(new int[]{p[0],p[1],p[2],v[0]&255});}
        };
        int[] materials={10005,10001,-1,10000,10201,10217,10304};
        for(int material:materials){
            stores.clear();run(loaded,resources,material,0,new float[]{1.25f,2.25f,3.75f});
            if(material==10001)check(stores.isEmpty(),"water reserved id skips actual shader imageStore");
            else{
                check(stores.size()==1,"one actual imageStore per first source vertex");int[] result=stores.get(0);
                check(result[0]==96&&result[1]==50&&result[2]==99,"full nonidentity matrix pair maps source center: "+Arrays.toString(result));
                int id=(int)((float)material-10000f),candidate=id-1,expected=(Integer.compareUnsigned(candidate,1)>0?candidate:1)&255;
                check(result[3]==expected,"full unsigned shader material/R8UI for "+material+": "+result[3]);
            }
        }
        stores.clear();run(loaded,resources,10005,1,new float[]{1.25f,2.25f,3.75f});check(stores.isEmpty(),"non-first original vertex cannot write");
        loaded.program.getClass().getField(loaded.meta.uniformFields.get("renderStage")).setInt(loaded.program,0);
        stores.clear();run(loaded,resources,10005,0,new float[]{1.25f,2.25f,3.75f});check(stores.isEmpty(),"nonterrain stage cannot write");
        System.out.println("SolasShadowSourceProbe OK: full unchanged VS/imageStore, nonidentity shadow matrices, stage/source-ID filters, unsigned material and waving-side-effect order; synthetic inputs, not in-game coverage");
    }
    private static void run(GlslProgramLoader.Loaded loaded,FragState resources,int material,int id,float[] position){
        var block=new TerrainVoxelAttributes.Vertex(id,1,2,3,TerrainVoxelAttributes.Stage.TERRAIN_SOLID);
        float[] mid=TerrainVoxelAttributes.midBlock(block,position[0],position[1],position[2]);
        var attributes=new VertexState.Attributes(position,new float[]{1,1,1,1},new float[]{.2f,.3f},new int[]{240,240},new float[]{0,1,0},material,new float[]{.5f,.5f});
        loaded.program.runVertex(new VertexState(new CpuImage[0],2048,2048,0,0,
            input->input.name().equals("at_midBlock")?mid:attributes.value(input)).sourceVertexId(id).imageServices(resources));
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}

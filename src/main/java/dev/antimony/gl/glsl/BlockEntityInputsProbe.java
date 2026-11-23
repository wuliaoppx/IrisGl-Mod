package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.IrisGlTexture;
import dev.antimony.core.render.BlockEntityShaderInputs;
import java.nio.file.*;
import java.util.*;

/** Real transformed block VS/FS links, exact integer texels, and per-draw input isolation. */
public final class BlockEntityInputsProbe {
    public static void main(String[] args) throws Exception {
        CpuImage uploaded=new CpuImage(2,2,true,true,true,true);
        for(int i=0;i<4;i++) uploaded.writePixel(i*4,i/4f,i/8f,i/16f,1);
        near(uploaded.fetch(1,0,0),new float[]{.25f,.125f,.0625f,1},"uploaded integer row zero");
        CpuImage framebuffer=new CpuImage(2,2);framebuffer.copyFrom(uploaded);
        near(framebuffer.fetch(1,0,0),uploaded.fetch(1,1,0),"FBO integer row zero is bottom");
        near(uploaded.mipmappedView().fetch(0,0,1),new float[]{.375f,.1875f,.09375f,1},"integer mip fetch");
        expectFailure(()->uploaded.fetch(2,0,0),"fetch must not wrap");
        expectFailure(()->uploaded.fetch(0,0,1),"missing mip rejected");
        int id=1782000000;
        String vertex="""
            uniform sampler2D unused; uniform sampler2D iris_overlay;
            in vec3 iris_Position; in ivec3 iris_Entity; in ivec2 iris_UV1;
            flat out ivec3 id; out vec4 overlay; int calls=0;
            ivec2 coord(){calls++;return iris_UV1;} int level(){calls++;return 0;}
            void main(){id=iris_Entity;overlay=texelFetch(iris_overlay,coord(),level());
            gl_Position=vec4(iris_Position.xy,float(calls),1.0);}
            """;
        var loaded=GlslProgramLoader.loadVertex(++id,vertex);
        if(loaded==null)throw new AssertionError(GlslProgramLoader.vertexFailure(id));
        int[] pixels=new int[256];Arrays.fill(pixels,0xff000000);pixels[10*16+3]=0xff4080c0;pixels[2*16+7]=0xffc08040;
        var images=IrisTerrainVertex.blockVertexImages(loaded,new IrisGlTexture(16,16,pixels));
        VertexState.InputProvider base=input->input.name().equals("iris_Position")?new float[]{.25f,.5f,0}:null;
        var first=BlockEntityShaderInputs.attributes(base,301,0xa3);
        var second=BlockEntityShaderInputs.attributes(base,9999,0x27);
        loaded.program.runVertex(new VertexState(images,8,8,0,0,first));
        near(loaded.program.vertexPosition(),new float[]{.25f,.5f,2,1},"coordinate and LOD single evaluation");
        var access=ShaderVaryings.outputs(loaded.meta).vertexAccess(loaded.program);
        float[] a=new float[ShaderVaryings.outputs(loaded.meta).components()];access.capture(a,0);
        check(Arrays.equals((int[])first.value(new GlslProgram.VertexInput("iris_Entity","",GlslProgram.VertexInputType.IVEC3)),new int[]{0,301,0}),"entity ID components");
        loaded.program.runVertex(new VertexState(images,8,8,0,0,second));
        float[] b=new float[a.length];access.capture(b,0);
        check(!Arrays.equals(a,b),"second draw has its own material/overlay");
        loaded.program.runVertex(new VertexState(images,8,8,0,0,first));
        float[] again=new float[a.length];access.capture(again,0);check(Arrays.equals(a,again),"first draw input survives later draw");
        check(images[loaded.meta.samplerUnits.get("iris_overlay")].fetch(3,10,0)[0]==64/255f,"overlay ARGB red and row order");
        if(args.length>0) {
            Path root=Path.of(args[0]);
            for(String key:List.of("block_entity","block_entity_diffuse","block_entity_bright","be_translucent")) {
                String vs=Files.readString(root.resolve(key+".vsh")),fs=Files.readString(root.resolve(key+".fsh"));
                var v=GlslProgramLoader.loadVertex(++id,vs);var f=GlslProgramLoader.load(++id,fs,vs);
                if(v==null||f==null)throw new AssertionError(key+": "+GlslProgramLoader.vertexFailure(id-1)+" / "+GlslProgramLoader.failure(id));
                var link=ShaderVaryings.link(v.meta,f.meta);
                System.out.println("PASS real block source "+key+" inputs="+v.meta.vertexInputs+" VSsamplers="+v.meta.usedSamplers+" FSsamplers="+f.meta.usedSamplers+" components="+link.components());
            }
        }
        System.out.println("BLOCK ENTITY INPUTS PROBE OK");
    }
    private static void expectFailure(Runnable op,String message){try{op.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void near(float[] a,float[] b,String message){for(int i=0;i<a.length;i++)if(Math.abs(a[i]-b[i])>1e-6)throw new AssertionError(message+Arrays.toString(a));}
}

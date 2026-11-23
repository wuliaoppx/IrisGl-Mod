package dev.antimony.gl.glsl;

import java.nio.file.*;
import java.util.*;

/** Biased non-mipped reads, pass-owned mip levels and the real captured MakeUp terrain sources. */
public final class GlslTextureLodProbe {
    public static void main(String[] args)throws Exception{
        CpuImage image=new CpuImage(2,2);image.writePixel(0,0,0,0,1);image.writePixel(4,1,0,0,1);image.writePixel(8,0,1,0,1);image.writePixel(12,1,1,0,1);
        near(image.sampleBias(.25f,.75f,-2),image.sample(.25f,.75f),"non-mipped finite bias clamps to the sole base level");
        CpuImage mip=image.mipmappedView();near(mip.sampleLod(.25f,.75f,1),new float[]{.5f,.5f,0,1},"mip level 1");
        near(mip.sampleLod(.25f,.75f,.5f),new float[]{.25f,.25f,0,1},"trilinear levels");
        image.clear(1,1,1,1);near(mip.sampleLod(.25f,.75f,1),new float[]{.5f,.5f,0,1},"mip snapshot remains independent from its output alias");
        near(mip.detached().sampleLod(.25f,.75f,1),new float[]{.5f,.5f,0,1},"detached mip chain retained");
        int[] reads={0};float[] receivedBias={0};var loaded=GlslProgramLoader.load(1781000001,"""
            uniform sampler2D source;layout(location=0)out vec4 color;int calls=0;
            vec2 uv(){calls++;return vec2(.25,.75);}float bias(){calls++;return -2.0;}
            void main(){vec4 value=texture2D(source,uv(),bias());color=vec4(value.r,float(calls),value.a,1.0);}
            ""","");
        if(loaded==null)throw new AssertionError(GlslProgramLoader.failure(1781000001));
        loaded.program.run(new FragState(){public float width(){return 2;}public float height(){return 2;}
            public float[] sample(int unit,float u,float v){throw new AssertionError("bias disappeared");}
            public float[] sampleBias(int unit,float[] uv,float bias){reads[0]++;receivedBias[0]=bias;return new float[]{.75f,0,0,1};}});
        near(((float[][])loaded.program.getClass().getField("out").get(loaded.program))[0],new float[]{.75f,2,1,1},"bias and coordinate expressions evaluate once");
        if(reads[0]!=1||receivedBias[0]!=-2)throw new AssertionError("bias lookup count/value");
        if(args.length>0){int id=1781000010;Path root=Path.of(args[0]);for(String key:List.of("terrain_solid","terrain_cutout","terrain_translucent")){
            String vs=Files.readString(root.resolve(key+".vsh")),fs=Files.readString(root.resolve(key+".fsh"));
            var vertex=GlslProgramLoader.loadVertex(++id,vs);var fragment=GlslProgramLoader.load(++id,fs,vs);
            if(vertex==null||fragment==null)throw new AssertionError("Real MakeUp "+key+" compile: "+GlslProgramLoader.vertexFailure(id-1)+" / "+GlslProgramLoader.failure(id));
            ShaderVaryings.link(vertex.meta,fragment.meta);System.out.println("PASS captured MakeUp "+key+" VS/FS link");
        }}
        System.out.println("GLSL TEXTURE LOD PROBE OK: finite bias transport, single evaluation, real mip levels and captured MakeUp terrain");
    }
    private static void near(float[] a,float[] b,String message){if(a.length!=b.length)throw new AssertionError(message);for(int i=0;i<a.length;i++)if(Math.abs(a[i]-b[i])>.00001f)throw new AssertionError(message+" "+Arrays.toString(a));}
}

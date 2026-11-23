package dev.antimony.gl.glsl;

import java.util.*;

/** Bit-exact scalar sampling A/B, compiler single evaluation and local allocation evidence. */
public final class ScalarSamplerProbe {
    public static void main(String[] args)throws Exception{
        Random random=new Random(1979);long checks=0;
        for(boolean linear:new boolean[]{false,true})for(boolean repeat:new boolean[]{false,true})for(boolean uploaded:new boolean[]{false,true}){
            CpuImage image=new CpuImage(17,11,linear,repeat,repeat,uploaded);
            for(int i=0;i<image.rgba.length;i++)image.rgba[i]=random.nextFloat()*8-2;
            CpuImage mip=image.mipmappedView();
            for(int i=0;i<2000;i++){
                float u=random.nextFloat()*3-1,v=random.nextFloat()*3-1,lod=random.nextFloat()*7-1;
                float[] old=image.sample(u,v),levels=mip.sampleLod(u,v,lod);
                for(int channel=0;channel<4;channel++){
                    exact(old[channel],image.sampleChannel(u,v,channel));exact(levels[channel],mip.sampleLodChannel(u,v,lod,channel));checks+=2;
                }
            }
        }
        String shader="uniform sampler2D source;layout(location=0)out vec4 color;int calls=0;vec2 uv(){calls++;return vec2(.25,.5);}void main(){float r=texture(source,uv()).r;color=vec4(r,float(calls),0.0,1.0);}";
        int[] vectors={0},scalars={0};var loaded=GlslProgramLoader.load(1782000001,shader,"");if(loaded==null)throw new AssertionError(GlslProgramLoader.failure(1782000001));
        loaded.program.run(new FragState(){public float width(){return 1;}public float height(){return 1;}
            public float[] sample(int unit,float u,float v){vectors[0]++;return new float[]{.75f,0,0,1};}
            public float sampleChannel(int unit,float[] uv,int channel){scalars[0]++;return .75f;}});
        float[] output=((float[][])loaded.program.getClass().getField("out").get(loaded.program))[0];exact(output[0],.75f);exact(output[1],1);
        if(vectors[0]!=0||scalars[0]!=1)throw new AssertionError("scalar lowering did not replace the allocation-producing call");
        CpuImage bench=new CpuImage(64,64);Arrays.fill(bench.rgba,.5f);float sum=0;
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        for(int i=0;i<100000;i++)sum+=bench.sampleChannel(.33f,.67f,0);
        long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();for(int i=0;i<1000000;i++)sum+=bench.sample(.33f,.67f)[0];long oldNanos=System.nanoTime()-start,oldBytes=bean.getThreadAllocatedBytes(thread)-before;
        before=bean.getThreadAllocatedBytes(thread);start=System.nanoTime();for(int i=0;i<1000000;i++)sum+=bench.sampleChannel(.33f,.67f,0);long newNanos=System.nanoTime()-start,newBytes=bean.getThreadAllocatedBytes(thread)-before;
        System.out.println("SCALAR SAMPLER PROBE OK checks="+checks+" localOldMs="+oldNanos/1e6+" localNewMs="+newNanos/1e6+" oldAllocated="+oldBytes+" newAllocated="+newBytes+" checksum="+sum+" (local test, not game FPS)");
    }
    private static void exact(float a,float b){if(Float.floatToRawIntBits(a)!=Float.floatToRawIntBits(b))throw new AssertionError("sample changed "+a+" / "+b);}
}

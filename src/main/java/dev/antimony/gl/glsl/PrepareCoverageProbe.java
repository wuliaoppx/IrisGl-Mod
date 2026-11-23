package dev.antimony.gl.glsl;

import java.lang.reflect.*;
import java.util.Map;

/** Production prepare coverage must not retain last-frame transparent/highlight pixels. */
public final class PrepareCoverageProbe {
    public static void main(String[] args)throws Exception{
        Class<?> chainType=Class.forName("dev.antimony.gl.glsl.IrisPostChain$Chain");
        Constructor<?> constructor=chainType.getDeclaredConstructor();constructor.setAccessible(true);
        Object chain=constructor.newInstance();
        Field depthId=chainType.getDeclaredField("depthTexture");depthId.setAccessible(true);depthId.setInt(chain,91);
        Field gbuffer=IrisPostChain.class.getDeclaredField("gbufferFrame");gbuffer.setAccessible(true);
        Method maskMethod=IrisPostChain.class.getDeclaredMethod("buildSkyMask",chainType,Map.class,int.class,int.class);maskMethod.setAccessible(true);
        Method rows=IrisPostChain.class.getDeclaredMethod("runRows",GlslProgram.class,CpuImage[].class,CpuImage[].class,CpuVolume[].class,int.class,int.class,int.class,int.class,float[].class);rows.setAccessible(true);
        var source=GlslProgramLoader.load(1793300021,"layout(location=0)out vec4 color;void main(){color=vec4(.25,.5,.75,1.0);}","");
        if(source==null)throw new AssertionError("prepare fixture compile");
        CpuImage depth=new CpuImage(2,2);depth.clear(.98f,0,0,1);depth.rgba[12]=1;
        CpuImage target=new CpuImage(2,2);
        boolean previous=gbuffer.getBoolean(null);
        try{
            gbuffer.setBoolean(null,false);
            float[] legacy=(float[])maskMethod.invoke(null,chain,Map.of(91,depth),2,2);
            target.clear(50,50,50,1);
            rows.invoke(null,source.program,new CpuImage[]{target},new CpuImage[16],new CpuVolume[16],2,2,0,2,legacy);
            if(target.rgba[0]!=50||target.rgba[12]!=.25f)throw new AssertionError("legacy masked repro invalid");
            gbuffer.setBoolean(null,true);
            float[] actual=(float[])maskMethod.invoke(null,chain,Map.of(91,depth),2,2);
            if(actual!=null)throw new AssertionError("real prepare incorrectly masked by stale depth");
            for(int frame=0;frame<10;frame++){
                target.clear(50,50,50,1);
                rows.invoke(null,source.program,new CpuImage[]{target},new CpuImage[16],new CpuVolume[16],2,2,0,2,actual);
                for(int pixel=0;pixel<4;pixel++)if(target.rgba[pixel*4]!=.25f||target.rgba[pixel*4+1]!=.5f||target.rgba[pixel*4+2]!=.75f)
                    throw new AssertionError("old HDR pixel survives fullscreen prepare");
            }
        }finally{gbuffer.setBoolean(null,previous);}
        System.out.println("PREPARE COVERAGE PROBE OK: stale-depth/highlight repro, real fullscreen overwrite over ten frames, legacy seeded-scene mask retained");
    }
}

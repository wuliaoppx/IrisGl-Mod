package dev.antimony.gl.glsl;

import java.lang.reflect.*;
import java.util.*;

/** Reproduces an attachment-format failure during an in-world pack switch without starting a game. */
public final class IrisPostPipelineIsolationProbe {
    public static final class Pipeline { public final Targets renderTargets; Pipeline(String format){renderTargets=new Targets(format);} }
    public static final class Targets { final String format;Targets(String format){this.format=format;}public int getRenderTargetCount(){return format==null?0:1;}public Target get(int index){return new Target(format);} }
    public static final class Target { final String format;Target(String format){this.format=format;}public int getMainTexture(){return 91;}public int getAltTexture(){return 92;}public int getWidth(){return 4;}public int getHeight(){return 4;}public String getInternalFormat(){return format;} }
    public static void main(String[] args)throws Exception{
        Object previous=new Object();set("cachedPipeline",previous);
        Class<?> chainType=Class.forName(IrisPostChain.class.getName()+"$Chain");var ctor=chainType.getDeclaredConstructor();ctor.setAccessible(true);set("chain",ctor.newInstance());
        set("imageDescriptors",List.of(new IrisImageDescriptors.Image(previous,"voxel_img","voxelSampler",50,0x806F,0x8232,0x8D94,0x1401,192,96,192,true,false,0,0)));
        set("customUniforms",new Object());
        @SuppressWarnings("unchecked") Map<Integer,CpuImage> pool=(Map<Integer,CpuImage>)get("IMAGE_POOL");pool.put(50,new CpuImage(1,1));
        Method ensure=IrisPostChain.class.getDeclaredMethod("ensurePipeline",Object.class);ensure.setAccessible(true);
        Pipeline invalid=new Pipeline("INTENTIONALLY_UNSUPPORTED_FORMAT");ensure.invoke(null,invalid);
        check(get("cachedPipeline")==invalid,"failure belongs to new pipeline");check(get("chain")==null,"old chain was discarded");
        check(((List<?>)get("imageDescriptors")).isEmpty(),"old Solas voxel descriptors were discarded");
        check(get("customUniforms")==null&&get("cpuImageFrame")==null,"old frame resources were discarded");
        check(pool.isEmpty(),"old texture storage was discarded");check(get("pipelineFailure")!=null,"deterministic format failure is retained");
        ensure.invoke(null,invalid);check(((List<?>)get("imageDescriptors")).isEmpty(),"same failed pipeline cannot revive old state");
        Pipeline valid=new Pipeline(null);ensure.invoke(null,valid);
        check(get("cachedPipeline")==valid&&get("pipelineFailure")==null&&get("chain")!=null,"later new pipeline can initialize");
        check(((List<?>)get("imageDescriptors")).isEmpty(),"image-free new pack remains image-free");
        System.out.println("IRIS POST PIPELINE ISOLATION PROBE OK: failure clears chain, descriptors, uniforms and textures; new pipeline recovers");
    }
    private static Field field(String name)throws Exception{Field f=IrisPostChain.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(String name)throws Exception{return field(name).get(null);}
    private static void set(String name,Object value)throws Exception{field(name).set(null,value);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

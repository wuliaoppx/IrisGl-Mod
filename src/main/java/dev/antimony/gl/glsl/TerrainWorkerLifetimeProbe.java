package dev.antimony.gl.glsl;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** One durable pool thread, 128 independent draws and 512 MiB of snapshots under a 256 MiB heap.
 * Numerical/lifetime fixture only; not shaderpack appearance or game FPS evidence. */
public final class TerrainWorkerLifetimeProbe {
    public static void main(String[] args)throws Exception {
        long max=Runtime.getRuntime().maxMemory();
        check(max<=300L*1024*1024,"fixture must enforce a small heap");
        var fragment=GlslProgramLoader.load(1890000001,"uniform sampler2D backing;layout(location=0) out vec4 c;void main(){c=texture(backing,vec2(0.5));}","");
        check(fragment!=null,"real CPU fragment compile");
        var vertex=new GlslCompiler().translateVertex("LifetimeV","void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        ShaderVaryings layout=ShaderVaryings.link(vertex,fragment.meta);
        CpuImage source=new CpuImage(512,512);source.clear(.25f,.5f,.75f,1);
        List<WeakReference<TerrainFragmentDraw>> references=new ArrayList<>();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try{
            for(int i=0;i<128;i++){
                references.add(pool.submit(()->{
                    CpuImage output=new CpuImage(1,1);
                    TerrainFragmentDraw draw=new TerrainFragmentDraw(fragment.program,fragment.meta,layout,Map.of(),Map.of("backing",source),
                        new int[]{0},Map.of(0,new TerrainFragmentDraw.Attachment(output,TerrainFragmentDraw.Encoding.RGBA32F,TerrainFragmentDraw.Blend.OFF)),1,1,null);
                    var worker=draw.worker();check(worker==draw.worker(),"same draw/thread keeps stable mutable worker");
                    float[] triangle=new float[51];triangle[3]=triangle[20]=triangle[37]=1;
                    check(worker.shade(triangle,17,34,51,1,0,0,1,1,1,0,0,.5f,1,true,true)==2,"CPU fragment committed");
                    check(output.rgba[0]==.25f&&output.rgba[1]==.5f&&output.rgba[2]==.75f,"detached sampler remains correct");
                    return new WeakReference<>(draw);
                }).get());
                if(i%16==15)System.gc();
            }
            pool.submit(()->0).get();System.gc();
            long reclaimed=references.stream().filter(r->r.get()==null).count();
            check(reclaimed>=64,"old draw/sampler snapshots must be reclaimable while pool thread stays alive: "+reclaimed);
            System.out.println("PASS worker lifetime: 128 real CPU draws, 512 MiB detached texture snapshots, maxHeapMiB="+max/1024/1024+", reclaimed="+reclaimed+" with live pool thread; no heap-size escape");
        }finally{pool.shutdownNow();}
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

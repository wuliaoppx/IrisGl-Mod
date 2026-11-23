package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.RasterShader;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Real VS transport diagnostic, deliberately NOT a terrain fragment shader or MRT producer. */
final class VertexPayloadDraw implements RasterShader {
    private final ShaderVaryings layout;
    private final AtomicInteger samples = new AtomicInteger();
    private final java.util.Map<Thread,Worker> workers=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.function.Function<Thread,Worker> workerFactory;
    VertexPayloadDraw(ShaderVaryings layout,String key,boolean diagnostic) {
        this.layout=layout;
        workerFactory=thread -> {
            float[] values=new float[layout.components()];
            return (triangle,a,b,c,e0,e1,e2,iw0,iw1,iw2,x,y,depth,iw,front,colorWrite) -> {
                if(diagnostic && samples.get()<4 && samples.getAndIncrement()<4) {
                    layout.interpolate(triangle,a,b,c,e0,e1,e2,iw0,iw1,iw2,values);
                    org.slf4j.LoggerFactory.getLogger("Antimony").info(
                        "[Antimony] terrain payload raster sample key={} pixel=[{},{}] depth={} fragW={} values={} (qualifier-aware transport only; ordinary FS retained)",
                        key,x,y,depth,iw,Arrays.toString(values));
                }
                return 0;
            };
        };
    }
    public ShaderVaryings layout(){return layout;}
    public Worker worker(){return workers.computeIfAbsent(Thread.currentThread(),workerFactory);}
}

package dev.antimony.gl.glsl;

import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Injects failure on vertex two and inspects staging before any raster submission. */
public final class IrisTerrainPrimitiveProbe {
    public static void main(String[] args) throws Exception {
        for (String failure : new String[]{"unknown-input", "nonfinite-clip", "provider-error"}) {
            IrisGlContext context = new IrisGlContext();
            context.framebuffer = new IrisGlFramebuffer(16,16);
            context.bgnpolygon();
            for (int i=0;i<4;i++) {
                context.terrainVertexAttributes(10175,.5f,.5f);
                context.vertex(i+1,2,3);
            }
            GlslProgram injected = new GlslProgram() {
                int invocations;
                float[] result;
                public void run(FragState state) { }
                public void runVertex(VertexState state) {
                    invocations++;
                    if (invocations==2 && failure.equals("unknown-input"))
                        state.vertexInput(new VertexInput("unknown", "", VertexInputType.VEC3));
                    if (invocations==2 && failure.equals("provider-error"))
                        throw new IllegalArgumentException("injected malformed provider");
                    result = new float[]{100+invocations,200,300,1};
                    if (invocations==2 && failure.equals("nonfinite-clip")) result[0]=Float.NaN;
                }
                public float[] vertexPosition() { return result; }
            };
            Field active=IrisTerrainVertex.class.getDeclaredField("active"); active.setAccessible(true);
            Field disabled=IrisTerrainVertex.class.getDeclaredField("disabled"); disabled.setAccessible(true);
            disabled.setBoolean(null,false);
            active.set(null,new GlslProgramLoader.Loaded(injected,new GlslCompiler().translateVertex("Unused", "void main(){gl_Position=vec4(0.0);}"),"Injected"));
            Method refresh=IrisGlContext.class.getDeclaredMethod("refreshMatrixCache"); refresh.setAccessible(true); refresh.invoke(context);
            Method convert=IrisGlContext.class.getDeclaredMethod("clipPrimitive"); convert.setAccessible(true); convert.invoke(context);
            Field staging=IrisGlContext.class.getDeclaredField("polyVerts"); staging.setAccessible(true);
            float[] result=(float[])staging.get(context);
            for(int i=0;i<4;i++) {
                int offset=i*17;
                if(result[offset] != i+1 || result[offset+1] != 2 || result[offset+2] != 3 || result[offset+3] != 1)
                    throw new AssertionError(failure+" mixed primitive at vertex "+i);
            }
            if(IrisTerrainVertex.active())throw new AssertionError("failed bridge remains active");
            System.out.println("PASS second vertex "+failure+": all four staged vertices ordinary, no mixed primitive submitted");
        }
    }
}

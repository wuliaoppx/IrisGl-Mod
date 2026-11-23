package dev.antimony.gl.glsl;

import java.lang.reflect.Method;
import java.util.Map;

/** Runs the actual post executor: VS and FS have different sampler declaration orders. */
public final class PostVertexSamplerProbe {
    public static void main(String[] args) throws Exception {
        Method execute = IrisPostChain.class.getDeclaredMethod("executeProgram", GlslProgramLoader.Loaded.class,
                GlslProgramLoader.Loaded.class, int.class, CpuImage[].class, Map.class, Map.class,
                int.class, int.class, float[].class, Object.class);
        execute.setAccessible(true);
        for (int round = 0; round < 2; round++) {
            int id = 1793300011;
            String vertex = (round == 0 ? "uniform sampler2D scene; uniform sampler2D history;"
                    : "uniform sampler2D history; uniform sampler2D scene;") + """
                out vec2 observed;
                void main() {
                    observed=vec2(texture(history,vec2(.5)).r,texture(scene,vec2(.5)).r);
                    gl_Position=vec4(0.0,0.0,0.0,1.0);
                }
                """;
            String fragment = """
                uniform sampler2D extra;
                uniform sampler2D scene;
                in vec2 observed;
                layout(location=0)out vec4 color;
                void main(){color=vec4(observed,texture(extra,vec2(.5)).r,texture(scene,vec2(.5)).r);}
                """;
            var vs=GlslProgramLoader.loadVertex(id,vertex);
            var fs=GlslProgramLoader.load(id,fragment,vertex);
            if(vs==null||fs==null)throw new AssertionError("Shader compile failed");
            CpuImage scene=constant(.125f),history=constant(.875f),extra=constant(.375f),out=new CpuImage(2,2);
            execute.invoke(null,fs,vs,id,new CpuImage[]{out},Map.of("scene",scene,"history",history,"extra",extra),
                    Map.of(),2,2,null,null);
            for(int p=0;p<4;p++)check(out,p,new float[]{.875f,.125f,.375f,.125f});
            // The next draw has fresh VS state; alias reads must see the pre-pass content.
            out.clear(.625f,0,0,1);
            execute.invoke(null,fs,vs,id,new CpuImage[]{out},Map.of("scene",scene,"history",out,"extra",extra),
                    Map.of(),2,2,null,null);
            for(int p=0;p<4;p++)check(out,p,new float[]{.625f,.125f,.375f,.125f});
            GlslProgramLoader.reset();
        }
        System.out.println("POST VERTEX SAMPLER PROBE OK: production VS/FS independent slots, fresh vertex instances, output alias snapshot and changed declaration order after reload");
    }
    private static CpuImage constant(float r){var image=new CpuImage(2,2);image.clear(r,0,0,1);return image;}
    private static void check(CpuImage image,int pixel,float[] expected){
        for(int c=0;c<4;c++)if(Math.abs(image.rgba[pixel*4+c]-expected[c])>1e-6)
            throw new AssertionError("Post stage sampler mismatch channel="+c+" expected="+expected[c]+" actual="+image.rgba[pixel*4+c]);
    }
}

package dev.antimony.gl.glsl;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Compiles/executes the real Solas corpus plus targeted language semantics, with synthetic inputs. */
public final class GlslSolasCompatibilityProbe {
    private static int serial;
    private static final FragState STATE = new FragState() {
        public float width() { return 8; }
        public float height() { return 8; }
        public float[] sample(int unit, float u, float v) { return new float[]{.2f, .4f, .6f, 1}; }
    };

    public static void main(String[] args) throws Exception {
        GlslProgram semantics = fragment("""
            layout(location=0) out vec4 c;
            uniform float time, unused;
            float derived = time * 2.0;
            const vec2 offsets[2] = vec2[2](vec2(1.0,2.0),vec2(3.0,4.0));
            float pick(float x) { return 10.0; }
            float pick(vec2 x) { return x.y; }
            float pick(vec3 x) { return x.z; }
            vec2 make() { return vec2(5.0,6.0); }
            void alter(inout vec3 x) { x = vec3(7.0,8.0,9.0); return; }
            void scalar(inout float x) { x = 4.0; }
            void ordinary(vec3 x) { x.x = 99.0; }
            vec2 trace(out float border) { border = 3.0; return vec2(2.0,4.0); }
            void main() {
                vec3 v = vec3(0.0);
                alter(v);
                ordinary(v);
                float x = 0.0;
                scalar(x);
                vec2 ray = vec2(0.0);
                float sum = 0.0;
                for(int i=0;i<3;i++,ray+=vec2(1.0),sum+=2.0) { if(i==1) continue; }
                float border = 0.0;
                vec2 traceResult = trace(border);
                traceResult = trace(border);
                c = vec4(pick(make()) + derived, v.z, x + sum, offsets[1].y + ray.x + border + traceResult.y);
            }
            """);
        semantics.getClass().getField("time").setFloat(semantics, 2);
        semantics.run(STATE);
        near(outputs(semantics)[0], new float[]{10,9,10,14});
        semantics.getClass().getField("time").setFloat(semantics, 3);
        semantics.run(STATE);
        near(outputs(semantics)[0], new float[]{12,9,10,14});
        System.out.println("PASS comma declarations, exact vec2/vec3 overload and nested return, vector arrays, per-invocation uniform global init, scalar/vector inout + early return + in copy isolation, ordered comma for updates/continue");

        GlslProgram coords = fragment("in vec2 texCoord; layout(location=0) out vec4 c; void main(){c=vec4(texCoord,0.0,1.0);}");
        coords.setFragment(.2f,.7f); coords.run(STATE); near(outputs(coords)[0],new float[]{.2f,.7f,0,1});
        coords.setFragment(.8f,.3f); coords.run(STATE); near(outputs(coords)[0],new float[]{.8f,.3f,0,1});
        System.out.println("PASS actual mixed-case texCoord is refreshed for each fragment");

        Path dump = Path.of(args[0]);
        int good = 0;
        for (String stem : List.of("73-p18","74-p21","75-p24","76-p201","77-p204","78-p207","79-p210","80-p213","81-p216")) {
            String source = Files.readString(dump.resolve(stem+"-fragment.glsl"));
            try {
                GlslProgram program = fragment(source);
                good++;
                if (stem.equals("81-p216")) {
                    program.getClass().getField("viewWidth").setFloat(program, 8);
                    program.getClass().getField("viewHeight").setFloat(program, 8);
                    program.setFragment(.1f,.2f); program.run(STATE);
                    float[] color = outputs(program)[0];
                    for(int i=0;i<3;i++) check(Math.abs(color[i] - (.2f*(i+1))) < .0001f,"full Solas sharpen must preserve uniform image");
                    program.setFragment(.9f,.8f); program.run(STATE);
                    for(int i=0;i<3;i++) check(Math.abs(outputs(program)[0][i]-(.2f*(i+1)))<.0001f,"no stale output");
                    System.out.println("EXECUTED complete Solas final source with deterministic constant image: RGB preserved");
                }
                System.out.println("COMPILED complete source " + stem);
            } catch (GlslCompiler.TranslateException unsupported) {
                throw new AssertionError("complete source unexpectedly unsupported after typed 3D foundation: " + stem,unsupported);
            }
        }
        check(good==9,"expected all 9/9 actual fragment stages compile; resource production is a separate gate");
        Path terrain = Path.of(args[1]);
        for(String file : List.of("terrain_solid.vsh","terrain_cutout.vsh")) {
            GlslCompiler.Result meta = new GlslCompiler().translateVertex("SolasVertex"+serial++,Files.readString(terrain.resolve(file)));
            GlslProgram program = compile(meta);
            for(String name : meta.usedUniforms) {
                String signature = meta.uniformTypes.get(name);
                Object value = switch(signature) {
                    case "float#1" -> name.equals("viewWidth") ? 854f : name.equals("viewHeight") ? 480f : 0f;
                    case "int#1" -> 0; case "boolean#1" -> false;
                    case "float[]#16" -> new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
                    case "float[]#9" -> new float[]{1,0,0,0,1,0,0,0,1};
                    default -> new float[Integer.parseInt(signature.split("#")[1])];
                };
                if(name.equals("iris_ColorModulator")) value=new float[]{1,1,1,1};
                program.getClass().getField(meta.uniformFields.get(name)).set(program,value);
            }
            VertexState.Attributes attributes = new VertexState.Attributes(new float[]{1,2,-10}, new float[]{1,1,1,1}, new float[]{.2f,.3f}, new int[]{240,240}, new float[]{0,1,0}, -1,new float[]{.5f,.5f});
            VertexState state = new VertexState(new CpuImage[0],854,480,0,0, input -> input.name().equals("at_tangent") ? new float[]{1,0,0,1} : attributes.value(input));
            program.runVertex(state);
            near(program.vertexPosition(),new float[]{1+.125f/854*.5f,2-.375f/480*.5f,-10,1});
            System.out.println("EXECUTED complete actual Solas "+file+" liveUniforms="+meta.usedUniforms.size()+"; synthetic neutral material, exact jitter; not vegetation/game equivalence");
        }
        System.out.println("SOLAS COMPATIBILITY PROBE OK: nine fragment stages plus both actual vertex sources; live 3D producers/full pack runtime still incomplete");
    }

    private static GlslProgram fragment(String source) throws Exception {return compile(new GlslCompiler().translate("SolasProbe"+serial++,source,""));}
    private static GlslProgram compile(GlslCompiler.Result result) throws Exception {
        Path dir=Path.of("build/tmp/solas-compatibility-probe"+serial++); Files.createDirectories(dir);
        String name=result.javaSource.substring(result.javaSource.indexOf("public final class ")+19).split(" ",2)[0];
        Path java=dir.resolve(name+".java");Files.writeString(java,result.javaSource);
        int rc=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,"-encoding","UTF-8","-proc:none","-cp",System.getProperty("java.class.path"),"-d",dir.toString(),java.toString());
        check(rc==0,"javac "+name);
        URLClassLoader loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},GlslSolasCompatibilityProbe.class.getClassLoader());
        return (GlslProgram)Class.forName("dev.antimony.gl.glsl.gen."+name,true,loader).getDeclaredConstructor().newInstance();
    }
    private static float[][] outputs(GlslProgram p) throws Exception {return (float[][])p.getClass().getField("out").get(p);}
    private static void near(float[] a,float[] b){check(a.length==b.length,"arity");for(int i=0;i<a.length;i++)check(Float.isFinite(a[i])&&Math.abs(a[i]-b[i])<.0001f,"component "+i+" expected="+Arrays.toString(b)+" actual="+Arrays.toString(a));}
    private static void check(boolean passed,String message){if(!passed)throw new AssertionError(message);}
}

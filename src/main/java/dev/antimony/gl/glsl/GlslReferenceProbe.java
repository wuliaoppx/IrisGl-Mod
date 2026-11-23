package dev.antimony.gl.glsl;

import java.util.*;

/** Exact scalar/vector component copy-in/copy-out regressions, no game or GL startup. */
public final class GlslReferenceProbe {
    private static int serial=1760000000;
    private static final FragState EMPTY=new FragState(){
        public float width(){return 1;}public float height(){return 1;}
        public float[] sample(int unit,float u,float v){throw new IllegalStateException("unbound test sampler");}
    };
    public static void main(String[] args)throws Exception{
        float[] reversed=run("""
            layout(location=0) out vec4 result;
            void change(inout vec3 value){value=vec3(10.0,20.0,30.0);}
            void main(){vec4 color=vec4(1.0,2.0,3.0,9.0);change(color.bgr);result=color;}
            """);
        near(reversed,new float[]{30,20,10,9},"vector swizzle copy-out preserves untouched alpha");
        float[] scalar=run("""
            layout(location=0) out vec4 result;
            void write(out float value){value=7.0;}
            void main(){vec4 color=vec4(1.0,2.0,3.0,4.0);write(color.a);result=color;}
            """);
        near(scalar,new float[]{1,2,3,7},"scalar component out lvalue");
        float[] returnValue=run("""
            layout(location=0) out vec4 result;
            float change(inout vec3 value){value*=2.0;return value.r;}
            void main(){vec4 color=vec4(1.0,2.0,3.0,8.0);float returned=change(color.rgb);result=vec4(color.rgb,returned);}
            """);
        near(returnValue,new float[]{2,4,6,2},"initializer return and copy-out execute once");
        float[] unsigned=run("""
            layout(location=0) out vec4 result;
            void change(inout uvec2 value){value=uvec2(4294967295u,17u);}
            void main(){uvec3 color=uvec3(1u,2u,3u);change(color.zx);result=vec4(float(color.x),float(color.y),float(color.z==4294967295u),1.0);}
            """);
        near(unsigned,new float[]{17,2,1,1},"unsigned vector component values retain bit domain");
        reject("void f(out vec2 value){value=vec2(1.0);}void main(){vec2 c=vec2(0.0);f(c.xx);}","repeated writable swizzle");
        reject("void f(out vec3 value){value=vec3(1.0);}void main(){f(vec3(0.0));}","temporary value cannot be out argument");
        reject("uniform vec3 value;void f(out vec3 v){v=vec3(1.0);}void main(){f(value);}","uniform cannot be written by out call");
        float[] matrixRight=run("""
            int calls=0;layout(location=0) out vec4 result;
            vec4 fetch(){calls++;return vec4(1.0,2.0,3.0,1.0);}
            void main(){vec4 value=mat4(1.0)*fetch();result=vec4(value.xyz,float(calls));}
            """);
        near(matrixRight,new float[]{1,2,3,1},"mat * vector function evaluates once");
        float[] matrixLeft=run("""
            int calls=0;layout(location=0) out vec4 result;
            vec4 fetch(){calls++;return vec4(1.0,2.0,3.0,1.0);}
            void main(){vec4 value=fetch()*mat4(1.0);result=vec4(value.xyz,float(calls));}
            """);
        near(matrixLeft,new float[]{1,2,3,1},"vector function * mat evaluates once");
        int[] samples={0};int id=++serial;
        var sampled=GlslProgramLoader.load(id,"uniform sampler2D source;layout(location=0)out vec4 result;void main(){result=mat4(1.0)*texture(source,vec2(0.5));}","");
        if(sampled==null)throw new AssertionError("matrix sampler compile: "+GlslProgramLoader.failure(id));
        sampled.program.getClass().getField("s$0").setInt(sampled.program,0);
        sampled.program.run(new FragState(){public float width(){return 1;}public float height(){return 1;}
            public float[] sample(int unit,float u,float v){samples[0]++;return new float[]{.25f,.5f,.75f,1};}});
        near(((float[][])sampled.program.getClass().getField("out").get(sampled.program))[0],new float[]{.25f,.5f,.75f,1},"matrix sampler value");
        if(samples[0]!=1)throw new AssertionError("matrix sampler must be fetched once, not "+samples[0]);
        System.out.println("GLSL REFERENCE PROBE OK: vector/scalar copy-in/out, swizzles, uint bits, invalid output rejection and matrix/vector single evaluation");
    }
    private static float[] run(String source)throws Exception{
        int id=++serial;var loaded=GlslProgramLoader.load(id,source,"");
        if(loaded==null)throw new AssertionError("Reference shader compile: "+GlslProgramLoader.failure(id));
        loaded.program.run(EMPTY);
        return ((float[][])loaded.program.getClass().getField("out").get(loaded.program))[0].clone();
    }
    private static void reject(String source,String name){
        try{new GlslCompiler().translate("RejectReference",source,"");throw new AssertionError("Expected rejection: "+name);}
        catch(GlslCompiler.TranslateException expected){}
    }
    private static void near(float[] actual,float[] expected,String name){
        if(actual.length!=expected.length)throw new AssertionError(name+" arity");
        for(int i=0;i<actual.length;i++)if(!Float.isFinite(actual[i])||Math.abs(actual[i]-expected[i])>.0001f)
            throw new AssertionError(name+" expected="+Arrays.toString(expected)+" actual="+Arrays.toString(actual));
    }
}

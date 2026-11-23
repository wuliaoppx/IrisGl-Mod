package dev.antimony.gl.glsl;

import java.util.*;

/** Current comparison-sampler domains and MakeUp's single-channel attachment formats. */
public final class GlslShadowSamplerProbe {
    private static int serial=1780000000;
    public static void main(String[] args)throws Exception{
        int[] samples=new int[2];
        var program=load("""
            uniform sampler2DShadow left;
            uniform sampler2DShadow right;
            layout(location=0)out vec4 color;
            int calls=0;
            vec3 coordinate(){calls++;return vec3(.5,.5,.5);}
            void main(){float a=shadow2D(left,coordinate()).r;float b=texture(right,vec3(.5));color=vec4(a,b,float(calls),1.0);}
            """);
        check(program.meta.samplerTypes.equals(Map.of("left","sampler2DShadow","right","sampler2DShadow")),"distinct sampler metadata");
        program.program.getClass().getField("s$0").setInt(program.program,0);
        program.program.getClass().getField("s$1").setInt(program.program,1);
        program.program.run(new FragState(){
            public float width(){return 1;}public float height(){return 1;}
            public float[] sample(int unit,float u,float v){throw new AssertionError("shadow must not become ordinary texture sample");}
            public float sampleShadow(int unit,float u,float v,float reference){samples[unit]++;return unit==0?.25f:.75f;}
        });
        near(((float[][])program.program.getClass().getField("out").get(program.program))[0],new float[]{.25f,.75f,1,1},"bound unit selection and single coordinate evaluation");
        check(Arrays.equals(samples,new int[]{1,1}),"each shadow lookup executes once");
        var passed=load("uniform sampler2DShadow source;layout(location=0)out vec4 color;float readShadow(sampler2DShadow value,vec3 uv){return texture(value,uv);}void main(){color=vec4(readShadow(source,vec3(.5)));}");
        check(passed.meta.usedSamplers.contains("source"),"sampler argument remains a live binding through function calls");
        passed.program.run(new FragState(){public float width(){return 1;}public float height(){return 1;}
            public float[] sample(int unit,float u,float v){throw new AssertionError();}
            public float sampleShadow(int unit,float u,float v,float reference){return .125f;}});
        near(((float[][])passed.program.getClass().getField("out").get(passed.program))[0],new float[]{.125f,.125f,.125f,.125f},"sampler function parameter");
        CpuImage depth=new CpuImage(2,2);depth.rgba[0]=0;depth.rgba[4]=1;depth.rgba[8]=0;depth.rgba[12]=1;
        check(depth.sampleCompare(.5f,.5f,.5f)==.5f,"PCF compares depth taps before interpolation");
        check(depth.sampleCompare(0,0,2)==0,"reference above depth is shadowed");
        check(depth.sampleCompare(0,0,-1)==1,"reference below depth is lit");
        CpuImage red=new CpuImage(1,1);red.storageEncoding(IrisCpuShadowTargets.encoding("R8"));red.writePixel(0,.3f,.7f,.9f,.1f);
        near(red.rgba,new float[]{Math.round(.3f*255)/255f,0,0,1},"R8 quantization and implicit GBA values");
        CpuImage rg=new CpuImage(1,1);rg.storageEncoding(IrisCpuShadowTargets.encoding("RG16F"));rg.writePixel(0,2.5f,-.5f,4,0);
        near(rg.rgba,new float[]{2.5f,-.5f,0,1},"RG floating channel domain");
        reject("uniform sampler2DShadow s;void main(){float v=texture(s,vec2(.5));}");
        reject("uniform sampler2D s;void main(){vec4 v=shadow2D(s,vec3(.5));}");
        try{new FragState(){public float width(){return 1;}public float height(){return 1;}public float[] sample(int u,float x,float y){return new float[4];}}.sampleShadow(0,new float[]{.5f,.5f,.5f});throw new AssertionError("unbound comparison must fail");}
        catch(IllegalStateException expected){}
        System.out.println("GLSL SHADOW SAMPLER PROBE OK: typed scalar/legacy reads, bound units, single evaluation, PCF and R/RG formats");
    }
    private static GlslProgramLoader.Loaded load(String source){int id=++serial;var result=GlslProgramLoader.load(id,source,"");if(result==null)throw new AssertionError(GlslProgramLoader.failure(id));return result;}
    private static void reject(String source){try{new GlslCompiler().translate("BadShadow",source,"");throw new AssertionError("accepted invalid shadow signature");}catch(GlslCompiler.TranslateException expected){}}
    private static void near(float[] actual,float[] expected,String description){check(actual.length==expected.length,description);for(int i=0;i<actual.length;i++)check(Math.abs(actual[i]-expected[i])<.00001f,description+" "+Arrays.toString(actual));}
    private static void check(boolean value,String description){if(!value)throw new AssertionError(description);}
}

package dev.antimony.gl.glsl;

import java.util.*;

/** CPU-only per-attachment blend/half encoding transaction probe. */
public final class TerrainBlendProbe {
    public static final class Mode {int srcRgb=0x0302,dstRgb=0x0303,srcAlpha=1,dstAlpha=0x0303;}
    public static final class Override {Object blendMode;Override(Object mode){blendMode=mode;}}
    public static final class BufferOverride {int drawBuffer;Object blendMode;BufferOverride(int slot,Object mode){drawBuffer=slot;blendMode=mode;}}
    public static final class Shader {Object blendModeOverride;List<BufferOverride> bufferBlendOverrides=List.of();}
    public static void main(String[] args){
        Shader shader=new Shader();
        var inherited=new TerrainFragmentDraw.Blend(true,0x0302,0x0303,1,0x0303);
        check(IrisBlendState.capture(shader,2,inherited).equals(List.of(inherited,inherited)),"inherit RenderType separate-alpha state");
        shader.blendModeOverride=new Override(null);
        check(IrisBlendState.capture(shader,2,inherited).equals(List.of(TerrainFragmentDraw.Blend.OFF,TerrainFragmentDraw.Blend.OFF)),"effective program OFF disables inherited transparency");
        shader.blendModeOverride=new Override(new Mode());shader.bufferBlendOverrides=List.of(new BufferOverride(1,null));
        check(IrisBlendState.capture(shader,2,TerrainFragmentDraw.Blend.OFF).equals(List.of(inherited,TerrainFragmentDraw.Blend.OFF)),"per-output override uses location rather than RT number");
        shader.bufferBlendOverrides=List.of(new BufferOverride(1,null),new BufferOverride(1,new Mode()));
        try{IrisBlendState.capture(shader,2,inherited);throw new AssertionError("Duplicate effective blend override accepted");}catch(IllegalStateException expected){}

        var vertex=new GlslCompiler().translateVertex("BlendLinkVertex","void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        var loaded=GlslProgramLoader.load(1770000001,"""
            uniform float value;
            layout(location=0) out vec4 color;
            layout(location=1) out vec4 halfColor;
            layout(location=2) out vec4 packedColor;
            void main(){color=vec4(10.0,20.0,30.0,0.5);halfColor=vec4(value,0.3333,2.0,0.5);packedColor=vec4(0.50390625,0.51171875,0.00000095367431640625,0.6);}
            ""","");
        check(loaded!=null,"blend/encoding shader compiled");
        var layout=ShaderVaryings.link(vertex,loaded.meta);
        CpuImage main=new CpuImage(1,1),half=new CpuImage(1,1),packed=new CpuImage(1,1);
        main.clear(6,4,2,.25f);half.clear(0,0,0,0);
        var attachments=Map.of(
            0,new TerrainFragmentDraw.Attachment(main,TerrainFragmentDraw.Encoding.RGBA32F,new TerrainFragmentDraw.Blend(true,0x0302,0x0303,1,0x0303)),
            3,new TerrainFragmentDraw.Attachment(half,TerrainFragmentDraw.Encoding.RGBA16F,TerrainFragmentDraw.Blend.OFF),
            6,new TerrainFragmentDraw.Attachment(packed,TerrainFragmentDraw.Encoding.R11F_G11F_B10F,TerrainFragmentDraw.Blend.OFF));
        var draw=new TerrainFragmentDraw(loaded.program,loaded.meta,layout,Map.of("value",65504f),Map.of(),new int[]{0,3,6},attachments,1,1,null);
        check(draw.handlesBlend(),"explicit captured per-attachment blend contract");
        float[] triangle=new float[51];triangle[3]=triangle[20]=triangle[37]=1;
        int result=draw.worker().shade(triangle,17,34,51,1,0,0,1,1,1,0,0,.5f,1,true,true);
        check(result==2,"HDR outputs committed");
        near(main.rgba[0],8,"source-alpha RGB blend");near(main.rgba[1],12,"green blend");near(main.rgba[2],16,"blue blend");
        near(main.rgba[3],.625f,"separate alpha source ONE/destination ONE_MINUS_SRC_ALPHA");
        near(half.rgba[0],65504,"largest half survives");near(half.rgba[1],Float.float16ToFloat(Float.floatToFloat16(.3333f)),"real half conversion");
        near(packed.rgba[0],.5f,"R 6-bit mantissa ties-to-even low");
        near(packed.rgba[1],.515625f,"G 6-bit mantissa ties-to-even high");
        near(packed.rgba[2],0,"B 5-bit subnormal half-way ties-to-even zero");near(packed.rgba[3],1,"packed format implicit alpha one");
        var packedEncoding=TerrainFragmentDraw.Encoding.R11F_G11F_B10F;
        near(packedEncoding.encode(Math.scalb(1f,-20),0),Math.scalb(1f,-20),"R minimum subnormal");
        near(packedEncoding.encode(Math.scalb(1f,-19),2),Math.scalb(1f,-19),"B minimum subnormal");
        near(packedEncoding.encode(70000,0),65024,"R finite overflow clamps");near(packedEncoding.encode(70000,2),64512,"B finite overflow clamps");
        near(packedEncoding.encode(Float.POSITIVE_INFINITY,1),65024,"R positive infinity clamps");
        near(packedEncoding.encode(-1,0),0,"negative values clamp to zero");
        packed.storageEncoding(packedEncoding);packed.clear(.50390625f,.51171875f,.00000095367431640625f,.25f);
        near(packed.rgba[0],.5f,"packed clear uses attachment encoding");near(packed.rgba[3],1,"packed clear alpha one");
        main.clear(9,8,7,.25f);half.clear(6,5,4,3);
        var overflow=new TerrainFragmentDraw(loaded.program,loaded.meta,layout,Map.of("value",70000f),Map.of(),new int[]{0,3,6},attachments,1,1,null);
        boolean rejected=false;
        try{overflow.worker().shade(triangle,17,34,51,1,0,0,1,1,1,0,0,.5f,1,true,true);}catch(IllegalStateException expected){rejected=true;}
        check(rejected,"finite float overflowing half rejected");
        near(main.rgba[0],9,"late half overflow cannot partially commit location zero");near(half.rgba[0],6,"half target unchanged on failure");
        System.out.println("TERRAIN BLEND PROBE OK: per-attachment blending, half encoding, R11F_G11F_B10F ties/subnormals/clamps/alpha, format-aware clears and atomic precommit validation");
    }
    private static void near(float a,float b,String name){if(!Float.isFinite(a)||Math.abs(a-b)>.0001f)throw new AssertionError(name+" expected="+b+" actual="+a);}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
}

package dev.antimony.gl.glsl;

import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Synthetic 3D/GLSL tests plus complete unchanged Solas terrain stages. NOT world/producer/visual equivalence. */
public final class GlslVolumeProbe {
    private static int serial;
    public static void main(String[] args) throws Exception {
        CpuVolume volume=new CpuVolume(2,2,2,CpuVolume.Format.RGBA16F,true,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++)volume.store(x,y,z,new float[]{x+2*y+8*z,.3333f,50,1});
        near(volume.sample(.5f,.5f,.5f)[0],5.5f,"trilinear uses eight real xyz neighbors");
        near(volume.sample(.25f,.25f,.25f)[0],0,"first texel center, no framebuffer Y flip");
        near(volume.sample(.25f,.25f,.75f)[0],8,"z coordinate not discarded");
        near(volume.sample(Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE)[0],11,"large finite clamp coordinate");
        near(volume.fetch(0,0,0,0)[1],Float.float16ToFloat(Float.floatToFloat16(.3333f)),"actual half conversion");
        near(volume.fetch(0,0,0,0)[2],50,"HDR storage not clipped");
        CpuVolume snapshot=volume.detached();volume.store(0,0,0,new float[]{99,0,0,0});near(snapshot.fetch(0,0,0,0)[0],0,"frozen snapshot separated from producer writes");
        reject(()->snapshot.store(0,0,0,new float[4]),"frozen volume mutation");reject(()->snapshot.fetch(0,0,0,1),"unsupported mip fetch");reject(()->snapshot.sampleLod(.5f,.5f,.5f,1),"unsupported mip sample");
        CpuVolume integer=new CpuVolume(2,1,1,CpuVolume.Format.R8UI,false,CpuVolume.Wrap.REPEAT,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);
        integer.storeUnsigned(0,0,0,255);integer.storeUnsigned(1,0,0,256);
        check(integer.fetchUnsigned(0,0,0,0)[0]==255&&integer.fetchUnsigned(1,0,0,0)[0]==0,"R8UI unsigned byte conversion");
        check(integer.sampleUnsigned(1.25f,.5f,.5f)[0]==255,"integer nearest repeat");
        check(integer.fetchUnsigned(-1,0,0,0)[0]==0,"robust OOB zero is distinct from missing binding");
        reject(()->integer.sample(.2f,.5f,.5f),"floating read of unsigned volume");reject(()->snapshot.fetchUnsigned(0,0,0,0),"unsigned read of floating volume");
        reject(()->new CpuVolume(2,2,2,CpuVolume.Format.R8UI,true,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE),"integer linear filter");
        System.out.println("PASS 3D xyz/eight-neighbor filter, half/HDR storage, R8UI domain, wrap/OOB, mip rejection and detached immutable snapshots");

        GlslCompiler.Result semantics=new GlslCompiler().translate("VolumeSemantics"+serial++,"""
            uniform sampler3D volume;
            uniform usampler3D declaredUnused;
            const vec3[] palette=vec3[](vec3(1.0,2.0,3.0),vec3(4.0,5.0,6.0));
            int calls=0;
            vec3 coordinate(){calls++;return vec3(0.25,0.25,0.75);}
            vec4 read(sampler3D image,vec3 p){return texture(image,p);}
            layout(location=0) out vec4 c;
            void main(){
                vec4 color=read(volume,coordinate());
                int n=((5 & 1)<<2) | (2 ^ 1);
                c=vec4(color.r,float(calls),palette[1].b,float(n+palette.length()));
            }
            ""","");
        check(semantics.samplerTypes.get("volume").equals("sampler3D")&&semantics.samplerTypes.get("declaredUnused").equals("usampler3D"),"sampler domains retained");
        check(semantics.usedSamplers.equals(Set.of("volume")),"unused 3D declaration not live");
        GlslProgram program=compile(semantics);program.getClass().getField("s$0").setInt(program,0);
        FragState state=new FragState(){public float width(){return 4;}public float height(){return 4;}public float[] sample(int unit,float u,float v){throw new AssertionError("3D collapsed to 2D");}public float[] sample3D(int unit,float u,float v,float w){check(unit==0,"correct unit");return snapshot.sample(u,v,w);}};
        program.run(state);float[] out=outputs(program)[0];near(out[0],8,"translated real 3D z read");near(out[1],1,"coordinate/helper evaluated once");near(out[2],6,"type-prefix unsized array");near(out[3],9,"int bitwise/shift and array length");
        reject(()->program.run(EMPTY),"missing 3D service fails instead of feeding black 2D");
        reject(()->new GlslCompiler().translate("WrongDim","uniform sampler3D image;layout(location=0) out vec4 c;void main(){c=texture(image,vec2(0.5));}",""),"3D vec2 coordinate rejected");
        reject(()->new GlslCompiler().translate("WrongBits","layout(location=0) out vec4 c;void main(){float f=1.0&1.0;c=vec4(f);}",""),"float bitwise rejected");
        reject(()->new GlslCompiler().translate("WrongDerivative","in float x;layout(location=0) out vec4 c;void main(){c=vec4(dFdx(x+1.0));}",""),"unimplemented derivative expression rejected");
        System.out.println("PASS generated sampler3D helper/coordinate single evaluation, strict dimensions, unsized arrays/length, integer bitwise, missing service rejection");

        reject(()->new GlslCompiler().translate("ParameterDerivative","in float a,b;layout(location=0) out vec4 c;float h(float a){return dFdx(a);}void main(){c=vec4(h(b)+a*0.0);}",""),"parameter cannot impersonate same-name global varying");
        GlslCompiler.Result deadDerivative=new GlslCompiler().translate("DeadDerivative","in float unused;layout(location=0) out vec4 c;float dead(){return dFdx(unused);}void main(){c=vec4(1.0);}","");
        check(deadDerivative.usedVaryings.isEmpty()&&deadDerivative.derivativeInputs.isEmpty()&&IrisPostChain.postInputsSupported(deadDerivative),"dead helper derivative is not an executable dependency");
        GlslCompiler.Result deadSampler=new GlslCompiler().translate("DeadSampler","uniform sampler3D unused;layout(location=0) out vec4 c;vec4 dead(){return texture(unused,vec3(0.5));}void main(){c=vec4(1.0);}","");
        check(deadSampler.usedSamplers.isEmpty()&&IrisPostChain.postInputsSupported(deadSampler),"dead helper 3D sampler not a live resource");
        GlslCompiler.Result activeDerivative=new GlslCompiler().translate("ActiveDerivative","in float a;float value=dFdx(a);layout(location=0) out vec4 c;void main(){c=vec4(value);}","");
        check(activeDerivative.derivativeInputs.equals(Set.of("a"))&&!IrisPostChain.postInputsSupported(activeDerivative),"executed global derivative initializer stays live");
        GlslCompiler.Result vertex3D=new GlslCompiler().translateVertex("Vertex3D","uniform sampler3D volume;out vec4 color;void main(){color=texture(volume,vec3(0.5));gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        check(IrisPostChain.postStagesSupported(deadDerivative,vertex3D),"VS 3D type is supported; current producer binding is checked independently at execution");
        System.out.println("PASS derivative Var identity/shadow rejection, helper/global-initializer dependency closure, joint VS/FS pre-clear capability gate");
        GlslCompiler.Result unaryMeta=new GlslCompiler().translate("UnaryInt"+serial++,"""
            int calls=0;vec3 f(){calls++;return vec3(1.0,-2.0,3.0);}
            layout(location=0) out vec4 c;
            void main(){vec3 v=-normalize(f());int a=max(16777217,0);int b=min(a,16777218);int d=clamp(b,16777216,16777218);c=vec4(v,float(calls));if(d!=16777217)c=vec4(99.0);}
            ""","");
        GlslProgram unary=compile(unaryMeta);unary.run(EMPTY);float[] unaryOutput=outputs(unary)[0];
        near(unaryOutput[0],-1f/(float)Math.sqrt(14),"whole returned vector negated");near(unaryOutput[1],2f/(float)Math.sqrt(14),"negated vector y");near(unaryOutput[3],1,"nested vector unary function executes once");
        System.out.println("PASS returned-vector unary negation single evaluation, exact integer min/max/clamp above float exact-integer range");
        dev.antimony.gl.shim.GlShimState.createProgram(91001);dev.antimony.gl.shim.GlShimState.createShader(91002,0x91B9);
        String computeSource="#version 430\nlayout(local_size_x=8) in;void main(){}";
        dev.antimony.gl.shim.GlShimState.setShaderSource(91002,computeSource);dev.antimony.gl.shim.GlShimState.attachShader(91001,91002);
        dev.antimony.gl.shim.GlShimState.linkProgram(91001);dev.antimony.gl.shim.GlShimState.deleteShader(91002);
        check(computeSource.equals(dev.antimony.gl.shim.GlShimState.program(91001).computeSource),"compute source survives native shader deletion");dev.antimony.gl.shim.GlShimState.deleteProgram(91001);
        System.out.println("PASS compute source link-time capture survives shader deletion; no compute execution claim");
        GlslCompiler.Result vm=new GlslCompiler().translateVertex("FullTerrainV"+serial++,Files.readString(Path.of(args[0],"terrain_solid.vsh")));
        GlslCompiler.Result fm=new GlslCompiler().translate("FullTerrainF"+serial++,Files.readString(Path.of(args[0],"terrain_solid.fsh")),"");
        GlslProgram vp=compile(vm),fp=compile(fm);ShaderVaryings link=ShaderVaryings.link(vm,fm);
        check(link.components()==30,"actual complete Solas live payload includes executed vTexCoord/vTexCoordAM expressions: "+link.components());
        check(fm.derivativeInputs.equals(Set.of("texCoord")),"actual direct global derivative provider required");
        check(!IrisPostChain.postInputsSupported(fm),"compilation cannot bypass typed producer preflight");
        Map<String,Object> vertexUniforms=labUniforms(vm,64,0),fragmentUniforms=labUniforms(fm,64,0);
        for(var entry:vertexUniforms.entrySet())vp.getClass().getField(vm.uniformFields.get(entry.getKey())).set(vp,entry.getValue());
        int stride=17+link.components();float[] triangle=new float[3*stride];ShaderVaryings.Access capture=link.vertexAccess(vp);
        float[][] positions={{-.75f,.75f,-.5f},{.75f,.75f,-.5f},{-.75f,-.75f,-.5f}};
        for(int i=0;i<3;i++){
            VertexState.Attributes input=new VertexState.Attributes(positions[i],new float[]{1,1,1,1},new float[]{.2f+i*.1f,.3f},new int[]{0,240},new float[]{0,1,0},-1,new float[]{.5f,.5f});
            vp.runVertex(new VertexState(new CpuImage[0],64,64,0,0,attribute->attribute.name().equals("at_tangent")?new float[]{1,0,0,1}:input.value(attribute)));
            System.arraycopy(vp.vertexPosition(),0,triangle,i*stride,4);capture.capture(triangle,i*stride+17);
        }
        link.flatten(triangle,3,stride,17,2);
        Map<String,CpuImage> textures=new HashMap<>();
        for(String name:fm.usedSamplers)if(fm.samplerTypes.get(name).equals("sampler2D")){
            CpuImage image=new CpuImage(4,4,false,true,true,true);
            if(name.equals("gtexture"))image.clear(.2f,.4f,.6f,1);else if(name.equals("noisetex"))image.clear(.5f,.5f,.5f,1);else image.clear(1,1,1,1);
            textures.put(name,image);
        }
        CpuVolume even=new CpuVolume(2,2,2,CpuVolume.Format.RGBA16F,true,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);
        CpuVolume odd=new CpuVolume(2,2,2,CpuVolume.Format.RGBA16F,true,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++){even.store(x,y,z,new float[]{.25f,.1f,.15f,0});odd.store(x,y,z,new float[]{.8f,.7f,.6f,0});}
        Map<String,CpuVolume> bindings=Map.of("floodfillSampler",even,"floodfillSamplerCopy",odd);
        float[][] results=new float[2][];
        for(int parity=0;parity<2;parity++){
            fragmentUniforms.put("frameCounter",parity);
            Map<Integer,TerrainFragmentDraw.Attachment> targets=new HashMap<>();for(int rt:new int[]{0,3,6,7})targets.put(rt,new TerrainFragmentDraw.Attachment(new CpuImage(64,64),TerrainFragmentDraw.Encoding.RGBA32F));
            TerrainFragmentDraw draw=new TerrainFragmentDraw(fp,fm,link,fragmentUniforms,textures,bindings,new int[]{0,3,6,7},targets,64,64,.1f);
            var worker=draw.worker();worker.prepareTriangle(triangle,17,stride+17,2*stride+17,0,0,64,64);
            int result=worker.shade(triangle,17,stride+17,2*stride+17,.3f,.3f,.4f,1,1,1,24,24,.25f,1,true,true);
            check(result==2,"full actual FS executes/commits with all explicit LAB inputs");
            results[parity]=Arrays.copyOfRange(targets.get(0).image().rgba,(24*64+24)*4,(24*64+24)*4+4);
            for(var target:targets.values())for(float value:target.image().rgba)check(Float.isFinite(value),"full source outputs finite");
            System.out.println("EXECUTED complete unchanged Solas terrain FS LAB parity="+parity+" out0="+Arrays.toString(results[parity]));
        }
        check(Math.abs(results[0][0]-results[1][0])>.001f,"actual full FS uses frame parity to read different provided 3D images");
        System.out.println("PASS complete actual Solas VS/FS javac/link liveUniforms="+fm.usedUniforms+" samplers="+fm.usedSamplers+" derivatives="+fm.derivativeInputs);
        System.out.println("GLSL VOLUME PROBE OK: populated synthetic resources/explicit LAB uniforms, NOT voxel producer execution or in-game Solas compatibility");
    }
    private static Map<String,Object> labUniforms(GlslCompiler.Result meta,int size,int frame){
        Map<String,Object> values=new HashMap<>();
        for(String name:meta.usedUniforms){Object value=switch(name){
            case "viewWidth","viewHeight"->(float)size;case "frameCounter"->frame;case "heldItemId","heldItemId2"->0;
            case "iris_currentAlphaTest"->.1f;case "frameTimeCounter"->.25f;case "framemod8","nightVision"->0f;case "far"->128f;
            case "atlasSize"->new int[]{4,4};case "cameraPosition","iris_ChunkOffset"->new float[]{0,0,0};case "relativeEyePosition"->new float[]{0,1.62f,0};
            case "lightningBoltPosition"->new float[]{0,0,0,0};case "iris_ColorModulator"->new float[]{1,1,1,1};
            case "gbufferProjectionInverse","gbufferModelView","gbufferModelViewInverse","shadowProjection","shadowModelView","iris_TextureMat","iris_ModelViewMat","iris_ProjMat"->identity();
            case "iris_NormalMat"->new float[]{1,0,0,0,1,0,0,0,1};
            case "iris_LightmapTextureMatrix"->new float[]{1/256f,0,0,0,0,1/256f,0,0,0,0,1,0,1/32f,1/32f,0,1};
            default->throw new AssertionError("LAB must explicitly supply each real live uniform, unexpected "+name+" "+meta.uniformTypes.get(name));
        };values.put(name,value);}return values;
    }
    private static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static final FragState EMPTY=new FragState(){public float width(){return 1;}public float height(){return 1;}public float[] sample(int unit,float u,float v){throw new IllegalStateException("missing sampler "+unit);}};
    private static GlslProgram compile(GlslCompiler.Result meta) throws Exception{
        String prefix="public final class ";int start=meta.javaSource.indexOf(prefix)+prefix.length();String name=meta.javaSource.substring(start,meta.javaSource.indexOf(' ',start));Path dir=Path.of("build/tmp/volume-probe",name);Files.createDirectories(dir);Path source=dir.resolve(name+".java");Files.writeString(source,meta.javaSource);
        int rc=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,"-proc:none","-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",dir.toString(),source.toString());check(rc==0,"javac "+name);
        return (GlslProgram)Class.forName("dev.antimony.gl.glsl.gen."+name,true,new URLClassLoader(new URL[]{dir.toUri().toURL()},GlslVolumeProbe.class.getClassLoader())).getConstructor().newInstance();
    }
    private static float[][] outputs(GlslProgram program)throws Exception{return (float[][])program.getClass().getField("out").get(program);}
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    private static void near(float value,float expected,String text){check(Float.isFinite(value)&&Math.abs(value-expected)<.0002f,text+" expected="+expected+" actual="+value);}
    private static void reject(Runnable call,String text){try{call.run();throw new AssertionError("Expected rejection: "+text);}catch(IllegalArgumentException|IllegalStateException|GlslCompiler.TranslateException expected){}}
}

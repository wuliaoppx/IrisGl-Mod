package dev.antimony.core.irisgl;

import dev.antimony.gl.glsl.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;

/** Actual production tile/raster + generated VS/FS numerical regression. Synthetic draws are not pack equivalence. */
public final class TerrainFragmentRasterProbe {
    private static int serial;
    private static final String VS="""
        uniform vec4 clip;
        uniform float value;
        uniform int materialInput;
        smooth out float sm;
        noperspective out float np;
        flat out int material;
        out vec4 unused;
        void main(){gl_Position=clip;sm=value;np=value;material=materialInput;unused=vec4(7.0);}
        """;
    private static final String FS="""
        smooth in float sm;
        noperspective in float np;
        flat in int material;
        in vec4 declaredDead;
        uniform float gain, alpha;
        layout(location=0) out vec4 c0;
        layout(location=1) out vec4 c1;
        layout(location=2) out vec4 c2;
        layout(location=3) out vec4 c3;
        float helper(){return sm;}
        float unreachable(){return declaredDead.x;}
        void main(){
            c0=vec4(helper()*gain,np,float(material),alpha);
            c1=gl_FragCoord;
            c2=vec4(np/40.0,0.25,0.75,1.0);
            c3=vec4(2.0,3.0,4.0,0.0);
        }
        """;
    public static void main(String[] args) throws Exception {
        GlslCompiler.Result vm=new GlslCompiler().translateVertex("TerrainV"+serial++,VS);
        GlslCompiler.Result fm=new GlslCompiler().translate("TerrainF"+serial++,FS,"");
        GlslProgram vp=compile(vm),fp=compile(fm);
        ShaderVaryings linked=ShaderVaryings.link(vm,fm);
        check(fm.usedVaryings.equals(Set.of("sm","np","material")),"reachable helper inputs/dead helper excluded: "+fm.usedVaryings);
        check(linked.components()==3,"typed live link stride");
        check(linked.lanes().get(2).variable().interpolation()==ShaderVaryings.Interpolation.FLAT,"integer material is flat");
        reject(() -> ShaderVaryings.link(vm,new GlslCompiler().translate("Bad",FS.replace("flat in int material","smooth in int material"),"")),"qualifier mismatch");
        reject(() -> ShaderVaryings.link(vm,new GlslCompiler().translate("Bad",FS.replace("flat in int material","flat in float material"),"")),"type mismatch");
        reject(() -> ShaderVaryings.link(vm,new GlslCompiler().translate("Bad",FS.replace("smooth in float sm","smooth in float missing").replace("return sm","return missing"),"")),"missing live producer");
        reject(() -> ShaderVaryings.link(new GlslCompiler().translateVertex("MatrixV","out mat2 m;void main(){m=mat2(1.0,0.0,0.0,1.0);gl_Position=vec4(0.0);}"),new GlslCompiler().translate("MatrixF","in vec4 m;layout(location=0) out vec4 c;void main(){c=m;}","")),"mat2 is not vec4 despite shared Java signature");
        GlslCompiler.Result locationV=new GlslCompiler().translateVertex("LocV","layout(location=2) out vec2 original;void main(){original=vec2(2.0);gl_Position=vec4(0.0);}");
        GlslCompiler.Result locationF=new GlslCompiler().translate("LocF","layout(location=2) in vec2 renamed;layout(location=0) out vec4 c;void main(){c=vec4(renamed,0.0,1.0);}","");
        ShaderVaryings locationLink=ShaderVaryings.link(locationV,locationF);
        float[] linkedValues={.2f,.8f};GlslProgram locationProgram=compile(locationF);
        locationLink.fragmentAccess(locationProgram).install(linkedValues,0);locationProgram.run(EMPTY);
        near(((float[][])locationProgram.getClass().getField("out").get(locationProgram))[0][0],.2f,"location linked field rename");
        System.out.println("PASS typed/name/location link, helper reachability, fail-closed missing/type/qualifier mismatch");
        GlslCompiler.Result deadV=new GlslCompiler().translateVertex("DeadV","out float valid;out float unused;void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);valid=3.0;unused=sqrt(-1.0);}");
        GlslCompiler.Result deadF=new GlslCompiler().translate("DeadF","in float valid;layout(location=0) out vec4 c;void main(){c=vec4(valid);}","");
        GlslProgram deadProgram=compile(deadV);deadProgram.runVertex(new VertexState(new CpuImage[0],4,4,0,0));
        ShaderVaryings deadLink=ShaderVaryings.link(deadV,deadF);float[] deadValue=new float[1];deadLink.vertexAccess(deadProgram).capture(deadValue,0);near(deadValue[0],3,"dead NaN output does not poison linked payload");

        int stride=17+linked.components();
        float[] triangle=triangle(vp,linked,stride,16777217);
        Map<Integer,TerrainFragmentDraw.Attachment> attachments=attachments(4,4);
        Map<String,Object> uniforms=new HashMap<>();uniforms.put("gain",50f);uniforms.put("alpha",.5f);
        TerrainFragmentDraw draw=new TerrainFragmentDraw(fp,fm,linked,uniforms,Map.of(),new int[]{0,3,6,7},attachments,4,4,.1f);
        uniforms.put("gain",0f);
        IrisGlContext context=context(4,4);TileRasterEngine engine=new TileRasterEngine(context);
        float[] originalTriangle=triangle.clone();
        engine.binTriangle(triangle,0,stride,2*stride,stride,draw);
        for(int lane=0;lane<triangle.length;lane++)check(Float.floatToRawIntBits(triangle[lane])==Float.floatToRawIntBits(originalTriangle[lane]),"caller vertex arena mutated at "+lane);
        // Queued draw must use its original viewport, not this later caller state.
        context.viewportW=context.viewportH=1;
        engine.flush();
        float[] c0=attachments.get(0).image().rgba,c1=attachments.get(3).image().rgba,c2=attachments.get(6).image().rgba,c3=attachments.get(7).image().rgba;
        near(context.framebuffer.depth[5],.75f,"affine screen depth");
        near(c0[20],50*110f/7,"smooth generated varying + HDR not clamped");
        near(c0[21],20,"noperspective screen affine");
        near(c1[20],1.5f,"gl_FragCoord x center");near(c1[21],2.5f,"gl_FragCoord bottom-up y");
        near(c1[22],.75f,"gl_FragCoord z");near(c1[23],7f/12,"gl_FragCoord reciprocal w");
        near(c2[20],Math.round(.5f*65535)/65535f,"UNORM16 attachment conversion");near(c3[23],1,"RGB-only read alpha");
        check(context.framebuffer.color[5]==0,"successful FS did not double-run ordinary ARGB shading");
        System.out.println("PASS production tile FS/MRT location0->RT0,1->RT3,2->RT6,3->RT7; HDR > 50; gl_FragCoord xyz/w; frozen uniform/viewport; no ordinary double shading");

        for(int material:new int[]{-1,Integer.MIN_VALUE,16777217,0x7f800001}) {
            float[] t=triangle(vp,linked,stride,material);
            // Clip first vertex through near plane; flat bits must survive all generated vertices.
            t[2]=-2;
            linked.flatten(t,3,stride,17,2);
            float[] clipped=new float[HomogeneousClipper.MAX_VERTICES*stride],tmp=clipped.clone();
            int n=HomogeneousClipper.triangle(t,0,stride,2*stride,stride,clipped,tmp,linked.modes(),17);
            check(n==4,"near clipped quad");
            int materialLane=linked.lanes().get(2).offset();
            for(int i=0;i<n;i++)check(Float.floatToRawIntBits(clipped[i*stride+17+materialLane])==material,"flat 32-bit material through clip "+material);
        }
        float[] clipTest={0,0,-2,1,10, 1,0,0,2,20, 0,1,0,2,30};
        float[] clipped=new float[60],tmp=clipped.clone();int n=HomogeneousClipper.triangle(clipTest,0,5,10,5,clipped,tmp,new byte[]{2},4);
        check(n==4,"noperspective clip polygon");
        near(clipped[4],15,"noperspective generated clip point uses screenT=.5, not homogeneous t=1/3");
        System.out.println("PASS six-plane payload: negative/large/NaN-encoded integer raw bits, original provoking values, unequal-w noperspective clip intersection");

        for(float alpha:new float[]{.1f,.09f}) {
            IrisGlContext c=context(4,4);Map<Integer,TerrainFragmentDraw.Attachment> a=attachments(4,4);
            TerrainFragmentDraw discard=new TerrainFragmentDraw(fp,fm,linked,Map.of("gain",50f,"alpha",alpha),Map.of(),new int[]{0,3,6,7},a,4,4,.1f);
            TileRasterEngine e=new TileRasterEngine(c);float[] t=triangle(vp,linked,stride,-1);e.binTriangle(t,0,stride,2*stride,stride,discard);e.flush();
            near(c.framebuffer.depth[5],1,"strict GREATER alpha equality discarded before depth");
            for(var target:a.values())for(float v:target.image().rgba)check(v==0,"discard did not commit any MRT");
        }
        GlslCompiler.Result discardMeta=new GlslCompiler().translate("DiscardF","layout(location=0) out vec4 c;void main(){c=vec4(100.0);discard;}","");
        ShaderVaryings empty=ShaderVaryings.link(vm,discardMeta);GlslProgram discardProgram=compile(discardMeta);
        IrisGlContext dc=context(4,4);Map<Integer,TerrainFragmentDraw.Attachment> da=attachments(4,4);
        TerrainFragmentDraw discarder=new TerrainFragmentDraw(discardProgram,discardMeta,empty,Map.of(),Map.of(),new int[]{0},da,4,4,null);
        TileRasterEngine de=new TileRasterEngine(dc);float[] base=Arrays.copyOfRange(triangle,0,0);base=new float[51];
        for(int i=0;i<3;i++)System.arraycopy(triangle,i*stride,base,i*17,17);
        de.binTriangle(base,0,17,34,17,discarder);de.flush();near(dc.framebuffer.depth[5],1,"DiscardException does not commit depth");near(da.get(0).image().rgba[20],0,"DiscardException does not commit prior out assignment");
        reject(() -> new TerrainFragmentDraw(fp,fm,linked,Map.of("alpha",1f),Map.of(),new int[]{0,3,6,7},attachments,4,4,null),"missing current uniform");
        System.out.println("PASS strict alpha .1 equality, explicit GLSL discard, depth/MRT commit order, missing uniform rejection");
        IrisGlContext maskContext=context(4,4);maskContext.colorWrite=false;
        Map<Integer,TerrainFragmentDraw.Attachment> masked=attachments(4,4);
        TerrainFragmentDraw maskDraw=new TerrainFragmentDraw(fp,fm,linked,Map.of("gain",50f,"alpha",.5f),Map.of(),new int[]{0,3,6,7},masked,4,4,.1f);
        TileRasterEngine maskEngine=new TileRasterEngine(maskContext);float[] maskTriangle=triangle(vp,linked,stride,-1);
        maskEngine.binTriangle(maskTriangle,0,stride,2*stride,stride,maskDraw);maskContext.colorWrite=true;maskEngine.flush();
        near(maskContext.framebuffer.depth[5],.75f,"depth-only FS commits depth");
        for(var target:masked.values())for(float value:target.image().rgba)check(value==0,"frozen colorWrite=false must not commit MRT");
        GlslCompiler.Result samplingMeta=new GlslCompiler().translate("SamplingF","uniform sampler2D atlas;layout(location=0) out vec4 c;void main(){c=texture(atlas,vec2(0.5));}","");
        GlslProgram sampleProgram=compile(samplingMeta);ShaderVaryings sampleLink=ShaderVaryings.link(vm,samplingMeta);
        CpuImage atlas=new CpuImage(16,16);atlas.clear(.25f,.5f,.75f,1);
        Map<Integer,TerrainFragmentDraw.Attachment> sampled=attachments(4,4);IrisGlContext sampleContext=context(4,4);
        TerrainFragmentDraw sampleDraw=new TerrainFragmentDraw(sampleProgram,samplingMeta,sampleLink,Map.of(),Map.of("atlas",atlas),new int[]{0},sampled,4,4,null);
        atlas.clear(0,0,0,0);TileRasterEngine sampleEngine=new TileRasterEngine(sampleContext);sampleEngine.binTriangle(base,0,17,34,17,sampleDraw);sampleEngine.flush();
        near(sampled.get(0).image().rgba[20],.25f,"resource pixel snapshot detached from caller mutation");
        reject(()->new TerrainFragmentDraw(sampleProgram,samplingMeta,sampleLink,Map.of(),Map.of(),new int[]{0},sampled,4,4,null),"missing sampler rejected");
        GlslCompiler.Result failureMeta=new GlslCompiler().translate("FailureF","layout(location=0) out vec4 c;void main(){c=vec4(50.0);if(gl_FragCoord.x>20.5)c=vec4(1.0/0.0);}","");
        GlslProgram failureProgram=compile(failureMeta);ShaderVaryings failureLink=ShaderVaryings.link(vm,failureMeta);
        IrisGlContext failureContext=context(128,128);Arrays.fill(failureContext.framebuffer.depth,.99f);Arrays.fill(failureContext.framebuffer.color,0xFFDDEEFF);
        Map<Integer,TerrainFragmentDraw.Attachment> rollback=attachments(128,128);rollback.get(0).image().clear(9,8,7,6);
        TerrainFragmentDraw failingDraw=new TerrainFragmentDraw(failureProgram,failureMeta,failureLink,Map.of(),Map.of(),new int[]{0},rollback,128,128,null);
        TileRasterEngine failureEngine=new TileRasterEngine(failureContext);for(int i=0;i<60;i++)failureEngine.binTriangle(base,0,17,34,17,failingDraw);
        reject(failureEngine::flush,"late nonfinite fragment aborts whole batch");
        for(int i=0;i<128*128;i++) {
            near(failureContext.framebuffer.depth[i],.99f,"failed batch restores all depth");check(failureContext.framebuffer.color[i]==0xFFDDEEFF,"failed batch restores ARGB");
            near(rollback.get(0).image().rgba[i*4],9,"failed batch restores every MRT pixel");
        }
        failureEngine.binTriangle(base,0,17,34);failureEngine.flush();
        System.out.println("PASS frozen colorWrite mask/depth-only discard semantics, detached real resource pixels, missing sampler rejection, parallel late-fragment failure restores batch MRT/depth/ARGB");
        GlslCompiler.Result derivativeMeta=new GlslCompiler().translate("DerivativeF","in float sm; noperspective in float np;layout(location=0) out vec4 c;void main(){c=vec4(dFdx(np),dFdy(np),dFdx(sm),dFdy(sm));}","");
        GlslProgram derivativeProgram=compile(derivativeMeta);ShaderVaryings derivativeLink=ShaderVaryings.link(vm,derivativeMeta);
        Map<Integer,TerrainFragmentDraw.Attachment> derivativeTargets=attachments(4,4);IrisGlContext derivativeContext=context(4,4);
        TerrainFragmentDraw derivativeDraw=new TerrainFragmentDraw(derivativeProgram,derivativeMeta,derivativeLink,Map.of(),Map.of(),new int[]{0},derivativeTargets,4,4,null);
        int derivativeStride=17+derivativeLink.components();float[] derivativeTriangle=triangle(vp,derivativeLink,derivativeStride,0);TileRasterEngine derivativeEngine=new TileRasterEngine(derivativeContext);
        derivativeEngine.binTriangle(derivativeTriangle,0,derivativeStride,2*derivativeStride,derivativeStride,derivativeDraw);derivativeEngine.flush();
        float[] derivatives=derivativeTargets.get(0).image().rgba;
        near(derivatives[20],10f/3,"noperspective dFdx actual quad");near(derivatives[21],-20f/3,"GL bottom-up dFdy sign");
        near(derivatives[22],220f/63,"smooth unequal-w dFdx finite difference");near(derivatives[23],-26f/7,"smooth unequal-w dFdy finite difference");
        System.out.println("PASS actual production quad derivatives: normalized winding, GL y direction, smooth unequal-w and noperspective interpolation, not zero defaults");
        IrisGlContext offsetContext=context(7,5);offsetContext.viewportX=1;offsetContext.viewportY=1;offsetContext.viewportW=4;offsetContext.viewportH=3;
        Map<Integer,TerrainFragmentDraw.Attachment> offsetTargets=attachments(7,5);
        TerrainFragmentDraw offsetDraw=new TerrainFragmentDraw(derivativeProgram,derivativeMeta,derivativeLink,Map.of(),Map.of(),new int[]{0},offsetTargets,7,5,null);
        TileRasterEngine offsetEngine=new TileRasterEngine(offsetContext);float[] offsetTriangle=triangle(vp,derivativeLink,derivativeStride,0);
        offsetEngine.binTriangle(offsetTriangle,0,derivativeStride,2*derivativeStride,derivativeStride,offsetDraw);offsetEngine.flush();
        int offsetPixel=(2*7+2)*4;near(offsetTargets.get(0).image().rgba[offsetPixel],10f/3,"nonzero viewport dFdx slope");
        near(offsetTargets.get(0).image().rgba[offsetPixel+1],-80f/9,"odd target height/nonzero viewport GL dFdy slope");
        System.out.println("PASS odd framebuffer height and nonzero viewport origin retain actual derivative slopes");

        // Force parallel dispatch; workers must have independent mutable program instances.
        IrisGlContext pc=context(128,128);Map<Integer,TerrainFragmentDraw.Attachment> pa=attachments(128,128);
        TerrainFragmentDraw parallel=new TerrainFragmentDraw(fp,fm,linked,Map.of("gain",50f,"alpha",.5f),Map.of(),new int[]{0,3,6,7},pa,128,128,.1f);
        TileRasterEngine pe=new TileRasterEngine(pc);float[] pt=triangle(vp,linked,stride,-1);
        for(int i=0;i<60;i++)pe.binTriangle(pt,0,stride,2*stride,stride,parallel);pe.flush();
        near(pa.get(3).image().rgba[(40*128+40)*4],40.5f,"parallel x state isolation");
        near(pa.get(3).image().rgba[(40*128+40)*4+1],87.5f,"parallel y state isolation");
        check(parallel.worker()==parallel.worker(),"one stable worker per thread");
        RasterShader throwing=new RasterShader(){public ShaderVaryings layout(){return empty;}public Worker worker(){return (t,a,b,c,e0,e1,e2,iw0,iw1,iw2,x,y,z,iw,f,colorWrite)->{throw new IllegalStateException("injected worker failure");};}};
        for(int i=0;i<60;i++)pe.binTriangle(base,0,17,34,17,throwing);
        reject(pe::flush,"parallel worker failure is propagated after quiescence");
        pe.binTriangle(base,0,17,34);pe.flush();
        System.out.println("PASS 60-triangle multi-tile independent worker draw, failure propagation/quiescence and reusable arena");

        if(args.length>0) {
            Path terrain=Path.of(args[0]);
            for(String file:List.of("terrain_solid.vsh","terrain_cutout.vsh")) {
                GlslCompiler.Result actual=new GlslCompiler().translateVertex("ActualV"+serial++,Files.readString(terrain.resolve(file)));
                ShaderVaryings actualOutputs=ShaderVaryings.outputs(actual);
                check(actual.varyingOutputs.get("mat").interpolation()==ShaderVaryings.Interpolation.FLAT,"actual Solas flat mat");
                check(actual.varyingOutputs.get("absMidCoordPos").interpolation()==ShaderVaryings.Interpolation.FLAT,"actual Solas flat uv extent");
                check(actualOutputs.components()==actual.vertexOutputs.values().stream().mapToInt(s->Integer.parseInt(s.split("#")[1])).sum(),"all real VS outputs packed");
                compile(actual);
                System.out.println("PASS complete actual Solas "+file+" typed payload components="+actualOutputs.components()+" (not FS execution equivalence)");
            }
            GlslCompiler.Result fragmentMeta=new GlslCompiler().translate("ActualSupportedSyntax",Files.readString(terrain.resolve("terrain_solid.fsh")),"");
            GlslCompiler.Result vertexMeta=new GlslCompiler().translateVertex("ActualLinkV",Files.readString(terrain.resolve("terrain_solid.vsh")));
            compile(fragmentMeta);ShaderVaryings actualLink=ShaderVaryings.link(vertexMeta,fragmentMeta);
            check(actualLink.components()==30,"real live VS/FS link");
            check(fragmentMeta.samplerTypes.containsValue("sampler3D")&&fragmentMeta.derivativeInputs.contains("texCoord"),"actual resources/derivative requirements retained");
            System.out.println("PASS complete actual Solas FS javac/link=30 components; typed 3D producer and real derivatives still required before game activation");
        }
        System.out.println("TERRAIN FRAGMENT RASTER PROBE OK; generic FS numerical contract, NOT complete Solas/MakeUp compatibility");
    }
    private static Map<Integer,TerrainFragmentDraw.Attachment> attachments(int w,int h) {
        Map<Integer,TerrainFragmentDraw.Attachment> a=new HashMap<>();
        a.put(0,new TerrainFragmentDraw.Attachment(new CpuImage(w,h),TerrainFragmentDraw.Encoding.RGBA32F));
        a.put(3,new TerrainFragmentDraw.Attachment(new CpuImage(w,h),TerrainFragmentDraw.Encoding.RGBA32F));
        a.put(6,new TerrainFragmentDraw.Attachment(new CpuImage(w,h),TerrainFragmentDraw.Encoding.RGBA16_UNORM));
        a.put(7,new TerrainFragmentDraw.Attachment(new CpuImage(w,h),TerrainFragmentDraw.Encoding.RGB32F));return a;
    }
    private static IrisGlContext context(int w,int h){IrisGlContext c=new IrisGlContext();c.framebuffer=new IrisGlFramebuffer(w,h);c.framebuffer.clearDepth();c.viewportW=w;c.viewportH=h;return c;}
    private static float[] triangle(GlslProgram vp,ShaderVaryings layout,int stride,int material) throws Exception {
        float[] t=new float[3*stride];ShaderVaryings.Access capture=layout.vertexAccess(vp);
        float[][] clip={{-.75f,.75f,0,1},{1.5f,1.5f,1,2},{-3,-3,4,4}};
        for(int i=0;i<3;i++) {
            vp.getClass().getField("clip").set(vp,clip[i]);vp.getClass().getField("value").setFloat(vp,10*(i+1));
            vp.getClass().getField("materialInput").setInt(vp,i==2?material:i);
            vp.runVertex(new VertexState(new CpuImage[0],4,4,0,0));
            System.arraycopy(vp.vertexPosition(),0,t,i*stride,4);t[i*stride+9]=1;
            capture.capture(t,i*stride+17);
        }
        return t;
    }
    private static final FragState EMPTY=new FragState(){public float width(){return 1;}public float height(){return 1;}public float[] sample(int u,float x,float y){throw new AssertionError("unexpected sampler");}};
    private static GlslProgram compile(GlslCompiler.Result meta) throws Exception {
        String prefix="public final class ";int start=meta.javaSource.indexOf(prefix)+prefix.length();String name=meta.javaSource.substring(start,meta.javaSource.indexOf(' ',start));
        Path dir=Path.of("build/tmp/terrain-fragment-raster-probe",name);Files.createDirectories(dir);Path source=dir.resolve(name+".java");Files.writeString(source,meta.javaSource);
        int rc=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,"-proc:none","-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",dir.toString(),source.toString());check(rc==0,"javac "+name);
        URLClassLoader loader=new URLClassLoader(new URL[]{dir.toUri().toURL()},TerrainFragmentRasterProbe.class.getClassLoader());
        return (GlslProgram)Class.forName("dev.antimony.gl.glsl.gen."+name,true,loader).getConstructor().newInstance();
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static void near(float actual,float expected,String label){check(Float.isFinite(actual)&&Math.abs(actual-expected)<.0002f,label+" expected="+expected+" actual="+actual);}
    private static void reject(Runnable body,String label){try{body.run();throw new AssertionError("Expected rejection: "+label);}catch(IllegalArgumentException|IllegalStateException accepted){}}
}

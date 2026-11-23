package dev.antimony.gl.glsl;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Modifier;

/** Executes unchanged captured Solas compute code on CPU. LAB volumes are not a world voxel producer. */
public final class CpuComputeProbe {
    private static final int SIZE_X=192,SIZE_Y=96,SIZE_Z=192;
    public static void main(String[] args)throws Exception{
        String source=Files.readString(Path.of(args[0]));
        GlslProgramLoader.Loaded loaded=GlslProgramLoader.loadCompute(1700000001,source);
        check(loaded!=null,"full captured compute javac: "+GlslProgramLoader.computeFailure(1700000001));
        check(Arrays.equals(loaded.meta.localSize,new int[]{8,8,8}),"actual local group dimensions");
        check(loaded.meta.usedImages.equals(Set.of("floodfill_img","floodfill_img_copy")),"actual dynamic writes retained");
        check(loaded.meta.usedSamplers.equals(Set.of("voxelSampler","floodfillSampler","floodfillSamplerCopy")),"actual typed read resources retained");
        check(GlslProgramLoader.loadCompute(1700000001,source)==loaded,"stable source cache");
        graphCaptureRegressions(source);
        unsignedSemantics();
        unsignedVectorRegressions();
        Object pipeline=new Object();CpuVolume voxel=volume(CpuVolume.Format.R8UI),main=volume(CpuVolume.Format.RGBA16F),copy=volume(CpuVolume.Format.RGBA16F);
        for(int z=0;z<10;z++)for(int y=0;y<10;y++)for(int x=0;x<10;x++)main.store(x,y,z,new float[]{.05f+x*.015f+y*.003f+z*.004f,.1f+x*.007f,.2f+z*.009f,0});
        voxel.storeUnsigned(1,1,1,4);voxel.storeUnsigned(3,3,3,200);voxel.storeUnsigned(4,4,4,216);
        copy.store(100,50,100,new float[]{13,13,13,13});
        Map<String,CpuVolume> reads=reads(voxel,main,copy),writes=writes(main,copy);
        try(CpuComputeDispatch dispatcher=new CpuComputeDispatch(pipeline,loaded.program,loaded.meta)){
            Map<String,Object> uniform=uniforms(0,new float[]{.1f,.2f,.3f},new float[]{.1f,.2f,.3f});
            reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(new Object(),1,0),uniform,reads,writes,new int[]{24,12,24}),"wrong pipeline");
            reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(pipeline,1,1),uniform,reads,writes,new int[]{24,12,24}),"counter mismatch");
            Map<String,CpuVolume> missing=new HashMap<>(reads);missing.remove("voxelSampler");
            reject(()->dispatcher.dispatchRegion(new CpuComputeDispatch.Frame(pipeline,1,0),uniform,missing,writes,new int[]{1,1,1}),"missing current voxel map");
            reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(pipeline,1,0),uniform,reads,writes,new int[]{1,1,1}),"partial cannot be full producer");
            CpuComputeDispatch.Receipt even=dispatcher.dispatchRegion(new CpuComputeDispatch.Frame(pipeline,1,0),uniform,reads,writes,new int[]{1,1,1});
            check(even.invocations()==512&&!even.fullDomain(),"actual partial invocations labeled incomplete");reject(()->even.verifyFrame(pipeline,1),"partial receipt cannot activate full chain");
            CpuVolume evenMain=even.outputs().get("floodfill_img"),evenCopy=even.outputs().get("floodfill_img_copy");
            near(evenMain.fetch(2,2,2,0)[0],main.fetch(2,2,2,0)[0],"inactive output side retained");
            near(evenCopy.fetch(2,2,2,0)[0],half(referenceFlood(main,2,2,2)[0]),"complete source seven-tap propagation");
            near(evenCopy.fetch(3,3,3,0)[1],half(referenceFlood(main,3,3,3)[1]*GlslRuntime.pow(.1f,1.6f)),"uint voxel tint index and exponent");
            near(evenCopy.fetch(4,4,4,0)[0],0,"last tint palette index black");near(evenCopy.fetch(3,3,3,0)[3],200,"voxel alpha not clamped to one");
            float animation=1-GlslRuntime.cos(GlslRuntime.sin(.25f*3)*5+.25f)*.1f;
            near(evenCopy.fetch(1,1,1,0)[1],half(GlslRuntime.pow(GlslRuntime.pow(.5f,animation),1.6f)),"complete source animated torch emission");
            near(copy.fetch(2,2,2,0)[0],0,"caller output volume untouched until receipt publication");
            Map<String,Object> shifted=uniforms(1,new float[]{1.1f,.2f,.3f},new float[]{.1f,.2f,.3f});
            CpuComputeDispatch.Receipt odd=dispatcher.dispatchRegion(new CpuComputeDispatch.Frame(pipeline,2,1),shifted,reads(voxel,evenMain,evenCopy),writes(evenMain,evenCopy),new int[]{1,1,1});
            near(odd.outputs().get("floodfill_img").fetch(2,2,2,0)[0],half(referenceFlood(evenCopy,3,2,2)[0]),"odd reads copy with floor-camera integer grid migration");
            near(odd.outputs().get("floodfill_img_copy").fetch(2,2,2,0)[0],evenCopy.fetch(2,2,2,0)[0],"odd inactive side unchanged");
            reject(()->dispatcher.dispatchRegion(new CpuComputeDispatch.Frame(pipeline,2,1),shifted,reads(voxel,evenMain,evenCopy),writes(evenMain,evenCopy),new int[]{1,1,1}),"stale generation");
            System.out.println("PASS actual unchanged Solas kernel: 512-invocation partial LAB, seven neighbors, typed R8UI/tint/emitter, half/HDR, even/odd images, camera grid migration, current frame failures");
            int[] declared=((int[])loaded.program.getClass().getField("workGroups").get(loaded.program)).clone();
            check(Arrays.equals(declared,new int[]{24,12,24}),"actual full dispatch groups");
            CpuComputeDispatch.Receipt full=dispatcher.dispatch(new CpuComputeDispatch.Frame(pipeline,3,0),uniform,reads,writes,declared);
            check(full.invocations()==3538944L&&full.fullDomain(),"all actual 192x96x192 invocations completed");full.verifyFrame(pipeline,3);
            reject(()->full.verifyFrame(pipeline,4),"published old output cannot be a new frame");
            near(full.outputs().get("floodfill_img_copy").fetch(100,50,100,0)[0],0,"full domain overwrites distant sentinel beyond LAB region");
            near(full.outputs().get("floodfill_img_copy").fetch(2,2,2,0)[0],half(referenceFlood(main,2,2,2)[0]),"full field semantic equality");
            System.out.println("EXECUTED full unchanged Solas shadowcomp: dimensions=192x96x192 invocations="+full.invocations()+" elapsedMs="+full.nanos()/1_000_000.0+"; CPU LAB data, not world voxelization or game FPS");
        }
        failureTransactions();
        publicationRegressions();
        submissionFailureQuiescence();
        System.out.println("CPU COMPUTE PROBE OK: real captured source execution; actual world producers/MRT lifecycle still incomplete");
    }
    public static final class GraphProgram {
        final int id;final int[] localSize={8,8,8};final org.joml.Vector3i absoluteWorkGroups=new org.joml.Vector3i(24,12,24);
        final org.joml.Vector2f relativeWorkGroups=null;final Object indirectPointer=null;
        GraphProgram(int id){this.id=id;}public int getProgramId(){return id;}
        public void use(){throw new AssertionError("capture must not execute program");}
    }
    public static class GraphPass {final Object program=null;final Object[] computes;GraphPass(Object[] computes){this.computes=computes;}}
    public static final class GraphComputeOnlyPass extends GraphPass {GraphComputeOnlyPass(Object[] computes){super(computes);}}
    public static final class GraphRenderer {final List<GraphPass> passes;GraphRenderer(GraphPass pass){passes=List.of(pass);}}
    public static final class GraphPipeline {
        final Object[] setup=new Object[0],shadowComputes=new Object[0];
        final Object beginRenderer=null,prepareRenderer=null,deferredRenderer=null,compositeRenderer=null,finalPassRenderer=null;
        final GraphRenderer shadowCompositeRenderer;
        GraphPipeline(GraphProgram program){shadowCompositeRenderer=new GraphRenderer(new GraphComputeOnlyPass(new Object[]{null,program}));}
    }
    public record ProbeImageFormat(int code){public int getGlType(){return code;}public int getGlFormat(){return code;}}
    public record ProbeImageInfo(String name,String samplerName,ProbeImageFormat target,ProbeImageFormat format,
        ProbeImageFormat internalTextureFormat,ProbeImageFormat type,int width,int height,int depth,boolean clear,
        boolean isRelative,float relativeWidth,float relativeHeight){}
    public record ProbeImageObject(ProbeImageInfo info,int id){
        public String getName(){return info.name();}public String getSamplerName(){return info.samplerName();}public int getId(){return id;}
        public Object getTarget(){return info.target();}public Object getFormat(){return info.format();}public Object getInternalFormat(){return info.internalTextureFormat();}public Object getPixelType(){return info.type();}public boolean shouldClear(){return info.clear();}
    }
    private static void graphCaptureRegressions(String source)throws Exception{
        int id=1700000020,shader=1700000021;
        dev.antimony.gl.shim.GlShimState.createProgram(id);dev.antimony.gl.shim.GlShimState.createShader(shader,0x91B9);
        dev.antimony.gl.shim.GlShimState.setShaderSource(shader,source);dev.antimony.gl.shim.GlShimState.attachShader(id,shader);
        dev.antimony.gl.shim.GlShimState.linkProgram(id);dev.antimony.gl.shim.GlShimState.deleteShader(shader);
        try{
            check(Arrays.equals(dev.antimony.gl.shim.GlShimState.computeLocalSize(id),new int[]{8,8,8}),"linked compute layout survives shader deletion");
            GraphPipeline pipeline=new GraphPipeline(new GraphProgram(id));var graph=IrisComputeGraph.capture(pipeline);
            ProbeImageInfo info=new ProbeImageInfo("voxel_img","voxelSampler",new ProbeImageFormat(0x806F),new ProbeImageFormat(0x8D94),new ProbeImageFormat(0x8232),new ProbeImageFormat(0x1401),192,96,192,true,false,0,0);
            var images=IrisImageDescriptors.pair(pipeline,List.of(info),Set.of(new ProbeImageObject(info,23)));
            check(images.size()==1&&images.get(0).pipeline()==pipeline&&images.get(0).textureId()==23&&images.get(0).cpuFormat()==CpuVolume.Format.R8UI&&images.get(0).clear()&&images.get(0).depth()==192,"typed declaration pairs actual ID/size/clear policy without readiness");
            try{IrisImageDescriptors.pair(pipeline,List.of(info),Set.of());throw new AssertionError("missing actual image must fail pairing");}catch(IllegalStateException expected){}
            check(graph.pipeline()==pipeline&&graph.requiresExecution()&&graph.nodes().size()==1,"compute-only inherited pass captured");
            var node=graph.nodes().get(0);check(node.slot()==1&&node.pass()==0&&node.stage().equals("shadowCompositeRenderer"),"null array slots do not shift source identity");
            check(node.loaded()!=null&&node.failure()==null&&node.sha256().length()==64,"current linked compute compiles and validates layout");
            check(Arrays.equals(node.dispatch().groups(854,480),new int[]{24,12,24}),"absolute dispatch independent of window dimensions");
            check(graph.requestedUniforms().containsAll(Set.of("frameCounter","cameraPosition","previousCameraPosition","frameTimeCounter")),"compute frame uniform dependencies retained");
            var relative=new IrisComputeGraph.DispatchSpec(List.of(8,8,8),List.of(),List.of(.5f,.25f),false);
            check(Arrays.equals(relative.groups(854,480),new int[]{54,15,1}),"relative workgroups match Iris ceil semantics");
            reject(()->new IrisComputeGraph.DispatchSpec(List.of(8,8,8),List.of(),List.of(),true).groups(854,480),"indirect cannot pretend direct");
            check(Arrays.equals(GlslCompiler.declaredComputeLocalSize("// layout(local_size_x=99) in;\nlayout(local_size_y=4) in;void main(){}"),new int[]{1,4,1}),"layout reader ignores comments and preserves specified axes");
            pipeline.shadowCompositeRenderer.passes.get(0).computes[1]=new GraphProgram(id+99);
            try{IrisComputeGraph.capture(pipeline);throw new AssertionError("missing owned source cannot become empty compute graph");}catch(IllegalStateException expected){}
        }finally{dev.antimony.gl.shim.GlShimState.deleteProgram(id);}
        System.out.println("PASS owned compute-only graph, inherited fields/null slots, source hash, local-size query, dispatch spec and missing-source rejection");
    }
    private static void unsignedSemantics()throws Exception{
        String source="""
            layout(location=0) out vec4 c;
            ivec3[2] offsets=ivec3[2](ivec3(1,2,3),ivec3(-1,0,1));
            void main(){uint n=0xffffffffu;uint q=n/2u;uint r=n%2u;uint shift=n>>31u;
                uint compound=n;compound/=2u;
                ivec3 p=clamp(ivec3(3,2,1)+offsets[0],0,4);p+=offsets[1];
                c=vec4(1.0);if(q!=2147483647u||r!=1u||shift!=1u||compound!=q||max(n,2147483647u)!=n||min(n,2147483647u)!=2147483647u||p!=ivec3(3,4,5))c=vec4(0.0);}
            """;
        var loaded=GlslProgramLoader.load(1700000002,source,"");check(loaded!=null,"unsigned semantic shader compile: "+GlslProgramLoader.failure(1700000002));
        loaded.program.run(EMPTY);float[][] out=(float[][])loaded.program.getClass().getField("out").get(loaded.program);near(out[0][0],1,"full uint range/div/mod/logical shift/minmax/compound and integer vector array arithmetic");
        System.out.println("PASS generated uint bit-domain exact max32/div/mod/shift/minmax/compound and integer vector arrays");
    }
    private static void unsignedVectorRegressions()throws Exception{
        String source="""
            layout(location=0) out vec4 c;
            int calls=0;
            uvec2 values(){calls++;return uvec2(16777217u,0xffffffffu);}
            int index(){calls++;return 0;}
            void main(){
                c=vec4(1.0);
                uvec2 a=uvec2(0x80000000u,0xffffffffu);a.x/=2u;a.y%=3u;
                uvec2 b=uvec2(0x80000000u,0xffffffffu);b[index()]/=2u;b[1]%=3u;
                if(a!=uvec2(0x40000000u,0u)||b!=a||calls!=1)c.x=0.0;
                vec2 f=uvec2(0x80000000u,0xffffffffu)*1.0;
                if(f.x!=2147483648.0||f.y!=4294967296.0)c.x=0.0;
                bvec2 hi=greaterThan(uvec2(0x80000000u,0u),uvec2(1u,1u));
                bvec2 lo=lessThan(uvec2(0xffffffffu,0u),uvec2(1u,1u));
                if(!hi.x||hi.y||lo.x||!lo.y)c.y=0.0;
                uvec2 selected=(true?values():uvec2(0u));
                if(selected!=uvec2(16777217u,0xffffffffu)||calls!=2)c.y=0.0;
                uvec2 overlap=uvec2(1u,2u);overlap.yx=overlap;
                if(overlap!=uvec2(2u,1u))c.z=0.0;
                overlap.xy=values().yx;
                if(overlap!=uvec2(0xffffffffu,16777217u)||calls!=3)c.z=0.0;
                ivec2 signedValue=(true?ivec2(16777217,-16777217):ivec2(0));
                if(signedValue!=ivec2(16777217,-16777217))c.w=0.0;
            }
            """;
        var loaded=GlslProgramLoader.load(1700000010,source,"");check(loaded!=null,"uint vector review shader compile: "+GlslProgramLoader.failure(1700000010));
        loaded.program.run(EMPTY);float[][] out=(float[][])loaded.program.getClass().getField("out").get(loaded.program);
        for(int i=0;i<4;i++)near(out[0][i],1,"uint vector regression channel "+i);
        check(GlslIntegerRuntime.equal(new float[]{0f},new float[]{-0f}),"GLSL vector signed zero equality");
        check(!GlslIntegerRuntime.equal(new float[]{Float.NaN},new float[]{Float.NaN}),"GLSL vector NaN inequality");
        System.out.println("PASS uint component/index divmod, unsigned floating conversion/comparison, exact ternary, RHS swizzle snapshots and single evaluation");
    }
    private static void failureTransactions(){
        String source="layout(local_size_x=1) in;writeonly uniform image3D output;void main(){imageStore(output,ivec3(0),vec4(50.0));}";
        var loaded=GlslProgramLoader.loadCompute(1700000003,source);check(loaded!=null,"failure fixture compile");Object identity=new Object();CpuVolume output=new CpuVolume(2,1,1,CpuVolume.Format.RGBA16F,false,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);
        try(CpuComputeDispatch dispatcher=new CpuComputeDispatch(identity,loaded.program,loaded.meta)){
            reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of(),Map.of(),Map.of("output",output),new int[]{2,1,1}),"non-owner writes fail and quiesce");
            near(output.fetch(0,0,0,0)[0],0,"failed dispatch publishes no preceding stores");
        }
        String alias="layout(local_size_x=1) in;uniform sampler3D input;writeonly uniform image3D output;void main(){ivec3 p=ivec3(gl_GlobalInvocationID);imageStore(output,p,texelFetch(input,p,0)+vec4(1.0));}";
        var aliasProgram=GlslProgramLoader.loadCompute(1700000004,alias);check(aliasProgram!=null,"alias fixture compile");
        try(CpuComputeDispatch dispatcher=new CpuComputeDispatch(identity,aliasProgram.program,aliasProgram.meta)){
            reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of(),Map.of("input",output),Map.of("output",output),new int[]{2,1,1}),"actual read/write alias without supported synchronization");near(output.fetch(0,0,0,0)[0],0,"alias failure no output publication");
            CpuVolume separate=output.mutableCopy();var receipt=dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of(),Map.of("input",output),Map.of("output",separate),new int[]{2,1,1});receipt.verifyFrame(identity,1);near(receipt.outputs().get("output").fetch(0,0,0,0)[0],1,"retry same failed generation with corrected nonalias bindings");
        }
        System.out.println("PASS staged no-publication on failed/nonowner/unsynchronized alias dispatch, quiescence and corrected retry");
    }
    private static void publicationRegressions(){
        for(var c:CpuComputeDispatch.Receipt.class.getDeclaredConstructors())check(Modifier.isPrivate(c.getModifiers()),"only dispatcher issues receipts");
        Object identity=new Object();CpuVolume output=smallVolume();
        String source="layout(local_size_x=1) in;uniform int mode;writeonly uniform image3D output;void main(){ivec3 p=ivec3(gl_GlobalInvocationID);if(mode==1||(mode==2&&p.x==0)){imageStore(output,p,vec4(2.0));imageStore(output,p,vec4(3.0));}}";
        var loaded=GlslProgramLoader.loadCompute(1700000011,source);check(loaded!=null,"coverage fixture compile");
        try(var dispatcher=new CpuComputeDispatch(identity,loaded.program,loaded.meta)){
            var absent=dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of("mode",0),Map.of(),Map.of("output",output),new int[]{2,1,1});
            check(!absent.fullDomain()&&absent.fullyWritten().isEmpty(),"no stores never mark producer ready");reject(()->absent.verifyFrame(identity,1),"no-write receipt import");
            var repeated=dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,2,0),Map.of("mode",2),Map.of(),Map.of("output",output),new int[]{2,1,1});
            check(!repeated.fullDomain()&&repeated.fullyWritten().isEmpty(),"duplicate writes cannot hide an unwritten texel");reject(()->repeated.verifyFrame(identity,2),"incomplete coverage import");
            var complete=dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,3,0),Map.of("mode",1),Map.of(),Map.of("output",output),new int[]{2,1,1});
            check(complete.fullDomain()&&complete.fullyWritten().equals(Set.of("output")),"actual unique-texel coverage tracked");complete.verifyFrame(identity,3);
            reject(()->complete.outputs().get("output").store(0,0,0,new float[]{9,9,9,9}),"published volume is frozen");
            near(output.fetch(0,0,0,0)[0],0,"publication does not mutate caller storage");
        }
        String overflow="layout(local_size_x=1) in;uniform float value;writeonly uniform image3D output;void main(){imageStore(output,ivec3(gl_GlobalInvocationID),vec4(value));}";
        var half=GlslProgramLoader.loadCompute(1700000012,overflow);check(half!=null,"half overflow fixture compile");
        try(var dispatcher=new CpuComputeDispatch(identity,half.program,half.meta)){
            reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of("value",70000f),Map.of(),Map.of("output",output),new int[]{2,1,1}),"finite float that overflows half fails before publication");
            near(output.fetch(0,0,0,0)[0],0,"half overflow leaves original intact");
            var retry=dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of("value",65504f),Map.of(),Map.of("output",output),new int[]{2,1,1});retry.verifyFrame(identity,1);
            near(retry.outputs().get("output").fetch(1,0,0,0)[0],65504f,"largest finite half survives corrected retry");
        }
        System.out.println("PASS private receipt issuance, no-write and duplicate-write coverage, immutable publication, half overflow rollback and retry");
    }
    private static void submissionFailureQuiescence()throws Exception{
        Object identity=new Object();CpuVolume output=smallVolume();
        var loaded=GlslProgramLoader.loadCompute(1700000013,"layout(local_size_x=1) in;writeonly uniform image3D output;void main(){imageStore(output,ivec3(gl_GlobalInvocationID),vec4(4.0));}");
        check(loaded!=null,"submission failure fixture compile");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),rejected=new CountDownLatch(1);AtomicInteger submissions=new AtomicInteger(),active=new AtomicInteger();
        ThreadPoolExecutor injected=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>()){
            @Override public void execute(Runnable command){int n=submissions.incrementAndGet();if(n==2){rejected.countDown();throw new RejectedExecutionException("probe second submission");}
                super.execute(()->{active.incrementAndGet();try{if(n==1){started.countDown();await(release);}command.run();}finally{active.decrementAndGet();}});
            }
        };
        ExecutorService driver=Executors.newSingleThreadExecutor();
        try(var dispatcher=new CpuComputeDispatch(identity,loaded.program,loaded.meta,injected,2)){
            Future<?> result=driver.submit(()->reject(()->dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of(),Map.of(),Map.of("output",output),new int[]{2,1,1}),"submission fails only after accepted worker quiesces"));
            check(started.await(5,TimeUnit.SECONDS)&&rejected.await(5,TimeUnit.SECONDS),"accepted worker and injected rejection observed");
            try{result.get(200,TimeUnit.MILLISECONDS);throw new AssertionError("dispatch returned before accepted worker quiesced");}catch(TimeoutException expected){}
            release.countDown();result.get(5,TimeUnit.SECONDS);check(active.get()==0,"accepted worker exited before failed dispatch returned");
            near(output.fetch(0,0,0,0)[0],0,"submission failure publishes nothing");
            var retry=dispatcher.dispatch(new CpuComputeDispatch.Frame(identity,1,0),Map.of(),Map.of(),Map.of("output",output),new int[]{2,1,1});retry.verifyFrame(identity,1);near(retry.outputs().get("output").fetch(1,0,0,0)[0],4,"submission failure generation remains retryable");
        }finally{release.countDown();driver.shutdownNow();injected.shutdownNow();check(driver.awaitTermination(5,TimeUnit.SECONDS)&&injected.awaitTermination(5,TimeUnit.SECONDS),"probe workers terminated");}
        System.out.println("PASS rejected submission joins accepted workers before failure and permits same-generation retry");
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("probe latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static CpuVolume smallVolume(){return new CpuVolume(2,1,1,CpuVolume.Format.RGBA16F,false,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);}
    private static Map<String,Object> uniforms(int counter,float[] camera,float[] previous){return Map.of("frameCounter",counter,"cameraPosition",camera,"previousCameraPosition",previous,"frameTimeCounter",.25f);}
    private static CpuVolume volume(CpuVolume.Format format){return new CpuVolume(SIZE_X,SIZE_Y,SIZE_Z,format,format!=CpuVolume.Format.R8UI,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);}
    private static Map<String,CpuVolume> reads(CpuVolume voxel,CpuVolume main,CpuVolume copy){return Map.of("voxelSampler",voxel,"floodfillSampler",main,"floodfillSamplerCopy",copy);}
    private static Map<String,CpuVolume> writes(CpuVolume main,CpuVolume copy){return Map.of("floodfill_img",main,"floodfill_img_copy",copy);}
    private static float[] referenceFlood(CpuVolume source,int x,int y,int z){float[] sum=source.fetch(x,y,z,0);int[][] offsets={{1,0,0},{0,1,0},{0,0,1},{-1,0,0},{0,-1,0},{0,0,-1}};
        for(int[] d:offsets){float[] p=source.fetch(Math.max(0,Math.min(191,x+d[0])),Math.max(0,Math.min(191,y+d[1])),Math.max(0,Math.min(191,z+d[2])),0);for(int c=0;c<3;c++)sum[c]+=p[c];}for(int c=0;c<3;c++)sum[c]/=7.01f;return sum;}
    private static float half(float value){return Float.float16ToFloat(Float.floatToFloat16(value));}
    private static final FragState EMPTY=new FragState(){public float width(){return 1;}public float height(){return 1;}public float[] sample(int unit,float x,float y){throw new IllegalStateException("unbound test sampler");}};
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void near(float value,float expected,String message){check(Float.isFinite(value)&&Math.abs(value-expected)<.0005f,message+" expected="+expected+" actual="+value);}
    private static void reject(Runnable body,String message){try{body.run();throw new AssertionError("Expected rejection: "+message);}catch(IllegalArgumentException|IllegalStateException expected){}}
}

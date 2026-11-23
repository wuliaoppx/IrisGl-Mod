package dev.antimony.gl.glsl;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synchronous CPU compute. Workers only read immutable inputs and write private staging images.
 * The supported subset has level-zero 3D images and stores to the invocation's own texel. */
public final class CpuComputeDispatch implements AutoCloseable {
    public record Frame(Object pipeline, long generation, int frameCounter) { }
    public static final class Receipt {
        private final Object pipeline;
        private final long generation, invocations, nanos;
        private final int frameCounter;
        private final boolean fullDomain;
        private final Map<String, CpuVolume> outputs;
        private final Set<String> fullyWritten;
        private Receipt(Frame frame, long invocations, boolean fullDomain, Map<String, CpuVolume> outputs,
                        Set<String> fullyWritten, long nanos) {
            pipeline=frame.pipeline;generation=frame.generation;frameCounter=frame.frameCounter;
            this.invocations=invocations;this.fullDomain=fullDomain;this.outputs=Map.copyOf(outputs);
            this.fullyWritten=Set.copyOf(fullyWritten);this.nanos=nanos;
        }
        public Object pipeline(){return pipeline;}
        public long generation(){return generation;}
        public int frameCounter(){return frameCounter;}
        public long invocations(){return invocations;}
        public boolean fullDomain(){return fullDomain;}
        public Map<String,CpuVolume> outputs(){return outputs;}
        public Set<String> fullyWritten(){return fullyWritten;}
        public long nanos(){return nanos;}
        public void verifyFrame(Object currentPipeline,long currentGeneration){
            if(!fullDomain||fullyWritten.isEmpty()||pipeline!=currentPipeline||generation!=currentGeneration)
                throw new IllegalStateException("Incomplete/stale/pipeline-mismatched compute receipt");
        }
    }
    private record Sampler(int unit,String type) { }
    private record Image(int unit,String type,String access) { }
    private final Object pipeline;
    private final Constructor<?> constructor;
    private final int[] localSize;
    private final Map<String,String> uniformFields,uniformTypes;
    private final Set<String> liveUniforms;
    private final Map<String,Sampler> samplers;
    private final Map<String,Image> images;
    private final int workers;
    private final ExecutorService pool;
    private long lastPublished;
    private boolean closed;

    public CpuComputeDispatch(Object pipeline,GlslProgram prototype,GlslCompiler.Result meta){
        this(pipeline,prototype,meta,null,Math.max(1,Math.min(12,Runtime.getRuntime().availableProcessors())));
    }
    CpuComputeDispatch(Object pipeline,GlslProgram prototype,GlslCompiler.Result meta,ExecutorService injected,int workerCount){
        this.pipeline=Objects.requireNonNull(pipeline);
        if(!meta.compute)throw new IllegalArgumentException("Not a compute program");
        try{constructor=prototype.getClass().getConstructor();}
        catch(ReflectiveOperationException e){throw new IllegalArgumentException("Compute program constructor",e);}
        localSize=meta.localSize.clone();
        if(localSize.length!=3)throw new IllegalArgumentException("local size");
        for(int n:localSize)if(n<1)throw new IllegalArgumentException("local size");
        if((long)localSize[0]*localSize[1]*localSize[2]>1024)throw new IllegalArgumentException("local group too large");
        liveUniforms=Set.copyOf(meta.usedUniforms);uniformFields=Map.copyOf(meta.uniformFields);uniformTypes=Map.copyOf(meta.uniformTypes);
        Map<String,Sampler> ss=new LinkedHashMap<>();
        for(String name:meta.usedSamplers){
            String type=meta.samplerTypes.get(name);Integer unit=meta.samplerUnits.get(name);
            if(unit==null||!Set.of("sampler3D","usampler3D").contains(type))throw new IllegalArgumentException("Unsupported compute sampler "+name);
            ss.put(name,new Sampler(unit,type));
        }
        samplers=Map.copyOf(ss);
        Map<String,Image> ii=new LinkedHashMap<>();
        for(String name:meta.usedImages){
            Integer unit=meta.imageUnits.get(name);String type=meta.imageTypes.get(name),access=meta.imageAccess.get(name);
            if(unit==null||!Set.of("image3D","uimage3D").contains(type)||!Set.of("writeonly","readwrite").contains(access))
                throw new IllegalArgumentException("Unsupported/readonly writable image "+name);
            ii.put(name,new Image(unit,type,access));
        }
        images=Map.copyOf(ii);
        if(workerCount<1||workerCount>256)throw new IllegalArgumentException("compute worker count");
        workers=workerCount;
        pool=injected!=null?injected:Executors.newFixedThreadPool(workers,r->{Thread t=new Thread(r,"antimony-compute");t.setDaemon(true);return t;});
    }
    public Set<String> samplerNames(){return samplers.keySet();}
    public Set<String> imageNames(){return images.keySet();}
    public synchronized Receipt dispatch(Frame frame,Map<String,Object> uniforms,Map<String,CpuVolume> reads,
                                         Map<String,CpuVolume> writes,int[] groups){
        return run(frame,uniforms,reads,writes,groups,false,null);
    }
    /** LAB/region execution; a partial receipt cannot satisfy a whole-image producer. */
    public synchronized Receipt dispatchRegion(Frame frame,Map<String,Object> uniforms,Map<String,CpuVolume> reads,
                                               Map<String,CpuVolume> writes,int[] groups){
        return run(frame,uniforms,reads,writes,groups,true,null);
    }
    /** Replaces exactly the selected whole images. Any executed store to another image rejects the dispatch. */
    public synchronized Receipt dispatchReplacing(Frame frame,Map<String,Object> uniforms,Map<String,CpuVolume> reads,
                                                  Map<String,CpuVolume> writes,int[] groups,Set<String> outputs){
        if(outputs==null||outputs.isEmpty()||!images.keySet().containsAll(outputs)||!writes.keySet().containsAll(outputs))
            throw new IllegalArgumentException("Invalid replacement outputs");
        return run(frame,uniforms,reads,writes,groups,false,Set.copyOf(outputs));
    }
    private Receipt run(Frame frame,Map<String,Object> uniforms,Map<String,CpuVolume> reads,
                        Map<String,CpuVolume> writes,int[] groups,boolean partial,Set<String> replacing){
        if(closed)throw new IllegalStateException("Compute dispatcher closed");
        if(frame==null||frame.pipeline!=pipeline||frame.generation<=lastPublished||frame.generation<=0)
            throw new IllegalStateException("Missing/stale/pipeline-mismatched compute frame");
        if(groups==null||groups.length!=3)throw new IllegalArgumentException("dispatch group arity");
        int[] extent=new int[3];
        for(int i=0;i<3;i++){
            if(groups[i]<=0)throw new IllegalArgumentException("Empty/negative dispatch");
            extent[i]=Math.multiplyExact(groups[i],localSize[i]);
        }
        long expected=Math.multiplyExact(Math.multiplyExact((long)extent[0],extent[1]),extent[2]);
        int rows=Math.multiplyExact(extent[1],extent[2]);
        Map<Field,Object> bound=new LinkedHashMap<>();
        try{
            for(String name:liveUniforms){
                Object value=uniforms.get(name);
                if(value==null)throw new IllegalArgumentException("Missing current compute uniform "+name);
                if(name.equals("frameCounter")&&!Objects.equals(value,frame.frameCounter))throw new IllegalArgumentException("Frame counter mismatch");
                String[] signature=uniformTypes.get(name).split("#");int n=Integer.parseInt(signature[1]);Object copy=copy(value);
                boolean valid=switch(signature[0]){
                    case "float"->copy instanceof Float f&&Float.isFinite(f);
                    case "int"->copy instanceof Integer;
                    case "boolean"->copy instanceof Boolean;
                    case "float[]"->copy instanceof float[] a&&a.length==n&&finite(a);
                    case "int[]"->copy instanceof int[] a&&a.length==n;
                    case "boolean[]"->copy instanceof boolean[] a&&a.length==n;
                    default->false;
                };
                if(!valid)throw new IllegalArgumentException("Malformed compute uniform "+name);
                bound.put(constructor.getDeclaringClass().getField(uniformFields.get(name)),copy);
            }
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Compute uniform schema",e);}
        Map<Integer,CpuVolume> frozen=new HashMap<>(),staging=new HashMap<>();
        Map<Integer,CpuVolume> readOrigins=new HashMap<>(),writeOrigins=new HashMap<>();
        IdentityHashMap<CpuVolume,CpuVolume> snapshots=new IdentityHashMap<>();
        for(var e:samplers.entrySet()){
            CpuVolume volume=reads.get(e.getKey());
            if(volume==null||volume.integer()!=e.getValue().type.equals("usampler3D"))
                throw new IllegalArgumentException("Missing/wrong typed current compute sampler "+e.getKey());
            frozen.put(e.getValue().unit,snapshots.computeIfAbsent(volume,CpuVolume::detached));
            readOrigins.put(e.getValue().unit,volume);
        }
        boolean complete=!images.isEmpty();Set<CpuVolume> targets=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var e:images.entrySet()){
            CpuVolume volume=writes.get(e.getKey());
            if(volume==null||volume.integer()!=e.getValue().type.equals("uimage3D"))throw new IllegalArgumentException("Missing/wrong typed compute image "+e.getKey());
            if(!targets.add(volume))throw new IllegalArgumentException("Writable images alias one another");
            boolean same=volume.width==extent[0]&&volume.height==extent[1]&&volume.depth==extent[2];complete&=same;
            if(!partial&&!same)throw new IllegalArgumentException("Full dispatch/image domain mismatch for "+e.getKey());
            if(partial&&(extent[0]>volume.width||extent[1]>volume.height||extent[2]>volume.depth))throw new IllegalArgumentException("Region exceeds output volume");
            if(replacing==null)staging.put(e.getValue().unit,volume.mutableCopy());
            else if(replacing.contains(e.getKey()))staging.put(e.getValue().unit,volume.emptyMutable());
            writeOrigins.put(e.getValue().unit,volume);
        }
        Set<CpuVolume> actuallyRead=Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<CpuVolume> actuallyWritten=Collections.newSetFromMap(new ConcurrentHashMap<>());
        Map<Integer,LongAdder> coverage=new ConcurrentHashMap<>();for(var image:images.values())coverage.put(image.unit,new LongAdder());
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger cursor=new AtomicInteger();AtomicLong completed=new AtomicLong();
        CountDownLatch latch=new CountDownLatch(workers);long start=System.nanoTime();
        for(int worker=0;worker<workers;worker++)try{
            pool.execute(()->{
                try{
                    GlslProgram program=(GlslProgram)constructor.newInstance();
                    for(var b:bound.entrySet())b.getKey().set(program,copy(b.getValue()));
                    for(var s:samplers.values())program.getClass().getField("s$"+s.unit).setInt(program,s.unit);
                    for(var image:images.values())program.getClass().getField("i$"+image.unit).setInt(program,image.unit);
                    Invocation state=new Invocation(extent,frozen,staging,readOrigins,writeOrigins,actuallyRead,actuallyWritten,coverage);
                    int row;
                    while(failure.get()==null&&(row=cursor.getAndIncrement())<rows){
                        state.y=row%extent[1];state.z=row/extent[1];
                        for(state.x=0;state.x<extent[0]&&failure.get()==null;state.x++){
                            state.writtenHere.clear();program.runCompute(state);completed.incrementAndGet();
                        }
                    }
                }catch(Throwable error){failure.compareAndSet(null,error);}finally{latch.countDown();}
            });
        }catch(Throwable submission){failure.compareAndSet(null,submission);for(int pending=worker;pending<workers;pending++)latch.countDown();break;}
        boolean interrupted=false;
        while(latch.getCount()!=0)try{latch.await();}catch(InterruptedException e){interrupted=true;failure.compareAndSet(null,e);}
        if(interrupted)Thread.currentThread().interrupt();
        if(failure.get()!=null)throw new IllegalStateException("Compute failed after all workers quiescent; no writes published",failure.get());
        if(completed.get()!=expected)throw new IllegalStateException("Incomplete compute invocation count");
        for(CpuVolume volume:actuallyWritten)if(actuallyRead.contains(volume))
            throw new IllegalStateException("Compute read/write alias requires unsupported synchronization; no writes published");
        Set<String> fullyWritten=new HashSet<>();
        for(var image:images.entrySet())if(coverage.get(image.getValue().unit).sum()==expected)fullyWritten.add(image.getKey());
        if(replacing!=null&&!fullyWritten.equals(replacing))throw new IllegalStateException("Replacement output coverage differs from selected images");
        Map<String,CpuVolume> published=new LinkedHashMap<>();
        for(var image:images.entrySet())if(replacing==null||replacing.contains(image.getKey()))
            published.put(image.getKey(),staging.get(image.getValue().unit).seal());
        complete&=!fullyWritten.isEmpty();
        Receipt receipt=new Receipt(frame,completed.get(),complete,published,fullyWritten,System.nanoTime()-start);
        lastPublished=frame.generation;return receipt;
    }
    private static Object copy(Object value){if(value instanceof float[] a)return a.clone();if(value instanceof int[] a)return a.clone();if(value instanceof boolean[] a)return a.clone();return value;}
    private static boolean finite(float[] a){for(float f:a)if(!Float.isFinite(f))return false;return true;}
    private static final class Invocation implements ComputeState {
        final int[] extent;final Map<Integer,CpuVolume> reads,writes,readOrigins,writeOrigins;
        final Set<CpuVolume> readSet,writeSet;final Map<Integer,LongAdder> coverage;final Set<Integer> writtenHere=new HashSet<>();
        final BitSet readUnits=new BitSet(),writeUnits=new BitSet();int x,y,z;
        Invocation(int[] extent,Map<Integer,CpuVolume> reads,Map<Integer,CpuVolume> writes,Map<Integer,CpuVolume> readOrigins,
                   Map<Integer,CpuVolume> writeOrigins,Set<CpuVolume> readSet,Set<CpuVolume> writeSet,Map<Integer,LongAdder> coverage){
            this.extent=extent;this.reads=reads;this.writes=writes;this.readOrigins=readOrigins;this.writeOrigins=writeOrigins;
            this.readSet=readSet;this.writeSet=writeSet;this.coverage=coverage;
        }
        public int[] globalInvocationID(){return new int[]{x,y,z};}
        public float width(){return extent[0];}public float height(){return extent[1];}
        public float[] sample(int unit,float u,float v){throw new IllegalStateException("2D compute sampling not bound");}
        private CpuVolume read(int unit){
            CpuVolume value=reads.get(unit);if(value==null)throw new IllegalStateException("Unbound compute sampler "+unit);
            if(!readUnits.get(unit)){readUnits.set(unit);readSet.add(readOrigins.get(unit));}return value;
        }
        public float[] sample3D(int unit,float u,float v,float w){return read(unit).sample(u,v,w);}
        public float[] sample3DLod(int unit,float u,float v,float w,float lod){return read(unit).sampleLod(u,v,w,lod);}
        public float[] fetch3D(int unit,int x,int y,int z,int lod){return read(unit).fetch(x,y,z,lod);}
        public int[] fetchUnsigned3D(int unit,int x,int y,int z,int lod){return read(unit).fetchUnsigned(x,y,z,lod);}
        private CpuVolume write(int unit,int[] pos){
            CpuVolume value=writes.get(unit);
            if(value==null)throw new IllegalStateException("Executed store to an unselected/unbound compute image "+unit);
            if(pos==null||pos.length!=3||pos[0]!=x||pos[1]!=y||pos[2]!=z)
                throw new IllegalStateException("Cross-invocation image write requires unsupported atomic/barrier semantics");
            if(!writeUnits.get(unit)){writeUnits.set(unit);writeSet.add(writeOrigins.get(unit));}
            if(writtenHere.add(unit))coverage.get(unit).increment();return value;
        }
        public void storeImage3D(int unit,int[] pos,float[] value){
            if(value==null||value.length!=4||!finite(value))throw new IllegalStateException("Nonfinite/malformed compute image value");
            CpuVolume target=write(unit,pos);
            if(target.format==CpuVolume.Format.RGBA16F)for(float f:value)
                if(!Float.isFinite(Float.float16ToFloat(Float.floatToFloat16(f))))throw new IllegalStateException("Compute half-format overflow");
            target.store(x,y,z,value);
        }
        public void storeUnsignedImage3D(int unit,int[] pos,int[] value){
            if(value==null||value.length!=4)throw new IllegalStateException("Malformed unsigned image value");
            write(unit,pos).storeUnsigned(x,y,z,value[0]);
        }
    }
    public synchronized void close(){if(!closed){closed=true;pool.shutdown();}}
}

package dev.antimony.gl.glsl;

import java.util.*;

/** Render-thread owner of typed Iris 3D images. Storage initialization and producer completion are distinct. */
public final class IrisCpuImageResources {
    public static final long DEFAULT_BUDGET_BYTES=256L*1024*1024;
    private final long budgetBytes;
    private Object pipeline;
    private Map<String,IrisImageDescriptors.Image> descriptors=Map.of();
    private Map<String,CpuVolume> volumes=Map.of();
    private final Map<String,ReadyStamp> ready=new HashMap<>();
    private long generation,resourceGeneration,lastFrameOrdinal,allocatedBytes;
    private Frame current;
    private Thread ownerThread;
    public IrisCpuImageResources(){this(configuredBudget());}
    public IrisCpuImageResources(long budgetBytes){if(budgetBytes<1)throw new IllegalArgumentException("CPU image budget must be positive");this.budgetBytes=budgetBytes;}
    private static long configuredBudget(){long bytes=Long.getLong("antimony.cpuImages.maxBytes",DEFAULT_BUDGET_BYTES);return Math.max(1,Math.min(bytes,512L*1024*1024));}
    public synchronized void invalidateCurrent(){checkThread();if(current!=null)current.invalid=true;current=null;}
    private void checkThread(){if(ownerThread!=null&&Thread.currentThread()!=ownerThread)throw new IllegalStateException("CPU image lifecycle must run on its owner thread");}

    public synchronized Frame beginFrame(Object currentPipeline,long frameOrdinal,List<IrisImageDescriptors.Image> currentDescriptors){
        if(ownerThread==null)ownerThread=Thread.currentThread();checkThread();
        Objects.requireNonNull(currentPipeline);Objects.requireNonNull(currentDescriptors);
        if(frameOrdinal<1||frameOrdinal<=lastFrameOrdinal)throw new IllegalArgumentException("Missing/stale CPU image frame ordinal");
        invalidateCurrent();
        try{
            Map<String,IrisImageDescriptors.Image> next=new LinkedHashMap<>();Set<Integer> ids=new HashSet<>();long total=0;
            for(var image:currentDescriptors){
                if(image.pipeline()!=currentPipeline)throw new IllegalStateException("CPU image descriptor belongs to another pipeline: "+image.name());
                if(image.target()!=0x806F)continue;
                if(image.textureId()<=0||!ids.add(image.textureId())||next.put(image.name(),image)!=null)
                    throw new IllegalStateException("Invalid/aliased CPU image identity: "+image.name());
                validate(image);total=Math.addExact(total,bytes(image));
                if(total>budgetBytes)throw new IllegalStateException("CPU 3D image budget exceeded: "+total+" > "+budgetBytes);
            }
            boolean rebuild=pipeline!=currentPipeline||!sameDescriptors(descriptors,next);
            if(rebuild){
                Map<String,CpuVolume> allocated=new LinkedHashMap<>();
                for(var image:next.values())allocated.put(image.name(),new CpuVolume(image.width(),image.height(),image.depth(),image.cpuFormat(),
                    image.cpuFormat()!=CpuVolume.Format.R8UI,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE));
                pipeline=currentPipeline;descriptors=Map.copyOf(next);volumes=allocated;ready.clear();
                resourceGeneration=Math.addExact(resourceGeneration,1);allocatedBytes=total;
            }
            for(var image:descriptors.values())if(image.clear()){
                CpuVolume volume=volumes.get(image.name());
                if(volume.frozen())volumes.put(image.name(),volume.emptyMutable());else volume.clear();
                ready.remove(image.name());
            }
            long nextGeneration=Math.addExact(generation,1);
            current=new Frame(this,currentPipeline,nextGeneration,frameOrdinal,rebuild);
            generation=nextGeneration;lastFrameOrdinal=frameOrdinal;return current;
        }catch(RuntimeException|Error failure){
            current=null;pipeline=null;descriptors=Map.of();volumes=Map.of();ready.clear();allocatedBytes=0;
            resourceGeneration=Math.addExact(resourceGeneration,1);throw failure;
        }
    }
    private static void validate(IrisImageDescriptors.Image image){
        CpuVolume.Format format=image.cpuFormat();
        if(format==null||image.relative()||image.width()<1||image.height()<1||image.depth()<1)
            throw new IllegalStateException("Unsupported CPU 3D image format/extent "+image.name());
        if(texels(image)>Integer.MAX_VALUE)throw new IllegalStateException("CPU image too large "+image.name());
        boolean typed=switch(format){
            case R8UI->image.pixelFormat()==0x8D94&&image.pixelType()==0x1405;
            case RGBA16F->image.pixelFormat()==0x1908&&image.pixelType()==0x140B;
            case RGBA32F->image.pixelFormat()==0x1908&&image.pixelType()==0x1406;
        };
        if(!typed)throw new IllegalStateException("Unsupported typed CPU image declaration "+image.name());
    }
    private static long texels(IrisImageDescriptors.Image image){return Math.multiplyExact(Math.multiplyExact((long)image.width(),image.height()),image.depth());}
    private static long bytes(IrisImageDescriptors.Image image){return Math.multiplyExact(texels(image),image.cpuFormat()==CpuVolume.Format.R8UI?1L:16L);}
    private enum Origin { INITIAL_CLEAR, STORES, SHADOW, COMPUTE }
    private record ReadyStamp(Object pipeline,int textureId,long resources,long frameOrdinal,Origin origin) { }
    private static boolean sameDescriptors(Map<String,IrisImageDescriptors.Image> a,Map<String,IrisImageDescriptors.Image> b){return a.equals(b);}

    public static final class Frame {
        private final IrisCpuImageResources owner;
        private final Object pipeline;
        private final long generation,frameOrdinal;
        private final boolean allocatedThisFrame;
        private final Map<String,BitSet> writes=new HashMap<>();
        private final Map<String,Long> writeCounts=new HashMap<>();
        private final Set<String> writeStarted=new HashSet<>();
        private boolean invalid,allocationClearImported;
        private Frame(IrisCpuImageResources owner,Object pipeline,long generation,long frameOrdinal,boolean allocated){
            this.owner=owner;this.pipeline=pipeline;this.generation=generation;this.frameOrdinal=frameOrdinal;allocatedThisFrame=allocated;
        }
        public Object pipeline(){return pipeline;}
        public long generation(){return generation;}
        public long frameOrdinal(){return frameOrdinal;}
        public synchronized void verifyCurrent(Object expectedPipeline,long expectedGeneration,long expectedOrdinal){
            active();if(pipeline!=expectedPipeline||generation!=expectedGeneration||frameOrdinal!=expectedOrdinal)throw new IllegalStateException("CPU image frame identity mismatch");
        }
        public synchronized int[] dimensions(String name){active();var image=descriptor(name);return new int[]{image.width(),image.height(),image.depth()};}
        /** A sealed image can be shared with workers; any later owner store performs copy-on-write. */
        public synchronized CpuVolume readableSnapshot(String name){return readable(name,null).seal();}
        /** Iris GlImage.setup explicitly clears every allocation, including clear=false images.
         * Only the checked Iris lifecycle bridge calls this at allocation time; it never issues a producer receipt. */
        synchronized void importIrisAllocationClear(){
            active();if(!allocatedThisFrame||allocationClearImported)return;
            for(var image:owner.descriptors.values())if(!image.clear())mark(image.name(),Origin.INITIAL_CLEAR);
            allocationClearImported=true;
        }
        public synchronized boolean hasReadableHistory(String name){
            active();try{requireReadable(name);return true;}catch(IllegalStateException unavailable){return false;}
        }
        public synchronized boolean hasCompleteImage(String name){
            active();var stamp=owner.ready.get(name);return identity(name,stamp)&&stamp.frameOrdinal==frameOrdinal&&stamp.origin!=Origin.INITIAL_CLEAR;
        }
        public synchronized long producerFrameOrdinal(String name){
            active();var stamp=owner.ready.get(name);return identity(name,stamp)&&stamp.origin!=Origin.INITIAL_CLEAR?stamp.frameOrdinal:-1;
        }
        public synchronized int[] readUnsigned(String name,int x,int y,int z){return readable(name,CpuVolume.Format.R8UI).fetchUnsigned(x,y,z,0);}
        public synchronized float[] readFloat(String name,int x,int y,int z){return readable(name,null).fetch(x,y,z,0);}
        public synchronized long writeUnsigned(String name,int x,int y,int z,int value){
            CpuVolume volume=typed(name,CpuVolume.Format.R8UI);
            if(!inside(volume,x,y,z))return 0;
            beginWrite(name);volume=writable(name);volume.storeUnsigned(x,y,z,value);return recordWrite(name,volume,x,y,z);
        }
        public synchronized long writeFloat(String name,int x,int y,int z,float[] value){
            CpuVolume volume=typed(name,null);
            if(volume.integer())throw new IllegalArgumentException("Floating store to integer CPU image "+name);
            if(value==null||value.length!=4)throw new IllegalArgumentException("Floating imageStore requires vec4");
            for(float f:value)if(!Float.isFinite(f)||volume.format==CpuVolume.Format.RGBA16F&&!Float.isFinite(Float.float16ToFloat(Float.floatToFloat16(f))))
                throw new IllegalArgumentException("Nonfinite/overflowing CPU image value");
            if(!inside(volume,x,y,z))return 0;
            beginWrite(name);volume=writable(name);volume.store(x,y,z,value);return recordWrite(name,volume,x,y,z);
        }
        private long recordWrite(String name,CpuVolume volume,int x,int y,int z){
            BitSet bits=writes.computeIfAbsent(name,k->new BitSet());int index=(z*volume.height+y)*volume.width+x;
            if(bits.get(index))return 0;
            bits.set(index);long count=writeCounts.merge(name,1L,Long::sum);
            if(count==texels(descriptor(name)))mark(name,Origin.STORES);return 1;
        }
        /** The proof can only be issued after the shadow producer consumes its validated current source set. */
        public synchronized void completeShadowProducer(dev.antimony.core.render.SolasVoxelProducer.Receipt proof){
            active();Objects.requireNonNull(proof);proof.verify(pipeline,generation,frameOrdinal);
            var image=descriptor("voxel_img");
            if(!image.clear()||image.cpuFormat()!=CpuVolume.Format.R8UI)throw new IllegalStateException("Shadow voxel image contract changed");
            mark("voxel_img",Origin.SHADOW);
        }
        /** Bind owned immutable inputs, run synchronously, and validate every replacement before publishing any. */
        public synchronized CpuComputeDispatch.Receipt dispatchAndCommit(CpuComputeDispatch dispatcher,
                Map<String,Object> uniforms,int[] groups,Set<String> selected,int frameCounter){
            active();Objects.requireNonNull(dispatcher);Objects.requireNonNull(selected);
            Map<String,CpuVolume> reads=new LinkedHashMap<>(),targets=new LinkedHashMap<>();
            for(String sampler:dispatcher.samplerNames()){
                String found=null;
                for(var image:owner.descriptors.values())if(sampler.equals(image.samplerName())){
                    if(found!=null)throw new IllegalStateException("Aliased CPU image sampler "+sampler);found=image.name();
                }
                if(found==null)throw new IllegalStateException("No current owned CPU sampler "+sampler);
                reads.put(sampler,readableSnapshot(found));
            }
            long stagingBytes=0;
            for(String name:dispatcher.imageNames())targets.put(name,typed(name,null));
            for(String name:selected){var image=descriptor(name);stagingBytes=Math.addExact(stagingBytes,bytes(image));}
            if(Math.addExact(owner.allocatedBytes,stagingBytes)>owner.budgetBytes)
                throw new IllegalStateException("CPU compute staging exceeds image budget");
            var receipt=dispatcher.dispatchReplacing(new CpuComputeDispatch.Frame(pipeline,generation,frameCounter),uniforms,reads,targets,groups,selected);
            receipt.verifyFrame(pipeline,generation);
            if(receipt.frameCounter()!=frameCounter||!receipt.fullyWritten().equals(selected)||!receipt.outputs().keySet().equals(selected))
                throw new IllegalStateException("Compute receipt does not cover selected frame outputs");
            for(var entry:receipt.outputs().entrySet()){
                CpuVolume next=entry.getValue(),old=typed(entry.getKey(),null);
                if(next==null||!next.frozen()||next.width!=old.width||next.height!=old.height||next.depth!=old.depth||next.format!=old.format)
                    throw new IllegalStateException("Compute replacement schema mismatch "+entry.getKey());
            }
            // Everything that can reject is checked above. Immutable output handoff is atomic to consumers.
            owner.volumes.putAll(receipt.outputs());
            for(String name:selected){writeStarted.add(name);writeCounts.put(name,texels(descriptor(name)));mark(name,Origin.COMPUTE);}
            return receipt;
        }
        public synchronized ImageReceipt receipt(String name){
            active();var image=descriptor(name);return new ImageReceipt(pipeline,generation,frameOrdinal,name,image.textureId(),
                writeCounts.getOrDefault(name,0L),texels(image),hasCompleteImage(name));
        }
        public synchronized CpuVolume requireComplete(String name,Object expectedPipeline,long expectedGeneration){
            active();if(pipeline!=expectedPipeline||generation!=expectedGeneration||!hasCompleteImage(name))
                throw new IllegalStateException("CPU image has no matching complete producer receipt: "+name);
            return typed(name,null).seal();
        }
        private void beginWrite(String name){if(writeStarted.add(name))owner.ready.remove(name);}
        private void mark(String name,Origin origin){owner.ready.put(name,new ReadyStamp(pipeline,descriptor(name).textureId(),owner.resourceGeneration,frameOrdinal,origin));}
        private boolean identity(String name,ReadyStamp stamp){return stamp!=null&&stamp.pipeline==pipeline&&stamp.textureId==descriptor(name).textureId()&&stamp.resources==owner.resourceGeneration;}
        private void requireReadable(String name){
            var stamp=owner.ready.get(name);var image=descriptor(name);
            if(!identity(name,stamp)||image.clear()&&stamp.frameOrdinal!=frameOrdinal)
                throw new IllegalStateException("CPU image has no initialized/complete current history: "+name);
        }
        private CpuVolume readable(String name,CpuVolume.Format expected){CpuVolume volume=typed(name,expected);requireReadable(name);return volume;}
        private CpuVolume typed(String name,CpuVolume.Format expected){
            active();descriptor(name);CpuVolume volume=owner.volumes.get(name);
            if(volume==null||expected!=null&&volume.format!=expected)throw new IllegalStateException("Missing/wrong typed current CPU image "+name);return volume;
        }
        private CpuVolume writable(String name){
            CpuVolume volume=typed(name,null);
            if(volume.frozen()){volume=volume.mutableCopy();owner.volumes.put(name,volume);}return volume;
        }
        private static boolean inside(CpuVolume volume,int x,int y,int z){return x>=0&&x<volume.width&&y>=0&&y<volume.height&&z>=0&&z<volume.depth;}
        public synchronized void invalidate(){active();invalid=true;}
        private void active(){owner.checkThread();if(invalid||owner.current!=this)throw new IllegalStateException("Stale/invalid CPU image frame");}
        private IrisImageDescriptors.Image descriptor(String name){var image=owner.descriptors.get(name);if(image==null)throw new IllegalStateException("No current 3D CPU image descriptor "+name);return image;}
    }
    public record ImageReceipt(Object pipeline,long generation,long frameOrdinal,String name,int textureId,long writtenTexels,long totalTexels,boolean complete) { }
}

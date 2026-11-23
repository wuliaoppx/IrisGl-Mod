package dev.antimony.gl.glsl;

import dev.antimony.gl.shim.GlShimState;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Current Iris compute inventory, including compute-only passes. List order is not a frame execution schedule.
 * Capture never calls use/dispatch or publishes resources. Setup uses 1x1, shadowComputes shadow resolution, other passes main RT dimensions. */
public final class IrisComputeGraph {
    private IrisComputeGraph() { }
    public record DispatchSpec(List<Integer> local, List<Integer> absolute, List<Float> relative, boolean indirect) {
        public DispatchSpec { local=List.copyOf(local);absolute=List.copyOf(absolute);relative=List.copyOf(relative); }
        public int[] groups(int width,int height){
            if(indirect)throw new IllegalStateException("Indirect CPU compute dispatch is not connected");
            if(local.size()!=3||local.stream().anyMatch(n->n<1))throw new IllegalStateException("Invalid Iris compute local size");
            if(!absolute.isEmpty()){
                if(absolute.size()!=3||absolute.stream().anyMatch(n->n<1))throw new IllegalStateException("Invalid absolute compute domain");
                return absolute.stream().mapToInt(Integer::intValue).toArray();
            }
            if(width<1||height<1)throw new IllegalArgumentException("Invalid compute viewport");
            if(relative.isEmpty())return new int[]{(int)Math.ceil((float)width/local.get(0)),(int)Math.ceil((float)height/local.get(1)),1};
            if(relative.size()!=2)throw new IllegalStateException("Invalid relative compute domain arity");
            double rx=relative.get(0),ry=relative.get(1);
            if(!Double.isFinite(rx)||!Double.isFinite(ry)||rx<=0||ry<=0)throw new IllegalStateException("Invalid relative compute domain");
            return new int[]{dimension(width,rx,local.get(0)),dimension(height,ry,local.get(1)),1};
        }
        private static int dimension(int size,double factor,int local){double groups=Math.ceil(Math.ceil(size*(float)factor)/local);if(groups<1||groups>Integer.MAX_VALUE)throw new IllegalStateException("Compute domain overflow");return (int)groups;}
    }
    public record Node(String stage,int pass,int slot,Object program,int programId,String sha256,
                       DispatchSpec dispatch,GlslProgramLoader.Loaded loaded,String failure) {
        public String location(){return stage+"["+pass+"].compute["+slot+"]";}
    }
    public record Snapshot(Object pipeline,List<Node> nodes){
        public Snapshot { nodes=List.copyOf(nodes); }
        public Set<String> requestedUniforms(){Set<String> names=new HashSet<>();for(Node node:nodes)if(node.loaded!=null)names.addAll(node.loaded.meta.usedUniforms);return Set.copyOf(names);}
        public boolean requiresExecution(){return !nodes.isEmpty();}
    }
    /** Read the actual renderer objects, not a global shader-ID search or positional ProgramSet guess. */
    public static Snapshot capture(Object pipeline)throws ReflectiveOperationException{
        Objects.requireNonNull(pipeline);
        if(pipeline.getClass().getName().equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline"))verifyOwner(pipeline);
        List<Node> nodes=new ArrayList<>();
        append(nodes,"setup",-1,field(pipeline,"setup"));
        for(String stage:List.of("beginRenderer","prepareRenderer"))passes(nodes,pipeline,stage);
        append(nodes,"shadowComputes",-1,field(pipeline,"shadowComputes"));
        passes(nodes,pipeline,"shadowCompositeRenderer");
        for(String stage:List.of("deferredRenderer","compositeRenderer"))passes(nodes,pipeline,stage);
        Object renderer=field(pipeline,"finalPassRenderer");
        if(renderer!=null){Object pass=field(renderer,"finalPass");if(pass!=null)append(nodes,"finalPassRenderer",0,field(pass,"computes"));}
        return new Snapshot(pipeline,nodes);
    }
    private static void passes(List<Node> nodes,Object pipeline,String stage)throws ReflectiveOperationException{
        Object renderer=field(pipeline,stage);if(renderer==null)return;
        Object passes=field(renderer,"passes");if(!(passes instanceof List<?> list))throw new IllegalStateException("Missing Iris pass list: "+stage);
        for(int i=0;i<list.size();i++)append(nodes,stage,i,field(list.get(i),"computes"));
    }
    private static void append(List<Node> nodes,String stage,int pass,Object values)throws ReflectiveOperationException{
        if(values==null)return;if(!values.getClass().isArray())throw new IllegalStateException("Iris computes is not an array: "+stage);
        for(int slot=0;slot<Array.getLength(values);slot++){
            Object program=Array.get(values,slot);if(program==null)continue;
            int id=((Number)program.getClass().getMethod("getProgramId").invoke(program)).intValue();
            if(id<=0)throw new IllegalStateException("Invalid current compute program ID");
            GlShimState.ShaderRecord source=GlShimState.program(id);
            if(source==null||source.computeSource==null||source.computeSource.isBlank())throw new IllegalStateException("Missing linked compute source at "+stage+"["+pass+"].compute["+slot+"] id="+id);
            int[] local=((int[])field(program,"localSize")).clone();
            if(local.length!=3)throw new IllegalStateException("Iris compute local size arity");
            Object absolute=field(program,"absoluteWorkGroups"),relative=field(program,"relativeWorkGroups"),indirect=field(program,"indirectPointer");
            DispatchSpec spec=new DispatchSpec(Arrays.stream(local).boxed().toList(),absolute==null?List.of():List.of(integer(absolute,"x"),integer(absolute,"y"),integer(absolute,"z")),relative==null?List.of():List.of(decimal(relative,"x"),decimal(relative,"y")),indirect!=null);
            var loaded=GlslProgramLoader.loadCompute(id,source.computeSource);String failure=loaded==null?GlslProgramLoader.computeFailure(id):null;
            if(loaded!=null&&!Arrays.equals(local,loaded.meta.localSize))failure="Iris local-size query differs from compiled source: iris="+Arrays.toString(local)+" source="+Arrays.toString(loaded.meta.localSize);
            if(indirect!=null)failure="Indirect compute resource/dispatch is not connected";
            nodes.add(new Node(stage,pass,slot,program,id,sha(source.computeSource),spec,loaded,failure));
        }
    }
    private static void verifyOwner(Object pipeline)throws ReflectiveOperationException{
        Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
        Object manager=iris.getMethod("getPipelineManager").invoke(null),dimension=iris.getMethod("getCurrentDimension").invoke(null);
        Object pack=((Optional<?>)iris.getMethod("getCurrentPack").invoke(null)).orElseThrow(()->new IllegalStateException("No current shader pack"));
        Object programs=field(field(pipeline,"resolver"),"programs"),map=field(manager,"pipelinesPerDimension");
        Object selected=pack.getClass().getMethod("getProgramSet",Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId")).invoke(pack,dimension);
        if(!(map instanceof Map<?,?> pipelines)||pipelines.get(dimension)!=pipeline||programs!=selected||programs.getClass().getMethod("getPack").invoke(programs)!=pack)
            throw new IllegalStateException("Compute graph does not belong to current pack/dimension pipeline");
    }
    private static int integer(Object value,String field)throws ReflectiveOperationException{return ((Number)field(value,field)).intValue();}
    private static float decimal(Object value,String field)throws ReflectiveOperationException{return ((Number)field(value,field)).floatValue();}
    private static Object field(Object owner,String name)throws ReflectiveOperationException{
        if(owner==null)throw new IllegalStateException("Null owner for Iris field "+name);
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
    }
    private static String sha(String source){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}

package dev.antimony.gl.glsl;

import java.util.List;

/** Focused descriptor/resource-owner lifecycle probe; it does not execute pack compute. */
public final class IrisCpuImageResourcesProbe {
    private IrisCpuImageResourcesProbe(){}
    public static void main(String[] args){
        Object pipeline=new Object();var images=List.of(
            image(pipeline,"voxel_img","voxelSampler",11,0x8D94,0x8232,0x1405,8,4,8,true),
            image(pipeline,"floodfill_img","floodfillSampler",12,0x1908,0x881A,0x140B,8,4,8,false),
            image(pipeline,"floodfill_img_copy","floodfillSamplerCopy",13,0x1908,0x881A,0x140B,8,4,8,false));
        var resources=new IrisCpuImageResources(16_384);var frame=resources.beginFrame(pipeline,1,images);
        check(frame.generation()==1&&frame.frameOrdinal()==1,"frame identity");
        check(frame.dimensions("voxel_img")[1]==4,"declared 3D dimensions retained");
        check(frame.receipt("voxel_img").writtenTexels()==0&&!frame.receipt("voxel_img").complete(),"allocation is not producer readiness");
        reject(()->frame.readFloat("floodfill_img",0,0,0),"clear=false allocation is not history readiness");
        frame.writeUnsigned("voxel_img",1,2,3,255);frame.writeUnsigned("voxel_img",1,2,3,17);
        reject(()->frame.readUnsigned("voxel_img",1,2,3),"partial image cannot be sampled as current input");
        check(frame.receipt("voxel_img").writtenTexels()==1,"coverage counts unique texels, not duplicate writes");
        for(int z=0;z<8;z++)for(int y=0;y<4;y++)for(int x=0;x<8;x++)frame.writeUnsigned("voxel_img",x,y,z,(x+y+z)&255);
        check(frame.readUnsigned("voxel_img",1,2,3)[0]==6,"complete R8UI stores and reads unsigned values");
        check(frame.receipt("voxel_img").writtenTexels()==256,"coverage counts unique texels, not duplicate writes");
        CpuVolume published=frame.requireComplete("voxel_img",pipeline,frame.generation());
        check(published.fetchUnsigned(1,2,3,0)[0]==6,"complete image publication");
        frame.writeUnsigned("voxel_img",1,2,3,99);
        check(published.fetchUnsigned(1,2,3,0)[0]==6&&frame.readUnsigned("voxel_img",1,2,3)[0]==99,
            "published snapshot survives later owner stores through copy-on-write");
        frame.writeFloat("floodfill_img",0,0,0,new float[]{2,3,4,5});
        for(int z=0;z<8;z++)for(int y=0;y<4;y++)for(int x=0;x<8;x++)frame.writeFloat("floodfill_img",x,y,z,new float[]{2,3,4,5});
        reject(()->frame.writeFloat("floodfill_img",1,0,0,new float[]{Float.NaN,1,1,1}),"nonfinite float storage rejected");
        reject(()->frame.writeFloat("floodfill_img",1,0,0,new float[]{70000,1,1,1}),"half overflow rejected");
        for(int z=0;z<8;z++)for(int y=0;y<4;y++)for(int x=0;x<8;x++)frame.writeFloat("floodfill_img",x,y,z,new float[]{2,3,4,5});
        frame.writeFloat("floodfill_img_copy",0,0,0,new float[]{6,7,8,9});
        var second=resources.beginFrame(pipeline,2,images);
        reject(()->second.readUnsigned("voxel_img",1,2,3),"clear=true frame has no producer readiness");
        check(second.readFloat("floodfill_img",0,0,0)[0]==2,"clear=false preserves complete prior image history");
        second.writeFloat("floodfill_img",1,0,0,new float[]{4,5,6,7});
        reject(()->second.readFloat("floodfill_img",0,0,0),"partial history rewrite invalidates reads");
        for(int z=0;z<8;z++)for(int y=0;y<4;y++)for(int x=0;x<8;x++)second.writeFloat("floodfill_img",x,y,z,new float[]{4,5,6,7});
        check(second.readFloat("floodfill_img",0,0,0)[0]==4,"full history rewrite publishes current generation");
        check(second.receipt("voxel_img").writtenTexels()==0,"new frame has independent write coverage");
        reject(()->frame.readUnsigned("voxel_img",1,2,3),"prior-frame image token is stale");
        for(int z=0;z<8;z++)for(int y=0;y<4;y++)for(int x=0;x<8;x++)second.writeUnsigned("voxel_img",x,y,z,(x+y+z)&255);
        var complete=second.receipt("voxel_img");check(complete.complete()&&complete.writtenTexels()==256,"full texel coverage receipt");
        check(second.requireComplete("voxel_img",pipeline,second.generation()).fetchUnsigned(7,3,3,0)[0]==13,"complete immutable publication");
        reject(()->second.requireComplete("voxel_img",new Object(),second.generation()),"pipeline mismatch");
        var retryOwner=new IrisCpuImageResources(128);reject(()->retryOwner.beginFrame(pipeline,1,images),"failed allocation rolls back before image publication");
        var retried=retryOwner.beginFrame(pipeline,1,List.of(image(pipeline,"small","smallSampler",31,0x8D94,0x8232,0x1405,2,2,2,true)));
        reject(()->retryOwner.beginFrame(pipeline,1,List.of(image(pipeline,"small","smallSampler",31,0x8D94,0x8232,0x1405,2,2,2,true))),"duplicate/stale frame ordinal rejected");
        check(retried.receipt("small").writtenTexels()==0,"same-ordinal retry publishes clean state");
        reject(()->new IrisCpuImageResources(128).beginFrame(pipeline,1,images),"allocation budget fails before image publication");
        var bad=List.of(image(pipeline,"bad","badSampler",21,0x8D94,0x8232,0x1401,2,2,2,true));
        reject(()->new IrisCpuImageResources(1024).beginFrame(pipeline,1,bad),"unsupported typed pixel input fails closed");
        allocationAndComputeTransactions();
        System.out.println("IrisCpuImageResourcesProbe OK: descriptor ownership, budget, R8UI/float types, clear/history, unique coverage, complete/partial and stale receipts");
    }
    private static void allocationAndComputeTransactions(){
        Object pipeline=new Object();var descriptors=List.of(
            image(pipeline,"input","source",41,0x1908,0x881A,0x140B,2,1,1,false),
            image(pipeline,"first","firstSampler",42,0x1908,0x881A,0x140B,2,1,1,false),
            image(pipeline,"second","secondSampler",43,0x1908,0x881A,0x140B,2,1,1,false));
        var owner=new IrisCpuImageResources(1024);var frame=owner.beginFrame(pipeline,1,descriptors);
        reject(()->frame.readFloat("input",0,0,0),"declaration alone cannot bootstrap history");
        frame.importIrisAllocationClear();
        check(frame.readFloat("input",0,0,0)[0]==0&&!frame.receipt("input").complete(),"Iris setup clear is readable initialization, not producer receipt");
        String source="layout(local_size_x=1) in;uniform int mode;uniform sampler3D source;writeonly uniform image3D first,second;void main(){ivec3 p=ivec3(gl_GlobalInvocationID);imageStore(first,p,texelFetch(source,p,0)+vec4(3.0));if(mode==1)imageStore(second,p,vec4(4.0));}";
        var loaded=GlslProgramLoader.loadCompute(1710000001,source);check(loaded!=null,"owner transaction shader compiles");
        try(var dispatch=new CpuComputeDispatch(pipeline,loaded.program,loaded.meta)){
            reject(()->frame.dispatchAndCommit(dispatch,java.util.Map.of("mode",1),new int[]{2,1,1},java.util.Set.of("first"),0),"store to unselected image rejects transaction");
            check(frame.readFloat("first",0,0,0)[0]==0&&!frame.receipt("first").complete(),"failed dispatch publishes no first output");
            frame.dispatchAndCommit(dispatch,java.util.Map.of("mode",1),new int[]{2,1,1},java.util.Set.of("first","second"),0);
            check(frame.readFloat("first",0,0,0)[0]==3&&frame.readFloat("second",0,0,0)[0]==4,"both validated outputs commit");
            CpuVolume frozen=frame.readableSnapshot("first");
            frame.writeFloat("first",0,0,0,new float[]{7,7,7,7});
            check(frozen.fetch(0,0,0,0)[0]==3,"compute receipt has no mutable backing alias");
        }
        var next=owner.beginFrame(pipeline,2,descriptors);var later=owner.beginFrame(pipeline,3,descriptors);
        check(later.readFloat("second",0,0,0)[0]==4,"clear=false complete history survives an unused frame");
        var changed=List.of(image(pipeline,"input","source",44,0x1908,0x881A,0x140B,2,1,1,false));
        var reset=owner.beginFrame(pipeline,4,changed);reject(()->reset.readFloat("input",0,0,0),"descriptor rebuild discards old history");
        System.out.println("PASS owner initialization provenance, immutable snapshots/copy-on-write, multi-output atomic commit, unselected-store rollback, retained history and descriptor rebuild");
    }
    private static IrisImageDescriptors.Image image(Object pipeline,String name,String sampler,int id,int pixelFormat,int internal,int pixelType,int w,int h,int d,boolean clear){
        return new IrisImageDescriptors.Image(pipeline,name,sampler,id,0x806F,internal,pixelFormat,pixelType,w,h,d,clear,false,0,0);
    }
    private static void reject(Runnable test,String label){try{test.run();throw new AssertionError("Expected rejection: "+label);}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
}

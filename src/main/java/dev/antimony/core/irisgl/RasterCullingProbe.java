package dev.antimony.core.irisgl;

import dev.antimony.gl.glsl.*;
import java.util.*;

/** CPU numerical/side-effect fixtures, not shaderpack appearance or game FPS evidence. */
public final class RasterCullingProbe {
    private static final int W=257,H=193;
    private static final Random RANDOM=new Random(0x435055434c49504cL);
    public static final class Fragment implements GlslProgram {
        public float[] tint=new float[4];
        public int material;
        public float[][] out=new float[1][4];
        public Fragment() { }
        public void run(FragState state){out[0]=new float[]{tint[0]+material%7,tint[1],tint[2],tint[3]};}
    }
    private record Result(int[] color,float[] depth,float[] hdr,IrisGlContext.RasterStats stats) { }
    public static void main(String[] args)throws Exception {
        coverageBounds();scissor();vertexStores();
        var vm=new GlslCompiler().translateVertex("CullFixtureV","out vec4 tint;flat out int material;void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        var fm=new GlslCompiler().translate("CullFixtureF","in vec4 tint;flat in int material;layout(location=0) out vec4 c;void main(){c=vec4(tint.x+float(material%7),tint.yzw);}","");
        ShaderVaryings layout=ShaderVaryings.link(vm,fm);
        long before=0,after=0;
        for(boolean hdr:new boolean[]{false,true})for(int mode=0;mode<3;mode++){
            float[][] triangles=triangles(hdr?layout:null);
            Result baseline=render(triangles,false,mode,hdr?layout:null,fm);
            Result optimized=render(triangles,true,mode,hdr?layout:null,fm);
            check(Arrays.equals(baseline.color,optimized.color),"ARGB differs mode="+mode+" hdr="+hdr);
            bits(baseline.depth,optimized.depth,"depth");bits(baseline.hdr,optimized.hdr,"HDR");
            before+=baseline.stats.tileReferences();after+=optimized.stats.tileReferences();
            check(optimized.stats.tileCandidates()==optimized.stats.tileReferences()+optimized.stats.tileRejected(),"tile receipt accounting");
            check(optimized.stats.submitted()==triangles.length,"all submitted vertices retained");
        }
        check(after<before,"conservative coverage must remove empty tile references in these fixtures");
        System.out.println("PASS raster culling A/B: exact ARGB/depth/HDR, both windings, six-plane clipping, unequal w, flat raw integer bits, frozen viewport/scissor and parallel tiles");
        System.out.println("CPU LAB tile references: uncull="+before+" cull="+after+" reduction="+(100.0*(before-after)/before)+"%; NOT game FPS");
    }
    private static void coverageBounds(){
        for(int i=0;i<6000;i++){
            float x0=random(-80,80),y0=random(-80,80),x1=random(-80,80),y1=random(-80,80),x2=random(-80,80),y2=random(-80,80);
            if(i%4==0){x2=x0+(x1-x0)*.75f;y2=Math.nextUp(y0+(y1-y0)*.75f);}
            float area=edge(x0,y0,x1,y1,x2,y2);
            int left=RANDOM.nextInt(33)-16,top=RANDOM.nextInt(33)-16,right=left+RANDOM.nextInt(16),bottom=top+RANDOM.nextInt(16);
            if(TriangleTileCoverage.mayCover(x0,y0,x1,y1,x2,y2,area,left,top,right,bottom))continue;
            if(area<0){float x=x1,y=y1;x1=x2;y1=y2;x2=x;y2=y;area=-area;}
            float inv=1f/area;
            for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++){
                float e0=edge(x1,y1,x2,y2,x+.5f,y+.5f)*inv,e1=edge(x2,y2,x0,y0,x+.5f,y+.5f)*inv;
                check(e0<0||e1<0||1f-e0-e1<0,"coverage classifier lost a scalar-raster pixel");
            }
        }
        check(TriangleTileCoverage.mayCover(0,0,1,1,2,2,Float.NaN,0,0,1,1),"unknown arithmetic cannot be culled");
        System.out.println("PASS 6000 float coverage bounds, including thin/near-degenerate triangles and conservative nonfinite fallback");
    }
    private static float[][] triangles(ShaderVaryings layout){
        int stride=17+(layout==null?0:layout.components());float[][] result=new float[600][];
        for(int i=0;i<result.length;i++){
            float[] t=result[i]=new float[3*stride];
            for(int q=0;q<3;q++){
                int o=q*stride;float w=random(.1f,4);
                t[o]=random(-1.8f,1.8f)*w;t[o+1]=random(-1.8f,1.8f)*w;t[o+2]=random(-1.3f,1.3f)*w;t[o+3]=w;
                t[o+6]=random(.1f,1);t[o+7]=random(.1f,1);t[o+8]=random(.1f,1);t[o+9]=.75f;
                if(layout!=null){
                    int p=o+17+layout.inputLane("tint").offset();for(int c=0;c<4;c++)t[p+c]=random(.1f,1);
                    t[o+17+layout.inputLane("material").offset()]=Float.intBitsToFloat(q==2?0x7fc00007-i:q);
                }
            }
        }
        return result;
    }
    private static Result render(float[][] triangles,boolean culling,int mode,ShaderVaryings layout,GlslCompiler.Result fm){
        IrisGlContext ctx=new IrisGlContext();ctx.beginFrame(W,H);ctx.framebuffer.clearColor(0x11335577);
        TileRasterEngine engine=new TileRasterEngine(ctx);engine.tileCulling=culling;
        CpuImage image=new CpuImage(W,H);image.clear(9,8,7,6);
        RasterShader shader=layout==null?null:new TerrainFragmentDraw(new Fragment(),fm,layout,Map.of(),Map.of(),new int[]{0},
            Map.of(0,new TerrainFragmentDraw.Attachment(image,TerrainFragmentDraw.Encoding.RGBA32F,TerrainFragmentDraw.Blend.OFF)),W,H,null);
        for(int i=0;i<triangles.length;i++){
            ctx.cull=mode==2&&i%3==0;ctx.blend=layout==null&&i%4==0;
            if(mode>0){ctx.viewportX=7;ctx.viewportY=9;ctx.viewportW=W-13;ctx.viewportH=H-21;}
            ctx.scissorEnabled=mode==1&&i%2==0;ctx.scissorX=17;ctx.scissorY=29;ctx.scissorW=135;ctx.scissorH=79;
            int stride=triangles[i].length/3;engine.binTriangle(triangles[i],0,stride,2*stride,stride,shader);
        }
        ctx.viewportW=1;ctx.viewportH=1;ctx.scissorEnabled=false;engine.flush();
        return new Result(ctx.framebuffer.color.clone(),ctx.framebuffer.depth.clone(),layout==null?new float[0]:image.rgba.clone(),engine.statistics());
    }
    private static void scissor(){
        IrisGlContext ctx=new IrisGlContext();ctx.beginFrame(17,13);ctx.depthTest=false;ctx.depthWrite=false;
        TileRasterEngine engine=new TileRasterEngine(ctx);
        ctx.scissorEnabled=true;ctx.scissorX=2;ctx.scissorY=3;ctx.scissorW=4;ctx.scissorH=5;quad(engine,0,0,1);
        ctx.scissorX=10;ctx.scissorY=9;ctx.scissorW=3;ctx.scissorH=2;quad(engine,1,0,0);
        ctx.scissorEnabled=false;engine.flush();
        for(int y=0;y<13;y++)for(int x=0;x<17;x++){
            int expected=x>=2&&x<6&&y>=3&&y<8?0xff0000ff:x>=10&&x<13&&y>=9&&y<11?0xffff0000:0;
            check(ctx.framebuffer.color[y*17+x]==expected,"exact sub-tile/frozen scissor "+x+","+y);
        }
        System.out.println("PASS exact sub-tile scissor and different queued scissors, not just tile selection");
    }
    private static void quad(TileRasterEngine engine,float r,float g,float b){
        float[] data=new float[4*17];float[][] xy={{-1,-1},{1,-1},{1,1},{-1,1}};
        for(int i=0;i<4;i++){int o=i*17;data[o]=xy[i][0];data[o+1]=xy[i][1];data[o+3]=1;data[o+6]=r;data[o+7]=g;data[o+8]=b;data[o+9]=1;}
        engine.binTriangle(data,0,17,34);engine.binTriangle(data,0,34,51);
    }
    private static void vertexStores()throws Exception{
        var loaded=GlslProgramLoader.loadVertex(1850000001,"#version 430\nuniform uimage3D voxel_img;uniform vec4 clip;void main(){imageStore(voxel_img,ivec3(gl_VertexID,0,0),uvec4(7));gl_Position=clip;}");
        check(loaded!=null,"actual imageStore VS compile");CpuVolume volume=new CpuVolume(6,1,1,CpuVolume.Format.R8UI,false,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE,CpuVolume.Wrap.CLAMP_TO_EDGE);
        int unit=loaded.meta.imageUnits.get("voxel_img");loaded.program.getClass().getField("i$"+unit).setInt(loaded.program,unit);
        loaded.program.getClass().getField(loaded.meta.uniformFields.get("clip")).set(loaded.program,new float[]{3,0,0,1});
        FragState stores=new FragState(){public float width(){return 1;}public float height(){return 1;}public float[] sample(int u,float x,float y){throw new AssertionError();}
            public void storeUnsignedImage3D(int u,int[] p,int[] value){check(u==unit,"image unit");volume.storeUnsigned(p[0],p[1],p[2],value[0]);}};
        IrisGlContext ctx=new IrisGlContext();ctx.beginFrame(8,8);TileRasterEngine engine=new TileRasterEngine(ctx);float[] triangle=new float[51];
        for(int i=0;i<6;i++){
            loaded.program.runVertex(new VertexState(new CpuImage[0],8,8,0,0).sourceVertexId(i).imageServices(stores));
            System.arraycopy(loaded.program.vertexPosition(),0,triangle,(i%3)*17,4);
            if(i%3==2)engine.binTriangle(triangle,0,17,34);
        }
        engine.flush();for(int x=0;x<6;x++)check(volume.fetchUnsigned(x,0,0,0)[0]==7,"offscreen VS imageStore retained");
        check(engine.statistics().clipRejected()==2,"post-VS rejection reached production engine");
        System.out.println("PASS actual CPU imageStore VS retains all six offscreen stores before clip rejection");
    }
    private static void bits(float[] a,float[] b,String name){check(a.length==b.length,name+" arity");for(int i=0;i<a.length;i++)check(Float.floatToRawIntBits(a[i])==Float.floatToRawIntBits(b[i]),name+" bits at "+i);}
    private static float random(float min,float max){return min+RANDOM.nextFloat()*(max-min);}
    private static float edge(float ax,float ay,float bx,float by,float px,float py){return (px-ax)*(by-ay)-(py-ay)*(bx-ax);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

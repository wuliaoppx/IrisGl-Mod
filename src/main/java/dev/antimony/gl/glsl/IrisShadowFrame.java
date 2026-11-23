package dev.antimony.gl.glsl;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** CPU frame geometry from Iris' actual ShadowRenderer settings and matrix/frustum builders. */
public final class IrisShadowFrame {
    private final Object pipeline;
    private final Matrix4f view,projection;
    private final Frustum frustum;
    private final boolean terrain,translucent;
    private final int resolution,renderDistance;
    private IrisShadowFrame(Object pipeline,Matrix4f view,Matrix4f projection,Frustum frustum,
                            boolean terrain,boolean translucent,int resolution,int renderDistance){
        this.pipeline=pipeline;this.view=new Matrix4f(view);this.projection=new Matrix4f(projection);
        this.frustum=frustum;this.terrain=terrain;this.translucent=translucent;
        this.resolution=resolution;this.renderDistance=renderDistance;
    }
    public static IrisShadowFrame capture(Object pipeline,Vec3 camera)throws ReflectiveOperationException{
        Object renderer=field(pipeline,"shadowRenderer");
        if(renderer==null)throw new IllegalStateException("Current Iris pipeline has no shadow renderer");
        Class<?> type=renderer.getClass(),matrices=Class.forName("net.irisshaders.iris.shadows.ShadowMatrices");
        float half=number(renderer,"halfPlaneLength"),near=number(renderer,"nearPlane"),far=number(renderer,"farPlane");
        float multiplier=number(renderer,"renderDistanceMultiplier");
        PoseStack stack=(PoseStack)type.getMethod("createShadowModelView",float.class,float.class,float.class,float.class)
            .invoke(null,number(renderer,"sunPathRotation"),number(renderer,"intervalSize"),near,far);
        Matrix4f view=new Matrix4f(stack.last().pose());
        Float fov=(Float)field(renderer,"fov");Matrix4f projection;
        if(fov!=null)projection=(Matrix4f)matrices.getMethod("createPerspectiveMatrix",float.class).invoke(null,fov);
        else{
            if(Math.abs(near+1)<1e-5f||Math.abs(far+1)<1e-5f){
                int distance=((Number)Class.forName("net.irisshaders.iris.compat.dh.DHCompat").getMethod("getRenderDistance").invoke(null)).intValue()*16;
                if(Math.abs(near+1)<1e-5f)near=-distance;if(Math.abs(far+1)<1e-5f)far=distance;
            }
            projection=(Matrix4f)matrices.getMethod("createOrthoMatrix",float.class,float.class,float.class).invoke(null,half,near,far);
        }
        // These are the same fields Iris' matrix uniform suppliers consume. No GL setup/render method is called.
        type.getField("MODELVIEW").set(null,new Matrix4f(view));
        type.getField("PROJECTION").set(null,new Matrix4f(projection));
        Object holder=field(renderer,"terrainFrustumHolder");
        Method factory=type.getDeclaredMethod("createShadowFrustum",float.class,holder.getClass());factory.setAccessible(true);
        Object result=factory.invoke(renderer,multiplier,holder);
        Frustum frustum=(Frustum)result.getClass().getMethod("getFrustum").invoke(result);
        frustum.prepare(camera.x,camera.y,camera.z);
        int normal=Minecraft.getInstance().options.getEffectiveRenderDistance();
        int distance=multiplier<0?((Number)Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings").getField("shadowDistance").get(null)).intValue():(int)(half*multiplier/16);
        return new IrisShadowFrame(pipeline,view,projection,frustum,(Boolean)field(renderer,"shouldRenderTerrain"),
            (Boolean)field(renderer,"shouldRenderTranslucent"),((Number)field(renderer,"resolution")).intValue(),Math.max(0,Math.min(normal,distance)));
    }
    public Object pipeline(){return pipeline;}
    public int resolution(){return resolution;}
    public int renderDistance(){return renderDistance;}
    public boolean terrain(){return terrain;}
    public boolean translucent(){return translucent;}
    public Matrix4f view(){return new Matrix4f(view);}
    public Matrix4f projection(){return new Matrix4f(projection);}
    public boolean includes(int x,int y,int z){return frustum.isVisible(new AABB(x*16.0,y*16.0,z*16.0,(x+1)*16.0,(y+1)*16.0,(z+1)*16.0));}
    public float[] matrix(String name){return switch(name){
        case "shadowModelView"->view.get(new float[16]);
        case "shadowModelViewInverse"->new Matrix4f(view).invert().get(new float[16]);
        case "shadowProjection"->projection.get(new float[16]);
        case "shadowProjectionInverse"->new Matrix4f(projection).invert().get(new float[16]);
        default->null;
    };}
    private static float number(Object owner,String name)throws ReflectiveOperationException{return ((Number)field(owner,name)).floatValue();}
    private static Object field(Object owner,String name)throws ReflectiveOperationException{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
    }
}

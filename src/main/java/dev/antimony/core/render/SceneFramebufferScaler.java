package dev.antimony.core.render;

import dev.antimony.core.irisgl.*;

/** Reuses a smaller CPU scene buffer and a full-window presentation/GUI buffer. No GL blit. */
public final class SceneFramebufferScaler {
    private static IrisGlFramebuffer scene,display;
    private static int windowWidth,windowHeight;
    private SceneFramebufferScaler(){}
    public static void beginScene(IrisGlContext context,int width,int height,RenderQualitySettings.Preset preset){
        windowWidth=width;windowHeight=height;
        int sw=Math.max(1,Math.round(width*preset.sceneScale)),sh=Math.max(1,Math.round(height*preset.sceneScale));
        if(sw==width&&sh==height&&display!=null&&display.width==width&&display.height==height)scene=display;
        else if(scene==null||scene.width!=sw||scene.height!=sh)scene=new IrisGlFramebuffer(sw,sh);
        if(sw==width&&sh==height)display=scene;
        else if(display==null||display==scene||display.width!=width||display.height!=height)display=new IrisGlFramebuffer(width,height);
        RenderQualitySettings.dimensions(width,height,sw,sh);context.beginFrame(scene);
    }
    public static void prepareGui(IrisGlContext context){
        if(scene==display)return;
        context.beginFrame(display);copy(scene,display);
    }
    public static void copy(IrisGlFramebuffer source,IrisGlFramebuffer destination){
        if(source==destination)return;
        for(int y=0;y<destination.height;y++){
            int sy=Math.min(source.height-1,y*source.height/destination.height);
            for(int x=0;x<destination.width;x++){
                int sx=Math.min(source.width-1,x*source.width/destination.width),s=sy*source.width+sx,d=y*destination.width+x;
                destination.color[d]=source.color[s];destination.depth[d]=source.depth[s];
            }
        }
    }
    public static int sceneWidth(){return scene.width;}public static int sceneHeight(){return scene.height;}
}

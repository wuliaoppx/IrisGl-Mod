package dev.antimony.core.present;

import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;

/** CPU framebuffer presentation through GDI only. Unavailable windows never trigger a GPU blit. */
public final class ScreenPresenter {
    private static final Win32Presenter WIN32=new Win32Presenter();
    private static boolean broken,loggedUnavailable,loggedHide,everPresented,loggedMode;
    private ScreenPresenter() { }
    public static boolean gdi(){return true;}

    public static void frameTick(boolean replacing){
        if(replacing)return;
        // Disable native following even if the last attempt did not become visible.
        WIN32.hide();
        if(everPresented&&!loggedHide){
            loggedHide=true;
            AntimonyMod.LOG.info("[Antimony] software renderer released the screen - GDI overlay hidden");
        }
    }
    public static boolean overlayGoneForProbe(){return WIN32.probeOverlayGone();}
    public static void present(Minecraft mc,IrisGlFramebuffer framebuffer){
        if(broken)return;
        if(!loggedMode&&!"gdi".equalsIgnoreCase(System.getProperty("antimony.present","gdi"))){
            loggedMode=true;
            AntimonyMod.LOG.warn("[Antimony] CPU-only presentation ignores non-GDI presenter selection; no GPU fallback");
        }
        try{
            if(WIN32.present(framebuffer,windowHandle(mc))){
                if(!everPresented)AntimonyMod.LOG.info("[Antimony] GDI overlay is painting the software frame");
                everPresented=true;loggedHide=false;return;
            }
        }catch(RuntimeException|LinkageError failure){
            broken=true;
            try{WIN32.hide();}catch(RuntimeException ignored){}
            AntimonyMod.LOG.warn("[Antimony] CPU presenter failed; no GL/GPU presentation fallback",failure);
            return;
        }
        if(!loggedUnavailable){
            loggedUnavailable=true;
            AntimonyMod.LOG.info("[Antimony] CPU presenter waits for a visible active game window");
        }
    }
    public static void close(){WIN32.close();everPresented=false;broken=false;loggedUnavailable=false;}
    private static long windowHandle(Minecraft mc){
        if(mc==null||mc.getWindow()==null||mc.getWindow().getWindow()==0)return 0;
        return GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
    }
}

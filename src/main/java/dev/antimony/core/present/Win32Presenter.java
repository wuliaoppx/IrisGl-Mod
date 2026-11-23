package dev.antimony.core.present;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import dev.antimony.AntimonyMod;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.win32.Win32;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** CPU-only presentation. The host's native message loop positions the last immutable frame,
 * even when GLFW is inside Windows' interactive move/size loop and Java is not rendering. */
public final class Win32Presenter {
    private static final int EX_STYLE=Win32.WS_EX_LAYERED|Win32.WS_EX_TRANSPARENT
        |Win32.WS_EX_NOACTIVATE|Win32.WS_EX_TOOLWINDOW;
    private static final Win32.UIntPtr SUBCLASS_ID=new Win32.UIntPtr(0x414e5449L);
    private final Win32.SubclassProc callback=this::hostMessage;
    private Thread ownerThread;
    private Pointer host,hwnd,screenDc,memDc,bitmap,stockBitmap;
    private IntBuffer pixels;
    private int dibWidth,dibHeight,lastX=Integer.MIN_VALUE,lastY=Integer.MIN_VALUE;
    private Frame lastFrame;
    private boolean enabled,visible,attached,updating,pendingSync,closing,loggedFailure,draining,pendingClose;
    private int nativeDepth;
    private long frames,dibCreated,dibDeleted;
    private volatile long geometryUpdates;
    private record Frame(int width,int height,int[] color) { }

    /** The caller retains the framebuffer; only a detached immutable pixel copy crosses the message boundary. */
    public boolean present(IrisGlFramebuffer fb,long mcWindowHandle){
        requireThread();
        if(updating)throw new IllegalStateException("Reentrant CPU frame submission during a native presentation operation");
        if(fb==null||fb.width<1||fb.height<1||fb.color.length!=Math.multiplyExact(fb.width,fb.height))
            throw new IllegalArgumentException("Invalid CPU presentation frame");
        if(mcWindowHandle==0){hide();return false;}
        Pointer nextHost=Pointer.createConstant(mcWindowHandle);
        if(!Win32.User32.INSTANCE.IsWindow(nextHost)){hide();return false;}
        if(!nextHost.equals(host)||hwnd==null){
            closeNative();
            if(attached)throw new IllegalStateException("Previous host subclass could not be detached");
            attach(nextHost);
        }
        int[] copy=CpuScreenImage.opaqueArgbCopy(fb);
        lastFrame=new Frame(fb.width,fb.height,copy);
        enabled=true;frames++;
        return synchronize(true);
    }
    private void requireThread(){
        if(ownerThread==null)ownerThread=Thread.currentThread();
        if(Thread.currentThread()!=ownerThread)throw new IllegalStateException("GDI presentation must stay on its window owner thread");
    }
    private void attach(Pointer nextHost){
        if(Win32.User32.INSTANCE.GetWindowThreadProcessId(nextHost,null)!=Win32.Kernel32.INSTANCE.GetCurrentThreadId())
            throw new IllegalStateException("Cannot subclass a game window owned by another native thread");
        closing=false;host=nextHost;
        try{
            Pointer previousDpi=Win32.User32.INSTANCE.SetThreadDpiAwarenessContext(
                Win32.User32.INSTANCE.GetWindowDpiAwarenessContext(host));
            try{
                hwnd=Win32.User32.INSTANCE.CreateWindowExA(EX_STYLE,"STATIC","Antimony CPU frame",Win32.WS_POPUP,
                    0,0,16,16,host,null,null,null);
            }finally{if(previousDpi!=null)Win32.User32.INSTANCE.SetThreadDpiAwarenessContext(previousDpi);}
            if(badHandle(hwnd))throw new IllegalStateException("CreateWindowExA failed");
            screenDc=Win32.User32.INSTANCE.GetDC(null);
            if(badHandle(screenDc))throw new IllegalStateException("GetDC failed");
            memDc=Win32.Gdi32.INSTANCE.CreateCompatibleDC(screenDc);
            if(badHandle(memDc))throw new IllegalStateException("CreateCompatibleDC failed");
            if(!Win32.Comctl32.INSTANCE.SetWindowSubclass(host,callback,SUBCLASS_ID,new Win32.UIntPtr()))
                throw new IllegalStateException("SetWindowSubclass failed");
            attached=true;
            AntimonyMod.LOG.info("[Antimony] CPU presenter attached to host {}; native-message following, GDI only",host);
        }catch(RuntimeException|Error failure){closeNative();throw failure;}
    }
    private Win32.IntPtr hostMessage(Pointer window,int message,Win32.UIntPtr wParam,Win32.IntPtr lParam,
                                    Win32.UIntPtr id,Win32.UIntPtr reference){
        nativeDepth++;
        boolean destroy=message==Win32.WM_NCDESTROY&&window.equals(host);
        try{
            // Helper failures must never bypass or unwind through GLFW's original window procedure.
            try{
                if(destroy){enabled=false;closing=true;pendingClose=true;detachSubclass();}
                else if(message==Win32.WM_ACTIVATEAPP&&wParam.longValue()==0
                     ||message==Win32.WM_ACTIVATE&&(wParam.longValue()&0xffff)==0
                     ||message==Win32.WM_SIZE&&wParam.longValue()==Win32.SIZE_MINIMIZED)show(false);
            }catch(Throwable failure){logFailure(failure);}
            Win32.IntPtr result=Win32.Comctl32.INSTANCE.DefSubclassProc(window,message,wParam,lParam);
            try{
                if(destroy)closeNative();
                else if(message==Win32.WM_WINDOWPOSCHANGED||message==Win32.WM_MOVE||message==Win32.WM_SIZE
                    ||message==Win32.WM_DPICHANGED||message==Win32.WM_EXITSIZEMOVE||message==Win32.WM_SHOWWINDOW
                    ||message==Win32.WM_ACTIVATE||message==Win32.WM_ACTIVATEAPP){
                    pendingSync=true;
                    // SC_MOVE/SC_SIZE keeps an outer DefSubclassProc on the stack until release.
                    // Synchronize this nested notification now, not when all callbacks unwind.
                    if(!updating&&!closing){pendingSync=false;synchronize(false);}
                }
            }catch(Throwable failure){enabled=false;pendingSync=false;try{show(false);}catch(Throwable ignored){}logFailure(failure);}
            return result;
        }catch(Throwable failure){
            // The original chain was still called exactly once; never let a Java exception escape JNA.
            enabled=false;logFailure(failure);return new Win32.IntPtr();
        }finally{nativeDepth--;}
    }
    private boolean synchronize(boolean newPixels){
        if(updating){pendingSync=true;return visible;}
        if(!enabled||closing||lastFrame==null||host==null||hwnd==null)return false;
        updating=true;
        try{
            if(!Win32.User32.INSTANCE.IsWindow(host)||!Win32.User32.INSTANCE.IsWindowVisible(host)
                ||Win32.User32.INSTANCE.IsIconic(host)) {show(false);return false;}
            if(!AntimonyMod.flag("antimony.forceOverlay")&&!host.equals(Win32.User32.INSTANCE.GetForegroundWindow())){
                show(false);return false;
            }
            Win32.Rect client=new Win32.Rect();Win32.Point origin=new Win32.Point(0,0);
            if(!Win32.User32.INSTANCE.GetClientRect(host,client)||!Win32.User32.INSTANCE.ClientToScreen(host,origin)){
                show(false);return false;
            }
            int width=client.right-client.left,height=client.bottom-client.top;
            if(width<=0||height<=0){show(false);return false;}
            boolean resized=width!=dibWidth||height!=dibHeight;
            if(resized||newPixels){
                ensureDib(width,height);copyScaled(lastFrame,width,height);
                if(!Win32.User32.INSTANCE.UpdateLayeredWindow(hwnd,screenDc,origin,new Win32.Size(width,height),memDc,
                    new Win32.Point(0,0),0,new Win32.BlendFunction(),Win32.ULW_ALPHA)){
                    show(false);throw new IllegalStateException("UpdateLayeredWindow failed: "+Native.getLastError());
                }
                geometryUpdates++;
            }else if(origin.x!=lastX||origin.y!=lastY){
                if(!Win32.User32.INSTANCE.SetWindowPos(hwnd,null,origin.x,origin.y,width,height,
                    Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER|Win32.SWP_NOSIZE)){
                    show(false);throw new IllegalStateException("SetWindowPos failed: "+Native.getLastError());
                }
                geometryUpdates++;
            }
            lastX=origin.x;lastY=origin.y;show(true);return true;
        }finally{
            updating=false;
            if(pendingClose)finishClose();
            else if(pendingSync&&!draining&&!closing){
                pendingSync=false;draining=true;
                try{synchronize(false);}finally{draining=false;}
            }
        }
    }
    private void copyScaled(Frame source,int width,int height){
        pixels.clear();
        if(source.width==width&&source.height==height){pixels.put(source.color);return;}
        int[] columns=new int[width];
        for(int x=0;x<width;x++)columns[x]=(int)((long)x*source.width/width);
        for(int y=0;y<height;y++){
            int row=(int)((long)y*source.height/height)*source.width;
            for(int x=0;x<width;x++)pixels.put(source.color[row+columns[x]]);
        }
    }
    private void ensureDib(int width,int height){
        if(bitmap!=null&&dibWidth==width&&dibHeight==height)return;
        int count=Math.multiplyExact(width,height);
        if(count>16_777_216)throw new IllegalArgumentException("Presentation DIB exceeds 64 MiB");
        Win32.BitmapInfo info=new Win32.BitmapInfo();
        info.bmiHeader.biSize=info.bmiHeader.size();info.bmiHeader.biWidth=width;info.bmiHeader.biHeight=-height;
        info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=Win32.BI_RGB;
        PointerByReference bits=new PointerByReference();
        Pointer replacement=Win32.Gdi32.INSTANCE.CreateDIBSection(memDc,info,Win32.DIB_RGB_COLORS,bits,null,0);
        if(badHandle(replacement))throw new IllegalStateException("CreateDIBSection failed");
        dibCreated++;
        IntBuffer replacementPixels;
        try{
            if(bits.getValue()==null)throw new IllegalStateException("DIB has no pixel backing");
            replacementPixels=bits.getValue().getByteBuffer(0,(long)count*4).order(ByteOrder.nativeOrder()).asIntBuffer();
        }catch(RuntimeException|Error failure){if(Win32.Gdi32.INSTANCE.DeleteObject(replacement))dibDeleted++;throw failure;}
        Pointer selected=Win32.Gdi32.INSTANCE.SelectObject(memDc,replacement);
        if(badHandle(selected)){if(Win32.Gdi32.INSTANCE.DeleteObject(replacement))dibDeleted++;throw new IllegalStateException("SelectObject failed");}
        if(stockBitmap==null)stockBitmap=selected;
        Pointer old=bitmap;
        bitmap=replacement;pixels=replacementPixels;dibWidth=width;dibHeight=height;
        // Selecting replacement deselects old. Deleting a still-selected bitmap would leak it.
        if(old!=null){if(!Win32.Gdi32.INSTANCE.DeleteObject(old))throw new IllegalStateException("Could not delete deselected DIB");dibDeleted++;}
    }
    private static boolean badHandle(Pointer value){
        if(value==null)return true;
        long raw=Pointer.nativeValue(value);return raw==0||raw==-1||Native.POINTER_SIZE==4&&raw==0xffffffffL;
    }
    private void show(boolean value){
        if(hwnd!=null&&visible!=value&&Win32.User32.INSTANCE.IsWindow(hwnd))
            Win32.User32.INSTANCE.ShowWindow(hwnd,value?Win32.SW_SHOWNOACTIVATE:Win32.SW_HIDE);
        visible=value;
    }
    public void hide(){requireThread();enabled=false;show(false);}
    public boolean probeOverlayGone(){return hwnd==null||!Win32.User32.INSTANCE.IsWindow(hwnd)||!Win32.User32.INSTANCE.IsWindowVisible(hwnd);}
    public void close(){requireThread();closeNative();}
    private void detachSubclass(){
        if(!attached)return;
        if(host==null||!Win32.User32.INSTANCE.IsWindow(host)
            ||Win32.Comctl32.INSTANCE.RemoveWindowSubclass(host,callback,SUBCLASS_ID))attached=false;
        else throw new IllegalStateException("Could not detach presenter subclass");
    }
    private void closeNative(){
        enabled=false;closing=true;pendingSync=false;pendingClose=true;
        // A destroy notification can be nested inside ULW/SetWindowPos. Never free their DC midway.
        if(!updating)finishClose();
    }
    private void finishClose(){
        pendingClose=false;
        try{detachSubclass();}catch(Throwable failure){logFailure(failure);}
        try{show(false);if(hwnd!=null&&Win32.User32.INSTANCE.IsWindow(hwnd))Win32.User32.INSTANCE.DestroyWindow(hwnd);}
        catch(Throwable failure){logFailure(failure);}
        hwnd=null;
        if(!attached)host=null;
        if(bitmap!=null&&memDc!=null){
            boolean restored=false;
            try{restored=!badHandle(Win32.Gdi32.INSTANCE.SelectObject(memDc,stockBitmap));}
            catch(Throwable failure){logFailure(failure);}
            if(!restored){
                try{Win32.Gdi32.INSTANCE.DeleteDC(memDc);}catch(Throwable failure){logFailure(failure);}
                memDc=null;
            }
        }
        try{if(bitmap!=null){if(Win32.Gdi32.INSTANCE.DeleteObject(bitmap))dibDeleted++;else logFailure(new IllegalStateException("Could not delete deselected presentation DIB"));}}
        catch(Throwable failure){logFailure(failure);}
        bitmap=null;stockBitmap=null;pixels=null;
        try{if(memDc!=null)Win32.Gdi32.INSTANCE.DeleteDC(memDc);}catch(Throwable failure){logFailure(failure);}
        memDc=null;
        try{if(screenDc!=null)Win32.User32.INSTANCE.ReleaseDC(null,screenDc);}catch(Throwable failure){logFailure(failure);}
        screenDc=null;lastFrame=null;dibWidth=dibHeight=0;lastX=lastY=Integer.MIN_VALUE;
    }
    private void logFailure(Throwable failure){if(!loggedFailure){loggedFailure=true;AntimonyMod.LOG.warn("[Antimony] Native CPU presentation update failed",failure);}}
    Pointer overlayForProbe(){return hwnd;}
    long frameCountForProbe(){return frames;}
    long liveDibsForProbe(){return dibCreated-dibDeleted;}
    long geometryUpdatesForProbe(){return geometryUpdates;}
    int[] dibPixelsForProbe(){int[] result=new int[dibWidth*dibHeight];pixels.duplicate().position(0).get(result);return result;}
}

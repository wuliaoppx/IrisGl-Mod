package dev.antimony.core.present;

import com.sun.jna.Pointer;
import dev.antimony.core.irisgl.IrisGlFramebuffer;
import dev.antimony.core.win32.Win32;
import java.util.concurrent.atomic.AtomicReference;

/** Native same-thread presentation regression. Creates only its own small Win32 fixture, no game/world/GL. */
public final class Win32PresenterProbe {
    private static final int NESTED_TEST=0x8001;
    private static final Win32.UIntPtr TEST_ID=new Win32.UIntPtr(0x50524f42L);
    public static void main(String[] args)throws Exception{
        System.setProperty("antimony.forceOverlay","true");
        Pointer host=Win32.User32.INSTANCE.CreateWindowExA(0,"STATIC","Antimony presenter regression",
            Win32.WS_OVERLAPPEDWINDOW,40,60,180,150,null,null,null,null);
        check(host!=null,"native fixture created");
        Win32Presenter presenter=new Win32Presenter();
        AtomicReference<Throwable> nestedFailure=new AtomicReference<>();
        Win32.SubclassProc original=(window,message,wParam,lParam,id,reference)->{
            if(message!=NESTED_TEST)return Win32.Comctl32.INSTANCE.DefSubclassProc(window,message,wParam,lParam);
            try{
                long frames=presenter.frameCountForProbe(),updates=presenter.geometryUpdatesForProbe();
                for(int i=0;i<12;i++){
                    check(Win32.User32.INSTANCE.SetWindowPos(host,null,80+i*3,100+i*2,180,150,Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER),"nested fixture move");
                    aligned(presenter,host);
                }
                check(presenter.frameCountForProbe()==frames,"no Java frame submitted inside original message chain");
                check(presenter.geometryUpdatesForProbe()>updates,"overlay follows before outer DefSubclassProc returns");
            }catch(Throwable failure){nestedFailure.set(failure);}
            return new Win32.IntPtr();
        };
        try{
            check(Win32.Comctl32.INSTANCE.SetWindowSubclass(host,original,TEST_ID,new Win32.UIntPtr()),"original fixture chain installed");
            Win32.User32.INSTANCE.ShowWindow(host,Win32.SW_SHOWNOACTIVATE);
            int before=gdiCount();
            IrisGlFramebuffer source=new IrisGlFramebuffer(4,3);
            for(int i=0;i<source.color.length;i++)source.color[i]=0xff203000+i*0x050301;
            int first=source.color[0];
            check(presenter.present(source,Pointer.nativeValue(host)),"initial CPU frame displayed");
            check(host.equals(Win32.User32.INSTANCE.GetWindow(presenter.overlayForProbe(),Win32.GW_OWNER)),"overlay owned by fixture, not desktop-topmost");
            aligned(presenter,host);
            source.clearColor(0xff000000);
            Win32.User32.INSTANCE.SendMessageW(host,NESTED_TEST,new Win32.UIntPtr(),new Win32.IntPtr());
            if(nestedFailure.get()!=null)throw new AssertionError("nested native following",nestedFailure.get());
            System.out.println("PASS nested native message chain: last frame follows while outer handler remains on stack, with no present calls");
            if(Boolean.getBoolean("antimony.presenter.probe.modal")){
                modal(presenter,host,0xf010,0x27,"SC_MOVE");
                modal(presenter,host,0xf008,0x28,"SC_SIZE");
            }
            long frames=presenter.frameCountForProbe();
            int warm=gdiCount(),peak=warm;
            for(int i=0;i<120;i++){
                int w=170+i%9,h=145+i%11;
                check(Win32.User32.INSTANCE.SetWindowPos(host,null,90,100,w,h,Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER),"native fixture resize");
                aligned(presenter,host);
                check(presenter.dibPixelsForProbe()[0]==first,"resize uses frozen source frame, not caller's rewritten framebuffer");
                peak=Math.max(peak,gdiCount());
            }
            check(presenter.frameCountForProbe()==frames,"resize does not submit/render a new frame");
            check(peak<=warm+1,"GDI count remains bounded through 120 DIB replacements after native-theme warmup: "+warm+" -> "+peak);
            check(presenter.liveDibsForProbe()==1,"exactly one native DIB remains after replacement");
            Win32.User32.INSTANCE.ShowWindow(host,Win32.SW_MINIMIZE);
            check(presenter.probeOverlayGone(),"minimize hides without next frame");
            Win32.User32.INSTANCE.ShowWindow(host,Win32.SW_RESTORE);
            aligned(presenter,host);
            check(!presenter.probeOverlayGone(),"restore follows native notification");
            presenter.hide();
            Win32.User32.INSTANCE.SetWindowPos(host,null,120,140,180,150,Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER);
            check(presenter.probeOverlayGone(),"released frame cannot reappear on a later native move");
            AtomicReference<Throwable> wrongThread=new AtomicReference<>();
            Thread foreign=new Thread(()->{try{presenter.present(source,Pointer.nativeValue(host));}catch(Throwable failure){wrongThread.set(failure);}});
            foreign.start();foreign.join();
            check(wrongThread.get() instanceof IllegalStateException,"cross-thread presentation rejected before native calls");
            presenter.close();
            int after=gdiCount();
            check(presenter.liveDibsForProbe()==0&&after<=warm-2,"DIB and memory DC are released after close: warm="+warm+" -> "+after);
            check(presenter.probeOverlayGone(),"close destroys owned overlay");
            Win32.User32.INSTANCE.SetWindowPos(host,null,80,90,180,150,Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER);
            check(presenter.probeOverlayGone(),"removed hook cannot resurrect overlay");
            check(presenter.present(source,Pointer.nativeValue(host)),"fresh frame can reattach after close");
            Win32.User32.INSTANCE.DestroyWindow(host);
            check(presenter.probeOverlayGone(),"host WM_NCDESTROY tears down presenter resources without future frames");
            check(presenter.liveDibsForProbe()==0&&gdiCount()<=after,"host destroy releases presenter and host GDI resources");
            System.out.println("PASS client-size scaling/snapshot isolation, native minimize/restore, released-frame visibility, owner-thread guard, close/destroy and GDI handle budget before="+before+" warm="+warm+" peak="+peak+" after="+after);
            System.out.println("WIN32 PRESENTER PROBE OK: real SC_MOVE/SC_SIZE and nested native callbacks; manual game drag and cross-monitor DPI still need game validation");
        }finally{
            presenter.close();
            if(Win32.User32.INSTANCE.IsWindow(host)){
                Win32.Comctl32.INSTANCE.RemoveWindowSubclass(host,original,TEST_ID);
                Win32.User32.INSTANCE.DestroyWindow(host);
            }
        }
    }
    private static void modal(Win32Presenter presenter,Pointer host,int command,int key,String label)throws Exception{
        pump();
        java.util.concurrent.atomic.AtomicBoolean returned=new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        long frames=presenter.frameCountForProbe(),updates=presenter.geometryUpdatesForProbe();
        Thread controller=new Thread(()->{
            try{
                Thread.sleep(150);
                Win32.Rect initial=new Win32.Rect();
                check(Win32.User32.INSTANCE.GetWindowRect(host,initial),"directed modal fixture geometry");
                for(int i=1;i<=8;i++){
                    check(!returned.get(),label+" must still be inside the native modal loop");
                    int x=initial.left+(command==0xf010?i*3:0),y=initial.top+(command==0xf010?i*2:0);
                    int width=initial.right-initial.left+(command==0xf008?i*2:0);
                    int height=initial.bottom-initial.top+(command==0xf008?i*3:0);
                    // Cross-thread window positioning is supported; notifications execute on the
                    // host owner thread inside the still-active native modal loop.
                    check(Win32.User32.INSTANCE.SetWindowPos(host,null,x,y,width,height,
                        Win32.SWP_NOACTIVATE|Win32.SWP_NOZORDER),"directed modal geometry update");
                    aligned(presenter,host);
                    Thread.sleep(25);
                }
                check(!returned.get(),label+" outer procedure remains blocked before Enter");
                check(presenter.geometryUpdatesForProbe()>updates,label+" native geometry updated before modal returned");
                aligned(presenter,host);
            }catch(Throwable error){failure.set(error);}
            finally{
                Win32.User32.INSTANCE.PostMessageW(host,0x0100,new Win32.UIntPtr(0x0d),new Win32.IntPtr(1));
                Win32.User32.INSTANCE.PostMessageW(host,0x001f,new Win32.UIntPtr(),new Win32.IntPtr());
            }
        },"antimony-native-fixture-controller");
        controller.setDaemon(true);controller.start();
        // Only this fixture HWND receives input. No global mouse/input injection or user game operation.
        Win32.User32.INSTANCE.SendMessageW(host,0x0112,new Win32.UIntPtr(command),new Win32.IntPtr());
        returned.set(true);controller.join(2000);
        check(!controller.isAlive(),label+" controller finished");
        if(failure.get()!=null)throw new AssertionError(label+" real modal regression",failure.get());
        check(presenter.frameCountForProbe()==frames,label+" does not render/submit another Java frame");
        pump(); // Drain the previous modal's cancellation/paint messages before starting another one.
        System.out.println("PASS real "+label+" native modal loop: overlay aligned before outer procedure returns, frame count unchanged");
    }
    private static void pump(){
        Win32.Message message=new Win32.Message();
        while(Win32.User32.INSTANCE.PeekMessageW(message,null,0,0,Win32.PM_REMOVE)){
            Win32.User32.INSTANCE.TranslateMessage(message);
            Win32.User32.INSTANCE.DispatchMessageW(message);
        }
    }
    private static int gdiCount(){return Win32.User32.INSTANCE.GetGuiResources(Win32.Kernel32.INSTANCE.GetCurrentProcess(),0);}
    private static void aligned(Win32Presenter presenter,Pointer host){
        Win32.Rect rect=new Win32.Rect(),client=new Win32.Rect();Win32.Point origin=new Win32.Point();
        check(presenter.overlayForProbe()!=null&&Win32.User32.INSTANCE.GetWindowRect(presenter.overlayForProbe(),rect),"overlay rect available");
        check(Win32.User32.INSTANCE.GetClientRect(host,client)&&Win32.User32.INSTANCE.ClientToScreen(host,origin),"client rect available");
        check(rect.left==origin.x&&rect.top==origin.y&&rect.right-rect.left==client.right-client.left&&rect.bottom-rect.top==client.bottom-client.top,
            "overlay/client exact alignment: overlay=["+rect.left+","+rect.top+","+rect.right+","+rect.bottom+"] client origin=["+origin.x+","+origin.y+"] size=["+client.right+","+client.bottom+"]");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

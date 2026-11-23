package dev.antimony.core.win32;

import com.sun.jna.IntegerType;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal hand-written Win32 bindings (JNA core only, no jna-platform).
 */
public final class Win32 {
    public static final int WS_EX_LAYERED = 0x00080000;
    public static final int WS_EX_TRANSPARENT = 0x00000020;
    public static final int WS_EX_TOPMOST = 0x00000008;
    public static final int WS_EX_NOACTIVATE = 0x08000000;
    public static final int WS_EX_TOOLWINDOW = 0x00000080;
    public static final int WS_POPUP = 0x80000000;
    public static final int WS_CHILD = 0x40000000;
    public static final int WS_VISIBLE = 0x10000000;
    public static final int SW_SHOWNOACTIVATE = 4;
    public static final int SW_HIDE = 0;

    public static final int ULW_ALPHA = 0x00000002;
    public static final byte AC_SRC_OVER = 0;
    public static final byte AC_SRC_ALPHA = 1;

    public static final int DIB_RGB_COLORS = 0;
    public static final int BI_RGB = 0;

    public static final int SWP_NOSIZE = 0x0001;
    public static final int SWP_NOMOVE = 0x0002;
    public static final int SWP_NOZORDER = 0x0004;
    public static final int SWP_NOACTIVATE = 0x0010;
    public static final int SWP_SHOWWINDOW = 0x0040;

    /** MessageBoxW uType flags. */
    public static final int MB_OK = 0x00000000;
    public static final int MB_ICONERROR = 0x00000010;
    public static final int MB_ICONWARNING = 0x00000030;
    public static final int MB_TOPMOST = 0x00040000;
    public static final int MB_SETFOREGROUND = 0x00010000;

    public static final int WM_MOVE=0x0003,WM_SIZE=0x0005,WM_ACTIVATE=0x0006;
    public static final int WM_ACTIVATEAPP=0x001C,WM_SHOWWINDOW=0x0018,WM_WINDOWPOSCHANGED=0x0047,WM_NCDESTROY=0x0082;
    public static final int WM_ENTERSIZEMOVE=0x0231,WM_EXITSIZEMOVE=0x0232,WM_DPICHANGED=0x02E0;
    public static final int SIZE_MINIMIZED=1,SW_RESTORE=9,SW_MINIMIZE=6,PM_REMOVE=1,GW_OWNER=4;
    public static final int WS_OVERLAPPEDWINDOW=0x00CF0000;

    /** Windows LLP64: LONG remains 32 bits, but these message parameters are pointer-sized. */
    public static final class UIntPtr extends IntegerType {
        public UIntPtr(){this(0);}public UIntPtr(long value){super(Native.POINTER_SIZE,value,true);}
    }
    public static final class IntPtr extends IntegerType {
        public IntPtr(){this(0);}public IntPtr(long value){super(Native.POINTER_SIZE,value,false);}
    }
    public interface SubclassProc extends StdCallLibrary.StdCallCallback {
        IntPtr invoke(Pointer hwnd,int message,UIntPtr wParam,IntPtr lParam,UIntPtr id,UIntPtr reference);
    }
    public interface Comctl32 extends StdCallLibrary {
        Comctl32 INSTANCE=Native.load("comctl32",Comctl32.class);
        boolean SetWindowSubclass(Pointer hwnd,SubclassProc callback,UIntPtr id,UIntPtr reference);
        boolean RemoveWindowSubclass(Pointer hwnd,SubclassProc callback,UIntPtr id);
        IntPtr DefSubclassProc(Pointer hwnd,int message,UIntPtr wParam,IntPtr lParam);
    }
    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE=Native.load("kernel32",Kernel32.class);
        int GetCurrentThreadId();
        Pointer GetCurrentProcess();
    }

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        /** Native modal message box (used by the OpenGL guard). */
        int MessageBoxW(Pointer hWnd, com.sun.jna.WString text, com.sun.jna.WString caption, int type);

        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer hwnd,Pointer processId);
        Pointer GetWindowDpiAwarenessContext(Pointer hwnd);
        Pointer SetThreadDpiAwarenessContext(Pointer context);
        Pointer GetWindow(Pointer hwnd,int command);
        int GetGuiResources(Pointer process,int flags);
        boolean PeekMessageW(Message message,Pointer hwnd,int first,int last,int flags);
        boolean TranslateMessage(Message message);
        IntPtr DispatchMessageW(Message message);
        IntPtr SendMessageW(Pointer hwnd,int message,UIntPtr wParam,IntPtr lParam);
        boolean PostMessageW(Pointer hwnd,int message,UIntPtr wParam,IntPtr lParam);

        int GetSystemMetrics(int index);

        Pointer GetDC(Pointer hWnd);

        int ReleaseDC(Pointer hWnd, Pointer hdc);

        boolean GetClientRect(Pointer hWnd, Rect rect);

        boolean ClientToScreen(Pointer hWnd, Point point);

        Pointer CreateWindowExA(int exStyle, String className, String windowName, int style,
                                int x, int y, int width, int height,
                                Pointer parent, Pointer menu, Pointer instance, Pointer param);

        Pointer SetParent(Pointer child, Pointer parent);

        boolean IsWindow(Pointer hWnd);

        boolean IsWindowVisible(Pointer hWnd);

        boolean IsIconic(Pointer hWnd);

        boolean GetWindowRect(Pointer hWnd, Rect rect);

        boolean DestroyWindow(Pointer hWnd);

        boolean ShowWindow(Pointer hWnd, int cmd);

        boolean SetWindowPos(Pointer hWnd, Pointer insertAfter, int x, int y, int cx, int cy, int flags);

        boolean UpdateLayeredWindow(Pointer hWnd, Pointer hdcDst, Point pptDst, Size psize,
                                    Pointer hdcSrc, Point pptSrc, int crKey, BlendFunction pblend, int dwFlags);
    }

    public interface Gdi32 extends StdCallLibrary {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class);

        Pointer CreateCompatibleDC(Pointer hdc);

        boolean DeleteDC(Pointer hdc);

        Pointer CreateDIBSection(Pointer hdc, BitmapInfo bmi, int usage, PointerByReference bits, Pointer section, int offset);

        Pointer SelectObject(Pointer hdc, Pointer obj);

        boolean DeleteObject(Pointer obj);
    }

    public static final class Message extends Structure {
        public Pointer hwnd;
        public int message;
        public UIntPtr wParam=new UIntPtr();
        public IntPtr lParam=new IntPtr();
        public int time;
        public Point point=new Point();
        public int privateData;
        @Override protected List<String> getFieldOrder(){return Arrays.asList("hwnd","message","wParam","lParam","time","point","privateData");}
    }

    public static final class Rect extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("left", "top", "right", "bottom");
        }
    }

    public static final class Point extends Structure {
        public int x;
        public int y;

        public Point() {
        }

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("x", "y");
        }
    }

    public static final class Size extends Structure {
        public int cx;
        public int cy;

        public Size() {
        }

        public Size(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("cx", "cy");
        }
    }

    public static final class BlendFunction extends Structure {
        public byte BlendOp;
        public byte BlendFlags;
        public byte SourceConstantAlpha;
        public byte AlphaFormat;

        public BlendFunction() {
            this.BlendOp = AC_SRC_OVER;
            this.BlendFlags = 0;
            this.SourceConstantAlpha = (byte) 255;
            this.AlphaFormat = AC_SRC_ALPHA;
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("BlendOp", "BlendFlags", "SourceConstantAlpha", "AlphaFormat");
        }
    }

    public static final class BitmapInfoHeader extends Structure {
        public int biSize;
        public int biWidth;
        public int biHeight;
        public short biPlanes;
        public short biBitCount;
        public int biCompression;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
                    "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
                    "biClrUsed", "biClrImportant");
        }
    }

    public static final class RgbQuad extends Structure {
        public byte rgbBlue;
        public byte rgbGreen;
        public byte rgbRed;
        public byte rgbReserved;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("rgbBlue", "rgbGreen", "rgbRed", "rgbReserved");
        }
    }

    public static final class BitmapInfo extends Structure {
        public BitmapInfoHeader bmiHeader = new BitmapInfoHeader();
        public RgbQuad[] bmiColors = new RgbQuad[1];

        public BitmapInfo() {
            this.bmiColors[0] = new RgbQuad();
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("bmiHeader", "bmiColors");
        }
    }

    private Win32() {
    }
}

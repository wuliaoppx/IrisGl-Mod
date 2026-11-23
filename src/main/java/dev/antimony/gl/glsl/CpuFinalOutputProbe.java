package dev.antimony.gl.glsl;

import java.util.Arrays;

/** Final RGBA8 presentation differs from a linear-scene transfer: do not apply gamma twice. */
public final class CpuFinalOutputProbe {
    public static void main(String[] args) {
        var ramp = new CpuImage(256,2);
        for(int x=0;x<256;x++) {
            ramp.writePixel(x*4,x/255f,(255-x)/255f,.5f,x/255f);
            ramp.writePixel((256+x)*4,.18f,0,1,1);
        }
        float[] before=ramp.rgba.clone();
        int[] display=new int[512];
        ramp.readNormalizedIntoArgb(display,256,2);
        for(int x=0;x<256;x++) {
            int expected=x<<24|x<<16|(255-x)<<8|128;
            check(display[x]==expected,"encoded ramp/alpha must round-trip without gamma at "+x);
            check(display[256+x]==0xff2e00ff,"row order and normalized color");
        }
        check(Arrays.equals(before,ramp.rgba),"presentation cannot modify shader/history pixels");
        var linear=new CpuImage(1,1);linear.clear(.5f,.5f,.5f,.5f);
        int[] output={0};linear.readIntoArgb(output,1,1);
        check(output[0]==0x80bcbcbc,"legacy explicit linear-to-sRGB conversion retained");
        linear.readNormalizedIntoArgb(output,1,1);
        check(output[0]==0x80808080,"final shader .5 must be 128, not 188");
        linear.clear(-1,2,.5f,2);linear.readNormalizedIntoArgb(output,1,1);
        check(output[0]==0xff00ff80,"RGBA8 clamp preserves intended HDR saturation boundary");
        var corners=new CpuImage(2,2);
        corners.writePixel(0,1,0,0,1);corners.writePixel(4,0,1,0,1);
        corners.writePixel(8,0,0,1,1);corners.writePixel(12,1,1,1,1);
        int[] scaled=new int[16];corners.readNormalizedIntoArgb(scaled,4,4);
        check(scaled[0]==0xffff0000&&scaled[3]==0xff00ff00&&scaled[12]==0xff0000ff&&scaled[15]==0xffffffff,"scaled output orientation");
        boolean rejected=false;
        try{corners.readNormalizedIntoArgb(new int[1],2,2);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"mismatched presentation extent rejected");
        System.out.println("CPU FINAL OUTPUT PROBE OK: 256 encoded levels and alpha, gamma boundary, legacy linear path, immutable HDR storage, orientation/resize and extent rejection");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

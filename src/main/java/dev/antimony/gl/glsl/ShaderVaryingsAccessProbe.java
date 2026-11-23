package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.util.*;

/** Typed transport and allocation fixture, not shaderpack appearance or game FPS evidence. */
public final class ShaderVaryingsAccessProbe {
    public static final class Program implements GlslProgram {
        public float aFloat;public float[] aVector=new float[3];
        public int anInt;public int[] anIntVector=new int[4];
        public boolean aBool;public boolean[] aBoolVector=new boolean[4];
        public void run(FragState state) { }
    }
    private static volatile long checksum;
    private static Field[] legacyFields;
    public static void main(String[] args)throws Exception {
        var meta=new GlslCompiler().translateVertex("TypedAccessFixture","out float aFloat;out vec3 aVector;flat out int anInt;flat out ivec4 anIntVector;flat out bool aBool;flat out bvec4 aBoolVector;void main(){gl_Position=vec4(0.0,0.0,0.0,1.0);}");
        ShaderVaryings layout=ShaderVaryings.outputs(meta);Program actual=new Program(),reference=new Program();
        legacyFields=new Field[layout.lanes().size()];
        for(int i=0;i<legacyFields.length;i++)legacyFields[i]=Program.class.getField(layout.lanes().get(i).variable().javaField());
        var access=layout.vertexAccess(actual);Random random=new Random(43179);
        float[][] packets=new float[128][layout.components()+3];
        for(float[] packet:packets)for(var lane:layout.lanes())for(int j=0;j<lane.components();j++)
            packet[3+lane.offset()+j]=lane.variable().signature().startsWith("float")?random.nextFloat()*100-50:Float.intBitsToFloat(random.nextInt());
        for(int i=0;i<1000;i++){
            float[] packet=packets[i%packets.length],original=packet.clone();
            access.install(packet,3);legacy(layout,reference,packet,3);
            float[] observed=new float[layout.components()],expected=new float[observed.length];
            access.capture(observed,0);legacyCapture(layout,reference,expected);
            bits(observed,expected,"all six storage domains");bits(packet,original,"source packet untouched");
            float[] vector=actual.aVector;int[] integers=actual.anIntVector;boolean[] flags=actual.aBoolVector;
            actual.aVector[0]=999;actual.anIntVector[0]=999;actual.aBoolVector[0]=!actual.aBoolVector[0];
            access.install(packet,3);access.capture(observed,0);bits(observed,expected,"fresh input restored after mutation");
            check(vector==actual.aVector&&integers==actual.anIntVector&&flags==actual.aBoolVector,"access-owned buffers are reused");
        }
        Program second=new Program();var secondAccess=layout.vertexAccess(second);secondAccess.install(packets[0],3);
        check(actual.aVector!=second.aVector&&actual.anIntVector!=second.anIntVector&&actual.aBoolVector!=second.aBoolVector,"worker buffers must never alias");
        float[] copied=second.aVector.clone();float[] source=packets[0];source[3+layout.inputLane("aVector").offset()]=777;
        bits(second.aVector,copied,"detached from caller mutation");
        actual.aVector[0]=Float.NaN;reject(()->access.capture(new float[layout.components()],0),"nonfinite float capture");
        reject(()->access.install(new float[1],0),"short payload");
        System.out.println("PASS varying access: legacy bit-exact float/vector/int/ivec/bool/bvec, raw NaN integer carriers, private reusable arrays, mutation reset, caller/worker isolation and finite/arity rejection");
        for(int i=0;i<20000;i++){legacy(layout,reference,packets[i%128],3);access.install(packets[i%128],3);}
        long start=System.nanoTime(),sum=0;for(int i=0;i<100000;i++){legacy(layout,reference,packets[i%128],3);sum+=reference.anInt;}long oldNanos=System.nanoTime()-start;
        start=System.nanoTime();for(int i=0;i<100000;i++){access.install(packets[i%128],3);sum+=actual.anInt;}long newNanos=System.nanoTime()-start;checksum=sum;
        System.out.println("CPU LAB varying install 100000 calls: legacyMs="+oldNanos/1e6+" reusableMs="+newNanos/1e6+" checksum="+checksum+"; NOT game FPS");
    }
    private static void legacy(ShaderVaryings layout,Program program,float[] source,int offset)throws Exception {
        int index=0;
        for(var lane:layout.lanes()){
            int o=offset+lane.offset();Field field=legacyFields[index++];
            Object value=switch(lane.variable().signature().split("#")[0]){
                case "float"->source[o];case "int"->Float.floatToRawIntBits(source[o]);case "boolean"->Float.floatToRawIntBits(source[o])!=0;
                case "float[]"->Arrays.copyOfRange(source,o,o+lane.components());
                case "int[]"->{int[] a=new int[lane.components()];for(int i=0;i<a.length;i++)a[i]=Float.floatToRawIntBits(source[o+i]);yield a;}
                case "boolean[]"->{boolean[] a=new boolean[lane.components()];for(int i=0;i<a.length;i++)a[i]=Float.floatToRawIntBits(source[o+i])!=0;yield a;}
                default->throw new AssertionError();
            };field.set(program,value);
        }
    }
    private static void legacyCapture(ShaderVaryings layout,Program program,float[] result)throws Exception {
        int index=0;
        for(var lane:layout.lanes()){
            Object value=legacyFields[index++].get(program);
            for(int i=0;i<lane.components();i++)result[lane.offset()+i]=switch(lane.variable().signature().split("#")[0]){
                case "float"->(Float)value;case "float[]"->((float[])value)[i];
                case "int"->Float.intBitsToFloat((Integer)value);case "int[]"->Float.intBitsToFloat(((int[])value)[i]);
                case "boolean"->Float.intBitsToFloat((Boolean)value?1:0);case "boolean[]"->Float.intBitsToFloat(((boolean[])value)[i]?1:0);
                default->throw new AssertionError();
            };
        }
    }
    private static void bits(float[] a,float[] b,String name){check(a.length==b.length,name+" arity");for(int i=0;i<a.length;i++)check(Float.floatToRawIntBits(a[i])==Float.floatToRawIntBits(b[i]),name+" "+i);}
    private static void reject(Runnable action,String name){try{action.run();throw new AssertionError("Expected rejection: "+name);}catch(IllegalStateException expected){}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

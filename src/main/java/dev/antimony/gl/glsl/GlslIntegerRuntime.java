package dev.antimony.gl.glsl;

/** 32-bit GLSL integer domain: uint is an int bit pattern, never a rounded float. */
public final class GlslIntegerRuntime {
    private GlslIntegerRuntime() { }
    public static float toFloat(int value){return (float)Integer.toUnsignedLong(value);}
    public static int toUint(float value){
        if(!Float.isFinite(value)||value<0||value>=4294967296.0)throw new IllegalArgumentException("Undefined float-to-uint conversion");
        return (int)(long)value;
    }
    public static int scalar(int a,int b,char op,boolean unsigned){
        return switch(op){case '+'->a+b;case '-'->a-b;case '*'->a*b;case '/'->unsigned?Integer.divideUnsigned(a,b):a/b;case '%'->unsigned?Integer.remainderUnsigned(a,b):a%b;case '&'->a&b;case '|'->a|b;case '^'->a^b;default->throw new IllegalArgumentException("integer operator");};
    }
    public static int shift(int a,int b,boolean right,boolean unsigned){
        if(b<0||b>=32)throw new IllegalArgumentException("Undefined shift count");
        return right?(unsigned?a>>>b:a>>b):a<<b;
    }
    public static int min(int a,int b,boolean unsigned){return (unsigned?Integer.compareUnsigned(a,b):Integer.compare(a,b))<=0?a:b;}
    public static int max(int a,int b,boolean unsigned){return (unsigned?Integer.compareUnsigned(a,b):Integer.compare(a,b))>=0?a:b;}
    public static int[] arithmetic(int[] a,int[] b,char op,boolean unsigned){
        int n=arity(a,b);int[] out=new int[n];for(int i=0;i<n;i++)out[i]=scalar(a[a.length==1?0:i],b[b.length==1?0:i],op,unsigned);return out;
    }
    public static int[] builtin(int[] a,int[] b,int[] c,String op,boolean unsigned){
        int n=arity(a,b);if(c!=null)n=Math.max(n,arity(a,c));int[] out=new int[n];
        for(int i=0;i<n;i++){int x=a[a.length==1?0:i],y=b[b.length==1?0:i];out[i]=switch(op){case "min"->min(x,y,unsigned);case "max"->max(x,y,unsigned);case "clamp"->min(max(x,y,unsigned),c[c.length==1?0:i],unsigned);default->throw new IllegalArgumentException("integer builtin");};}return out;
    }
    public static boolean[] compare(int[] a,int[] b,boolean greater,boolean unsigned){if(a.length!=b.length)throw new IllegalArgumentException("integer comparison arity");boolean[] out=new boolean[a.length];for(int i=0;i<out.length;i++){int c=unsigned?Integer.compareUnsigned(a[i],b[i]):Integer.compare(a[i],b[i]);out[i]=greater?c>0:c<0;}return out;}
    public static boolean[] swizzle(boolean[] value,int... indices){boolean[] out=new boolean[indices.length];for(int i=0;i<out.length;i++)out[i]=value[indices[i]];return out;}
    public static boolean[] equalComponents(int[] a,int[] b,boolean negate){if(a.length!=b.length)throw new IllegalArgumentException("vector comparison arity");boolean[] out=new boolean[a.length];for(int i=0;i<a.length;i++)out[i]=(a[i]==b[i])!=negate;return out;}
    public static boolean any(boolean[] a){for(boolean v:a)if(v)return true;return false;}
    public static boolean all(boolean[] a){for(boolean v:a)if(!v)return false;return true;}
    public static boolean equal(int[] a,int[] b){return java.util.Arrays.equals(a,b);}
    public static boolean equal(boolean[] a,boolean[] b){return java.util.Arrays.equals(a,b);}
    public static boolean equal(float[] a,float[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true;}
    private static int arity(int[] a,int[] b){if(a.length<1||b.length<1||a.length!=b.length&&a.length!=1&&b.length!=1)throw new IllegalArgumentException("integer vector arity");return Math.max(a.length,b.length);}
    public static int[] swizzle(int[] value,int... indices){int[] out=new int[indices.length];for(int i=0;i<out.length;i++)out[i]=value[indices[i]];return out;}
    public static int[] toInts(int[] value,int n){if(value.length<n)throw new IllegalArgumentException("vector conversion arity");return java.util.Arrays.copyOf(value,n);}
    public static int[] toInts(float[] value,int n,boolean unsigned){if(value.length<n)throw new IllegalArgumentException("vector conversion arity");int[] out=new int[n];for(int i=0;i<n;i++)out[i]=unsigned?toUint(value[i]):(int)value[i];return out;}
    public static float[] toFloats(int[] value,int n,boolean unsigned){if(value.length<n)throw new IllegalArgumentException("vector conversion arity");float[] out=new float[n];for(int i=0;i<n;i++)out[i]=unsigned?toFloat(value[i]):value[i];return out;}
    public static float[] toFloats(float[] value,int n){if(value.length<n)throw new IllegalArgumentException("vector conversion arity");return java.util.Arrays.copyOf(value,n);}
}

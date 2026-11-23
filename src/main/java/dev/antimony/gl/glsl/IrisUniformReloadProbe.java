package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Same generated binary name in independent classloaders must bind independent Fields. */
public final class IrisUniformReloadProbe {
    public static void main(String[] args) throws Exception {
        sourceKeyCollisionRegression();
        GlslCompiler.Result meta = new GlslCompiler().translateVertex("TerrainReloadProbe",
            "uniform float amplitude; void main(){gl_Position=vec4(amplitude,0.0,0.0,1.0);}");
        Path directory=Path.of("build/tmp/terrain-reload-probe");
        Files.createDirectories(directory);
        Path source=directory.resolve("TerrainReloadProbe.java"); Files.writeString(source,meta.javaSource);
        int rc=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-encoding","UTF-8",
            "-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),source.toString());
        if(rc!=0)throw new AssertionError("generated compile failed");
        try(URLClassLoader firstLoader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},IrisUniformReloadProbe.class.getClassLoader());
            URLClassLoader secondLoader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},IrisUniformReloadProbe.class.getClassLoader())) {
            Class<?> first=firstLoader.loadClass("dev.antimony.gl.glsl.gen.TerrainReloadProbe");
            Class<?> second=secondLoader.loadClass(first.getName());
            if(first==second || !first.getName().equals(second.getName()))throw new AssertionError("independent same-name class setup");
            Object a=first.getConstructor().newInstance(), b=second.getConstructor().newInstance();
            String uniform=meta.uniformFields.get("amplitude");
            Map<String,Field> oldCache=new HashMap<>();
            oldCache.put(first.getName()+"#"+uniform,first.getField(uniform));
            boolean reproduced=false;
            try {oldCache.get(second.getName()+"#"+uniform).setFloat(b,2f);}catch(IllegalArgumentException expected){reproduced=true;}
            if(!reproduced)throw new AssertionError("old cache did not reproduce stale Field failure");
            Method lookup=IrisPostChain.class.getDeclaredMethod("fieldOf",Class.class,String.class); lookup.setAccessible(true);
            Field af=(Field)lookup.invoke(null,first,uniform), bf=(Field)lookup.invoke(null,second,uniform);
            af.setFloat(a,1f); bf.setFloat(b,2f);
            if(af.getDeclaringClass()!=first || bf.getDeclaringClass()!=second || af.getFloat(a)!=1 || bf.getFloat(b)!=2)
                throw new AssertionError("Class identity cache failed");
            Class<?> snapshot=Class.forName("dev.antimony.gl.glsl.IrisPostChain$UniformSnapshot");
            var constructor=snapshot.getDeclaredConstructor(String.class,float[].class,int[].class); constructor.setAccessible(true);
            Method assign=IrisPostChain.class.getDeclaredMethod("assignSnapshot",Object.class,String.class,String.class,int.class,snapshot); assign.setAccessible(true);
            boolean assigned=(Boolean)assign.invoke(null,b,uniform,"float",1,constructor.newInstance("probe",new float[]{7},null));
            if(!assigned || bf.getFloat(b)!=7)throw new AssertionError("snapshot binding after reload failed");
            System.out.println("PASS old name-only cache reproduces stale Field IllegalArgumentException");
            System.out.println("PASS independent same-name generated classloaders get distinct Fields; current production assignSnapshot binds reload instance");
        }
    }

    private static void locationCollisionRegression() {
        int program=1_812_736;
        String first="Aa",second="BB";
        if(first.hashCode()!=second.hashCode()||first.equals(second))throw new AssertionError("location collision fixture invalid");
        int uniformA=dev.antimony.gl.shim.GlShimState.uniformLocation(program,first);
        int uniformB=dev.antimony.gl.shim.GlShimState.uniformLocation(program,second);
        if(uniformA==uniformB||uniformA!=dev.antimony.gl.shim.GlShimState.uniformLocation(program,first))
            throw new AssertionError("uniform names collided or lookup was unstable");
        int attribA=dev.antimony.gl.shim.GlShimState.attribLocation(program,first);
        int attribB=dev.antimony.gl.shim.GlShimState.attribLocation(program,second);
        if(attribA==attribB||attribA!=dev.antimony.gl.shim.GlShimState.attribLocation(program,first))
            throw new AssertionError("attribute names collided or lookup was unstable");
        if(uniformA==dev.antimony.gl.shim.GlShimState.uniformLocation(program+1,first)
                ||attribA==dev.antimony.gl.shim.GlShimState.attribLocation(program+1,first))
            throw new AssertionError("locations leaked across program IDs");
        dev.antimony.gl.shim.GlShimState.useProgram(program);
        dev.antimony.gl.shim.GlShimState.recordUniformI(program,uniformA,"1i",11);
        dev.antimony.gl.shim.GlShimState.recordUniformI(program,uniformB,"1i",22);
        int valueA=dev.antimony.gl.shim.GlShimState.uniformValue(program,uniformA).i[0];
        int valueB=dev.antimony.gl.shim.GlShimState.uniformValue(program,uniformB).i[0];
        if(valueA!=11||valueB!=22)throw new AssertionError("uniform values crossed colliding names");
        System.out.println("PASS GL shim exact program/name identity isolates Java hash collisions for uniform and attribute locations");
    }

    private static void sourceKeyCollisionRegression() throws Exception {
        locationCollisionRegression();
        String first="Aa", second="BB";
        if(first.hashCode()!=second.hashCode()||first.equals(second))throw new AssertionError("collision fixture invalid");
        Method key=GlslProgramLoader.class.getDeclaredMethod("sourceKey",String.class);key.setAccessible(true);
        Object a=key.invoke(null,first),b=key.invoke(null,second);
        if(a.equals(b))throw new AssertionError("different source texts share cache key");
        Method pair=GlslProgramLoader.class.getDeclaredMethod("sourcePairKey",String.class,String.class);pair.setAccessible(true);
        String p1=(String)pair.invoke(null,"frag","vertex-one"),p2=(String)pair.invoke(null,"frag","vertex-two");
        if(p1.equals(p2))throw new AssertionError("fragment cache key ignored vertex source");
        if(p1.equals(pair.invoke(null,"fragvertex-one","")))throw new AssertionError("source pair key is ambiguous");
        System.out.println("PASS source cache identity distinguishes Java hash collisions and fragment/vertex source pairs");
    }
}

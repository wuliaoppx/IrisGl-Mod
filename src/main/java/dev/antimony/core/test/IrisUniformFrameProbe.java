package dev.antimony.core.test;

import dev.antimony.gl.glsl.IrisUniformFrame;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/** Standalone numerical probe: actual Iris classes + the production CPU bridge, no game or GL context. */
public final class IrisUniformFrameProbe {
    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        return o.getClass().getMethod(name, types).invoke(o, args);
    }
    static Field field(Object o, String name) throws Exception {
        for (Class<?> c=o.getClass(); c!=null; c=c.getSuperclass()) {
            try { var f=c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }
    static float value(IrisUniformFrame frame, String name) {
        var v=frame.get(name);
        check(v!=null, "Missing " + name);
        return v.floats()!=null ? v.floats()[0] : v.ints()[0];
    }
    public static void main(String[] args) throws Exception {
        Class<?> frequency=Class.forName("net.irisshaders.iris.gl.uniform.UniformUpdateFrequency");
        Object tick=frequency.getField("PER_TICK").get(null);
        Class<?> hb=Class.forName("net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder$Builder");
        Object inputs=hb.getConstructor().newInstance();
        AtomicInteger supplierCalls=new AtomicInteger();
        // Simulates a dimension with fixedTime, distinct from the diagnostic value.
        IntSupplier originalTime=()->{supplierCalls.incrementAndGet();return 9000;};
        call(inputs,"uniform1i",new Class<?>[]{frequency,String.class,IntSupplier.class},tick,"worldTime",originalTime);
        Object holder=call(inputs,"build",new Class<?>[0]);
        Class<?> builderType=Class.forName("net.irisshaders.iris.uniforms.custom.CustomUniforms$Builder");
        Object builder=builderType.getConstructor().newInstance();
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            if (!(line.startsWith("variable.")||line.startsWith("uniform."))) continue;
            var split=line.split("\\s*=\\s*",2);
            var name=split[0].split("\\.",3);
            // Only the time dependency subgraph; expressions are read unchanged from the pack.
            if (name[2].equals("hour_world")) { }
            else if (!(name[2].equals("dayMoment")||name[2].startsWith("moment_aux")
                    ||name[2].equals("dayMixer")||name[2].equals("nightMixer")
                    ||name[2].equals("volumetricDayMixer")||name[2].endsWith("_vol_mixer")
                    ||name[2].startsWith("light_mix_")||name[2].equals("dayNightMix"))) continue;
            call(builder,"addVariable",new Class<?>[]{String.class,String.class,String.class,boolean.class},
                name[1],name[2],split[1],name[0].equals("uniform"));
        }
        call(builder,"addVariable",new Class<?>[]{String.class,String.class,String.class,boolean.class},"bool","zeroBoolean","worldTime < 0",true);
        Object custom=call(builder,"build",new Class<?>[]{holder.getClass()},holder);
        Set<String> requested=new LinkedHashSet<>(List.of("dayMoment","dayMixer","nightMixer","dayNightMix","volumetricDayMixer","worldTime","zeroBoolean"));
        // Prove exact old leaf-only behavior before exercising production capture.
        Map<?,?> declared=(Map<?,?>)field(custom,"variables").get(custom);
        float[] stale={0,1,1,0};
        int k=0;
        for(String name:List.of("dayMoment","dayMixer","nightMixer","volumetricDayMixer")) {
            Object node=declared.get(name);
            Method update=node.getClass().getDeclaredMethod("doUpdate");update.setAccessible(true);update.invoke(node);
            check(((Number)field(node,"cached").get(node)).floatValue()==stale[k++],"Old path reproduction");
        }
        System.out.println("PASS old leaf-only path reproduces [0,1,1,0]");
        call(custom,"optimise",new Class<?>[0]);
        List<?> order=(List<?>)field(custom,"uniformOrder").get(custom);
        check(!order.contains(declared.get("dayMixer")),"Expected optimise-pruned terminal leaf");
        Class<?> nt=Class.forName("net.irisshaders.iris.uniforms.FrameUpdateNotifier");
        Object notifier=nt.getConstructor().newInstance();
        AtomicInteger notifications=new AtomicInteger();
        call(notifier,"addListener",new Class<?>[]{Runnable.class},(Runnable)notifications::incrementAndGet);
        int[] times={6000,18000,0,23999,6000};
        float[][] expected={{.25f,1,0,1,8},{.75f,0,1,0,8},{0,0,0,1,1},{23999f/24000,0,.00104f,1,1.00466f},{.25f,1,0,1,8}};
        var names=List.of("dayMoment","dayMixer","nightMixer","dayNightMix","volumetricDayMixer");
        IrisUniformFrame first=null;
        for(int j=0;j<times.length;j++) {
            IrisUniformFrame frame=IrisUniformFrame.capture(custom,notifier,requested,true,(float)times[j]);
            if(first==null)first=frame;
            for(int i=0;i<5;i++) check(Math.abs(value(frame,names.get(i))-expected[j][i])<=1e-4,"time="+times[j]+" "+names.get(i));
            check(value(frame,"worldTime")==times[j],"override input");
            check(value(frame,"zeroBoolean")==0,"legal false");
            float[] leaked=frame.get("dayMoment").floats();leaked[0]=999;
            check(value(frame,"dayMoment")!=999,"snapshot array escaped");
            for(int pass=0;pass<4;pass++) for(String name:names) value(frame,name);
            check(supplierCalls.get()==j+1&&notifications.get()==j+1,"multiple updates per frame");
            System.out.println("PASS production bridge time="+times[j]+" values="+names.stream().map(n->n+"="+value(frame,n)).toList());
        }
        check(Math.abs(value(first,"dayMoment")-.25f)<1e-4,"previous frame mutated");
        IrisUniformFrame normal=IrisUniformFrame.capture(custom,notifier,requested,false,18000f);
        check(value(normal,"worldTime")==9000&&Math.abs(value(normal,"dayMoment")-.375f)<1e-4,"normal supplier semantics");
        System.out.println("PASS normal supplier=9000 ignores diagnostic override; zero/false, pruned leaves, once/frame, detached snapshots");
        // A failed supplier must abort capture instead of publishing partially updated values.
        Object inputHolder=field(custom,"inputHolder").get(custom);
        Object timeNode=((Map<?,?>)field(inputHolder,"inputVariables").get(inputHolder)).get("worldTime");
        field(timeNode,"supplier").set(timeNode,(IntSupplier)()->{throw new IllegalStateException("probe failure");});
        boolean failed=false;
        try { IrisUniformFrame.capture(custom,notifier,requested,false,null); }
        catch (InvocationTargetException expectedFailure) { failed=true; }
        check(failed,"supplier error silently accepted");
        System.out.println("PASS failed supplier aborts snapshot publication");
    }
}

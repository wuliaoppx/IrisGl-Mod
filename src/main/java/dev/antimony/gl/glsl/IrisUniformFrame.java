package dev.antimony.gl.glsl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** Render-thread-only Iris cache refresh, followed by detached, read-only values.
 * Never calls push: Iris cache evaluation and GL upload have different owners. */
public final class IrisUniformFrame {
    public static final class Value {
        private final float[] floats;
        private final int[] ints;
        private Value(float[] floats, int[] ints) {
            this.floats = floats == null ? null : floats.clone();
            this.ints = ints == null ? null : ints.clone();
        }
        public float[] floats() { return floats == null ? null : floats.clone(); }
        public int[] ints() { return ints == null ? null : ints.clone(); }
    }
    private final Map<String, Value> values;
    private IrisUniformFrame(Map<String, Value> values) { this.values = Map.copyOf(values); }
    public static IrisUniformFrame empty() { return new IrisUniformFrame(Map.of()); }
    public Value get(String name) { return values.get(name); }

    /** Invoke once per software frame, before any pass/worker reads uniforms.
     * The original fixed-input supplier always runs; diagnostics replace only its
     * cached result, before the first dependent node is evaluated. */
    public static IrisUniformFrame capture(Object custom, Object notifier, Set<String> requested,
                                           boolean diagnostic, Float timeOverride) throws ReflectiveOperationException {
        if (custom == null) {
            if (notifier != null) notifier.getClass().getMethod("onNewFrame").invoke(notifier);
            return empty();
        }
        Map<?, ?> declared = (Map<?, ?>) field(custom, "variables").get(custom);
        Map<?, ?> dependencies = (Map<?, ?>) field(custom, "dependsOn").get(custom);
        Object holder = field(custom, "inputHolder").get(custom);
        Map<?, ?> inputs = (Map<?, ?>) field(holder, "inputVariables").get(holder);
        List<?> nativeOrder = (List<?>) field(custom, "uniformOrder").get(custom);
        Set<Object> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Object> named = new LinkedHashMap<>();
        for (String name : requested) {
            Object node = declared.get(name);
            if (node == null) node = inputs.get(name);
            if (node != null) { roots.add(node); named.put(name, node); }
        }
        List<Object> order = new ArrayList<>();
        IdentityHashMap<Object, Integer> marks = new IdentityHashMap<>();
        // Preserve Iris' ordering where possible, then recover optimise-pruned leaves.
        for (Object node : nativeOrder) if (roots.contains(node)) visit(node, dependencies, marks, order);
        for (Object node : named.values()) visit(node, dependencies, marks, order);
        if (notifier != null) notifier.getClass().getMethod("onNewFrame").invoke(notifier);
        Object worldTime = inputs.get("worldTime");
        for (Object node : order) {
            node.getClass().getMethod("update").invoke(node);
            if (node == worldTime && diagnostic && timeOverride != null) {
                if (!Float.isFinite(timeOverride) || timeOverride < 0 || timeOverride >= 24000)
                    throw new IllegalArgumentException("Invalid diagnostic time");
                Field cache = field(node, "cached");
                if (cache.getType() == int.class) cache.setInt(node, timeOverride.intValue());
                else if (cache.getType() == float.class) cache.setFloat(node, timeOverride);
                else throw new IllegalArgumentException("Unsupported worldTime cache type");
            }
        }
        Map<String, Value> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : named.entrySet()) {
            Object node = entry.getValue();
            Value value = snapshot(field(node, "cached").get(node));
            if (value == null) throw new IllegalArgumentException("Unsupported/nonfinite Iris value: " + entry.getKey());
            values.put(entry.getKey(), value);
        }
        return new IrisUniformFrame(values);
    }
    private static void visit(Object node, Map<?, ?> deps, IdentityHashMap<Object, Integer> marks,
                              List<Object> order) {
        Integer mark = marks.get(node);
        if (mark != null) {
            if (mark == 1) throw new IllegalStateException("Cyclic Iris uniform dependency");
            return;
        }
        marks.put(node, 1);
        Object children = deps.get(node);
        if (children instanceof Collection<?> list) for (Object child : list) visit(child, deps, marks, order);
        marks.put(node, 2);
        order.add(node);
    }
    private static Field field(Object target, String name) throws NoSuchFieldException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }
    private static Value snapshot(Object value) throws ReflectiveOperationException {
        if (value instanceof Boolean b) return new Value(null, new int[]{b ? 1 : 0});
        if (value instanceof Integer i) return new Value(null, new int[]{i});
        if (value instanceof Number n) return floats(new float[]{n.floatValue()});
        if (value instanceof float[] f) return floats(f);
        if (value instanceof int[] i) return new Value(null, i);
        if (value == null) return null;
        String name = value.getClass().getSimpleName();
        if (name.matches("Vector[234][fdi]")) {
            int size = name.charAt(6) - '0';
            float[] f = new float[size]; int[] ints = new int[size];
            for (int i = 0; i < size; i++) {
                Number n = (Number) value.getClass().getMethod(new String[]{"x", "y", "z", "w"}[i]).invoke(value);
                f[i] = n.floatValue(); ints[i] = n.intValue();
            }
            return name.endsWith("i") ? new Value(null, ints) : floats(f);
        }
        if (name.equals("Matrix4f")) {
            float[] matrix = new float[16];
            Method get = value.getClass().getMethod("get", float[].class);
            get.invoke(value, (Object) matrix);
            return floats(matrix);
        }
        return null;
    }
    private static Value floats(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) return null;
        return new Value(values, null);
    }
}

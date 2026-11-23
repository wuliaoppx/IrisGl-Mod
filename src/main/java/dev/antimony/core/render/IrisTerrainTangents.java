package dev.antimony.core.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/** Calls Iris's actual vanilla extended-vertex tangent builder, not a guessed normal/tangent. */
public final class IrisTerrainTangents {
    private static final MethodHandle BUILDER = builder();
    private IrisTerrainTangents() { }

    private static MethodHandle builder() {
        try {
            Class<?>[] params = new Class<?>[18];
            Arrays.fill(params, float.class);
            return MethodHandles.publicLookup().findStatic(
                Class.forName("net.irisshaders.iris.vertices.NormalHelper"), "computeTangent",
                MethodType.methodType(int.class, params));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    /** Original triangle 0/1/2 of the source quad; packs/decode SNORM8 exactly like Iris. */
    static boolean fill(float[] source, int firstVertex, float[] destination) {
        if (BUILDER == null) return false;
        int a = firstVertex * MeshBuffer.STRIDE, b = a + MeshBuffer.STRIDE, c = b + MeshBuffer.STRIDE;
        try {
            int packed = (int) BUILDER.invokeExact(source[a+11], source[a+12], source[a+13],
                source[a], source[a+1], source[a+2], source[a+3], source[a+4],
                source[b], source[b+1], source[b+2], source[b+3], source[b+4],
                source[c], source[c+1], source[c+2], source[c+3], source[c+4]);
            for (int vertex = firstVertex; vertex < firstVertex + 4; vertex++)
                for (int component = 0; component < 4; component++)
                    destination[vertex * 4 + component] = Math.max(-1.0f, (byte)(packed >>> (8 * component)) / 127.0f);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }
}

package dev.antimony.core.irisgl;

/** Sutherland-Hodgman in homogeneous GL clip coordinates, preserving the complete payload. */
public final class HomogeneousClipper {
    public static final int MAX_VERTICES = 12;
    private HomogeneousClipper() { }

    /** Returns -1 for invalid clip coordinates, otherwise the six-plane outcode. */
    public static int outcode(float[] vertices, int offset) {
        for (int i = 0; i < 4; i++) if (!Float.isFinite(vertices[offset + i])) return -1;
        int result = 0;
        for (int plane = 0; plane < 6; plane++) if (distance(vertices, offset, plane) < 0) result |= 1 << plane;
        return result;
    }

    private static float distance(float[] vertices, int offset, int plane) {
        float w = vertices[offset + 3];
        return switch (plane) {
            case 0 -> w + vertices[offset]; case 1 -> w - vertices[offset];
            case 2 -> w + vertices[offset + 1]; case 3 -> w - vertices[offset + 1];
            case 4 -> w + vertices[offset + 2]; case 5 -> w - vertices[offset + 2];
            default -> throw new IllegalArgumentException("clip plane");
        };
    }

    /** Caller-owned scratch must each hold MAX_VERTICES * stride; result always lands in output. */
    public static int triangle(float[] source, int a, int b, int c, int stride, float[] output, float[] scratch) {
        return triangle(source, a, b, c, stride, output, scratch, null, 0);
    }

    /** Interpolation modes apply to the shader payload, after the fixed raster lanes. */
    public static int triangle(float[] source, int a, int b, int c, int stride, float[] output, float[] scratch,
                               byte[] modes, int payloadOffset) {
        int ca = outcode(source, a), cb = outcode(source, b), cc = outcode(source, c);
        if (ca < 0 || cb < 0 || cc < 0 || (ca & cb & cc) != 0) return 0;
        System.arraycopy(source, a, output, 0, stride);
        System.arraycopy(source, b, output, stride, stride);
        System.arraycopy(source, c, output, 2 * stride, stride);
        int count = 3;
        float[] input = output, dest = scratch;
        for (int plane = 0; plane < 6 && count != 0; plane++) {
            int nextCount = 0;
            for (int i = 0; i < count; i++) {
                int from = i * stride, to = ((i + 1) % count) * stride;
                float da = distance(input, from, plane), db = distance(input, to, plane);
                boolean insideA = da >= 0, insideB = db >= 0;
                if (insideA) {
                    System.arraycopy(input, from, dest, nextCount * stride, stride);
                    nextCount++;
                }
                if (insideA != insideB) {
                    float t = da / (da - db);
                    int offset = nextCount * stride;
                    float screenT = t * input[to + 3] / ((1 - t) * input[from + 3] + t * input[to + 3]);
                    for (int component = 0; component < stride; component++) {
                        int lane = component - payloadOffset;
                        int mode = modes != null && lane >= 0 && lane < modes.length ? modes[lane] : 0;
                        if (mode == 1) dest[offset + component] = input[from + component];
                        else {
                            if (mode == 2 && !Float.isFinite(screenT)) return 0;
                            float weight = mode == 2 ? screenT : t;
                            dest[offset + component] = input[from + component] + (input[to + component] - input[from + component]) * weight;
                        }
                    }
                    nextCount++;
                }
            }
            count = nextCount;
            float[] swap = input; input = dest; dest = swap;
        }
        if (input != output) System.arraycopy(input, 0, output, 0, count * stride);
        return count;
    }
}

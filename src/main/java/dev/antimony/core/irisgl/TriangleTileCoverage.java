package dev.antimony.core.irisgl;

/** Conservative pixel-centre coverage bounds using the scalar rasterizer's float arithmetic.
 * Never uses a camera/world bound: shader vertices and their image stores have already run. */
final class TriangleTileCoverage {
    private TriangleTileCoverage() { }

    static boolean mayCover(float x0, float y0, float x1, float y1, float x2, float y2,
                            float signedArea, int minX, int minY, int maxX, int maxY) {
        if (minX > maxX || minY > maxY) return false;
        if (!Float.isFinite(signedArea) || signedArea == 0) return true;
        if (signedArea < 0) {
            float x = x1, y = y1;
            x1 = x2; y1 = y2; x2 = x; y2 = y;
            signedArea = -signedArea;
        }
        float invArea = 1.0f / signedArea;
        if (!Float.isFinite(invArea) || invArea <= 0) return true;
        float loX = minX + .5f, hiX = maxX + .5f;
        float loY = minY + .5f, hiY = maxY + .5f;
        float a = edge(x1, y1, x2, y2, loX, loY) * invArea;
        float b = edge(x1, y1, x2, y2, hiX, loY) * invArea;
        float c = edge(x1, y1, x2, y2, loX, hiY) * invArea;
        float d = edge(x1, y1, x2, y2, hiX, hiY) * invArea;
        if (!finite(a, b, c, d)) return true;
        float min0 = Math.min(Math.min(a, b), Math.min(c, d));
        float max0 = Math.max(Math.max(a, b), Math.max(c, d));
        if (max0 < 0) return false;
        a = edge(x2, y2, x0, y0, loX, loY) * invArea;
        b = edge(x2, y2, x0, y0, hiX, loY) * invArea;
        c = edge(x2, y2, x0, y0, loX, hiY) * invArea;
        d = edge(x2, y2, x0, y0, hiX, hiY) * invArea;
        if (!finite(a, b, c, d)) return true;
        float min1 = Math.min(Math.min(a, b), Math.min(c, d));
        float max1 = Math.max(Math.max(a, b), Math.max(c, d));
        if (max1 < 0) return false;
        // The pixel loop computes e2 as 1-e0-e1, not as a third edge/area.
        // Independent minima give an upper bound with that SAME rounding order;
        // using the geometric third edge could lose pixels near a shared edge.
        return !(1.0f - min0 - min1 < 0);
    }

    private static boolean finite(float a, float b, float c, float d) {
        return Float.isFinite(a) && Float.isFinite(b) && Float.isFinite(c) && Float.isFinite(d);
    }
    private static float edge(float ax, float ay, float bx, float by, float px, float py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }
}

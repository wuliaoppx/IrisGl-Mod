package dev.antimony.core.irisgl;

/**
 * Static facade mirroring the classic SGI IRIS GL 4.0 C API.
 *
 * <p>Only the subset needed by IrisGL is implemented; names and call order follow
 * the 1990 IRIS GL reference manual where practical (winset, RGBmode, cpack,
 * bgnpolygon/endpolygon, v3f/t2f/c3f, pushmatrix/popmatrix, ...).</p>
 */
public final class IrisGl {
    private static final IrisGlContext CTX = new IrisGlContext();

    private IrisGl() {
    }

    public static IrisGlContext ctx() {
        return CTX;
    }

    public static IrisGlFramebuffer framebuffer() {
        return CTX.framebuffer();
    }

    /** Forces all binned polygons to be rasterized now (frame boundary). */
    public static void flushRaster() {
        CTX.flushRaster();
    }

    // ---- window / frame -------------------------------------------------

    public static void winset(String name) {
        // Single window; kept for API compatibility.
    }

    public static void beginFrame(int width, int height) {
        CTX.beginFrame(width, height);
    }

    public static void swapbuffers() {
        // Presentation is done by MainTextureBlit; flush any binned triangles
        // so screenshots/present see a complete frame.
        CTX.flushRaster();
    }

    public static void viewport(int x, int y, int w, int h) {
        CTX.viewport(x, y, w, h);
    }

    // ---- colour ---------------------------------------------------------

    public static void RGBmode() {
        // Always RGB; present for API compatibility.
    }

    public static void cmode() {
        // Colour-index mode is not supported.
        throw new UnsupportedOperationException("IRIS GL cmode() is not supported by IrisGL");
    }

    public static void cpack(int rgb) {
        CTX.cpack(rgb);
    }

    public static void c3f(float r, float g, float b) {
        CTX.c3f(r, g, b);
    }

    public static void c4f(float r, float g, float b, float a) {
        CTX.c4f(r, g, b, a);
    }

    public static void clear() {
        CTX.clear();
    }

    public static void color(int rgb) {
        CTX.cpack(rgb);
    }

    public static void setClearColor(int argb) {
        CTX.setClearColor(argb);
    }

    // ---- depth / blending ----------------------------------------------

    public static void zbuffer(boolean on) {
        CTX.zbuffer(on);
    }

    public static void zclear() {
        CTX.zclear();
    }

    public static void blendfunction(int src, int dst) {
        CTX.blendfunction(src, dst);
    }

    /** GL blendFuncSeparate: independent colour/alpha factor pairs. */
    public static void blendfuncseparate(int src, int dst, int alphaSrc, int alphaDst) {
        CTX.blendfuncseparate(src, dst, alphaSrc, alphaDst);
    }

    public static void blendmode(boolean on) {
        CTX.blendmode(on);
    }

    /** GL color mask: false keeps depth writes but leaves the colour buffer alone. */
    public static void colorwrite(boolean on) {
        CTX.colorwrite(on);
    }

    public static void alpha(boolean on) {
        CTX.blendmode(on);
    }

    public static void alphatest(boolean on, float ref) {
        CTX.alphaTest = on;
        CTX.alphaRef = ref;
    }

    /**
     * Mirrors GL cull face: back-facing triangles (negative window-space area,
     * flipped-Y screen space) are dropped at rasterization time when enabled.
     * Vanilla RenderTypes enable it for terrain, entity_solid/entity_cutout and
     * leave it off for the *_no_cull sheets, GUI quads and particles.
     */
    public static void cullface(boolean on) {
        CTX.cull = on;
    }

    // ---- matrices -------------------------------------------------------

    public static void perspective(float fovYDegrees, float aspect, float near, float far) {
        CTX.perspective(fovYDegrees, aspect, near, far);
    }

    public static void ortho(float left, float right, float bottom, float top, float near, float far) {
        CTX.ortho(left, right, bottom, top, near, far);
    }

    public static void lookat(float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        CTX.lookat(ex, ey, ez, cx, cy, cz, ux, uy, uz);
    }

    public static void pushmatrix() {
        CTX.pushmatrix();
    }

    public static void popmatrix() {
        CTX.popmatrix();
    }

    public static void loadidentity() {
        CTX.loadidentity();
    }

    /** Resets the view matrix to identity (invalidates the cached MVP too). */
    public static void viewidentity() {
        CTX.viewidentity();
    }

    public static void translate(float x, float y, float z) {
        CTX.translate(x, y, z);
    }

    public static void scale(float x, float y, float z) {
        CTX.scale(x, y, z);
    }

    public static void rot(float angle, char axis) {
        CTX.rot(angle, axis);
    }

    public static void rot(float angle, float ax, float ay, float az) {
        CTX.rot(angle, ax, ay, az);
    }

    // ---- primitives -----------------------------------------------------

    public static void bgnpolygon() {
        CTX.bgnpolygon();
    }

    public static void endpolygon() {
        CTX.endpolygon();
    }

    public static void bgnline() {
        CTX.bgnline();
    }

    public static void endline() {
        CTX.endline();
    }

    public static void bgnpoint() {
        CTX.bgnpoint();
    }

    public static void endpoint() {
        CTX.endpoint();
    }

    public static void v2f(float x, float y) {
        CTX.v2f(x, y);
    }

    public static void v3f(float x, float y, float z) {
        CTX.v3f(x, y, z);
    }

    public static void v3f(float[] xyz) {
        CTX.v3f(xyz[0], xyz[1], xyz[2]);
    }

    public static void t2f(float u, float v) {
        CTX.t2f(u, v);
    }

    public static void l2f(float u, float v) {
        CTX.l2f(u, v);
    }

    /** IRIS GL n3f: sets the current vertex normal (model space). */
    public static void n3f(float x, float y, float z) {
        CTX.n3f(x, y, z);
    }

    /** Vanilla entity overlay coordinate (UV1) packed as (v << 4) | u; 0 = none. */
    public static void overlay(float code) {
        CTX.overlay(code);
    }

    /** Enables the per-pixel overlay tint branch for the batch being drawn. */
    public static void overlayEnable(boolean on) {
        CTX.overlayEnable(on);
    }

    /** Vanilla entity shader light directions (normal space). */
    public static void shaderLights(float l0x, float l0y, float l0z, float l1x, float l1y, float l1z) {
        CTX.light0x = l0x;
        CTX.light0y = l0y;
        CTX.light0z = l0z;
        CTX.light1x = l1x;
        CTX.light1y = l1y;
        CTX.light1z = l1z;
    }

    // ---- texture / fog --------------------------------------------------

    public static void texture(boolean on) {
        CTX.texture(on);
    }

    public static void texbind(IrisGlTexture texture) {
        CTX.texbind(texture);
    }

    public static void lightmap(IrisGlTexture texture) {
        CTX.lightmap(texture);
    }

    /** B3：地形批次是否计算 shadowPos varying（也决定逐像素阴影查表）。 */
    public static void shadow(boolean on) {
        CTX.shadow(on);
    }

    public static void fog(boolean on) {
        CTX.fog(on);
    }

    public static void fogcolor(int rgb) {
        CTX.fogcolor(rgb);
    }

    public static void fogrange(float start, float end) {
        CTX.fogrange(start, end);
    }

    /** GL fog shape: true = cylindrical (max(xz, |y|)), false = spherical. */
    public static void fogshape(boolean cylindrical) {
        CTX.fogshape(cylindrical);
    }

    public static void scissor(int x, int y, int w, int h) {
        CTX.scissor(x, y, w, h);
    }

    public static void scissorOff() {
        CTX.scissorOff();
    }

    public static void tint(float r, float g, float b, float a) {
        CTX.tintR = r;
        CTX.tintG = g;
        CTX.tintB = b;
        CTX.tintA = a;
    }
}

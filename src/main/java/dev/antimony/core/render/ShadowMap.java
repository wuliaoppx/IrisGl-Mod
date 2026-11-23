package dev.antimony.core.render;

import dev.antimony.gl.glsl.CpuImage;
import java.util.Collection;
import org.joml.Vector3f;

/**
 * 阶段 B2：CPU 阴影贴图（docs §22）。
 *
 * <p>光影包不存在"引擎给一张阴影图"这回事——它自己写着 {@code shadow.vsh}，
 * 把世界几何用 {@code shadowProjection * shadowModelView} 投到光空间，再做一个
 * 非线性的中心加密变形，写进 {@code shadowtex0/1}。既然本模组的三维几何不经过
 * GL，Iris 那一遍阴影绘制永远是空的，于是 {@code shadow2D(shadowtex1, ...)}
 * 恒读到 1.0（全亮），影子从来不存在。</p>
 *
 * <p>这里用 Java 复刻那一个变换，把地形四边面光栅化成一张深度图。关键点：
 * 生成端和查询端必须用<b>同一组</b> {@code shadowProjection}/{@code shadowModelView}
 * 和<b>同一个</b> {@link #WARP} 变形系数，否则影子会整体偏移。查询端的公式来自
 * 包里 {@code lib/shadow_vertex.glsl} 的 {@code get_shadow_pos()}：</p>
 *
 * <pre>
 *   shadowPos = mat3(shadowModelView) * p + shadowModelView[3].xyz;
 *   shadowPos = diagonal3(shadowProjection) * shadowPos + shadowProjection[3].xyz;
 *   dist = length(shadowPos.xy);
 *   distort = dist * SHADOW_DIST + (1 - SHADOW_DIST);
 *   shadowPos.xy /= distort;   shadowPos.z *= 0.2;   return shadowPos * 0.5 + 0.5;
 * </pre>
 *
 * <p>所以 {@code shadowProjection} 必须是<b>对角</b>矩阵（包里用
 * {@code diagonal3()} 只取对角元），{@code shadowModelView} 是纯旋转
 * （平移列取 0，因为包传进来的是<b>相机相对</b>坐标）。</p>
 */
public final class ShadowMap {

    /** {@code -Dantimony.shadow=off} 关闭，默认开。 */
    public static final boolean ENABLED =
        !"off".equalsIgnoreCase(System.getProperty("antimony.shadow", "on"));

    /**
     * B3 的地形阴影开关，只用来做 A/B 对照：{@code -Dantimony.shadow.terrain=off}
     * 时地形批次不计算 shadowPos、光栅器也不采样深度图，其余管线完全不变。
     */
    public static final boolean TERRAIN =
        !"off".equalsIgnoreCase(System.getProperty("antimony.shadow.terrain", "on"));

    /**
     * 贴图边长；包里 {@code shadowMapResolution} 是 const，加载时会被改写成这个值。
     * 1024² 实测要 46-101 ms/帧（每帧 1M 纹素、多次 overdraw），512² 把像素量砍到
     * 四分之一，配合增量边函数与背面剔除后落在几毫秒——CPU 光栅器必须认这个账。
     */
    public static final int RESOLUTION = Math.max(64, Integer.getInteger("antimony.shadow.size", 512));

    /**
     * 包里 {@code #define SHADOW_DIST}。它决定"中心加密"的强度：d=1 处任何值都
     * 得到 1.0（不变），只有中段有差异，所以 0.75/0.83 的误差表现为径向缩放，
     * 可用 {@code -Dantimony.shadow.dist=} 覆盖。
     */
    private static final float WARP =
        Float.parseFloat(System.getProperty("antimony.shadow.dist", "0.83"));
    /** 光轴方向的深度半程（方块）。地形最高 384，取 256 足够且精度更好。 */
    private static final float DEPTH_RANGE = 256.0f;
    /** 深度写入偏移（以 [0,1] 阴影坐标计）。正交投影 + 平面四边面自身不产生 acne。 */
    private static final float DEPTH_BIAS = 1.0e-5f;
    /** 阴影能覆盖的半径（方块）——包里 {@code shadowDistance = 105}。 */
    private static final float DISTANCE = 105.0f;

    private static float[] depth = new float[RESOLUTION * RESOLUTION];
    private static CpuImage depthImage;
    private static CpuImage whiteImage;
    private static final Vector3f SUN = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final Vector3f RIGHT = new Vector3f(1.0F, 0.0F, 0.0F);
    private static final Vector3f UP2 = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final Vector3f FWD = new Vector3f(0.0F, 0.0F, -1.0F);
    private static float[] projection = {1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F};
    private static float[] modelView;
    private static float[] modelViewInverse;
    private static float[] projectionInverse;
    private static boolean valid;
    private static long frames;
    private static long statFrames;
    private static long nanos;
    private static long covered;
    private static long lastLog = -1;
    /** 光栅化参照点的网格吸附步长（方块）。 */
    private static final float CELL =
        Float.parseFloat(System.getProperty("antimony.shadow.snap", "4.0"));
    private static float lastBaseX = Float.NaN;
    private static float lastBaseY = Float.NaN;
    private static float lastBaseZ = Float.NaN;
    private static float lastDx;
    private static float lastDy;
    private static float lastDz;
    private static long lastBuild = -1000;
    private static long rebuilds;
    private static final Vector3f lastSun = new Vector3f(0.0F, -1.0F, 0.0F);
    /** -Dantimony.shadow.dump=on：第 60/120 帧把阴影贴图写成 PNG。 */
    private static final boolean DUMP = dev.antimony.AntimonyMod.flag("antimony.shadow.dump");

    private ShadowMap() {
    }

    public static boolean valid() {
        return valid;
    }

    /** 阴影覆盖半径（方块），与包里 {@code shadowDistance} 一致。 */
    public static float distance() {
        return DISTANCE;
    }

    public static CpuImage depthImage() {
        if (depthImage == null) {
            depthImage = new CpuImage(RESOLUTION, RESOLUTION);
        }
        return depthImage;
    }

    /** {@code shadowcolor0/1}：全白即"不染色"，包里就成了纯黑白阴影。 */
    public static CpuImage whiteImage() {
        if (whiteImage == null) {
            whiteImage = new CpuImage(1, 1);
            whiteImage.clear(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return whiteImage;
    }

    public static float[] projection() {
        return projection;
    }

    public static float[] modelView() {
        return modelView;
    }

    public static float[] modelViewInverse() {
        return modelViewInverse;
    }

    public static float[] projectionInverse() {
        return projectionInverse;
    }

    /** 世界空间太阳方向（单位向量，指向太阳）。 */
    public static Vector3f sunDirection() {
        return SUN;
    }

    static {
        modelView = identity();
        modelViewInverse = identity();
        projectionInverse = identity();
        java.util.Arrays.fill(depth, 1.0F);
        depthImage().clear(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = 1.0F;
        m[5] = 1.0F;
        m[10] = 1.0F;
        m[15] = 1.0F;
        return m;
    }

    /**
     * 重建这一帧的阴影贴图。
     *
     * @param meshes 可见区段（相机相对坐标由 {@code origin - eye} 提供）
     * @param eyeX   相机世界坐标
     */
    public static void update(Collection<SectionMesh> meshes, double eyeX, double eyeY, double eyeZ,
                              float sunX, float sunY, float sunZ) {
        if (!ENABLED) {
            return;
        }
        frames++;
        statFrames++;
        // 太阳方向。夜晚太阳落到地平线下，影子跟着太阳走（月光影子由包自己处理），
        // 这里只保证方向连续，不至于在日出日落瞬间翻转。
        float len = (float) Math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ);
        if (len < 1.0e-4F) {
            return;
        }
        SUN.set(sunX / len, sunY / len, sunZ / len);
        // 正对头顶时 up 向量会退化，换一个。
        Vector3f up = Math.abs(SUN.y) > 0.99F ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
        // 光空间基：forward = 从太阳指向场景。
        Vector3f fwd = new Vector3f(-SUN.x, -SUN.y, -SUN.z);
        Vector3f right = new Vector3f(fwd).cross(up).normalize();
        Vector3f up2 = new Vector3f(right).cross(fwd).normalize();

        // 列主序（m[col*4+row]），列 = 世界轴的像：
        //   view.x = right·p   view.y = up2·p   view.z = -fwd·p
        modelView = new float[]{
            right.x, up2.x, -fwd.x, 0.0F,
            right.y, up2.y, -fwd.y, 0.0F,
            right.z, up2.z, -fwd.z, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F};
        RIGHT.set(right);
        UP2.set(up2);
        FWD.set(fwd);
        // 正交投影：q.xy = view.xy / DISTANCE，q.z = -view.z / DEPTH_RANGE。
        // view.z = -fwd·p，所以 q.z = (fwd·p)/DEPTH_RANGE：离太阳越远越大，
        // 与 GPU 上 gl_Position.z 沿视轴递增一致。
        projection = new float[]{
            1.0F / DISTANCE, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F / DISTANCE, 0.0F, 0.0F,
            0.0F, 0.0F, -1.0F / DEPTH_RANGE, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F};
        projectionInverse = new float[]{
            DISTANCE, 0.0F, 0.0F, 0.0F,
            0.0F, DISTANCE, 0.0F, 0.0F,
            0.0F, 0.0F, -DEPTH_RANGE, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F};

        // 光栅化的参照点吸附到 CELL 网格上：玩家在这一格里走动、太阳几乎不动时，
        // 整张深度图可以原样复用，只把「相机与吸附点之间的残差」塞进
        // shadowModelView 的平移列——包里的 get_shadow_pos 正是
        // `mat3(shadowModelView) * p + shadowModelView[3].xyz`，所以这个残差是精确
        // 补偿，不是近似。阴影图每帧重建要 25-30 ms（几十万个三角形的建立是瓶颈），
        // 吸附之后只在跨格 / 太阳转过 ~0.8° / 每 60 帧兜底时重建。
        float baseX = Math.round(eyeX / CELL) * CELL;
        float baseY = Math.round(eyeY / CELL) * CELL;
        float baseZ = Math.round(eyeZ / CELL) * CELL;
        boolean sameBase = valid && baseX == lastBaseX && baseY == lastBaseY && baseZ == lastBaseZ;
        boolean sameSun = valid && (SUN.x * lastSun.x + SUN.y * lastSun.y + SUN.z * lastSun.z) > 0.9999F;
        boolean stale = frames - lastBuild > 60;
        // 平移列 = R * (eye - base)：光栅化用 (世界 - base)，包传入 (世界 - eye)。
        float dx = (float) eyeX - baseX;
        float dy = (float) eyeY - baseY;
        float dz = (float) eyeZ - baseZ;
        float tx = right.x * dx + right.y * dy + right.z * dz;
        float ty = up2.x * dx + up2.y * dy + up2.z * dz;
        float tz = -(fwd.x * dx + fwd.y * dy + fwd.z * dz);
        modelView[12] = tx;
        modelView[13] = ty;
        modelView[14] = tz;
        // M = [R|t] → M⁻¹ = [Rᵀ | -Rᵀt]
        modelViewInverse = new float[]{
            right.x, right.y, right.z, 0.0F,
            up2.x, up2.y, up2.z, 0.0F,
            -fwd.x, -fwd.y, -fwd.z, 0.0F,
            -(right.x * tx + up2.x * ty - fwd.x * tz),
            -(right.y * tx + up2.y * ty - fwd.y * tz),
            -(right.z * tx + up2.z * ty - fwd.z * tz),
            1.0F};
        lastSun.set(SUN);
        lastDx = dx;
        lastDy = dy;
        lastDz = dz;

        if (DUMP && valid && (frames == 60 || frames == 120 || frames == 300)) {
            dump((int) frames);
        }
        if (sameBase && sameSun && !stale) {
            return;
        }
        lastBaseX = baseX;
        lastBaseY = baseY;
        lastBaseZ = baseZ;
        lastBuild = frames;
        rebuilds++;

        java.util.Arrays.fill(depth, 1.0F);
        covered = 0;
        long t0 = System.nanoTime();
        for (SectionMesh mesh : meshes) {
            float ox = mesh.sectionX * 16.0F - baseX;
            float oy = mesh.sectionY * 16.0F - baseY;
            float oz = mesh.sectionZ * 16.0F - baseZ;
            // 只画阴影半径内的区段：包里的 shadowDistance 之外查询会落在地图外。
            if (ox * ox + oz * oz > (DISTANCE + 16.0F) * (DISTANCE + 16.0F)) {
                continue;
            }
            rasterMesh(mesh.solid, ox, oy, oz, right, up2, fwd);
            rasterMesh(mesh.cutoutMipped, ox, oy, oz, right, up2, fwd);
            rasterMesh(mesh.cutout, ox, oy, oz, right, up2, fwd);
        }
        nanos += System.nanoTime() - t0;
        valid = true;
        publish();
        if (lastLog < 0) {
            lastLog = frames;
        }
        if (frames - lastLog >= 120) {
            lastLog = frames;
            long built = rebuilds;
            dev.antimony.AntimonyMod.LOG.info(
                "[Antimony] shadow map {}x{}: {} rebuilds/{} frames, {} ms per rebuild, coverage {}%, ground shadowed {}%, sun=({},{},{})",
                RESOLUTION, RESOLUTION, built, statFrames,
                String.format(java.util.Locale.ROOT, "%.1f", nanos / 1.0e6 / Math.max(1, built)),
                String.format(java.util.Locale.ROOT, "%.1f", 100.0 * filledRatio()),
                String.format(java.util.Locale.ROOT, "%.1f", 100.0 * groundShadowed()),
                String.format(java.util.Locale.ROOT, "%.2f", SUN.x),
                String.format(java.util.Locale.ROOT, "%.2f", SUN.y),
                String.format(java.util.Locale.ROOT, "%.2f", SUN.z));
            if (STATS) {
                long frames120 = Math.max(1, statFrames);
                dev.antimony.AntimonyMod.LOG.info(
                    "[Antimony] shadow sampling: {} lookups/frame, {}% of them shadowed, {} ms/frame ({} taps)",
                    statLookups / frames120,
                    String.format(java.util.Locale.ROOT, "%.1f",
                        100.0 * statShadowed / Math.max(1, statLookups)),
                    String.format(java.util.Locale.ROOT, "%.2f", statNanos / 1.0e6 / frames120),
                    TAPS);
                statLookups = 0;
                statShadowed = 0;
                statNanos = 0;
            }
            nanos = 0;
            statFrames = 0;
            rebuilds = 0;
        }
    }

    /** 真正有几何的纹素比例。{@code covered} 计数器会把写入值 ≥1.0 的重复写也算进去，别信它。 */
    private static float filledRatio() {
        int n = 0;
        for (float z : depth) {
            if (z < 1.0F) {
                n++;
            }
        }
        return n / (float) depth.length;
    }

    /**
     * 端到端自检：在玩家脚下 1.6 格处取 64x64 个点（±40 格），走一遍和着色器
     * 相同的 {@code get_shadow_pos} 变换后查深度。树荫/山影下的点应当被判为阴影，
     * 这个比例是"阴影真的存在"的客观证据，不是靠肉眼看截图。
     */
    public static float groundShadowed() {
        int n = 64;
        int shadowed = 0;
        int total = 0;
        for (int i = 0; i < n; i++) {
            float x = (i / (float) (n - 1) - 0.5F) * 80.0F;
            for (int j = 0; j < n; j++) {
                float z = (j / (float) (n - 1) - 0.5F) * 80.0F;
                float px = x + lastDx;
                float py = -1.6F + lastDy;
                float pz = z + lastDz;
                float vx = RIGHT.x * px + RIGHT.y * py + RIGHT.z * pz;
                float vy = UP2.x * px + UP2.y * py + UP2.z * pz;
                float vz = -(FWD.x * px + FWD.y * py + FWD.z * pz);
                float qx = vx / DISTANCE;
                float qy = vy / DISTANCE;
                float qz = -vz / DEPTH_RANGE;
                float dist = (float) Math.sqrt(qx * qx + qy * qy);
                float distort = dist * WARP + (1.0F - WARP);
                qx /= distort;
                qy /= distort;
                float u = qx * 0.5F + 0.5F;
                float v = qy * 0.5F + 0.5F;
                float ref = qz * 0.2F * 0.5F + 0.5F;
                total++;
                if (compare(u, v, ref - 1.0e-4F) < 0.5F) {
                    shadowed++;
                }
            }
        }
        return total == 0 ? 0.0F : shadowed / (float) total;
    }

    /** 三个顶点的临时缓冲：每顶点 6 个 float = 相机相对 xyz + 贴图坐标 xy + 深度。 */
    private static final float[] SCRATCH = new float[18];
    /**
     * 背对太阳的三角形不会遮挡任何东西。地形网格只生成暴露面，其中大约一半背光，
     * 丢掉它们能把 overdraw 砍掉近一半。{-Dantimony.shadow.cull=false} 可关。
     */
    private static final boolean CULL_BACKFACE =
        !"false".equalsIgnoreCase(System.getProperty("antimony.shadow.cull", "true"));

    private static void rasterMesh(MeshBuffer buffer, float ox, float oy, float oz,
                                   Vector3f right, Vector3f up2, Vector3f fwd) {
        buffer.finish();
        float[] d = buffer.rawData();
        int stride = MeshBuffer.STRIDE;
        int count = buffer.rawVertices();
        if (count < 3) {
            return;
        }
        // 四边面按 (0,1,2),(0,2,3) 拆三角；三角形模式按三连。
        boolean quads = buffer.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS;
        int step = quads ? 4 : 3;
        for (int i = 0; i + step - 1 < count; i += step) {
            triangle(d, stride, i, i + 1, i + 2, ox, oy, oz, right, up2, fwd);
            if (quads) {
                triangle(d, stride, i, i + 2, i + 3, ox, oy, oz, right, up2, fwd);
            }
        }
    }

    private static void triangle(float[] d, int stride, int a, int b, int c,
                                 float ox, float oy, float oz,
                                 Vector3f right, Vector3f up2, Vector3f fwd) {
        float[] p = SCRATCH;
        if (!project(d, stride, a, ox, oy, oz, right, up2, fwd, p, 0)
            || !project(d, stride, b, ox, oy, oz, right, up2, fwd, p, 6)
            || !project(d, stride, c, ox, oy, oz, right, up2, fwd, p, 12)) {
            return;
        }
        if (CULL_BACKFACE) {
            float e1x = p[6] - p[0];
            float e1y = p[7] - p[1];
            float e1z = p[8] - p[2];
            float e2x = p[12] - p[0];
            float e2y = p[13] - p[1];
            float e2z = p[14] - p[2];
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            if (nx * SUN.x + ny * SUN.y + nz * SUN.z <= 0.0F) {
                return;
            }
        }
        rasterize(p[3], p[4], p[5], p[9], p[10], p[11], p[15], p[16], p[17]);
    }

    /** 顶点 → 相机相对坐标 + 阴影贴图像素坐标 + 深度。false = 被裁剪掉。 */
    private static boolean project(float[] d, int stride, int index, float ox, float oy, float oz,
                                   Vector3f right, Vector3f up2, Vector3f fwd,
                                   float[] out, int o) {
        int i = index * stride;
        float x = d[i] + ox;
        float y = d[i + 1] + oy;
        float z = d[i + 2] + oz;
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        // 视图空间（modelView 是纯旋转，平移列 0）
        float vx = right.x * x + right.y * y + right.z * z;
        float vy = up2.x * x + up2.y * y + up2.z * z;
        float vz = -(fwd.x * x + fwd.y * y + fwd.z * z);
        float qx = vx / DISTANCE;
        float qy = vy / DISTANCE;
        float qz = -vz / DEPTH_RANGE;
        if (qz < -1.0F || qz > 1.0F) {
            return false;
        }
        // 包里 shadow.vsh 的中心加密变形（与 get_shadow_pos 完全一致）
        float len = (float) Math.sqrt(qx * qx + qy * qy);
        float distort = len * WARP + (1.0F - WARP);
        qx /= distort;
        qy /= distort;
        qz *= 0.2F;
        out[o + 3] = (qx * 0.5F + 0.5F) * RESOLUTION - 0.5F;
        out[o + 4] = (qy * 0.5F + 0.5F) * RESOLUTION - 0.5F;
        out[o + 5] = qz * 0.5F + 0.5F;
        return true;
    }

    /**
     * 屏幕空间重心坐标填充，深度取最小（最近光源）。
     *
     * <p>边函数对 x 是线性的，所以每行只在行首算一次三个边函数，然后逐像素累加
     * 增量——这去掉了内层循环里每像素 6 次乘法，是这张 512² 深度图能压到毫秒级
     * 的关键（原始逐像素重算版本 1024² 要 100 ms/帧）。</p>
     */
    private static void rasterize(float x0, float y0, float z0, float x1, float y1, float z1,
                                  float x2, float y2, float z2) {
        float minX = Math.min(x0, Math.min(x1, x2));
        float maxX = Math.max(x0, Math.max(x1, x2));
        float minY = Math.min(y0, Math.min(y1, y2));
        float maxY = Math.max(y0, Math.max(y1, y2));
        int ix0 = Math.max(0, (int) Math.floor(minX));
        int ix1 = Math.min(RESOLUTION - 1, (int) Math.ceil(maxX));
        int iy0 = Math.max(0, (int) Math.floor(minY));
        int iy1 = Math.min(RESOLUTION - 1, (int) Math.ceil(maxY));
        if (ix0 > ix1 || iy0 > iy1) {
            return;
        }
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (area > -1.0e-7F && area < 1.0e-7F) {
            return;
        }
        float inv = 1.0F / area;
        float a0x = (y1 - y2) * inv;
        float a1x = (y2 - y0) * inv;
        float a2x = (y0 - y1) * inv;
        float dzdx = a0x * z0 + a1x * z1 + a2x * z2;
        for (int y = iy0; y <= iy1; y++) {
            float py = y + 0.5F;
            float px = ix0 + 0.5F;
            float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
            float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
            float w2 = 1.0F - w0 - w1;
            float z = w0 * z0 + w1 * z1 + w2 * z2 + DEPTH_BIAS;
            int row = y * RESOLUTION;
            for (int x = ix0; x <= ix1; x++) {
                if (w0 >= 0.0F && w1 >= 0.0F && w2 >= 0.0F) {
                    int o = row + x;
                    if (z < depth[o]) {
                        if (depth[o] >= 1.0F) {
                            covered++;
                        }
                        depth[o] = z;
                    }
                }
                w0 += a0x;
                w1 += a1x;
                w2 += a2x;
                z += dzdx;
            }
        }
    }

    /** 深度图灌进 {@link CpuImage}（R 通道存深度，其余填 1）。 */
    private static void publish() {
        CpuImage image = depthImage();
        float[] rgba = image.rgba;
        for (int i = 0; i < depth.length; i++) {
            int o = i * 4;
            float v = depth[i];
            rgba[o] = v;
            rgba[o + 1] = v;
            rgba[o + 2] = v;
            rgba[o + 3] = 1.0F;
        }
    }

    // ------------------------------------------------------------------
    // B3：地形查询接口（把 shadowPos 当 varying 一样插值，逐像素采样）
    // ------------------------------------------------------------------

    /** 包里 {@code SHADOW_TYPE 1}（软阴影）的模糊半径，config.glsl:65。 */
    private static final float BLUR = Float.parseFloat(
            System.getProperty("antimony.shadow.blur", "2.0"));

    /**
     * 每个像素的 tap 数。包装默认 {@code SHADOW_TYPE 1} = 4 tap，但四个 tap 只相隔
     * ≤2 个纹素（512² 下等于 <0.4 个方块），视觉上几乎就是"硬边+一点毛边"。CPU 端
     * 这是最贵的一步，所以留一个 2 tap 的旋钮（等价于包里的 {@code SHADOW_TYPE 0}
     * 加上一次半宽偏移），默认仍按包里的 4 tap 走。
     */
    private static final int TAPS = Math.max(1, Math.min(4,
            Integer.getInteger("antimony.shadow.taps", 4)));

    /** {@code -Dantimony.shadow.stats=on}：统计逐像素查询次数与耗时（只为测量）。 */
    static final boolean STATS = dev.antimony.AntimonyMod.flag("antimony.shadow.stats");
    static long statLookups;
    static long statShadowed;
    static long statNanos;

    /**
     * {@code lib/shadow_vertex.glsl get_shadow_pos} 的逐字复刻，用于<strong>相机
     * 相对</strong>坐标（{@code world - eye}）。输出 3 个 float 到 {@code out[o..o+2]}：
     * shadowUV.xy（贴图坐标）与 shadowUV.z（比较用的参考深度）。
     *
     * <p>与 {@link #project} 的区别只有两点：这里没有 {@code ox/oy/oz}（调用方给的
     * 就是相机相对坐标），并且必须加上 {@code modelView} 的平移列——光栅化那一遍
     * 用的是 {@code world - base} 空间，平移列 {@code R*(eye-base)} 正好把两者对齐，
     * 所以这一步是精确补偿而不是近似。</p>
     */
    public static void projectCameraRelative(float x, float y, float z, float[] out, int o) {
        float[] m = modelView;
        float vx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float vy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float vz = m[2] * x + m[6] * y + m[10] * z + m[14];
        // diagonal3(shadowProjection) * p + shadowProjection[3].xyz
        float qx = vx / DISTANCE;
        float qy = vy / DISTANCE;
        float qz = -vz / DEPTH_RANGE;
        float len = (float) Math.sqrt(qx * qx + qy * qy);
        float distort = len * WARP + (1.0F - WARP);
        qx /= distort;
        qy /= distort;
        qz *= 0.2F;
        out[o] = qx * 0.5F + 0.5F;
        out[o + 1] = qy * 0.5F + 0.5F;
        out[o + 2] = qz * 0.5F + 0.5F;
    }

    /**
     * {@code lib/shadow_frag.glsl get_shadow}（{@code SHADOW_TYPE 1}）的 CPU 版：
     * 四个按 dither 旋转的 2×2 分布采样取平均。
     *
     * <p>{@code dither01} 是 [0,1) 的逐像素噪声。包里用 {@code dither17()}，这里是
     * 交错梯度噪声——两者都只是"把 4 个 tap 摊成蓝噪声"，视觉上不可区分，但省掉了
     * 一次 hash 里的浮点三角函数。</p>
     */
    public static float sampleSoft(float u, float v, float z, float dither01) {
        // 地图外（超过 shadowDistance）一律算被照亮，和 compare() 的 clamp 一致。
        // 这一步省掉 4 次数组取样，雾里绝大多数地形都在这里直接返回。
        if (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) {
            return 1.0F;
        }
        float radius = dither01;
        float angle = radius * 6.283185307179586F;
        // cos/sin 两次 6.28 弧度的调用是热点，用 64 段查表代替（1/64 圈 ≈ 5.6°）。
        int ia = (int) (angle * 10.185916357881302F) & 63;          // 64/2π
        int ib = (ia + 16) & 63;                                    // +π/2
        float sx = radius * BLUR / RESOLUTION;
        float sy = (1.0F - radius) * BLUR / RESOLUTION;
        float ox = COS64[ia] * sx;
        float oy = SIN64[ia] * sx;
        // 包里 z_bias 用的是"已经乘过 2π 的"dither，故最大 1.3e-4。
        float ref = z - angle * 0.00002F;
        long t0 = STATS ? System.nanoTime() : 0L;
        float sum = compare(u + ox, v + oy, ref) + compare(u - ox, v - oy, ref);
        float out;
        if (TAPS > 2) {
            float ox2 = COS64[ib] * sy;
            float oy2 = SIN64[ib] * sy;
            sum += compare(u + ox2, v + oy2, ref) + compare(u - ox2, v - oy2, ref);
            out = sum * 0.25F;
        } else {
            out = sum * 0.5F;
        }
        if (STATS) {
            statLookups++;
            if (out < 0.999F) {
                statShadowed++;
            }
            statNanos += System.nanoTime() - t0;
        }
        return out;
    }

    /**
     * 阴影里的"环境光底"，对应包里 {@code omniLight}：它<b>不</b>参与 {@code shadowValue}
     * 的衰减，所以影子不会全黑。正午顶面实测 {@code |omni|/|direct|} ≈ 0.26/1.16 ≈ 0.22，
     * 取 0.25 让被挡住的直射光留下两成多。{@code -Dantimony.shadow.ambient=} 可调。
     */
    public static float ambient() {
        return AMBIENT;
    }

    private static final float AMBIENT = Math.max(0.0F,
            Math.min(1.0F, Float.parseFloat(System.getProperty("antimony.shadow.ambient", "0.25"))));

    private static final float[] COS64 = new float[64];
    private static final float[] SIN64 = new float[64];

    static {
        for (int i = 0; i < 64; i++) {
            double a = i * (Math.PI * 2.0 / 64.0);
            COS64[i] = (float) Math.cos(a);
            SIN64[i] = (float) Math.sin(a);
        }
    }

    /**
     * {@code shadow2D} 的 CPU 实现：返回 1.0 表示"被照亮"，0.0 表示"在影里"。
     * 语义照 GL：{@code ref <= depth} 通过（LEQUAL 比较模式）。
     */
    public static float compare(float u, float v, float ref) {        if (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) {
            return 1.0F;
        }
        int x = (int) (u * RESOLUTION);
        int y = (int) ((1.0F - v) * RESOLUTION);
        if (x < 0 || x >= RESOLUTION || y < 0 || y >= RESOLUTION) {
            return 1.0F;
        }
        return ref <= depth[y * RESOLUTION + x] ? 1.0F : 0.0F;
    }

    /** 把深度图写成 PNG：绿=深度（近处亮），红=有遮挡物，蓝=天空（未覆盖）。 */
    private static void dump(int frame) {
        try {
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(RESOLUTION, RESOLUTION,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[RESOLUTION];
            // 对比度按实际深度范围拉伸：太阳接近天顶时整张图的深度只差几毫米，
            // 固定区间会把地形结构全压成一片同色。
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (float z : depth) {
                if (z < 1.0F) {
                    if (z < min) {
                        min = z;
                    }
                    if (z > max) {
                        max = z;
                    }
                }
            }
            if (max <= min) {
                max = min + 1.0e-4F;
            }
            int filled = 0;
            for (float z : depth) {
                if (z < 1.0F) {
                    filled++;
                }
            }
            dev.antimony.AntimonyMod.LOG.info("[Antimony] shadow depth range {}/{} (filled {})",
                String.format(java.util.Locale.ROOT, "%.6f", min),
                String.format(java.util.Locale.ROOT, "%.6f", max),
                String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * filled / depth.length));
            for (int y = 0; y < RESOLUTION; y++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    float z = depth[y * RESOLUTION + x];
                    int rgb;
                    if (z >= 1.0F) {
                        rgb = 0xFF000040;
                    } else {
                        float t = (z - min) / (max - min);
                        int g = (int) (255.0F * (1.0F - t));
                        int r = 0xFF;
                        rgb = (r << 16) | (g << 8);
                    }
                    row[x] = rgb;
                }
                image.setRGB(0, y, RESOLUTION, 1, row, 0, RESOLUTION);
            }
            java.io.File dir = new java.io.File(
                net.minecraft.client.Minecraft.getInstance().gameDirectory, "antimony-shots");
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, "shadow-" + frame + ".png");
            javax.imageio.ImageIO.write(image, "PNG", out);
            dev.antimony.AntimonyMod.LOG.info("[Antimony] shadow map saved {}", out.getAbsolutePath());
        } catch (Throwable t) {
            dev.antimony.AntimonyMod.LOG.warn("[Antimony] shadow map dump failed", t);
        }
    }

    private static float[] transpose(float[] m) {        float[] t = new float[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                t[c * 4 + r] = m[r * 4 + c];
            }
        }
        return t;
    }

    /** 纯旋转矩阵的逆 = 转置。 */
    private static float[] invertRigid(float[] m) {
        return transpose(m);
    }
}

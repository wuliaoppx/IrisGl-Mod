package dev.antimony.core.irisgl;

import dev.antimony.core.render.ShadowMap;

/**
 * Scanline triangle rasterizer. The pixel loop is a pure function of
 * (context snapshot, packed vertices, pixel bounds) so the tile engine can run
 * tiles on worker threads; lines and points stay synchronous immediate mode.
 */
public final class IrisGlRasterizer {
    /** Clip-space vertex floats; must equal {@link IrisGlContext#STRIDE}. */
    static final int STRIDE = 17;

    private static final boolean DEBUG = dev.antimony.AntimonyMod.flag("antimony.debug.raster");
    private final IrisGlContext ctx;

    IrisGlRasterizer(IrisGlContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Clips the triangle at buf[o0..o1..o2] (STRIDE-float clip-space vertices)
     * against the near plane z >= -w, writing polygon vertices to dest.
     * Returns the number of resulting vertices (0..4).
     */
    static int clipNear(float[] buf, int o0, int o1, int o2, float[] dest) {
        int count = 0;
        count = clipEdge(buf, o0, o1, dest, count);
        count = clipEdge(buf, o1, o2, dest, count);
        count = clipEdge(buf, o2, o0, dest, count);
        return count;
    }

    private static int clipEdge(float[] buf, int from, int to, float[] dest, int count) {
        float da = buf[from + 2] + buf[from + 3];
        float db = buf[to + 2] + buf[to + 3];
        boolean ina = da >= 0.0F;
        boolean inb = db >= 0.0F;
        if (ina) {
            System.arraycopy(buf, from, dest, count * STRIDE, STRIDE);
            count++;
        }
        if (ina != inb) {
            float t = da / (da - db);
            int o = count * STRIDE;
            for (int i = 0; i < STRIDE; i++) {
                dest[o + i] = buf[from + i] + (buf[to + i] - buf[from + i]) * t;
            }
            count++;
        }
        return count;
    }

    /**
     * Rasterizes one packed triangle (three STRIDE-float clip-space vertices at
     * tris[off]) with the state captured in snap, restricted to the given
     * pixel bounds (tile + framebuffer + scissor already applied).
     */
    static void rasterTriangle(IrisGlContext ctx, float[] tris, int off, TileRasterEngine.StateSnap snap,
                               int boundsMinX, int boundsMinY, int boundsMaxX, int boundsMaxY) {
        rasterTriangle(ctx, tris, off, snap, boundsMinX, boundsMinY, boundsMaxX, boundsMaxY, null);
    }
    static void rasterTriangle(IrisGlContext ctx, float[] tris, int off, TileRasterEngine.StateSnap snap,
                               int boundsMinX, int boundsMinY, int boundsMaxX, int boundsMaxY,
                               TileRasterEngine.ShaderTriangle shaderTriangle) {
        if (shaderTriangle != null && shaderTriangle.shader().fragmentDraw() && snap.blend
                && !shaderTriangle.shader().handlesBlend())
            throw new IllegalStateException("Terrain fragment blending not implemented; draw rejected");
        RasterShader.Worker shaderWorker = shaderTriangle == null ? null : shaderTriangle.shader().worker();
        boolean reversed = false;
        int oa = off;
        int ob = off + STRIDE;
        int oc = off + 2 * STRIDE;

        float w0 = tris[oa + 3];
        float w1 = tris[ob + 3];
        float w2 = tris[oc + 3];
        if (w0 <= 0.0F || w1 <= 0.0F || w2 <= 0.0F) {
            return;
        }

        float iw0 = 1.0F / w0;
        float iw1 = 1.0F / w1;
        float iw2 = 1.0F / w2;

        // Project through the reciprocal (screenX/screenY would re-divide).
        float vx0 = tris[oa] * iw0;
        float vy0 = tris[oa + 1] * iw0;
        float vx1 = tris[ob] * iw1;
        float vy1 = tris[ob + 1] * iw1;
        float vx2 = tris[oc] * iw2;
        float vy2 = tris[oc + 1] * iw2;
        float x0 = snap.viewportX + (vx0 * 0.5F + 0.5F) * snap.viewportW;
        float y0 = snap.viewportY + (1.0F - (vy0 * 0.5F + 0.5F)) * snap.viewportH;
        float x1 = snap.viewportX + (vx1 * 0.5F + 0.5F) * snap.viewportW;
        float y1 = snap.viewportY + (1.0F - (vy1 * 0.5F + 0.5F)) * snap.viewportH;
        float x2 = snap.viewportX + (vx2 * 0.5F + 0.5F) * snap.viewportW;
        float y2 = snap.viewportY + (1.0F - (vy2 * 0.5F + 0.5F)) * snap.viewportH;

        float area = edge(x0, y0, x1, y1, x2, y2);
        if (area == 0.0F) {
            return;
        }
        if (area < 0.0F) {
            reversed = true;
            // Back face. Empirically proven (CullDirectionTest against
            // vanilla's FaceInfo.UP winding): a vanilla front face lands here
            // with POSITIVE area in our flipped-Y pixel space, a back face
            // with negative area. GPU pipelines drop back faces when culling
            // is on - this is what hides the far side of stairs, slabs, cross
            // models and entities.
            if (snap.cull) {
                return;
            }
            // Normalize the winding for the edge weights below.
            float tx = x1;
            float ty = y1;
            x1 = x2;
            y1 = y2;
            x2 = tx;
            y2 = ty;
            int tmp = ob;
            ob = oc;
            oc = tmp;
            float tw = w1;
            w1 = w2;
            w2 = tw;
            float tiw = iw1;
            iw1 = iw2;
            iw2 = tiw;
            area = -area;
        }

        int minX = Math.max(boundsMinX, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int minY = Math.max(boundsMinY, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxX = Math.min(boundsMaxX, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int maxY = Math.min(boundsMaxY, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        // A scissor may end inside a tile; bin selection alone cannot mask its pixels.
        if(snap.scissorEnabled){
            if(snap.scissorW<=0||snap.scissorH<=0)return;
            minX=Math.max(minX,snap.scissorX);minY=Math.max(minY,snap.scissorY);
            maxX=(int)Math.min(maxX,(long)snap.scissorX+snap.scissorW-1);
            maxY=(int)Math.min(maxY,(long)snap.scissorY+snap.scissorH-1);
        }
        if (minX > maxX || minY > maxY) {
            return;
        }

        float invArea = 1.0F / area;
        float z0 = tris[oa + 2] * iw0;
        float z1 = tris[ob + 2] * iw1;
        float z2 = tris[oc + 2] * iw2;
        float u0 = tris[oa + 4] * iw0;
        float u1 = tris[ob + 4] * iw1;
        float u2 = tris[oc + 4] * iw2;
        float vv0 = tris[oa + 5] * iw0;
        float vv1 = tris[ob + 5] * iw1;
        float vv2 = tris[oc + 5] * iw2;
        float r0 = tris[oa + 6] * iw0;
        float r1 = tris[ob + 6] * iw1;
        float r2 = tris[oc + 6] * iw2;
        float g0 = tris[oa + 7] * iw0;
        float g1 = tris[ob + 7] * iw1;
        float g2 = tris[oc + 7] * iw2;
        float b0 = tris[oa + 8] * iw0;
        float b1 = tris[ob + 8] * iw1;
        float b2 = tris[oc + 8] * iw2;
        float a0 = tris[oa + 9] * iw0;
        float a1 = tris[ob + 9] * iw1;
        float a2 = tris[oc + 9] * iw2;
        float d0 = tris[oa + 10] * iw0;
        float d1 = tris[ob + 10] * iw1;
        float d2 = tris[oc + 10] * iw2;
        float l0 = tris[oa + 11] * iw0;
        float l1 = tris[ob + 11] * iw1;
        float l2 = tris[oc + 11] * iw2;
        float m0 = tris[oa + 12] * iw0;
        float m1 = tris[ob + 12] * iw1;
        float m2 = tris[oc + 12] * iw2;
        // Vanilla entity overlay coordinate, packed (v << 4) | u.
        float q0 = tris[oa + 13] * iw0;
        float q1 = tris[ob + 13] * iw1;
        float q2 = tris[oc + 13] * iw2;
        // B3: shadowPos varying (u, v, refZ), already in shadow-map space at
        // each vertex; interpolating it is exactly what the GPU does with the
        // pack's `varying vec3 shadowPos`.
        float s0 = tris[oa + 14] * iw0;
        float s1 = tris[ob + 14] * iw1;
        float s2 = tris[oc + 14] * iw2;
        float t0 = tris[oa + 15] * iw0;
        float t1 = tris[ob + 15] * iw1;
        float t2 = tris[oc + 15] * iw2;
        float w0z = tris[oa + 16] * iw0;
        float w1z = tris[ob + 16] * iw1;
        float w2z = tris[oc + 16] * iw2;

        IrisGlTexture tex = snap.texture;
        IrisGlTexture light = snap.lightmap;
        // 阴影里的环境光底：包里的 omniLight 不乘 shadowValue，所以直射光被挡住
        // 的地方不该全黑，而是留下约四分之一天空光（见 ShadowMap.ambient()）。
        float shadowAmbient = snap.shadow ? ShadowMap.ambient() : 1.0F;

        if(shaderWorker!=null){
            int stride=shaderTriangle.stride();
            shaderWorker.prepareTriangle(shaderTriangle.vertices(),STRIDE,(reversed?2:1)*stride+STRIDE,(reversed?1:2)*stride+STRIDE,
                snap.viewportX,snap.viewportY,snap.viewportW,snap.viewportH);
        }
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5F;
            int row = y * ctx.framebuffer.width;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5F;
                float e0 = edge(x1, y1, x2, y2, px, py) * invArea;
                if (e0 < 0.0F) continue;
                float e1 = edge(x2, y2, x0, y0, px, py) * invArea;
                if (e1 < 0.0F) continue;
                float e2 = 1.0F - e0 - e1;
                if (e2 < 0.0F) continue;

                float iw = e0 * iw0 + e1 * iw1 + e2 * iw2;
                if (iw <= 0.0F) continue;
                float invIw = 1.0F / iw;

                // Window depth is affine in screen barycentrics; only smooth varyings divide by iw.
                float ndcZ = e0 * z0 + e1 * z1 + e2 * z2;
                float depth = ndcZ * 0.5F + 0.5F;
                if (depth < 0.0F || depth > 1.0F) continue;
                int idx = row + x;
                if (snap.depthTest && depth > ctx.framebuffer.depth[idx]) {
                    continue;
                }

                if (shaderWorker != null) {
                    int s = shaderTriangle.stride();
                    int result = shaderWorker.shade(shaderTriangle.vertices(), STRIDE,
                        (reversed ? 2 : 1) * s + STRIDE, (reversed ? 1 : 2) * s + STRIDE,
                        e0,e1,e2,iw0,iw1,iw2,x,y,depth,iw,!reversed,snap.colorWrite);
                    if (result == 1) continue;
                    if (result == 2) {
                        if (snap.depthWrite) ctx.framebuffer.depth[idx] = depth;
                        continue;
                    }
                    if (result != 0) throw new IllegalStateException("Invalid fragment commit result");
                }
                float u = (e0 * u0 + e1 * u1 + e2 * u2) * invIw;
                float vv = (e0 * vv0 + e1 * vv1 + e2 * vv2) * invIw;
                float r = (e0 * r0 + e1 * r1 + e2 * r2) * invIw;
                float g = (e0 * g0 + e1 * g1 + e2 * g2) * invIw;
                float b = (e0 * b0 + e1 * b1 + e2 * b2) * invIw;
                float a = (e0 * a0 + e1 * a1 + e2 * a2) * invIw;

                if (tex != null) {
                    int tc = tex.sampleNearest(u, vv);
                    float[] inv = dev.antimony.core.irisgl.Inv255.INV_255;
                    float ta = inv[(tc >>> 24) & 0xFF];
                    r *= inv[(tc >>> 16) & 0xFF];
                    g *= inv[(tc >>> 8) & 0xFF];
                    b *= inv[tc & 0xFF];
                    a *= ta;
                }
                // Vanilla entity shader: color.rgb = mix(overlay.rgb, color.rgb,
                // overlay.a), applied after texture/vertex colour and before the
                // lightmap. The 16x16 overlay texture holds the red damage flash
                // on rows 0..7 and the white flash (alpha ramp) on rows 8..15.
                if (snap.overlay) {
                    float overlayCoord = (e0 * q0 + e1 * q1 + e2 * q2) * invIw;
                    int code = (int) (overlayCoord + 0.5F);
                    if (code > 0) {
                        int ou = code & 15;
                        int ov = code >> 4;
                        float oAlpha;
                        float orr;
                        float ogg;
                        float obb;
                        if (ov < 8) {
                            oAlpha = 178.0F / 255.0F;
                            orr = 1.0F;
                            ogg = 0.0F;
                            obb = 0.0F;
                        } else {
                            oAlpha = 1.0F - (ou / 15.0F) * 0.75F;
                            orr = 1.0F;
                            ogg = 1.0F;
                            obb = 1.0F;
                        }
                        float inv = 1.0F - oAlpha;
                        r = orr * inv + r * oAlpha;
                        g = ogg * inv + g * oAlpha;
                        b = obb * inv + b * oAlpha;
                    }
                }
                if (light != null) {
                    float lu = (e0 * l0 + e1 * l1 + e2 * l2) * invIw;
                    float lv = (e0 * m0 + e1 * m1 + e2 * m2) * invIw;
                    // vanilla light.glsl: clamp(UV2 / 256.0, 0.5 / 16, 15.5 / 16)
                    // sampled with GL_LINEAR - nearest sampling made block light
                    // look like flat bands instead of a soft falloff.
                    float u0l = 0.5F / light.width;
                    float u1l = (light.width - 0.5F) / light.width;
                    float v0l = 0.5F / light.height;
                    float v1l = (light.height - 0.5F) / light.height;
                    float su = Math.max(u0l, Math.min(u1l, lu / 256.0F));
                    float sv = Math.max(v0l, Math.min(v1l, lv / 256.0F));
                    int lc = light.sampleBilinear(su, sv);
                    float[] inv = dev.antimony.core.irisgl.Inv255.INV_255;
                    if (snap.shadow && sv > v0l + 1.0e-6F) {
                        // B3: 阴影只该压掉"直射阳光"那一份，不该连方块光一起压。
                        // vanilla 的 lightmap 是 16x16、u=方块光、v=天空光，两种光
                        // 逐通道合成后写入同一像素，所以直接乘一个系数会把火把光
                        // 也乘暗。这里用两条边界各取一次：v=首行 ⇒ 只剩方块光，
                        // u=首列 ⇒ 只剩天空光，然后 max(block, sky*shadow) 重组。
                        // （additive 和 max 两种合成方式下这个式子都成立。）
                        // sv 落在首行（天空光为 0，洞穴/夜晚背光面）时阴影不可能
                        // 改变任何通道，直接走快路径，省掉一次查表 + 4 次比较。
                        float sh = ShadowMap.sampleSoft(
                                (e0 * s0 + e1 * s1 + e2 * s2) * invIw,
                                (e0 * t0 + e1 * t1 + e2 * t2) * invIw,
                                (e0 * w0z + e1 * w1z + e2 * w2z) * invIw,
                                interleavedGradient(x, y));
                        if (sh < 0.999F) {
                            int lb = light.sampleBilinear(su, v0l);
                            int lsx = light.sampleBilinear(u0l, sv);
                            float shAdj = shadowAmbient + (1.0F - shadowAmbient) * sh;
                            r *= Math.max(inv[(lb >>> 16) & 0xFF], inv[(lsx >>> 16) & 0xFF] * shAdj);
                            g *= Math.max(inv[(lb >>> 8) & 0xFF], inv[(lsx >>> 8) & 0xFF] * shAdj);
                            b *= Math.max(inv[lb & 0xFF], inv[lsx & 0xFF] * shAdj);
                        } else {
                            r *= inv[(lc >>> 16) & 0xFF];
                            g *= inv[(lc >>> 8) & 0xFF];
                            b *= inv[lc & 0xFF];
                        }
                    } else {
                        r *= inv[(lc >>> 16) & 0xFF];
                        g *= inv[(lc >>> 8) & 0xFF];
                        b *= inv[lc & 0xFF];
                    }
                }

                if (snap.alphaTest && a < snap.alphaRef) {
                    continue;
                }

                if (snap.fogEnabled && snap.fogEnd > snap.fogStart) {
                    float dist = (e0 * d0 + e1 * d1 + e2 * d2) * invIw;
                    // Vanilla fog.glsl linear_fog: smoothstep, not linear - the
                    // difference is clearly visible on the sky disc, where the
                    // whole gradient is this curve sampled across 512 blocks.
                    float t = (dist - snap.fogStart) / (snap.fogEnd - snap.fogStart);
                    t = t <= 0.0F ? 0.0F : (t >= 1.0F ? 1.0F : t);
                    float fogValue = t * t * (3.0F - 2.0F * t);
                    float f = 1.0F - fogValue;
                    int fc = snap.fogColor;
                    float[] inv = dev.antimony.core.irisgl.Inv255.INV_255;
                    float fr = inv[(fc >>> 16) & 0xFF];
                    float fg = inv[(fc >>> 8) & 0xFF];
                    float fb2 = inv[fc & 0xFF];
                    r = fr + (r - fr) * f;
                    g = fg + (g - fg) * f;
                    b = fb2 + (b - fb2) * f;
                }

                int sr = clamp255((int) (r * 255.0F));
                int sg = clamp255((int) (g * 255.0F));
                int sb = clamp255((int) (b * 255.0F));
                int sa = clamp255((int) (a * 255.0F));
                int src = (sa << 24) | (sr << 16) | (sg << 8) | sb;

                int out;
                if (snap.blend) {
                    // GL semantics: when blending is enabled it applies to
                    // every fragment regardless of its alpha. The old
                    // "skip when alpha==255" shortcut silently disabled
                    // the vignette's multiply blend (ZERO, ONE_MINUS_SRC_COLOR),
                    // writing the grayscale texture opaque over the world -
                    // the whole frame went black with a radial gradient.
                    out = blend(snap.blendSrc, snap.blendDst,
                            (snap.blendAlphaSrc << 4) | snap.blendAlphaDst, src, ctx.framebuffer.color[idx]);
                } else {
                    out = src;
                }
                if (snap.colorWrite) {
                    ctx.framebuffer.color[idx] = out;
                }
                if (snap.depthWrite) {
                    ctx.framebuffer.depth[idx] = depth;
                }
            }
        }
        if (DEBUG) {
            // Kept for the -Dantimony.debug.raster diagnostics path.
        }
    }

    /**
     * Interleaved gradient noise (Jimenez 2014) 代替包里的 {@code dither17()}：
     * 两者都只是给软阴影的 4 个 tap 提供一个稳定的空间旋转角，肉眼无法区分，
     * 但这个版本没有 hash 里的三角函数，逐像素只要 6 次浮点运算。
     */
    private static float interleavedGradient(int x, int y) {
        float f = 0.06711056F * x + 0.00583715F * y;
        f -= (float) Math.floor(f);
        f = 52.9829189F * f;
        return f - (float) Math.floor(f);
    }

    private static int blend(int srcFactor, int dstFactor, int alphaPair, int src, int dst) {
        float[] inv = dev.antimony.core.irisgl.Inv255.INV_255;
        float sa = inv[(src >>> 24) & 0xFF];
        float sr = inv[(src >>> 16) & 0xFF];
        float sg = inv[(src >>> 8) & 0xFF];
        float sb = inv[src & 0xFF];
        float da = inv[(dst >>> 24) & 0xFF];
        float dr = inv[(dst >>> 16) & 0xFF];
        float dg = inv[(dst >>> 8) & 0xFF];
        float db = inv[dst & 0xFF];

        // Separate colour/alpha factor pairs, as in vanilla's
        // blendFuncSeparate (the vignette multiplies colour by
        // (1 - srcColor) while keeping the destination alpha).
        float sf = factor(srcFactor, sa, da, sr, dr);
        float df = factor(dstFactor, sa, da, sr, dr);

        float rr = sr * sf + dr * df;
        float gg = sg * sf + dg * df;
        float bb = sb * sf + db * df;
        float aa;
        if (alphaPair != 0) {
            float asf = factor((alphaPair >>> 4) & 0xF, sa, da, sr, dr);
            float adf = factor(alphaPair & 0xF, sa, da, sr, dr);
            aa = sa * asf + da * adf;
        } else {
            aa = sa * sf + da * df;
        }
        return (clamp255((int) (aa * 255.0F)) << 24)
                | (clamp255((int) (rr * 255.0F)) << 16)
                | (clamp255((int) (gg * 255.0F)) << 8)
                | clamp255((int) (bb * 255.0F));
    }

    private static float factor(int f, float sa, float da, float sr, float dr) {
        return switch (f) {
            case IrisGlContext.BF_ZERO -> 0.0F;
            case IrisGlContext.BF_ONE -> 1.0F;
            case IrisGlContext.BF_SRC_COLOR -> sr;
            case IrisGlContext.BF_ONE_MINUS_SRC_COLOR -> 1.0F - sr;
            case IrisGlContext.BF_SRC_ALPHA -> sa;
            case IrisGlContext.BF_ONE_MINUS_SRC_ALPHA -> 1.0F - sa;
            case IrisGlContext.BF_DST_ALPHA -> da;
            case IrisGlContext.BF_ONE_MINUS_DST_ALPHA -> 1.0F - da;
            case IrisGlContext.BF_DST_COLOR -> dr;
            case IrisGlContext.BF_ONE_MINUS_DST_COLOR -> 1.0F - dr;
            default -> 1.0F;
        };
    }

    public void line(float[] a, float[] b) {
        if (a[3] <= 0.0F || b[3] <= 0.0F) return;
        float x0 = screenX(a[0], a[3], this.ctx);
        float y0 = screenY(a[1], a[3], this.ctx);
        float x1 = screenX(b[0], b[3], this.ctx);
        float y1 = screenY(b[1], b[3], this.ctx);
        float z0 = a[2] / a[3] * 0.5F + 0.5F;
        float z1 = b[2] / b[3] * 0.5F + 0.5F;
        float dx = x1 - x0;
        float dy = y1 - y0;
        int steps = (int) Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) return;
        IrisGlFramebuffer fb = this.ctx.framebuffer;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int x = (int) (x0 + dx * t);
            int y = (int) (y0 + dy * t);
            if (x < 0 || y < 0 || x >= fb.width || y >= fb.height) continue;
            if (this.ctx.scissorEnabled
                    && (x < this.ctx.scissorX || y < this.ctx.scissorY
                    || x >= this.ctx.scissorX + this.ctx.scissorW
                    || y >= this.ctx.scissorY + this.ctx.scissorH)) {
                continue;
            }
            int idx = y * fb.width + x;
            float z = z0 + (z1 - z0) * t;
            if (this.ctx.depthTest && z > fb.depth[idx]) continue;
            float r = a[6] + (b[6] - a[6]) * t;
            float g = a[7] + (b[7] - a[7]) * t;
            float bl = a[8] + (b[8] - a[8]) * t;
            float al = a[9] + (b[9] - a[9]) * t;
            if (this.ctx.alphaTest && al < this.ctx.alphaRef) continue;
            int src = (clamp255((int) (al * 255)) << 24)
                    | (clamp255((int) (r * 255)) << 16)
                    | (clamp255((int) (g * 255)) << 8)
                    | clamp255((int) (bl * 255));
            // Debug/outline lines arrive through RenderType.lines(), which has
            // TRANSLUCENT_TRANSPARENCY: the block outline is a 25% alpha black
            // line, so blending must be honoured here too.
            fb.color[idx] = this.ctx.blend
                    ? blend(this.ctx.blendSrc, this.ctx.blendDst,
                            (this.ctx.blendAlphaSrc << 4) | this.ctx.blendAlphaDst, src, fb.color[idx])
                    : src;
            if (this.ctx.depthWrite) fb.depth[idx] = z;
        }
    }

    public void point(float[] a) {
        if (a[3] <= 0.0F) return;
        int x = (int) screenX(a[0], a[3], this.ctx);
        int y = (int) screenY(a[1], a[3], this.ctx);
        IrisGlFramebuffer fb = this.ctx.framebuffer;
        if (x < 0 || y < 0 || x >= fb.width || y >= fb.height) return;
        int idx = y * fb.width + x;
        float z = a[2] / a[3] * 0.5F + 0.5F;
        if (this.ctx.depthTest && z > fb.depth[idx]) return;
        fb.color[idx] = (clamp255((int) (a[9] * 255)) << 24)
                | (clamp255((int) (a[6] * 255)) << 16)
                | (clamp255((int) (a[7] * 255)) << 8)
                | clamp255((int) (a[8] * 255));
        if (this.ctx.depthWrite) fb.depth[idx] = z;
    }

    private static float screenX(float x, float w, IrisGlContext ctx) {
        float ndc = x / w;
        return ctx.viewportX + (ndc * 0.5F + 0.5F) * ctx.viewportW;
    }

    private static float screenY(float y, float w, IrisGlContext ctx) {
        float ndc = y / w;
        return ctx.viewportY + (1.0F - (ndc * 0.5F + 0.5F)) * ctx.viewportH;
    }

    private static float edge(float ax, float ay, float bx, float by, float px, float py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}

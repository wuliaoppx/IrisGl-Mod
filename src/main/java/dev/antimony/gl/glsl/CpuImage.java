package dev.antimony.gl.glsl;

/**
 * CPU mirror of one GL render target (phase A3, docs section 19).
 *
 * <p>Storage holds the attachment's RGBA values, one float per channel. It is
 * not implicitly linear light: shaderpacks own their color-transfer functions.
 * Framebuffer arrays are top-down; sampling maps GL bottom-up coordinates to
 * those rows. Resource uploads can instead retain their original GL row order.</p>
 *
 * <p>Filtering and wrapping follow the binding. Declared mip lookups use frozen
 * CPU mip chains. Display output uses explicit normalized storage, while the
 * legacy scene seed/read methods explicitly convert between sRGB and linear.</p>
 */
public final class CpuImage {
    public final int width;
    public final int height;
    private final boolean linearFilter;
    private final boolean repeatS;
    private final boolean repeatT;
    private final boolean uploadedRows;
    private TerrainFragmentDraw.Encoding storageEncoding = TerrainFragmentDraw.Encoding.RGBA32F;
    private CpuImage[] mipLevels;
    public boolean hasMipmaps(){return mipLevels!=null;}
    /** Attachment RGBA values, length width*height*4. */
    public final float[] rgba;

    /**
     * sRGB transfer tables. The seed/readback path runs over every window
     * pixel twice per frame, so the exact pow() in the sRGB curve would cost
     * more than the whole post chain; 8-bit in / 10-bit out tables are exact
     * enough for a display-referred round trip.
     */
    private static final float[] SRGB_TO_LINEAR = new float[256];
    private static final int[] LINEAR_TO_SRGB = new int[1025];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255.0f;
            SRGB_TO_LINEAR[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
        }
        for (int i = 0; i <= 1024; i++) {
            float c = i / 1024.0f;
            int v = Math.round((c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055)) * 255.0f);
            LINEAR_TO_SRGB[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
    }

    public CpuImage(int width, int height) {
        this(width, height, true, false, false, false);
    }

    /** Resource uploads retain their first row at GL v=0; framebuffer rows run top-down. */
    public CpuImage(int width, int height, boolean linearFilter, boolean repeatS, boolean repeatT, boolean uploadedRows) {
        this.linearFilter = linearFilter;
        this.repeatS = repeatS;
        this.repeatT = repeatT;
        this.uploadedRows = uploadedRows;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.rgba = new float[this.width * this.height * 4];
    }

    public CpuImage detached() {
        CpuImage copy = new CpuImage(width,height,linearFilter,repeatS,repeatT,uploadedRows);
        copy.storageEncoding = storageEncoding;
        copy.copyFrom(this);
        if(mipLevels!=null){
            copy.mipLevels=new CpuImage[mipLevels.length];copy.mipLevels[0]=copy;
            for(int level=1;level<mipLevels.length;level++)copy.mipLevels[level]=mipLevels[level].detached();
        }
        return copy;
    }

    /** Freeze this pass' base image and build its declared mip chain entirely on CPU. */
    public CpuImage mipmappedView(){
        CpuImage base=new CpuImage(width,height,linearFilter,repeatS,repeatT,uploadedRows);
        base.storageEncoding=storageEncoding;base.copyFrom(this);
        java.util.List<CpuImage> levels=new java.util.ArrayList<>();levels.add(base);
        CpuImage previous=base;
        while(previous.width>1||previous.height>1){
            CpuImage next=new CpuImage(Math.max(1,previous.width/2),Math.max(1,previous.height/2),linearFilter,repeatS,repeatT,uploadedRows);
            next.storageEncoding=storageEncoding;
            for(int y=0;y<next.height;y++)for(int x=0;x<next.width;x++){
                int x0=Math.min(previous.width-1,x*2),x1=Math.min(previous.width-1,x0+1);
                int y0=Math.min(previous.height-1,y*2),y1=Math.min(previous.height-1,y0+1);
                int a=(y0*previous.width+x0)*4,b=(y0*previous.width+x1)*4,c=(y1*previous.width+x0)*4,d=(y1*previous.width+x1)*4;
                int output=(y*next.width+x)*4;
                for(int channel=0;channel<4;channel++)next.rgba[output+channel]=storageEncoding.encode(
                    (previous.rgba[a+channel]+previous.rgba[b+channel]+previous.rgba[c+channel]+previous.rgba[d+channel])*.25f,channel);
            }
            levels.add(next);previous=next;
        }
        base.mipLevels=levels.toArray(CpuImage[]::new);return base;
    }
    /** Exact integer texel address: no filtering, wrap or normalized-coordinate conversion. */
    public float[] fetch(int x, int y, int lod) {
        if (lod < 0 || (mipLevels == null ? lod != 0 : lod >= mipLevels.length))
            throw new IllegalArgumentException("Unavailable integer texture LOD " + lod);
        CpuImage level = mipLevels == null ? this : mipLevels[lod];
        if (x < 0 || y < 0 || x >= level.width || y >= level.height)
            throw new IllegalArgumentException("Undefined out-of-range texelFetch address " + x + "," + y);
        int row = level.uploadedRows ? y : level.height - 1 - y;
        int offset = (row * level.width + x) * 4;
        return java.util.Arrays.copyOfRange(level.rgba, offset, offset + 4);
    }

    public float[] sampleLod(float u,float v,float lod){
        if(!Float.isFinite(lod))throw new IllegalArgumentException("Nonfinite texture LOD");
        if(mipLevels==null)return sample(u,v); // This binding has only its base level.
        float level=Math.max(0,Math.min(mipLevels.length-1,lod));
        if(!linearFilter)return mipLevels[Math.round(level)].sample(u,v);
        int lower=(int)level,upper=Math.min(mipLevels.length-1,lower+1);float amount=level-lower;
        float[] a=mipLevels[lower].sample(u,v);if(amount==0||upper==lower)return a;
        float[] b=mipLevels[upper].sample(u,v);for(int channel=0;channel<4;channel++)a[channel]=a[channel]*(1-amount)+b[channel]*amount;
        return a;
    }
    public float[] sampleBias(float u,float v,float bias){
        if(!Float.isFinite(bias))throw new IllegalArgumentException("Nonfinite texture bias");
        if(mipLevels!=null)throw new IllegalStateException("Biased mip lookup requires an explicit coordinate-gradient owner");
        // With a non-mipped binding every finite biased LOD clamps to the sole base level.
        return sample(u,v);
    }

    public void storageEncoding(TerrainFragmentDraw.Encoding encoding) {
        storageEncoding = java.util.Objects.requireNonNull(encoding);
    }

    public boolean matches(int w, int h) {
        return width == w && height == h;
    }

    /**
     * Copy another target's pixels into this one. Used for the read-before-write
     * snapshot: a pass that samples the same render target it draws to (every
     * MakeUp composite stage reads colortex1 and writes colortex1) must see the
     * PRE-pass content, exactly like a GPU reading a texture it also renders
     * into at the same texel.
     */
    public void copyFrom(CpuImage src) {
        int length = Math.min(rgba.length, src.rgba.length);
        for (int i = 0; i < length; i += 4)
            writePixel(i, src.rgba[i], src.rgba[i + 1], src.rgba[i + 2], src.rgba[i + 3]);
    }

    public void clear(float r, float g, float b, float a) {
        for (int i = 0; i < rgba.length; i += 4) writePixel(i, r, g, b, a);
    }

    public void writePixel(int offset, float r, float g, float b, float a) {
        rgba[offset] = storageEncoding.encode(r, 0);
        rgba[offset + 1] = storageEncoding.encode(g, 1);
        rgba[offset + 2] = storageEncoding.encode(b, 2);
        rgba[offset + 3] = storageEncoding.encode(a, 3);
    }

    /** Copy an 0xAARRGGBB frame (sRGB encoded) into this target as linear light. */
    public void seedFromArgb(int[] argb, int srcWidth, int srcHeight) {
        for (int y = 0; y < height; y++) {
            int sy = srcHeight == height ? y : Math.min(srcHeight - 1, y * srcHeight / height);
            int row = y * width * 4;
            int srow = sy * srcWidth;
            for (int x = 0; x < width; x++) {
                int sx = srcWidth == width ? x : Math.min(srcWidth - 1, x * srcWidth / width);
                int c = argb[srow + sx];
                int o = row + x * 4;
                writePixel(o, SRGB_TO_LINEAR[(c >>> 16) & 0xFF], SRGB_TO_LINEAR[(c >>> 8) & 0xFF],
                    SRGB_TO_LINEAR[c & 0xFF], ((c >>> 24) & 0xFF) / 255.0f);
            }
        }
    }

    /**
     * B1: copy a GlTexMirror level-0 int[] (NativeImage ABGR order) into this
     * target as linear-light RGBA float. Resource textures (the pack's cloud
     * noise, specular maps) read through this path; nearest downscale to the
     * chain resolution, wrap by clamping at the edge.
     */
    public void mirrorCopy(int[] abgr, int srcWidth, int srcHeight) {
        for (int y = 0; y < height; y++) {
            int sy = srcHeight == height ? y : Math.min(srcHeight - 1, y * srcHeight / height);
            int row = y * width * 4;
            int srow = sy * srcWidth;
            for (int x = 0; x < width; x++) {
                int sx = srcWidth == width ? x : Math.min(srcWidth - 1, x * srcWidth / width);
                int c = abgr[srow + sx];
                int o = row + x * 4;
                // NativeImage int layout is ABGR: R in the low byte.
                writePixel(o, SRGB_TO_LINEAR[c & 0xFF], SRGB_TO_LINEAR[(c >>> 8) & 0xFF],
                    SRGB_TO_LINEAR[(c >>> 16) & 0xFF], ((c >>> 24) & 0xFF) / 255.0f);
            }
        }
    }

    /** Copy this target into an 0xAARRGGBB frame, converting linear light to sRGB. */
    public void readIntoArgb(int[] argb, int dstWidth, int dstHeight) {
        for (int y = 0; y < dstHeight; y++) {
            int sy = dstHeight == height ? y : Math.min(height - 1, y * height / dstHeight);
            int row = sy * width * 4;
            int drow = y * dstWidth;
            for (int x = 0; x < dstWidth; x++) {
                int sx = dstWidth == width ? x : Math.min(width - 1, x * width / dstWidth);
                int o = row + sx * 4;
                int r = linearToByte(rgba[o]);
                int g = linearToByte(rgba[o + 1]);
                int b = linearToByte(rgba[o + 2]);
                int a = clampByte(rgba[o + 3]);
                argb[drow + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    /**
     * Store shader output into an RGBA8 display target without a transfer function.
     * Iris final shaders already emit display-referred values; its SRGB converter
     * is a no-op. This is distinct from readIntoArgb's legacy linear scene path.
     */
    public void readNormalizedIntoArgb(int[] argb, int dstWidth, int dstHeight) {
        if (dstWidth <= 0 || dstHeight <= 0 || (long) dstWidth * dstHeight != argb.length)
            throw new IllegalArgumentException("Display target extent mismatch");
        for (int y = 0; y < dstHeight; y++) {
            int sy = dstHeight == height ? y : Math.min(height - 1, (int) ((long) y * height / dstHeight));
            for (int x = 0; x < dstWidth; x++) {
                int sx = dstWidth == width ? x : Math.min(width - 1, (int) ((long) x * width / dstWidth));
                int at = (sy * width + sx) * 4;
                argb[y * dstWidth + x] = clampByte(rgba[at + 3]) << 24 | clampByte(rgba[at]) << 16
                        | clampByte(rgba[at + 1]) << 8 | clampByte(rgba[at + 2]);
            }
        }
    }

    /**
     * Bilinear clamp-to-edge sample. (u,v) are GL texture coordinates with v=0
     * at the bottom, so the row lookup flips against our top-down storage.
     * Returns a fresh float[4]: the translated programs own the array they get
     * (they mutate it through swizzle assignments).
     */
    public float[] sample(float u, float v) {
        if (repeatS) u -= (float) Math.floor(u);
        if (repeatT) v -= (float) Math.floor(v);
        float rowV = uploadedRows ? v : 1.0f - v;
        if (!linearFilter) {
            int x = address((int) Math.floor(u * width), width, repeatS);
            int y = address((int) Math.floor(rowV * height), height, repeatT);
            int i = (y * width + x) * 4;
            return new float[]{rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3]};
        }
        float fx = u * width - 0.5f;
        // GL window space has v = 0 at the BOTTOM, our array row 0 is the TOP
        // row of the framebuffer, so the row lookup flips. Keeping this flip
        // here (instead of in the caller) lets every shader use plain GL
        // semantics for both texture lookups and gl_FragCoord.y - without it
        // the sky gradient ran upside down and collapsed to the horizon colour.
        float fy = rowV * height - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;
        int x1 = address(x0 + 1, width, repeatS);
        int y1 = address(y0 + 1, height, repeatT);
        x0 = address(x0, width, repeatS);
        y0 = address(y0, height, repeatT);
        int i00 = (y0 * width + x0) * 4;
        int i10 = (y0 * width + x1) * 4;
        int i01 = (y1 * width + x0) * 4;
        int i11 = (y1 * width + x1) * 4;
        float w00 = (1.0f - tx) * (1.0f - ty);
        float w10 = tx * (1.0f - ty);
        float w01 = (1.0f - tx) * ty;
        float w11 = tx * ty;
        float[] out = new float[4];
        for (int c = 0; c < 4; c++) {
            out[c] = rgba[i00 + c] * w00 + rgba[i10 + c] * w10
                + rgba[i01 + c] * w01 + rgba[i11 + c] * w11;
        }
        return out;
    }

    /** PCF compares each depth tap BEFORE filtering; interpolating depth then comparing is not equivalent. */
    public float sampleCompare(float u, float v, float reference) {
        if (repeatS) u -= (float) Math.floor(u);
        if (repeatT) v -= (float) Math.floor(v);
        float rowV = uploadedRows ? v : 1.0f - v;
        if (!linearFilter) {
            int x = address((int) Math.floor(u * width), width, repeatS);
            int y = address((int) Math.floor(rowV * height), height, repeatT);
            return reference <= rgba[(y * width + x) * 4] ? 1 : 0;
        }
        float fx = u * width - .5f, fy = rowV * height - .5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int x1 = address(x0 + 1, width, repeatS), y1 = address(y0 + 1, height, repeatT);
        x0 = address(x0, width, repeatS); y0 = address(y0, height, repeatT);
        float a = reference <= rgba[(y0 * width + x0) * 4] ? 1 : 0;
        float b = reference <= rgba[(y0 * width + x1) * 4] ? 1 : 0;
        float c = reference <= rgba[(y1 * width + x0) * 4] ? 1 : 0;
        float d = reference <= rgba[(y1 * width + x1) * 4] ? 1 : 0;
        return a * (1 - tx) * (1 - ty) + b * tx * (1 - ty) + c * (1 - tx) * ty + d * tx * ty;
    }

    /** The same bilinear operation order as sample(), without allocating the unused RGBA lanes. */
    public float sampleChannel(float u,float v,int channel){
        if(channel<0||channel>3)throw new IllegalArgumentException("Texture channel");
        if(repeatS)u-=(float)Math.floor(u);if(repeatT)v-=(float)Math.floor(v);
        float rowV=uploadedRows?v:1-v;
        if(!linearFilter){int x=address((int)Math.floor(u*width),width,repeatS),y=address((int)Math.floor(rowV*height),height,repeatT);return rgba[(y*width+x)*4+channel];}
        float fx=u*width-.5f,fy=rowV*height-.5f;int x0=(int)Math.floor(fx),y0=(int)Math.floor(fy);float tx=fx-x0,ty=fy-y0;
        int x1=address(x0+1,width,repeatS),y1=address(y0+1,height,repeatT);x0=address(x0,width,repeatS);y0=address(y0,height,repeatT);
        float w00=(1-tx)*(1-ty),w10=tx*(1-ty),w01=(1-tx)*ty,w11=tx*ty;
        return rgba[(y0*width+x0)*4+channel]*w00+rgba[(y0*width+x1)*4+channel]*w10+rgba[(y1*width+x0)*4+channel]*w01+rgba[(y1*width+x1)*4+channel]*w11;
    }
    public float sampleLodChannel(float u,float v,float lod,int channel){
        if(!Float.isFinite(lod))throw new IllegalArgumentException("Nonfinite texture LOD");
        if(mipLevels==null)return sampleChannel(u,v,channel);
        float level=Math.max(0,Math.min(mipLevels.length-1,lod));if(!linearFilter)return mipLevels[Math.round(level)].sampleChannel(u,v,channel);
        int lower=(int)level,upper=Math.min(mipLevels.length-1,lower+1);float amount=level-lower,a=mipLevels[lower].sampleChannel(u,v,channel);
        return amount==0||lower==upper?a:a*(1-amount)+mipLevels[upper].sampleChannel(u,v,channel)*amount;
    }
    public float sampleBiasChannel(float u,float v,float bias,int channel){
        if(!Float.isFinite(bias))throw new IllegalArgumentException("Nonfinite texture bias");
        if(mipLevels!=null)throw new IllegalStateException("Biased mip lookup requires an explicit coordinate-gradient owner");
        return sampleChannel(u,v,channel);
    }

    /** Nearest sample without allocation, used by tests and diagnostics. */
    public void readPixel(int x, int y, float[] out) {
        int o = (clamp(y, height) * width + clamp(x, width)) * 4;
        out[0] = rgba[o];
        out[1] = rgba[o + 1];
        out[2] = rgba[o + 2];
        out[3] = rgba[o + 3];
    }

    /** Decode RGBA8 data without a color-space transfer. ABGR is NativeImage; ARGB is GlTexMirror. */
    public void copyRgba8(int[] pixels, boolean abgr) {
        if (pixels.length != width * height) throw new IllegalArgumentException("Resource size mismatch");
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i], o = i * 4;
            rgba[o] = (abgr ? c & 255 : (c >>> 16) & 255) / 255.0f;
            rgba[o + 1] = ((c >>> 8) & 255) / 255.0f;
            rgba[o + 2] = (abgr ? (c >>> 16) & 255 : c & 255) / 255.0f;
            rgba[o + 3] = (c >>> 24) / 255.0f;
        }
    }

    private static int address(int v, int size, boolean repeat) {
        return repeat ? Math.floorMod(v, size) : clamp(v, size);
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : (v >= size ? size - 1 : v);
    }

    private static int clampByte(float v) {
        int i = Math.round(v * 255.0f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static int linearToByte(float v) {
        if (v <= 0.0f) {
            return 0;
        }
        int index = (int) (v * 1024.0f + 0.5f);
        return index >= 1024 ? 255 : LINEAR_TO_SRGB[index];
    }

    public static float srgbToLinear(float c) {
        c = c < 0.0f ? 0.0f : (c > 1.0f ? 1.0f : c);
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    public static float linearToSrgb(float c) {
        c = c < 0.0f ? 0.0f : c;
        return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
    }
}

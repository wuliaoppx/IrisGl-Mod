package dev.antimony.core.render;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Byte-order, unpack-stride, binding identity, reuse and rejection tests. Does not create a GL context. */
public final class CpuDynamicTextureProbe {
    public static void main(String[] args) {
        ByteBuffer bytes = ByteBuffer.allocate(40);
        bytes.position(5);
        bytes.limit(29);
        int[] expected = new int[6];
        for (int y = 0; y < 3; y++) for (int x = 0; x < 2; x++) {
            int value = 1 + y * 10 + x * 3, at = 5 + y * 8 + x * 3;
            bytes.put(at, (byte) value).put(at + 1, (byte) (value + 1)).put(at + 2, (byte) (value + 2));
            expected[y * 2 + x] = 0xff000000 | value << 16 | (value + 1) << 8 | value + 2;
        }
        long before = CpuDynamicTextures.allocations();
        var texture = CpuDynamicTextures.uploadRgb(701, 2, 3, bytes);
        check(Arrays.equals(texture.pixels, expected), "RGB, row padding and nonzero source position");
        check(bytes.position() == 5 && bytes.limit() == 29, "upload does not mutate caller buffer state");
        check(texture.sampleNearest(.25f, 1f / 6f) == expected[0], "original upload/UV row order retained");
        check(texture.sampleNearest(.75f, 5f / 6f) == expected[5], "opposite corner retained");
        for (int i = 0; i < 1000; i++) check(CpuDynamicTextures.uploadRgb(701, 2, 3, bytes) == texture, "fixed-size update reuses storage");
        check(CpuDynamicTextures.allocations() == before + 1, "no pixel array allocation per repeated upload");
        IrisGlTextures.rememberShaderTexture(0, 701);
        check(IrisGlTextures.shaderTexture(0) == texture && IrisGlTextures.lastShaderTexture(0) == null,
                "raw texture binding resolves only its captured CPU identity");
        IrisGlTextures.rememberShaderTexture(0, (net.minecraft.resources.ResourceLocation) null);
        check(IrisGlTextures.shaderTexture(0) == null, "resource binding clears previous raw ID");
        IrisGlTextures.rememberShaderTexture(0, 702);
        expectFailure(() -> IrisGlTextures.shaderTexture(0), "unknown raw ID must not sample stale resource or GPU pixels");
        IrisGlTextures.rememberShaderTexture(0, 0);
        check(IrisGlTextures.shaderTexture(0) == null, "zero ID is an unbound unit");
        int[] unchanged = texture.pixels.clone();
        ByteBuffer incomplete = bytes.duplicate();
        incomplete.limit(10);
        expectFailure(() -> CpuDynamicTextures.uploadRgb(701, 2, 3, incomplete), "incomplete upload rejection");
        check(Arrays.equals(texture.pixels, unchanged), "rejected upload cannot corrupt prior pixels");
        expectFailure(() -> CpuDynamicTextures.uploadRgb(0, 2, 3, bytes), "invalid identity rejection");
        expectFailure(() -> CpuDynamicTextures.uploadRgb(701, 2048, 3, bytes), "extent budget rejection");
        check(XaeroCpuCompatibility.supportsVersion("26.6.0") && !XaeroCpuCompatibility.supportsVersion("26.7.0"),
                "compatibility claim is version-scoped");
        CpuDynamicTextures.forget(701);
        check(CpuDynamicTextures.texture(701) == null, "texture release removes CPU backing");
        ByteBuffer one = ByteBuffer.wrap(new byte[]{1, 2, 3});
        for (int id = 800; id < 808; id++) CpuDynamicTextures.uploadRgb(id, 1, 1, one);
        expectFailure(() -> CpuDynamicTextures.uploadRgb(808, 1, 1, one), "bounded texture count");
        for (int id = 800; id < 808; id++) CpuDynamicTextures.forget(id);
        System.out.println("CPU DYNAMIC TEXTURE PROBE OK: RGB/stride/UV, immutable source cursor, reused storage, raw/resource isolation, no GPU fallback and bounded lifecycle");
    }
    private static void expectFailure(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

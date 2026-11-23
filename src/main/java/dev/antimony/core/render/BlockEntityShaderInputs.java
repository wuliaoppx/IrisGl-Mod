package dev.antimony.core.render;

import dev.antimony.core.irisgl.IrisGlContext;
import dev.antimony.core.irisgl.IrisGlTexture;
import dev.antimony.gl.glsl.IrisSamplerBridge;
import dev.antimony.gl.glsl.TerrainFragmentDraw;
import dev.antimony.gl.glsl.VertexState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** Vanilla shader-shard identities and CPU-owned inputs for block-entity batches. */
public final class BlockEntityShaderInputs {
    private BlockEntityShaderInputs() { }

    /** Identity mapping mirrors Iris MixinGameRenderer's getters, not RenderType name substrings. */
    public static String shaderKey(RenderType type) {
        Object state = IrisSamplerBridge.field(type, "state");
        Object shard = IrisSamplerBridge.field(state, "shaderState");
        if (shard == null) return null;
        if (shard == RenderStateShard.RENDERTYPE_ENTITY_SOLID_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_Z_OFFSET_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_DECAL_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_SMOOTH_CUTOUT_SHADER
                || shard == RenderStateShard.RENDERTYPE_ARMOR_CUTOUT_NO_CULL_SHADER) return "BLOCK_ENTITY_DIFFUSE";
        if (shard == RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER
                || shard == RenderStateShard.RENDERTYPE_ITEM_ENTITY_TRANSLUCENT_CULL_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_NO_OUTLINE_SHADER) return "BE_TRANSLUCENT";
        if (shard == RenderStateShard.RENDERTYPE_EYES_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENERGY_SWIRL_SHADER
                || shard == RenderStateShard.RENDERTYPE_ENTITY_SHADOW_SHADER) return "BLOCK_ENTITY";
        return null;
    }

    /** Packed UV1 is the original integer overlay coordinate, not a normalized sample. */
    public static VertexState.InputProvider attributes(VertexState.InputProvider base, int material, int overlay) {
        return input -> switch (input.name()) {
            case "iris_Entity" -> new int[]{0, material, 0};
            case "iris_UV1", "UV1" -> new int[]{overlay & 15, (overlay >>> 4) & 15};
            case "blockEntityId" -> material;
            default -> base.value(input);
        };
    }

    /** Read the actual NativeImage backing. No GL texture download or reconstructed overlay constants. */
    public static IrisGlTexture overlayTexture() {
        Object overlay = Minecraft.getInstance().gameRenderer.overlayTexture();
        Object dynamic = IrisSamplerBridge.field(overlay, "texture");
        Object pixels = IrisSamplerBridge.call(dynamic, "getPixels");
        if (!(pixels instanceof com.mojang.blaze3d.platform.NativeImage image))
            throw new IllegalStateException("Native CPU overlay pixels unavailable");
        int width = image.getWidth(), height = image.getHeight();
        int[] argb = new int[Math.multiplyExact(width, height)];
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            int abgr=image.getPixelRGBA(x,y);
            argb[y*width+x]=(abgr & 0xff00ff00) | ((abgr & 255)<<16) | ((abgr>>>16)&255);
        }
        return new IrisGlTexture(width,height,argb);
    }

    public static TerrainFragmentDraw.Blend blend(IrisGlContext context) {
        if (!context.blend) return TerrainFragmentDraw.Blend.OFF;
        boolean separate=context.blendAlphaSrc!=0 || context.blendAlphaDst!=0;
        return new TerrainFragmentDraw.Blend(true,factor(context.blendSrc),factor(context.blendDst),
            factor(separate?context.blendAlphaSrc:context.blendSrc),factor(separate?context.blendAlphaDst:context.blendDst));
    }
    private static int factor(int factor) {
        return switch(factor) {
            case IrisGlContext.BF_ZERO -> 0;
            case IrisGlContext.BF_ONE -> 1;
            case IrisGlContext.BF_SRC_ALPHA -> 0x0302;
            case IrisGlContext.BF_ONE_MINUS_SRC_ALPHA -> 0x0303;
            case IrisGlContext.BF_DST_ALPHA -> 0x0304;
            case IrisGlContext.BF_ONE_MINUS_DST_ALPHA -> 0x0305;
            case IrisGlContext.BF_SRC_COLOR -> 0x0300;
            case IrisGlContext.BF_ONE_MINUS_SRC_COLOR -> 0x0301;
            case IrisGlContext.BF_DST_COLOR -> 0x0306;
            case IrisGlContext.BF_ONE_MINUS_DST_COLOR -> 0x0307;
            default -> throw new IllegalArgumentException("Unsupported captured CPU blend factor " + factor);
        };
    }
}

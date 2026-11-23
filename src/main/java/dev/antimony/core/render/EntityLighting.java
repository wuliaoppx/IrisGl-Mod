package dev.antimony.core.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Vanilla entity directional lighting constants, replicated from
 * {@code com.mojang.blaze3d.platform.Lighting} and
 * {@code GlStateManager.setupGui3DDiffuseLighting}.
 *
 * <p>The directions live in the shader's normal space (view*model). World
 * entities use the raw camera-space pair set once per level frame;
 * GUI item icons use the pair transformed by the fixed GUI 3D matrix that
 * vanilla installs before rendering any screen.</p>
 */
public final class EntityLighting {
    /** com.mojang.blaze3d.platform.Lighting constants (normalized). */
    public static final Vector3f DIFFUSE_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    public static final Vector3f DIFFUSE_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();

    private EntityLighting() {
    }

    /**
     * Raw camera-space pair, exactly what {@code Lighting.setupLevel} installs
     * for world entity geometry (the shader dots these against
     * ModelView-transformed normals, and our view matrix applies the same
     * rotation).
     *
     * @return flat {@code [l0x, l0y, l0z, l1x, l1y, l1z]}
     */
    public static float[] rawLights() {
        return new float[]{DIFFUSE_0.x, DIFFUSE_0.y, DIFFUSE_0.z, DIFFUSE_1.x, DIFFUSE_1.y, DIFFUSE_1.z};
    }

    /**
     * Light directions for GUI item icons: the exact matrix from
     * {@code GlStateManager.setupGui3DDiffuseLighting} applied to the raw
     * pair. Vanilla installs these right before rendering any screen.
     *
     * @return flat {@code [l0x, l0y, l0z, l1x, l1y, l1z]}
     */
    public static float[] gui3DLights() {
        Matrix4f matrix = new Matrix4f()
                .scaling(1.0F, -1.0F, 1.0F)
                .rotateYXZ(1.0821041F, 3.2375858F, 0.0F)
                .rotateYXZ((float) (-Math.PI / 8), (float) (Math.PI * 3.0 / 4.0), 0.0F);
        Vector3f l0 = matrix.transformDirection(new Vector3f(DIFFUSE_0));
        Vector3f l1 = matrix.transformDirection(new Vector3f(DIFFUSE_1));
        return new float[]{l0.x, l0.y, l0.z, l1.x, l1.y, l1.z};
    }
}

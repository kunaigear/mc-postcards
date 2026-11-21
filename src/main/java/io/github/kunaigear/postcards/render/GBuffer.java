package io.github.kunaigear.postcards.render;

import io.github.kunaigear.postcards.obscura.sore.Uniforms;
import io.github.kunaigear.postcards.obscura.sore.shader.FragmentIn;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Surface data recorded while Camera Obscura draws the frame, for the "realistic" shader: what each pixel shows
 * without Camera Obscura's lighting (texture colour, normal, sky and block light, ambient occlusion), so the shader
 * can light it again from scratch.
 * <p>
 * One sample per output pixel is kept. For opaque surfaces it's the sample nearest the centre of the pixel's
 * supersampled block that shows one ({@link #opaqueSample}), so a thin edge isn't lost when the centre sample misses
 * it; translucent surfaces use the centre sample. The frontmost opaque surface and the frontmost translucent one
 * (water, glass) are kept separately.
 * Only active on the render thread while {@link #active} is set.
 */
public final class GBuffer {
    private static @Nullable GBuffer active;

    final int width, height, scale, fbWidth;
    /** Index ({@code row * scale + column} in the framebuffer's orientation) of the centre sample of a block. */
    final int centre;
    /** Order of preference of each sample in a block: nearest the centre first. */
    private final int[] rank;

    /** Opaque: texture colour times tint (sRGB, alpha unused), normal (packed), lights (packed), Camera Obscura's light. */
    final int[] albedo, normal, light;
    final float[] coLight;
    /** Which sample of the block the opaque surface was recorded at. */
    final byte[] opaqueSample;
    /** Translucent: colour with alpha, normal, lights, distance from the camera (infinite = none). */
    final int[] tAlbedo, tNormal, tLight;
    final float[] tDist;

    GBuffer(int width, int height, int scale, int fbWidth) {
        this.width = width;
        this.height = height;
        this.scale = scale;
        this.fbWidth = fbWidth;
        int half = scale / 2;
        this.centre = half * scale + half;
        rank = new int[scale * scale];
        for (int i = 0; i < rank.length; i++) {
            int dx = i % scale - half, dy = i / scale - half;
            rank[i] = (dx * dx + dy * dy) * rank.length + i;
        }
        int n = width * height;
        albedo = new int[n];
        normal = new int[n];
        light = new int[n];
        coLight = new float[n];
        opaqueSample = new byte[n];
        tAlbedo = new int[n];
        tNormal = new int[n];
        tLight = new int[n];
        tDist = new float[n];
        Arrays.fill(coLight, -1); // -1 = no opaque surface
        Arrays.fill(tDist, Float.POSITIVE_INFINITY);
    }

    public static @Nullable GBuffer active() {
        return RenderFlags.rendering() ? active : null;
    }

    void activate() {
        active = this;
    }

    static void deactivate() {
        active = null;
    }

    boolean hasOpaque(int i) {
        return coLight[i] >= 0;
    }

    /**
     * Called for every fragment that passed the depth test, with the colour Camera Obscura's terrain shader
     * returned for it. {@code idx} is the framebuffer index (rows bottom-up).
     */
    public void record(int idx, FragmentIn in, Uniforms uniforms, int src) {
        int fx = idx % fbWidth, fy = idx / fbWidth;
        int ox = fx / scale, oy = height - 1 - fy / scale;
        if (ox >= width || oy < 0) return;
        int i = oy * width + ox;
        int sample = (fy % scale) * scale + fx % scale;

        int alpha = src >>> 24;
        if (alpha == 0) return; // cut-out part of a texture: nothing is drawn
        if (alpha == 255) {
            // A surface at a sample further from the centre than the one already kept doesn't replace it.
            if (hasOpaque(i) && sample != opaqueSample[i] && rank[sample] > rank[opaqueSample[i]]) return;
        } else if (sample != centre) {
            return;
        }

        int tex = in.texel;
        int tint = in.tint;
        int r = ((tex >> 16) & 255) * ARGB.red(tint) / 255;
        int g = ((tex >> 8) & 255) * ARGB.green(tint) / 255;
        int b = (tex & 255) * ARGB.blue(tint) / 255;
        int packedNormal = packNormal(in.normalX, in.normalY, in.normalZ);
        int packedLight = unit8(in.skyLight) | unit8(in.blockLight) << 8 | unit8(in.ao) << 16;
        double dx = in.worldX - uniforms.cameraPos.x, dy = in.worldY - uniforms.cameraPos.y, dz = in.worldZ - uniforms.cameraPos.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (alpha == 255) {
            albedo[i] = 0xFF000000 | r << 16 | g << 8 | b;
            normal[i] = packedNormal;
            light[i] = packedLight;
            coLight[i] = (float) matteLight(in, uniforms);
            opaqueSample[i] = (byte) sample;
            // An opaque surface drawn later in front of a translucent one (entities are drawn late) hides it.
            if (sample == centre && dist < tDist[i]) tDist[i] = Float.POSITIVE_INFINITY;
        } else if (dist < tDist[i]) {
            tAlbedo[i] = alpha << 24 | r << 16 | g << 8 | b;
            tNormal[i] = packedNormal;
            tLight[i] = packedLight;
            tDist[i] = dist;
        }
    }

    /** The light factor Camera Obscura's MatteShader multiplies the texture with (same formula). */
    private static double matteLight(FragmentIn in, Uniforms uniforms) {
        double nx = Math.abs(in.localNormalX), ny = in.localNormalY, nz = Math.abs(in.localNormalZ);
        double faceLight = 0.9f;
        if (in.shade) faceLight = ny > 0.5 ? 0.9f : ny < -0.5 ? 0.6f : nz > nx ? 0.8f : 0.7f;
        double sky = Math.max(uniforms.skyLight * in.skyLight, 0);
        double ambient = Math.max(Math.min(1, in.blockLight + sky), 0.1);
        return Math.max(0, (ambient - Math.min(1, 0.8f - 0.9f * in.ao)) * faceLight);
    }

    private static int unit8(double v) {
        return (int) Math.round(Math.clamp(v, 0, 1) * 255);
    }

    private static int packNormal(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) return packNormal(0, 1, 0);
        return comp(x / len) | comp(y / len) << 10 | comp(z / len) << 20;
    }

    private static int comp(double v) {
        return (int) Math.round((v * 0.5 + 0.5) * 1023);
    }

    static double nx(int p) { return (p & 1023) / 1023.0 * 2 - 1; }
    static double ny(int p) { return ((p >> 10) & 1023) / 1023.0 * 2 - 1; }
    static double nz(int p) { return ((p >> 20) & 1023) / 1023.0 * 2 - 1; }
    static double sky(int p) { return (p & 255) / 255.0; }
    static double block(int p) { return ((p >> 8) & 255) / 255.0; }
    static double ao(int p) { return ((p >> 16) & 255) / 255.0; }
}

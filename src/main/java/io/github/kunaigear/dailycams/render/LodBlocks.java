package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import de.tomalbrc.cameraobscura.model.resource.RPModel;
import de.tomalbrc.cameraobscura.model.triangle.ModelTesselator;
import de.tomalbrc.cameraobscura.sore.Texture;
import de.tomalbrc.cameraobscura.sore.model.Mesh;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block models baked for distant terrain, the way Voxy does it: each block state's model (the same one Camera
 * Obscura draws up close) is drawn straight on from each of the six directions into a small 16x16 picture, with
 * depth and transparency. A ray that enters a block through a face looks that picture up, so distant blocks have
 * their real textures, overlays (like the grass on a grass block's side) and shapes (plants, slabs, fences are partly
 * see-through). Each picture is kept in five sizes (16x16 down to 1x1) so far-away faces average their texture
 * instead of flickering. Tinted parts (grass, leaves) are kept apart, to be coloured by the biome later.
 */
final class LodBlocks {
    static final int RES = 16, LEVELS = 5;
    /** Faces in the order +X, -X, +Y, -Y, +Z, -Z (the side the block is seen from). */
    static final int[][] NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[] LEVEL_OFFSET = {0, 256, 256 + 64, 256 + 64 + 16, 256 + 64 + 16 + 4};
    private static final int TEXELS = 256 + 64 + 16 + 4 + 1;
    /** Per texel: untinted r, g, b (linear, premultiplied), tinted r, g, b, coverage. */
    private static final int STRIDE = 7;

    enum Tint { NONE, GRASS, FOLIAGE, DRY_FOLIAGE, FIXED }

    static final class Baked {
        final float[][] faces = new float[6][];
        /** Per face and texel (see {@link #relief}): height slope along a and b, and the height. */
        final float[][] relief = new float[6][];
        Tint tint = Tint.NONE;
        int fixedTint = -1;
        boolean empty = true;
    }

    private static final Map<BlockState, Baked> CACHE = new ConcurrentHashMap<>();

    private static final Set<Block> GRASS = Set.of(Blocks.GRASS_BLOCK, Blocks.BUSH, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.POTTED_FERN, Blocks.PINK_PETALS);
    private static final Set<Block> FOLIAGE = Set.of(Blocks.SUGAR_CANE, Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES,
            Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.VINE, Blocks.MANGROVE_LEAVES);
    private static final Set<Block> DRY = Set.of(Blocks.SHORT_DRY_GRASS, Blocks.TALL_DRY_GRASS, Blocks.DEAD_BUSH, Blocks.LEAF_LITTER);

    private LodBlocks() {}

    static Baked get(BlockState state, BlockGetter getter) {
        Baked b = CACHE.get(state);
        if (b == null) {
            b = bake(state, getter);
            CACHE.put(state, b);
        }
        return b;
    }

    /** The biome colour for a block's tinted parts, as Camera Obscura colours them up close (sRGB). */
    static int tintColor(Baked b, Biome biome, int x, int z) {
        return switch (b.tint) {
            case GRASS -> biome.getGrassColor(x, z);
            case FOLIAGE -> biome.getFoliageColor();
            case DRY_FOLIAGE -> biome.getDryFoliageColor();
            case FIXED -> b.fixedTint;
            case NONE -> 0xFFFFFF;
        };
    }

    /** Coverage (0-1) of a face at (a, b) in the full-resolution picture: whether a ray stops here. */
    static float coverage(Baked b, int face, double a, double c) {
        float[] f = b.faces[face];
        if (f == null) return 0;
        int i = Math.min(RES - 1, (int) (c * RES)) * RES + Math.min(RES - 1, (int) (a * RES));
        return f[i * STRIDE + 6];
    }

    /**
     * Linear colour of a face at (a, b), with a picture size chosen from {@code mip} (0 = 16x16, 4 = 1x1, fractions
     * blend two sizes). {@code tint} is the linear biome colour for tinted parts.
     */
    static void sample(Baked b, int face, double a, double c, double mip, double[] tint, double[] out) {
        float[] f = b.faces[face];
        out[0] = out[1] = out[2] = 0;
        if (f == null) return;
        mip = Math.clamp(mip, 0, LEVELS - 1);
        int l0 = (int) mip, l1 = Math.min(LEVELS - 1, l0 + 1);
        double w1 = mip - l0;
        accumulate(f, l0, a, c, tint, 1 - w1, out);
        if (w1 > 0) accumulate(f, l1, a, c, tint, w1, out);
    }

    private static void accumulate(float[] f, int level, double a, double c, double[] tint, double weight, double[] out) {
        int res = RES >> level;
        int i = (LEVEL_OFFSET[level] + Math.min(res - 1, (int) (c * res)) * res + Math.min(res - 1, (int) (a * res))) * STRIDE;
        double cov = f[i + 6];
        if (cov <= 1e-4) return;
        // Premultiplied average; divide by coverage so partly covered texels keep their full colour.
        double k = weight / cov;
        out[0] += (f[i] + f[i + 3] * tint[0]) * k;
        out[1] += (f[i + 1] + f[i + 4] * tint[1]) * k;
        out[2] += (f[i + 2] + f[i + 5] * tint[2]) * k;
    }

    private static Baked bake(BlockState state, BlockGetter getter) {
        Baked baked = new Baked();
        Block block = state.getBlock();
        if (GRASS.contains(block)) baked.tint = Tint.GRASS;
        else if (FOLIAGE.contains(block)) baked.tint = Tint.FOLIAGE;
        else if (DRY.contains(block)) baked.tint = Tint.DRY_FOLIAGE;
        else if (block == Blocks.SPRUCE_LEAVES) { baked.tint = Tint.FIXED; baked.fixedTint = 0x619961; }
        else if (block == Blocks.BIRCH_LEAVES) { baked.tint = Tint.FIXED; baked.fixedTint = 0x80A755; }
        else if (block == Blocks.LILY_PAD) { baked.tint = Tint.FIXED; baked.fixedTint = 0x208030; }
        if (state.isAir() || block == Blocks.BARRIER || block == Blocks.STRUCTURE_VOID || block == Blocks.LIGHT) return baked;

        List<RPModel.View> views;
        try {
            views = ChunkMeshCacheInvoker.dailycams$getModelViewForState(getter, 0, 64, 0, state);
        } catch (Exception e) {
            views = null;
        }
        if (views == null || views.isEmpty()) return baked;
        float[][] color = new float[6][RES * RES * 4];
        byte[][] tinted = new byte[6][RES * RES];
        float[][] depth = new float[6][RES * RES];
        for (float[] d : depth) java.util.Arrays.fill(d, Float.NEGATIVE_INFINITY);
        for (RPModel.View view : views) {
            Mesh mesh;
            try {
                mesh = new ModelTesselator(view, EnumSet.noneOf(Direction.class)).build();
            } catch (Exception e) {
                continue;
            }
            if (mesh == null) continue;
            for (int face = 0; face < 6; face++) rasterize(mesh, face, color[face], tinted[face], depth[face]);
        }
        for (int face = 0; face < 6; face++) {
            float[] data = new float[TEXELS * STRIDE];
            boolean any = false;
            for (int i = 0; i < RES * RES; i++) {
                float a = color[face][i * 4 + 3];
                if (a <= 0) continue;
                any = true;
                int o = i * STRIDE + (tinted[face][i] != 0 ? 3 : 0);
                data[o] = (float) (RealisticShader.toLinear(color[face][i * 4]) * a);
                data[o + 1] = (float) (RealisticShader.toLinear(color[face][i * 4 + 1]) * a);
                data[o + 2] = (float) (RealisticShader.toLinear(color[face][i * 4 + 2]) * a);
                data[i * STRIDE + 6] = a;
            }
            if (!any) continue;
            baked.relief[face] = relief(depth[face], color[face], NORMALS[face][0] + NORMALS[face][1] + NORMALS[face][2]);
            buildMips(data);
            baked.faces[face] = data;
            baked.empty = false;
        }
        return baked;
    }

    /** Orthographic view of the model from one side into the 16x16 picture, nearest surface wins. */
    private static void rasterize(Mesh mesh, int face, float[] color, byte[] tinted, float[] depth) {
        int[] n = NORMALS[face];
        // Picture axes (a, b) and depth for each side: X faces use (z, y), Y faces (x, z), Z faces (x, y).
        int ax = n[0] != 0 ? 2 : 0, bx = n[1] != 0 ? 2 : 1, dx = n[0] != 0 ? 0 : n[1] != 0 ? 1 : 2;
        int sign = n[0] + n[1] + n[2];
        float[] p = mesh.positions, uv = mesh.uvs;
        int[] idx = mesh.indices;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int i0 = idx[t], i1 = idx[t + 1], i2 = idx[t + 2];
            double a0 = p[i0 * 3 + ax], b0 = p[i0 * 3 + bx], d0 = p[i0 * 3 + dx] * sign;
            double a1 = p[i1 * 3 + ax], b1 = p[i1 * 3 + bx], d1 = p[i1 * 3 + dx] * sign;
            double a2 = p[i2 * 3 + ax], b2 = p[i2 * 3 + bx], d2 = p[i2 * 3 + dx] * sign;
            double area = (a1 - a0) * (b2 - b0) - (a2 - a0) * (b1 - b0);
            if (Math.abs(area) < 1e-9) continue; // seen edge-on from this side
            Texture tex = mesh.textureForVertex(i0);
            if (tex == null) tex = Texture.DEFAULT_TEXTURE;
            boolean tint = mesh.tintIndices != null && mesh.tintIndices[i0] >= 0;
            int minA = Math.max(0, (int) Math.floor(Math.min(a0, Math.min(a1, a2)) * RES - 1));
            int maxA = Math.min(RES - 1, (int) Math.ceil(Math.max(a0, Math.max(a1, a2)) * RES));
            int minB = Math.max(0, (int) Math.floor(Math.min(b0, Math.min(b1, b2)) * RES - 1));
            int maxB = Math.min(RES - 1, (int) Math.ceil(Math.max(b0, Math.max(b1, b2)) * RES));
            for (int y = minB; y <= maxB; y++) {
                for (int x = minA; x <= maxA; x++) {
                    double pa = (x + 0.5) / RES, pb = (y + 0.5) / RES;
                    double w1 = ((pa - a0) * (b2 - b0) - (a2 - a0) * (pb - b0)) / area;
                    double w2 = ((a1 - a0) * (pb - b0) - (pa - a0) * (b1 - b0)) / area;
                    double w0 = 1 - w1 - w2;
                    if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                    double dd = w0 * d0 + w1 * d1 + w2 * d2;
                    int k = y * RES + x;
                    if (dd < depth[k] - 1e-4) continue;
                    double u = w0 * uv[i0 * 2] + w1 * uv[i1 * 2] + w2 * uv[i2 * 2];
                    double v = w0 * uv[i0 * 2 + 1] + w1 * uv[i1 * 2 + 1] + w2 * uv[i2 * 2 + 1];
                    int argb = tex.sample(u, v);
                    int alpha = argb >>> 24;
                    if (alpha < 16) continue; // cut-out pixel: see through to what's behind
                    depth[k] = (float) dd;
                    color[k * 4] = ((argb >> 16) & 255) / 255f;
                    color[k * 4 + 1] = ((argb >> 8) & 255) / 255f;
                    color[k * 4 + 2] = (argb & 255) / 255f;
                    color[k * 4 + 3] = alpha / 255f;
                    tinted[k] = (byte) (tint ? 1 : 0);
                }
            }
        }
    }

    /**
     * A height map of a face for lighting leaves up close as if each cluster of leaves were a small bump: the face
     * itself is high, the gaps through it (where the far side shows) are low, and darker texels a little lower.
     * Smoothed so the clusters look rounded; it wraps around because leaf textures tile.
     */
    private static float[] relief(float[] depth, float[] color, int sign) {
        float[] height = new float[RES * RES];
        for (int i = 0; i < RES * RES; i++) {
            if (depth[i] == Float.NEGATIVE_INFINITY) continue;
            // Depth runs from 0 (the far side of the block) to 1 (the side it's seen from).
            double d = Math.clamp(depth[i] + (sign < 0 ? 1 : 0), 0, 1);
            double luma = 0.2126 * color[i * 4] + 0.7152 * color[i * 4 + 1] + 0.0722 * color[i * 4 + 2];
            height[i] = (float) (0.7 * d + 0.3 * luma);
        }
        float[] smooth = blur3(blur3(height));
        float[] out = new float[RES * RES * 3];
        for (int y = 0; y < RES; y++) {
            for (int x = 0; x < RES; x++) {
                int i = y * RES + x;
                out[i * 3] = (smooth[y * RES + (x + 1) % RES] - smooth[y * RES + (x + RES - 1) % RES]) * 0.5f;
                out[i * 3 + 1] = (smooth[((y + 1) % RES) * RES + x] - smooth[((y + RES - 1) % RES) * RES + x]) * 0.5f;
                out[i * 3 + 2] = smooth[i];
            }
        }
        return out;
    }

    private static float[] blur3(float[] in) {
        float[] out = new float[RES * RES];
        for (int y = 0; y < RES; y++) {
            for (int x = 0; x < RES; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) sum += in[((y + dy + RES) % RES) * RES + (x + dx + RES) % RES];
                }
                out[y * RES + x] = sum / 9;
            }
        }
        return out;
    }

    /** Averages the 16x16 picture down to 8x8, 4x4, 2x2 and 1x1 (premultiplied, so coverage averages too). */
    private static void buildMips(float[] d) {
        for (int level = 1; level < LEVELS; level++) {
            int res = RES >> level, prevRes = res * 2;
            for (int y = 0; y < res; y++) {
                for (int x = 0; x < res; x++) {
                    int o = (LEVEL_OFFSET[level] + y * res + x) * STRIDE;
                    for (int c = 0; c < STRIDE; c++) {
                        float s = 0;
                        for (int yy = 0; yy < 2; yy++) {
                            for (int xx = 0; xx < 2; xx++) {
                                s += d[(LEVEL_OFFSET[level - 1] + (y * 2 + yy) * prevRes + x * 2 + xx) * STRIDE + c];
                            }
                        }
                        d[o + c] = s / 4;
                    }
                }
            }
        }
    }
}

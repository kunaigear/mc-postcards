package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.sore.rasterizer.Framebuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * A small "shader pack" applied after Camera Obscura has drawn the frame. It uses the frame's depth buffer to
 * find each pixel's position in the world, then:
 * <ul>
 *   <li>casts a ray towards the sun through the world's blocks for real shadows,</li>
 *   <li>tints sunlit surfaces warm and shadows cool,</li>
 *   <li>adds light aerial haze, bloom on bright areas, a colour grade and a vignette.</li>
 * </ul>
 * Runs on the render thread and yields through {@link Throttle} like the rest of the render.
 */
final class SimpleShader {
    private SimpleShader() {}

    /** How far a shadow ray travels before giving up (blocks). */
    private static final int MAX_SHADOW_STEPS = 160;

    record Frame(Framebuffer framebuffer, int scale, Matrix4d inverseViewProjection, Vector3d camera,
                 double fogStart, double fogEnd, int fogColor) {}

    static void apply(BufferedImage image, Frame f, Level level, DailyCamsConfig.ShaderSettings st) {
        int w = image.getWidth(), h = image.getHeight();
        int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double[] depth = f.framebuffer().getDepthBuffer();
        int fbW = f.framebuffer().width, fbH = f.framebuffer().height, scale = f.scale();

        // Sun direction and daylight, computed the same way Camera Obscura lights its sky.
        Vec3 camPos = new Vec3(f.camera().x, f.camera().y, f.camera().z);
        double sunAngle = level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, camPos) * Mth.DEG_TO_RAD;
        double daylight = Math.clamp(level.environmentAttributes().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, camPos), 0, 1);
        double sx = -Math.sin(sunAngle), sy = Math.cos(sunAngle), sz = 0;
        // Low sun: fade shadows out instead of stretching them across the whole picture.
        double sunUp = Math.clamp((sy - 0.05) / 0.25, 0, 1) * daylight;

        ChunkMeshCache.FastBlockGetter blocks = new ChunkMeshCache.FastBlockGetter(level);
        var skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
        int maxY = level.getMaxY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        double fogR = ((f.fogColor() >> 16) & 255) / 255.0, fogG = ((f.fogColor() >> 8) & 255) / 255.0, fogB = (f.fogColor() & 255) / 255.0;
        Vector3d world = new Vector3d();

        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            // The framebuffer is stored bottom-up; sample the centre of each supersampled block.
            int fbY = (h - 1 - y) * scale + scale / 2;
            double ndcY = (fbY + 0.5) / fbH * 2 - 1;
            for (int x = 0; x < w; x++) {
                int fbX = x * scale + scale / 2;
                double d = depth[fbY * fbW + fbX];
                if (Double.isInfinite(d)) continue; // sky

                world.set((fbX + 0.5) / fbW * 2 - 1, ndcY, d);
                f.inverseViewProjection().transformProject(world);
                double dx = f.camera().x - world.x, dy = f.camera().y - world.y, dz = f.camera().z - world.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double fog = Math.clamp((f.fogEnd() - dist) / (f.fogEnd() - f.fogStart()), 0, 1);
                if (fog <= 0.02) continue; // hidden in fog anyway

                // Step slightly towards the camera so we start in the air in front of the visible face.
                double inv = 0.03 / Math.max(dist, 1e-6);
                double ox = world.x + dx * inv, oy = world.y + dy * inv, oz = world.z + dz * inv;

                int c = px[y * w + x];
                double r = ((c >> 16) & 255) / 255.0, g = ((c >> 8) & 255) / 255.0, b = (c & 255) / 255.0;

                if (sunUp > 0) {
                    cursor.set(Mth.floor(ox), Mth.floor(oy), Mth.floor(oz));
                    double sky = skyLight.getLightValue(cursor) / 15.0;
                    double weight = sky * sunUp * fog;
                    if (weight > 0) {
                        if (inShadow(blocks, cursor, ox, oy, oz, sx, sy, sz, maxY)) {
                            double s = st.shadowStrength * weight;
                            r *= 1 - s;
                            g *= 1 - s * 0.92;
                            b *= 1 - s * 0.72; // shadows lean blue (skylight)
                        } else {
                            double warm = st.sunWarmth * weight;
                            r *= 1 + warm;
                            g *= 1 + warm * 0.55;
                            b *= 1 - warm * 0.25;
                        }
                    }
                }

                // Aerial perspective: distant terrain picks up a bit of the sky's colour.
                double haze = st.haze * (1 - Math.exp(-dist / 120.0));
                r += (fogR - r) * haze;
                g += (fogG - g) * haze;
                b += (fogB - b) * haze;

                px[y * w + x] = rgb(r, g, b);
            }
        }

        if (st.bloom > 0) bloom(px, w, h, st.bloom);
        grade(px, w, h, st);
    }

    /** Amanatides-Woo voxel walk towards the sun. True if an opaque block (or leaves) is in the way. */
    private static boolean inShadow(ChunkMeshCache.FastBlockGetter blocks, BlockPos.MutableBlockPos cursor,
                                    double ox, double oy, double oz, double dx, double dy, double dz, int maxY) {
        int x = Mth.floor(ox), y = Mth.floor(oy), z = Mth.floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? x + 1 - ox : ox - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? y + 1 - oy : oy - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? z + 1 - oz : oz - z) * tDeltaZ);

        for (int i = 0; i < MAX_SHADOW_STEPS; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (y > maxY) return false;
            BlockState state = blocks.getBlockState(cursor.set(x, y, z));
            if (state.isAir()) continue;
            if (state.canOcclude() || state.getBlock() instanceof LeavesBlock) return true;
        }
        return false;
    }

    /** Soft glow around bright pixels (sky near the sun, light sources, bright sand and snow). */
    private static void bloom(int[] px, int w, int h, double amount) {
        int q = 4, bw = Math.max(1, w / q), bh = Math.max(1, h / q);
        float[] r = new float[bw * bh], g = new float[bw * bh], b = new float[bw * bh];
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int c = px[Math.min(h - 1, y * q + q / 2) * w + Math.min(w - 1, x * q + q / 2)];
                float cr = ((c >> 16) & 255) / 255f, cg = ((c >> 8) & 255) / 255f, cb = (c & 255) / 255f;
                float lum = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
                float k = Math.max(0, (lum - 0.72f) / 0.28f);
                int i = y * bw + x;
                r[i] = cr * k;
                g[i] = cg * k;
                b[i] = cb * k;
            }
        }
        for (int pass = 0; pass < 3; pass++) {
            Throttle.checkpoint();
            blur(r, bw, bh, 3);
            blur(g, bw, bh, 3);
            blur(b, bw, bh, 3);
        }
        for (int y = 0; y < h; y++) {
            int by = Math.min(bh - 1, y / q);
            for (int x = 0; x < w; x++) {
                int i = by * bw + Math.min(bw - 1, x / q);
                int c = px[y * w + x];
                px[y * w + x] = rgb(((c >> 16) & 255) / 255.0 + r[i] * amount,
                        ((c >> 8) & 255) / 255.0 + g[i] * amount,
                        (c & 255) / 255.0 + b[i] * amount);
            }
        }
    }

    private static void blur(float[] a, int w, int h, int radius) {
        float[] tmp = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx >= 0 && xx < w) { sum += a[y * w + xx]; n++; }
                }
                tmp[y * w + x] = sum / n;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy >= 0 && yy < h) { sum += tmp[yy * w + x]; n++; }
                }
                a[y * w + x] = sum / n;
            }
        }
    }

    /** Contrast, saturation and a vignette. */
    private static void grade(int[] px, int w, int h, DailyCamsConfig.ShaderSettings st) {
        double cx = w / 2.0, cy = h / 2.0, maxR = Math.sqrt(cx * cx + cy * cy);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                double r = ((c >> 16) & 255) / 255.0, g = ((c >> 8) & 255) / 255.0, b = (c & 255) / 255.0;
                double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                r = lum + (r - lum) * st.saturation;
                g = lum + (g - lum) * st.saturation;
                b = lum + (b - lum) * st.saturation;
                r = (r - 0.5) * st.contrast + 0.5;
                g = (g - 0.5) * st.contrast + 0.5;
                b = (b - 0.5) * st.contrast + 0.5;
                double dx = (x - cx) / maxR, dy = (y - cy) / maxR;
                double v = 1 - st.vignette * Math.pow(Math.min(1, Math.sqrt(dx * dx + dy * dy)), 2.2);
                px[y * w + x] = rgb(r * v, g * v, b * v);
            }
        }
    }

    private static int rgb(double r, double g, double b) {
        int ir = (int) Math.round(Math.clamp(r, 0, 1) * 255);
        int ig = (int) Math.round(Math.clamp(g, 0, 1) * 255);
        int ib = (int) Math.round(Math.clamp(b, 0, 1) * 255);
        return 0xFF000000 | ir << 16 | ig << 8 | ib;
    }
}

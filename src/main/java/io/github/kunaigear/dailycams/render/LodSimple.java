package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Distant (LOD) terrain for the simple shader and for no shader: drawn where Camera Obscura shows sky, with plain
 * sun and sky lighting, sun shadows over the height map, and the same haze as the simple shader. Runs before the
 * simple shader, so its colour grade and bloom apply to the distant terrain too.
 */
final class LodSimple {
    private LodSimple() {}

    static void apply(BufferedImage image, SimpleShader.Frame f, LodTerrain lod, Level level,
                      DailyCamsConfig.ShaderSettings st, double lodFar) {
        int w = image.getWidth(), h = image.getHeight();
        int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double[] depth = f.framebuffer().getDepthBuffer();
        int[] color = f.framebuffer().getColorBuffer();
        int fbW = f.framebuffer().width, fbH = f.framebuffer().height, scale = f.scale(), samples = scale * scale;
        Vector3d cam = f.camera();
        double start = f.fogEnd() * 0.75;

        Vec3 camPos = new Vec3(cam.x, cam.y, cam.z);
        double sunAngle = level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, camPos) * Mth.DEG_TO_RAD;
        double daylight = Math.clamp(level.environmentAttributes().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, camPos), 0, 1);
        double sx = -Math.sin(sunAngle), sy = Math.cos(sunAngle);
        double sunUp = Math.clamp((sy - 0.02) / 0.2, 0, 1) * daylight;
        double fogR = ((f.fogColor() >> 16) & 255) / 255.0, fogG = ((f.fogColor() >> 8) & 255) / 255.0, fogB = (f.fogColor() & 255) / 255.0;

        LodTerrain.Hit hit = new LodTerrain.Hit(), shadowHit = new LodTerrain.Hit();
        Vector3d tmp = new Vector3d();
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            int fbYBase = (h - 1 - y) * scale;
            for (int x = 0; x < w; x++) {
                // Sky samples in this pixel's supersampled block, and their colour (to be replaced).
                int bg = 0;
                double br = 0, bgc = 0, bb = 0;
                for (int sy2 = 0; sy2 < scale; sy2++) {
                    int row = (fbYBase + sy2) * fbW + x * scale;
                    for (int sx2 = 0; sx2 < scale; sx2++) {
                        if (!Double.isInfinite(depth[row + sx2])) continue;
                        int c = color[row + sx2];
                        br += (c >> 16) & 255; bgc += (c >> 8) & 255; bb += c & 255;
                        bg++;
                    }
                }
                if (bg == 0) continue;
                int fbX = x * scale + scale / 2, fbY = fbYBase + scale / 2;
                tmp.set((fbX + 0.5) / fbW * 2 - 1, (fbY + 0.5) / fbH * 2 - 1, 1.0);
                f.inverseViewProjection().transformProject(tmp);
                double dx = tmp.x - cam.x, dy = tmp.y - cam.y, dz = tmp.z - cam.z, dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= dl; dy /= dl; dz /= dl;
                if (!lod.trace(cam.x, cam.y, cam.z, dx, dy, dz, start, lodFar, hit)) continue;

                int c = lod.color[hit.index];
                double r = ((c >> 16) & 255) / 255.0, g = ((c >> 8) & 255) / 255.0, b = (c & 255) / 255.0;
                double t = hit.t;
                double hx = cam.x + dx * t, hy = cam.y + dy * t, hz = cam.z + dz * t;
                double ndl = Math.max(0, hit.nx * sx + hit.ny * sy);
                double lit = ndl > 0 && sunUp > 0 && !lod.trace(hx + hit.nx * 0.05, hy + hit.ny * 0.05 + 0.02, hz + hit.nz * 0.05,
                        sx, sy, 0, 0.02, 500, shadowHit) ? 1 : 0;
                // Roughly Camera Obscura's own shading: daylight with brighter tops, plus the sun.
                double light = (0.35 + 0.35 * Math.max(hit.ny, 0) + 0.15) * Math.max(daylight, 0.12) + 0.35 * ndl * lit * sunUp;
                r *= light; g *= light; b *= light;
                // The simple shader's haze, then fading out towards the end of the distant terrain.
                double haze = Math.min(1, st.haze * (1 - Math.exp(-t / 120)) + RealisticShader.smoothstep(lodFar * 0.6, lodFar * 0.99, t));
                r += (fogR - r) * haze; g += (fogG - g) * haze; b += (fogB - b) * haze;

                int i = y * w + x, p = px[i];
                double k = 1.0 / samples;
                double nr = (((p >> 16) & 255) * samples - br + r * 255 * bg) * k;
                double ng = (((p >> 8) & 255) * samples - bgc + g * 255 * bg) * k;
                double nb = ((p & 255) * samples - bb + b * 255 * bg) * k;
                px[i] = 0xFF000000 | to8(nr) << 16 | to8(ng) << 8 | to8(nb);
            }
        }
    }

    private static int to8(double v) {
        return (int) Math.round(Math.clamp(v, 0, 255));
    }
}

package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Distant (LOD) terrain for the simple shader and for no shader, drawn where Camera Obscura shows sky. Blocks get
 * their baked textures and the same lighting formula Camera Obscura uses up close (face shading, sky and block light,
 * corner shading), plus the simple shader's sun shadows, warm light and haze, so they match the near terrain. Traced
 * per supersample like the near terrain is drawn. Runs before the simple shader, so its grade and bloom apply too.
 */
final class LodSimple {
    private LodSimple() {}

    static void apply(BufferedImage image, SimpleShader.Frame f, LodTerrain lod, Level level,
                      DailyCamsConfig.ShaderSettings st, boolean shaderOn, double lodFar) {
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
        double sunUp = Math.clamp((sy - 0.05) / 0.25, 0, 1) * daylight;
        int fc = f.fogColor();
        double fogR = ((fc >> 16) & 255) / 255.0, fogG = ((fc >> 8) & 255) / 255.0, fogB = (fc & 255) / 255.0;

        LodTerrain.Hit hit = new LodTerrain.Hit(), shadowHit = new LodTerrain.Hit();
        Vector3d tmp = new Vector3d();
        double[] tint = new double[3], alb = new double[3];
        // Angle of one supersample, for choosing texture detail.
        tmp.set(0.5 / fbW * 2 - 1, 0, 1);
        Vector3d a0 = f.inverseViewProjection().transformProject(new Vector3d(tmp)).sub(cam).normalize();
        Vector3d a1 = f.inverseViewProjection().transformProject(new Vector3d(1.5 / fbW * 2 - 1, 0, 1)).sub(cam).normalize();
        double sampleAngle = Math.acos(Math.clamp(a0.dot(a1), -1, 1));

        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            int fbYBase = (h - 1 - y) * scale;
            for (int x = 0; x < w; x++) {
                double nr = 0, ng = 0, nb = 0;
                int replaced = 0;
                double oldR = 0, oldG = 0, oldB = 0;
                for (int sy2 = 0; sy2 < scale; sy2++) {
                    int row = (fbYBase + sy2) * fbW + x * scale;
                    for (int sx2 = 0; sx2 < scale; sx2++) {
                        if (!Double.isInfinite(depth[row + sx2])) continue;
                        tmp.set((x * scale + sx2 + 0.5) / fbW * 2 - 1, (fbYBase + sy2 + 0.5) / fbH * 2 - 1, 1.0);
                        f.inverseViewProjection().transformProject(tmp);
                        double dx = tmp.x - cam.x, dy = tmp.y - cam.y, dz = tmp.z - cam.z, dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        dx /= dl; dy /= dl; dz /= dl;
                        if (dy > 0 && cam.y > lod.maxTop + 1) continue;
                        if (!lod.trace(cam.x, cam.y, cam.z, dx, dy, dz, start, lodFar, hit)) continue;
                        double[] c = shade(lod, hit, shadowHit, cam, dx, dy, dz, sampleAngle, daylight, sunUp, sx, sy,
                                st, shaderOn, tint, alb);
                        double dist = hit.water ? hit.waterT : hit.t;
                        // The simple shader's haze, and the end of the distant terrain fading into the sky colour.
                        double haze = shaderOn ? st.haze * (1 - Math.exp(-dist / 120.0)) : 0;
                        haze = Math.min(1, haze + RealisticShader.smoothstep(lodFar * 0.55, lodFar * 0.99, dist));
                        nr += c[0] + (fogR - c[0]) * haze;
                        ng += c[1] + (fogG - c[1]) * haze;
                        nb += c[2] + (fogB - c[2]) * haze;
                        int old = color[row + sx2];
                        oldR += ((old >> 16) & 255) / 255.0; oldG += ((old >> 8) & 255) / 255.0; oldB += (old & 255) / 255.0;
                        replaced++;
                    }
                }
                if (replaced == 0) continue;
                int i = y * w + x, p = px[i];
                double k = 1.0 / samples;
                double r = ((p >> 16) & 255) / 255.0 + (nr - oldR) * k;
                double g = ((p >> 8) & 255) / 255.0 + (ng - oldG) * k;
                double b = (p & 255) / 255.0 + (nb - oldB) * k;
                px[i] = 0xFF000000 | to8(r) << 16 | to8(g) << 8 | to8(b);
            }
        }
    }

    /** sRGB colour of a distant block, lit like Camera Obscura lights blocks up close (plus water over it). */
    private static double[] shade(LodTerrain lod, LodTerrain.Hit hit, LodTerrain.Hit shadowHit, Vector3d cam,
                                  double dx, double dy, double dz, double sampleAngle, double daylight, double sunUp,
                                  double sx, double sy, DailyCamsConfig.ShaderSettings st, boolean shaderOn,
                                  double[] tint, double[] alb) {
        double r = 0, g = 0, b = 0;
        if (!hit.deep) {
            Biome biome = lod.biomeAt(hit.x, hit.y, hit.z);
            int tc = biome != null ? LodBlocks.tintColor(hit.baked, biome, hit.x, hit.z) : 0xFFFFFF;
            tint[0] = RealisticShader.toLinear(((tc >> 16) & 255) / 255.0);
            tint[1] = RealisticShader.toLinear(((tc >> 8) & 255) / 255.0);
            tint[2] = RealisticShader.toLinear((tc & 255) / 255.0);
            double cosI = Math.max(0.15, Math.abs(dx * hit.nx + dy * hit.ny + dz * hit.nz));
            double mip = Math.log(Math.max(1e-6, hit.t * sampleAngle * LodBlocks.RES / cosI)) / Math.log(2);
            LodBlocks.sample(hit.baked, hit.face, hit.a, hit.b, mip, tint, alb);
            r = RealisticShader.toSrgb(alb[0]); g = RealisticShader.toSrgb(alb[1]); b = RealisticShader.toSrgb(alb[2]);
            // Camera Obscura's MatteShader: face shading times sky/block light, darkened in corners.
            double face = hit.ny > 0.5 ? 0.9 : hit.ny < -0.5 ? 0.6 : hit.nz != 0 ? 0.8 : 0.7;
            double sky = lod.skyAt(hit.fx, hit.fy, hit.fz) / 15.0, block = lod.blockLightAt(hit.fx, hit.fy, hit.fz) / 15.0;
            double ambient = Math.max(Math.min(1, block + daylight * sky), 0.1);
            double light = Math.max(0, (ambient - Math.min(1, 0.8 - 0.9 * lod.ambientOcclusion(hit))) * face);
            r *= light; g *= light; b *= light;
            // The simple shader's sun shadows and warm sunlight.
            if (shaderOn && sunUp > 0 && sky > 0) {
                double hx = cam.x + dx * hit.t, hy = cam.y + dy * hit.t, hz = cam.z + dz * hit.t;
                boolean shadow = lod.trace(hx + hit.nx * 0.01, hy + hit.ny * 0.01, hz + hit.nz * 0.01, sx, sy, 0, 0.01, 400, shadowHit)
                        && !shadowHit.deep;
                double weight = sky * sunUp;
                if (shadow) {
                    double s = st.shadowStrength * weight;
                    r *= 1 - s; g *= 1 - s * 0.92; b *= 1 - s * 0.72;
                } else {
                    double warm = st.sunWarmth * weight;
                    r *= 1 + warm; g *= 1 + warm * 0.55; b *= 1 - warm * 0.25;
                }
            }
        }
        if (hit.water) {
            // Water as Camera Obscura draws it: the tinted water texture blended over what's beneath.
            Biome biome = lod.biomeAt(hit.waterX, hit.waterY, hit.waterZ);
            int wc = biome != null ? biome.getWaterColor() : 0x3F76E4;
            double sky = lod.skyAt(hit.waterX, hit.waterY + 1, hit.waterZ) / 15.0;
            double light = Math.max(daylight * sky, 0.1) * 0.9;
            double a = hit.deep ? 1 : 0.69;
            r = r * (1 - a) + ((wc >> 16) & 255) / 255.0 * 0.75 * light * a;
            g = g * (1 - a) + ((wc >> 8) & 255) / 255.0 * 0.75 * light * a;
            b = b * (1 - a) + (wc & 255) / 255.0 * 0.75 * light * a;
        }
        return new double[]{r, g, b};
    }

    private static int to8(double v) {
        return (int) Math.round(Math.clamp(v, 0, 1) * 255);
    }
}

package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The "realistic" shader: relights the frame from the {@link GBuffer} in high dynamic range, in the style of
 * modern shader packs:
 * <ul>
 *   <li>a physically based sky ({@link Atmosphere}) with sun, moon, stars and procedural clouds that change daily,</li>
 *   <li>sunlight coloured by the atmosphere, with soft voxel shadows, cloud shadows and light through leaves,</li>
 *   <li>sky light from the actual sky colour, warm block light and a light floor, so nothing is pitch black,</li>
 *   <li>water with sky reflections, sun glints and depth-based absorption,</li>
 *   <li>aerial perspective, automatic exposure, bloom and AgX filmic tone mapping.</li>
 * </ul>
 * Runs on the render thread and yields through {@link Throttle}.
 */
final class RealisticShader {
    private RealisticShader() {}

    private static final int MAX_SHADOW_STEPS = 200;
    private static final double CLOUD_ALTITUDE = 900;
    private static final double[] TORCH = {1.0, 0.56, 0.26};
    private static final double[] FLOOR_TINT = {0.86, 0.93, 1.1};
    /** How much of the sky's light reaches surfaces in shade. Below 1 for deeper, more contrasty shadows. */
    private static final double SHADE = 0.72;

    /**
     * {@code lod}: distant terrain beyond the render distance (or null), drawn up to {@code lodFar} blocks.
     */
    static void apply(BufferedImage image, SimpleShader.Frame f, GBuffer gb, Level level, DailyCamsConfig.RealisticSettings st,
                      @org.jetbrains.annotations.Nullable LodTerrain lod, double lodFar) {
        int w = image.getWidth(), h = image.getHeight(), n = w * h;
        int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double[] depth = f.framebuffer().getDepthBuffer();
        int fbW = f.framebuffer().width, fbH = f.framebuffer().height, scale = f.scale();
        int samples = scale * scale;
        Vector3d cam = f.camera();
        double renderDistance = f.fogEnd();

        // Sun, moon and weather, from the same values Minecraft uses for its own sky.
        Vec3 camPos = new Vec3(cam.x, cam.y, cam.z);
        var env = level.environmentAttributes();
        double sunAngle = env.getValue(EnvironmentAttributes.SUN_ANGLE, camPos) * Mth.DEG_TO_RAD;
        double moonAngle = env.getValue(EnvironmentAttributes.MOON_ANGLE, camPos) * Mth.DEG_TO_RAD;
        double starBrightness = env.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, camPos);
        int moonPhase = env.getValue(EnvironmentAttributes.MOON_PHASE, camPos).ordinal();
        double moonBrightness = 0.15 + 0.85 * (Math.cos(moonPhase * Math.PI / 4) * 0.5 + 0.5);
        double rain = level.getRainLevel(1), thunder = level.getThunderLevel(1);
        double overcast = Math.clamp(rain * 0.85 + thunder * 0.15, 0, 1);
        Atmosphere atm = new Atmosphere(-Math.sin(sunAngle), Math.cos(sunAngle), 0, -Math.sin(moonAngle), Math.cos(moonAngle), 0,
                moonBrightness, Math.clamp(cam.y - 63, 0, 3000) + 100, overcast);
        long clock = level.getDefaultClockTime(), day = clock / 24000;
        Sky sky = new Sky(atm, cam, st, overcast, starBrightness, day, clock % 24000);
        sky.dimensionLight = level.dimensionType().ambientLight();
        if (!level.dimensionType().hasSkyLight()) {
            // The Nether and the End have no sky to speak of: their background is the dimension's fog colour.
            int fc = f.fogColor();
            sky.flat = new double[]{toLinear(((fc >> 16) & 255) / 255.0) * 0.08, toLinear(((fc >> 8) & 255) / 255.0) * 0.08,
                    toLinear((fc & 255) / 255.0) * 0.08};
        }
        // Where the world fades into the sky: the end of the distant terrain if there is any.
        double fadeEnd = lod != null ? lodFar : renderDistance;
        double lodStart = renderDistance * 0.75;
        LodTerrain.Hit lodHit = new LodTerrain.Hit(), shadowHit = new LodTerrain.Hit();

        // The light that casts shadows: the sun by day, the moon by night.
        boolean sunLight = atm.sy > 0 || atm.sy > atm.my;
        double lx = sunLight ? atm.sx : atm.mx, ly = sunLight ? atm.sy : atm.my, lz = sunLight ? atm.sz : atm.mz;
        double[] lightIrr = sunLight ? atm.sunIrradiance : atm.moonIrradiance;
        double lightUp = Math.clamp(ly / 0.06, 0, 1);

        ChunkMeshCache.FastBlockGetter blocks = new ChunkMeshCache.FastBlockGetter(level);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int maxY = level.getMaxY();

        // Pass 1: positions and shadows for every opaque pixel.
        float[] dist = new float[n], shadow = new float[n], vdx = new float[n], vdy = new float[n], vdz = new float[n];
        Vector3d tmp = new Vector3d();
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                // The sample the G-buffer kept the opaque surface at (the centre one if there is none).
                int os = gb.hasOpaque(i) ? gb.opaqueSample[i] : gb.centre;
                int fbX = x * scale + os % scale, fbY = (h - 1 - y) * scale + os / scale;
                double ndcY = (fbY + 0.5) / fbH * 2 - 1;
                tmp.set((fbX + 0.5) / fbW * 2 - 1, ndcY, 1.0);
                f.inverseViewProjection().transformProject(tmp);
                double vx = tmp.x - cam.x, vy = tmp.y - cam.y, vz = tmp.z - cam.z, vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                vdx[i] = (float) (vx / vl); vdy[i] = (float) (vy / vl); vdz[i] = (float) (vz / vl);
                double d = depth[fbY * fbW + fbX];
                if (Double.isInfinite(d) || !gb.hasOpaque(i)) {
                    dist[i] = Float.POSITIVE_INFINITY;
                    continue;
                }
                tmp.set((fbX + 0.5) / fbW * 2 - 1, ndcY, d);
                f.inverseViewProjection().transformProject(tmp);
                double ox = tmp.x - cam.x, oy = tmp.y - cam.y, oz = tmp.z - cam.z;
                dist[i] = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
                if (lightUp <= 0 || GBuffer.sky(gb.light[i]) <= 0.01) continue;
                int np = gb.normal[i];
                double nx = GBuffer.nx(np), ny = GBuffer.ny(np), nz = GBuffer.nz(np);
                if (nx * vdx[i] + ny * vdy[i] + nz * vdz[i] > 0) { nx = -nx; ny = -ny; nz = -nz; }
                double ndl = nx * lx + ny * ly + nz * lz;
                BlockState state = blocks.getBlockState(cursor.set(Mth.floor(tmp.x - nx * 0.02), Mth.floor(tmp.y - ny * 0.02), Mth.floor(tmp.z - nz * 0.02)));
                if (ndl <= 0 && !isFoliage(state)) continue;
                shadow[i] = (float) transmittanceToLight(blocks, cursor, tmp.x + nx * 0.03, tmp.y + ny * 0.03, tmp.z + nz * 0.03, lx, ly, lz, maxY);
            }
        }

        // Pass 2: soften the shadows (depth-aware blur), so edges look like a real sun's penumbra.
        int radius = (int) Math.round(Math.max(1, w / 1300.0) * st.shadowSoftness);
        if (radius > 0) shadow = softenShadows(shadow, dist, w, h, radius);

        // Pass 3: lighting.
        float[] hr = new float[n], hg = new float[n], hb = new float[n];
        boolean[] surface = new boolean[n];
        float[] meter = new float[n];
        double[] c = new double[3], s = new double[3], amb = new double[3], opq = new double[3], haze = new double[3], d = new double[3];
        double[] scratch = new double[3], sd = new double[3], skyC = new double[3], c2 = new double[3], tint = new double[3], lodAlb = new double[3];
        // Angle covered by one supersample, for choosing how detailed distant textures are.
        double sampleAngle = f.sampleAngle();
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            int fbYBase = (h - 1 - y) * scale;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                d[0] = vdx[i]; d[1] = vdy[i]; d[2] = vdz[i];

                // Background (sky) samples in this pixel's supersampled block, and the colour behind them.
                int bg = 0;
                for (int sy2 = 0; sy2 < scale; sy2++) {
                    int row = (fbYBase + sy2) * fbW + x * scale;
                    for (int sx2 = 0; sx2 < scale; sx2++) if (Double.isInfinite(depth[row + sx2])) bg++;
                }
                boolean opaque = Float.isFinite(dist[i]);
                boolean translucent = gb.tDist[i] < (opaque ? dist[i] : Float.POSITIVE_INFINITY);
                // What's behind the near terrain: distant (LOD) terrain where a ray finds some, otherwise the sky.
                // Traced for every background sample of the supersampled block, so its edges are as smooth as the
                // near terrain's.
                boolean lodBg = false;
                double lodMeter = 0;
                if (bg > 0 || !opaque) {
                    if (lod == null) {
                        sky.radiance(d[0], d[1], d[2], true, s);
                    } else {
                        boolean skyDone = false;
                        int traced = 0, hits = 0;
                        double sr = 0, sg = 0, sb = 0, msum = 0;
                        for (int sy2 = 0; sy2 < scale; sy2++) {
                            int row = (fbYBase + sy2) * fbW + x * scale;
                            for (int sx2 = 0; sx2 < scale; sx2++) {
                                if (bg > 0 && !Double.isInfinite(depth[row + sx2])) continue;
                                tmp.set((x * scale + sx2 + 0.5) / fbW * 2 - 1, (fbYBase + sy2 + 0.5) / fbH * 2 - 1, 1.0);
                                f.inverseViewProjection().transformProject(tmp);
                                double ex = tmp.x - cam.x, ey = tmp.y - cam.y, ez = tmp.z - cam.z, el = Math.sqrt(ex * ex + ey * ey + ez * ez);
                                sd[0] = ex / el; sd[1] = ey / el; sd[2] = ez / el;
                                traced++;
                                if ((sd[1] <= 0 || cam.y <= lod.maxTop + 1)
                                        && lod.trace(cam.x, cam.y, cam.z, sd[0], sd[1], sd[2], lodStart, lodFar, lodHit)) {
                                    msum += shadeLod(lod, lodHit, shadowHit, sd, cam, atm, sky, st, lx, ly, lz, lightIrr, lightUp,
                                            overcast, fadeEnd, sampleAngle, amb, haze, scratch, tint, lodAlb, c2);
                                    hits++;
                                } else {
                                    if (!skyDone) {
                                        sky.radiance(d[0], d[1], d[2], true, skyC);
                                        skyDone = true;
                                    }
                                    c2[0] = skyC[0]; c2[1] = skyC[1]; c2[2] = skyC[2];
                                }
                                sr += c2[0]; sg += c2[1]; sb += c2[2];
                            }
                        }
                        s[0] = sr / traced; s[1] = sg / traced; s[2] = sb / traced;
                        lodBg = hits * 2 >= traced;
                        lodMeter = hits > 0 ? msum / hits : 0;
                    }
                }
                if (!opaque && !translucent) {
                    store(hr, hg, hb, i, s);
                    if (lodBg) {
                        surface[i] = true;
                        meter[i] = (float) lodMeter;
                    }
                    continue;
                }

                // Surface colour. Where Camera Obscura's light is reliable, divide it out of the anti-aliased
                // pixel; otherwise use the recorded centre sample.
                int pc = px[i];
                double pr = ((pc >> 16) & 255) / 255.0, pg = ((pc >> 8) & 255) / 255.0, pb = (pc & 255) / 255.0;
                int fog = f.fogColor();
                if (bg > 0) {
                    double k = 1.0 / (samples - bg);
                    pr = (pr * samples - bg * ((fog >> 16) & 255) / 255.0) * k;
                    pg = (pg * samples - bg * ((fog >> 8) & 255) / 255.0) * k;
                    pb = (pb * samples - bg * (fog & 255) / 255.0) * k;
                }

                double surfDist;
                if (opaque) {
                    double lco = gb.coLight[i];
                    int alb = gb.albedo[i];
                    double ar, ag, ab;
                    if (lco > 0.12 && !translucent) {
                        ar = Math.clamp(pr / lco, 0, 1); ag = Math.clamp(pg / lco, 0, 1); ab = Math.clamp(pb / lco, 0, 1);
                    } else {
                        ar = ((alb >> 16) & 255) / 255.0; ag = ((alb >> 8) & 255) / 255.0; ab = (alb & 255) / 255.0;
                    }
                    double ox = cam.x + d[0] * dist[i], oy = cam.y + d[1] * dist[i], oz = cam.z + d[2] * dist[i];
                    shadeOpaque(gb, i, d, ox, oy, oz, toLinear(ar), toLinear(ag), toLinear(ab), shadow[i], atm, sky, st,
                            blocks, cursor, lx, ly, lz, lightIrr, lightUp, translucent, amb, opq);
                    surfDist = dist[i];
                } else {
                    // Glass or water with nothing near behind it: it's in front of the background.
                    opq[0] = s[0]; opq[1] = s[1]; opq[2] = s[2];
                    surfDist = gb.tDist[i];
                }

                if (translucent) {
                    double td = gb.tDist[i];
                    double wx = cam.x + d[0] * td, wy = cam.y + d[1] * td, wz = cam.z + d[2] * td;
                    int tnp = gb.tNormal[i];
                    double tnx = GBuffer.nx(tnp), tny = GBuffer.ny(tnp), tnz = GBuffer.nz(tnp);
                    BlockState tstate = blocks.getBlockState(cursor.set(Mth.floor(wx - tnx * 0.02), Mth.floor(wy - tny * 0.02), Mth.floor(wz - tnz * 0.02)));
                    if (tstate.getFluidState().is(FluidTags.WATER)) {
                        shadeWater(gb.tAlbedo[i], GBuffer.sky(gb.tLight[i]), d, wx, wz, tnx, tny, tnz,
                                opaque ? dist[i] - td : Double.POSITIVE_INFINITY, opq, atm, sky, st, c);
                    } else {
                        shadeGlass(gb, i, opq, atm, c);
                    }
                    surfDist = td;
                } else {
                    c[0] = opq[0]; c[1] = opq[1]; c[2] = opq[2];
                }

                meter[i] = (float) (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]);
                applyHaze(c, d, surfDist, cam, sky, st, overcast, fadeEnd, haze);

                // Background samples without a surface. Camera Obscura doesn't write depth for translucent surfaces,
                // so where there is only glass or water, its background samples are still covered by it.
                double cover = opaque ? bg / (double) samples : 0;
                for (int k = 0; k < 3; k++) c[k] += (s[k] - c[k]) * cover;
                store(hr, hg, hb, i, c);
                surface[i] = cover < 0.5 || lodBg;
            }
        }

        finish(px, hr, hg, hb, surface, meter, w, h, st);
    }

    /**
     * Aerial perspective: distant surfaces fade into the light of the atmosphere in front of them, and near
     * {@code fadeEnd} (the end of what's loaded) into the sky itself.
     */
    private static void applyHaze(double[] c, double[] d, double surfDist, Vector3d cam, Sky sky,
                                  DailyCamsConfig.RealisticSettings st, double overcast, double fadeEnd, double[] haze) {
        double py = cam.y + d[1] * surfDist;
        double height = Math.exp(-Math.max(0, (py + cam.y) * 0.5 - 64) / 140);
        double density = st.haze / 2200.0 * (1 + 3 * overcast) * height;
        double fogAmt = 1 - Math.exp(-surfDist * density);
        double hy = Math.max(d[1], Atmosphere.HAZE_ELEVATION), hl = Math.sqrt(1 - hy * hy), dh = Math.hypot(d[0], d[2]);
        sky.radiance(dh > 1e-6 ? d[0] / dh * hl : hl, hy, dh > 1e-6 ? d[2] / dh * hl : 0, false, haze);
        for (int k = 0; k < 3; k++) c[k] += (haze[k] - c[k]) * fogAmt;
        double border = smoothstep(fadeEnd * 0.55, fadeEnd * 0.99, surfDist);
        if (border > 0) {
            sky.radiance(d[0], d[1], d[2], false, haze);
            for (int k = 0; k < 3; k++) c[k] += (haze[k] - c[k]) * border;
        }
    }

    /**
     * Distant (LOD) terrain: the hit block's baked texture (biome-tinted, detail chosen by distance), its saved sky
     * and block light, corner shading, shadows traced through the distant blocks, and water over the ground beneath
     * it. Lit by {@link #lightSurface}, exactly like the near terrain. Writes the hazed radiance to {@code out} and
     * returns the brightness before haze (for metering).
     */
    private static double shadeLod(LodTerrain lod, LodTerrain.Hit hit, LodTerrain.Hit shadowHit, double[] d, Vector3d cam,
                                   Atmosphere atm, Sky sky, DailyCamsConfig.RealisticSettings st, double lx, double ly,
                                   double lz, double[] lightIrr, double lightUp, double overcast, double fadeEnd,
                                   double sampleAngle, double[] amb, double[] haze, double[] behind, double[] tint,
                                   double[] alb, double[] out) {
        double t = hit.t;
        behind[0] = behind[1] = behind[2] = 0;
        if (!hit.deep) {
            double px = cam.x + d[0] * t, py = cam.y + d[1] * t, pz = cam.z + d[2] * t;
            net.minecraft.world.level.biome.Biome biome = lod.biomeAt(hit.x, hit.y, hit.z);
            int tc = biome != null ? LodBlocks.tintColor(hit.baked, biome, hit.x, hit.z) : 0xFFFFFF;
            tint[0] = toLinear(((tc >> 16) & 255) / 255.0); tint[1] = toLinear(((tc >> 8) & 255) / 255.0); tint[2] = toLinear((tc & 255) / 255.0);
            double cosI = Math.max(0.15, Math.abs(d[0] * hit.nx + d[1] * hit.ny + d[2] * hit.nz));
            double mip = Math.log(Math.max(1e-6, t * sampleAngle * LodBlocks.RES / cosI)) / Math.log(2);
            LodBlocks.sample(hit.baked, hit.face, hit.a, hit.b, mip, tint, alb);
            double skyL = lod.skyAt(hit.fx, hit.fy, hit.fz) / 15.0, blockL = lod.blockLightAt(hit.fx, hit.fy, hit.fz) / 15.0;
            double ao = lod.ambientOcclusion(hit);
            boolean foliage = LodTerrain.isFoliage(hit.state);
            double ndl = hit.nx * lx + hit.ny * ly + hit.nz * lz;
            double shadow = 0;
            if (lightUp > 0 && skyL > 0.01 && (ndl > 0 || foliage)) {
                boolean blocked = lod.trace(px + hit.nx * 0.01, py + hit.ny * 0.01, pz + hit.nz * 0.01, lx, ly, lz, 0.01, 400, shadowHit);
                shadow = blocked && !shadowHit.deep ? 0 : 1;
            }
            lightSurface(d, hit.nx, hit.ny, hit.nz, alb[0], alb[1], alb[2], skyL, blockL, ao, shadow, foliage,
                    hit.state.getLightEmission() / 15.0, hit.water, px, py, pz, atm, sky, st, lightIrr, lx, ly, lz, lightUp, amb, behind);
        }
        double dist = t;
        if (hit.water) {
            double wt = hit.waterT;
            double wx = cam.x + d[0] * wt, wz = cam.z + d[2] * wt;
            net.minecraft.world.level.biome.Biome biome = lod.biomeAt(hit.waterX, hit.waterY, hit.waterZ);
            int wc = biome != null ? biome.getWaterColor() : 0x3F76E4;
            // Water as Camera Obscura draws it up close: the water texture (about 0.75 grey) times the biome colour.
            int r = (int) (((wc >> 16) & 255) * 0.75), g = (int) (((wc >> 8) & 255) * 0.75), b = (int) ((wc & 255) * 0.75);
            double skyW = lod.skyAt(hit.waterX, hit.waterY + 1, hit.waterZ) / 15.0;
            shadeWater(0xB0000000 | r << 16 | g << 8 | b, skyW, d, wx, wz, 0, 1, 0,
                    hit.deep ? Double.POSITIVE_INFINITY : t - wt, behind, atm, sky, st, out);
            dist = wt;
        } else {
            out[0] = behind[0]; out[1] = behind[1]; out[2] = behind[2];
        }
        double lum = 0.2126 * out[0] + 0.7152 * out[1] + 0.0722 * out[2];
        applyHaze(out, d, dist, cam, sky, st, overcast, fadeEnd, haze);
        return lum;
    }

    /** Direct light, sky light, block light and a light floor on an opaque surface. */
    private static void shadeOpaque(GBuffer gb, int i, double[] d, double ox, double oy, double oz,
                                    double ar, double ag, double ab, double shadow, Atmosphere atm, Sky sky,
                                    DailyCamsConfig.RealisticSettings st, ChunkMeshCache.FastBlockGetter blocks,
                                    BlockPos.MutableBlockPos cursor, double lx, double ly, double lz, double[] lightIrr,
                                    double lightUp, boolean covered, double[] amb, double[] out) {
        int np = gb.normal[i], lp = gb.light[i];
        double nx = GBuffer.nx(np), ny = GBuffer.ny(np), nz = GBuffer.nz(np);
        if (nx * d[0] + ny * d[1] + nz * d[2] > 0) { nx = -nx; ny = -ny; nz = -nz; } // two-sided plants
        double skyL = GBuffer.sky(lp), blockL = GBuffer.block(lp), ao = GBuffer.ao(lp);
        BlockState state = blocks.getBlockState(cursor.set(Mth.floor(ox - nx * 0.02), Mth.floor(oy - ny * 0.02), Mth.floor(oz - nz * 0.02)));
        lightSurface(d, nx, ny, nz, ar, ag, ab, skyL, blockL, ao, shadow, isFoliage(state), state.getLightEmission() / 15.0,
                covered, ox, oy, oz, atm, sky, st, lightIrr, lx, ly, lz, lightUp, amb, out);
    }

    /**
     * The lighting shared by near and distant terrain: sky light from the sky's colour (weakened in shade and in
     * corners), sunlight or moonlight with shadows (and through leaves), warm block light, a light floor, and glow
     * for light sources. Albedo is linear; {@code ao} is 0-1 corner brightness.
     */
    private static void lightSurface(double[] d, double nx, double ny, double nz, double ar, double ag, double ab,
                                     double skyL, double blockL, double aoRaw, double shadow, boolean foliage,
                                     double emission, boolean covered, double ox, double oy, double oz, Atmosphere atm,
                                     Sky sky, DailyCamsConfig.RealisticSettings st, double[] lightIrr, double lx,
                                     double ly, double lz, double lightUp, double[] amb, double[] out) {
        double ao = Math.pow(Math.clamp(aoRaw, 0, 1), 2.0);
        double skyVis = Math.pow(skyL, 2.4) * SHADE;
        atm.ambient(nx, ny, nz, amb);
        double ndl = nx * lx + ny * ly + nz * lz;
        double diffuse = foliage ? Math.max(0, (ndl + 0.35) / 1.35) : Math.max(0, ndl);
        double direct = diffuse * shadow * lightUp * (skyL > 0.01 ? sky.cloudShadow(ox, oy, oz) : 0);
        double back = foliage ? Math.max(0, -ndl) * 0.3 * shadow * lightUp : 0;
        double torch = 2.2 * Math.pow(blockL, 3.2);
        double floor = Math.PI * (st.minLight + 0.1 * sky.dimensionLight);
        // Light sources glow (and bloom), less so seen through water or glass.
        double glow = emission > 0 ? (covered ? 0.15 : 0.7) * emission * emission : 0;

        double[] alb = {ar, ag, ab};
        for (int k = 0; k < 3; k++) {
            double e = amb[k] * skyVis * ao + lightIrr[k] * (direct + back) + TORCH[k] * torch * ao + FLOOR_TINT[k] * floor * ao;
            out[k] = alb[k] / Math.PI * e + alb[k] * glow;
        }
    }

    /** Reflection of the sky (Fresnel), sun glint and light absorbed by the water body. */
    /** {@code tintArgb}: the water's colour as drawn (texture and biome tint); {@code skyLight}: 0-1 at the surface. */
    private static void shadeWater(int tintArgb, double skyLight, double[] d, double wx, double wz, double nx, double ny, double nz,
                                   double depthBelow, double[] behind, Atmosphere atm, Sky sky,
                                   DailyCamsConfig.RealisticSettings st, double[] out) {
        if (ny > 0.5) {
            // Small waves: tilt the surface normal with smooth noise.
            double e = 0.35;
            double hx = Noise.fbm(wx * 0.55 + e, wz * 0.55, 3) - Noise.fbm(wx * 0.55 - e, wz * 0.55, 3);
            double hz = Noise.fbm(wx * 0.55, wz * 0.55 + e, 3) - Noise.fbm(wx * 0.55, wz * 0.55 - e, 3);
            nx = -hx * 0.35; nz = -hz * 0.35; ny = 1;
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= l; ny /= l; nz /= l;
        }
        double cosV = Math.max(0.02, -(d[0] * nx + d[1] * ny + d[2] * nz));
        double fresnel = (0.02 + 0.98 * Math.pow(1 - cosV, 5)) * st.waterReflections;
        double dn = d[0] * nx + d[1] * ny + d[2] * nz;
        double rx = d[0] - 2 * dn * nx, ry = Math.max(0.02, d[1] - 2 * dn * ny), rz = d[2] - 2 * dn * nz;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double[] refl = new double[3];
        sky.radiance(rx / rl, ry / rl, rz / rl, true, refl);
        double skyVis = Math.pow(skyLight, 2);
        double[] up = new double[3];
        atm.ambient(0, 1, 0, up);

        int ta = tintArgb;
        double tr = toLinear(((ta >> 16) & 255) / 255.0), tg = toLinear(((ta >> 8) & 255) / 255.0), tb = toLinear((ta & 255) / 255.0);
        double sunUp = Math.max(0, atm.sy), moonUp = Math.max(0, atm.my);
        double[] scatter = new double[3], tint = {tr, tg, tb};
        for (int k = 0; k < 3; k++) {
            double e = up[k] * skyVis + atm.sunIrradiance[k] * sunUp * 0.5 + atm.moonIrradiance[k] * moonUp * 0.5;
            scatter[k] = tint[k] * (0.22 * e / Math.PI + 0.04 * st.minLight);
        }
        double[] absorb = {0.55, 0.12, 0.08};
        for (int k = 0; k < 3; k++) {
            // Light reaching the bottom crosses the water twice: down from the surface and back up to the eye.
            double t = Double.isFinite(depthBelow) ? Math.exp(-absorb[k] * 2 * Math.max(0, depthBelow)) : 0;
            double body = behind[k] * t + scatter[k] * (1 - t);
            out[k] = body * (1 - fresnel) + refl[k] * (fresnel * skyVis + (1 - skyVis) * 0.02 * fresnel);
        }
    }

    /** Glass, ice and other translucent blocks: lit with the sky light only, blended over what's behind. */
    private static void shadeGlass(GBuffer gb, int i, double[] behind, Atmosphere atm, double[] out) {
        int ta = gb.tAlbedo[i];
        double a = (ta >>> 24) / 255.0;
        int np = gb.tNormal[i];
        double[] amb = new double[3];
        atm.ambient(GBuffer.nx(np), GBuffer.ny(np), GBuffer.nz(np), amb);
        double skyVis = Math.pow(GBuffer.sky(gb.tLight[i]), 2);
        double[] alb = {toLinear(((ta >> 16) & 255) / 255.0), toLinear(((ta >> 8) & 255) / 255.0), toLinear((ta & 255) / 255.0)};
        for (int k = 0; k < 3; k++) {
            double lit = alb[k] / Math.PI * (amb[k] * skyVis + atm.sunIrradiance[k] * Math.max(0, atm.sy) * skyVis);
            out[k] = behind[k] * (1 - a) + lit * a;
        }
    }

    private static boolean isFoliage(BlockState state) {
        return state.getBlock() instanceof LeavesBlock || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CROPS);
    }

    /** Fraction of light reaching a point (voxel walk; leaves let some light through). */
    private static double transmittanceToLight(ChunkMeshCache.FastBlockGetter blocks, BlockPos.MutableBlockPos cursor,
                                               double ox, double oy, double oz, double dx, double dy, double dz, int maxY) {
        int x = Mth.floor(ox), y = Mth.floor(oy), z = Mth.floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? x + 1 - ox : ox - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? y + 1 - oy : oy - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? z + 1 - oz : oz - z) * tDeltaZ);
        double t = 1;
        for (int i = 0; i < MAX_SHADOW_STEPS; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
            else if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY; }
            else { z += stepZ; tMaxZ += tDeltaZ; }
            if (y > maxY) return t;
            BlockState state = blocks.getBlockState(cursor.set(x, y, z));
            if (state.isAir()) continue;
            if (state.getBlock() instanceof LeavesBlock) {
                t *= 0.42;
                if (t < 0.04) return 0;
            } else if (state.canOcclude()) {
                return 0;
            }
        }
        return t;
    }

    /** Separable blur that only mixes pixels at a similar distance, so shadows don't bleed across edges. */
    private static float[] softenShadows(float[] shadow, float[] dist, int w, int h, int r) {
        float[] tmp = new float[shadow.length], out = new float[shadow.length];
        for (int pass = 0; pass < 2; pass++) {
            float[] src = pass == 0 ? shadow : tmp, dst = pass == 0 ? tmp : out;
            for (int y = 0; y < h; y++) {
                Throttle.checkpoint();
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float di = dist[i];
                    if (!Float.isFinite(di)) { dst[i] = src[i]; continue; }
                    double sum = 0, wsum = 0;
                    for (int k = -r; k <= r; k++) {
                        int xx = pass == 0 ? x + k : x, yy = pass == 0 ? y : y + k;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        int j = yy * w + xx;
                        float dj = dist[j];
                        if (!Float.isFinite(dj)) continue;
                        double rel = (dj - di) / (0.03 * di + 0.05);
                        double wt = Math.exp(-rel * rel) * (1 - Math.abs(k) / (r + 1.0));
                        sum += src[j] * wt;
                        wsum += wt;
                    }
                    dst[i] = wsum > 0 ? (float) (sum / wsum) : src[i];
                }
            }
        }
        return out;
    }

    /** Exposure, bloom, AgX tone mapping, vignette, and back to 8-bit sRGB. */
    private static void finish(int[] px, float[] r, float[] g, float[] b, boolean[] surface, float[] meter, int w, int h,
                               DailyCamsConfig.RealisticSettings st) {
        // Automatic exposure from log-average brightness (so the sun doesn't dominate). Metered mostly on the land
        // itself, before haze is added, like a photographer metering on the scene: the sky and a hazy horizon stay
        // bright instead of darkening everything. The sky still counts a little, so sunsets don't blow out.
        double all = logAverage(r, g, b, null, null, 1e-5);
        int surfaces = 0;
        for (int i = 0; i < r.length; i += 7) if (surface[i]) surfaces++;
        double avg = all;
        if (surfaces > r.length / 7 / 10) {
            double land = logAverage(null, null, null, meter, surface, 1e-5);
            // Very dark pockets (deep shade under trees) shouldn't push the exposure up much.
            land = logAverage(null, null, null, meter, surface, land * 0.3);
            avg = Math.exp(0.75 * Math.log(land) + 0.25 * Math.log(all));
        }
        // Adapt only partly to each scene, like the eye: a dark forest stays a little darker than an open field,
        // but nights and caves still get brightened enough to see (up to maxExposure).
        double exposure = Math.clamp(0.10 / Math.pow(avg, 0.85) / Math.pow(0.05, 0.15), 0.45, st.maxExposure);
        // Highlight protection: keep the brightest tenth of the picture (often the sky at sunset) out of the range
        // where the tone curve washes colours out to white.
        exposure = Math.min(exposure, 0.8 / Math.max(percentile(r, g, b, 0.9), 1e-6));
        // ...and for small, very bright areas, such as the ground around torches at night.
        exposure = Math.min(exposure, 3.2 / Math.max(percentile(r, g, b, 0.995), 1e-6)) * st.exposure;
        // How much of a night scene this is, for night vision below.
        double night = smoothstep(3.5, 12, exposure);
        io.github.kunaigear.dailycams.DailyCams.LOGGER.debug("Realistic shader: average luminance {}, exposure {}",
                String.format("%.4f", avg), String.format("%.2f", exposure));

        int q = 4, bw = Math.max(1, w / q), bh = Math.max(1, h / q);
        float[] br = new float[bw * bh], bgn = new float[bw * bh], bb = new float[bw * bh], local = new float[bw * bh];
        if (st.clarity > 0) {
            // Clarity: the average brightness of each pixel's surroundings (log), to boost differences from it.
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    double sum = 0;
                    for (int yy = 0; yy < q; yy++) {
                        for (int xx = 0; xx < q; xx++) {
                            int i = Math.min(h - 1, y * q + yy) * w + Math.min(w - 1, x * q + xx);
                            sum += Math.log(Math.max(1e-5, 0.2126 * r[i] + 0.7152 * g[i] + 0.0722 * b[i]));
                        }
                    }
                    local[y * bw + x] = (float) (sum / (q * q));
                }
            }
            for (int pass = 0; pass < 2; pass++) {
                Throttle.checkpoint();
                boxBlur(local, bw, bh, 6);
            }
        }
        if (st.bloom > 0) {
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    double sr = 0, sg = 0, sb = 0;
                    for (int yy = 0; yy < q; yy++) {
                        for (int xx = 0; xx < q; xx++) {
                            int i = Math.min(h - 1, y * q + yy) * w + Math.min(w - 1, x * q + xx);
                            sr += r[i]; sg += g[i]; sb += b[i];
                        }
                    }
                    double k = exposure / (q * q);
                    sr *= k; sg *= k; sb *= k;
                    double lum = 0.2126 * sr + 0.7152 * sg + 0.0722 * sb;
                    double kk = Math.max(0, lum - 0.8) / Math.max(lum, 1e-6);
                    int i = y * bw + x;
                    br[i] = (float) Math.min(sr * kk, 40); bgn[i] = (float) Math.min(sg * kk, 40); bb[i] = (float) Math.min(sb * kk, 40);
                }
            }
            for (int pass = 0; pass < 3; pass++) {
                Throttle.checkpoint();
                boxBlur(br, bw, bh, 4); boxBlur(bgn, bw, bh, 4); boxBlur(bb, bw, bh, 4);
            }
        }

        double cx = w / 2.0, cy = h / 2.0, maxR = Math.sqrt(cx * cx + cy * cy);
        double[] c = new double[3];
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int bi = Math.min(bh - 1, y / q) * bw + Math.min(bw - 1, x / q);
                c[0] = r[i] * exposure + br[bi] * st.bloom;
                c[1] = g[i] * exposure + bgn[bi] * st.bloom;
                c[2] = b[i] * exposure + bb[bi] * st.bloom;
                if (st.clarity > 0) {
                    double lum = 0.2126 * r[i] + 0.7152 * g[i] + 0.0722 * b[i];
                    double f = Math.exp(Math.clamp((Math.log(Math.max(lum, 1e-5)) - local[bi]) * st.clarity, -0.45, 0.45));
                    c[0] *= f; c[1] *= f; c[2] *= f;
                }
                if (night > 0) {
                    double lum = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
                    double k = 0.6 * night * (1 - smoothstep(0.02, 0.3, lum));
                    c[0] += (lum * 0.72 - c[0]) * k;
                    c[1] += (lum * 0.92 - c[1]) * k;
                    c[2] += (lum * 1.35 - c[2]) * k;
                }
                AgX.apply(c, st.saturation, st.contrast);
                double dx = (x - cx) / maxR, dy = (y - cy) / maxR;
                double v = 1 - st.vignette * Math.pow(Math.min(1, Math.sqrt(dx * dx + dy * dy)), 2.4);
                // A little noise before quantising hides banding in smooth skies.
                double dither = (Noise.hash(x, y, 7) - 0.5) / 255.0;
                px[i] = 0xFF000000 | to8(toSrgb(c[0] * v) + dither) << 16 | to8(toSrgb(c[1] * v) + dither) << 8 | to8(toSrgb(c[2] * v) + dither);
            }
        }
    }

    /** Luminance below which the given fraction of (every 7th) pixel lies; the sun's disc is ignored. */
    private static double percentile(float[] r, float[] g, float[] b, double fraction) {
        int m = (r.length + 6) / 7;
        double[] lum = new double[m];
        int k = 0;
        for (int i = 0; i < r.length; i += 7) lum[k++] = Math.min(0.2126 * r[i] + 0.7152 * g[i] + 0.0722 * b[i], 20);
        java.util.Arrays.sort(lum, 0, k);
        return lum[Math.min(k - 1, (int) (k * fraction))];
    }

    /** Log-average luminance of every 7th pixel (of {@code lum} where given, else of r, g, b), optionally masked. */
    private static double logAverage(float[] r, float[] g, float[] b, float[] lum, boolean[] mask, double floor) {
        int n = lum != null ? lum.length : r.length;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < n; i += 7) {
            if (mask != null && !mask[i]) continue;
            double l = lum != null ? lum[i] : 0.2126 * r[i] + 0.7152 * g[i] + 0.0722 * b[i];
            sum += Math.log(Math.clamp(l, floor, 8));
            count++;
        }
        return Math.exp(sum / Math.max(1, count));
    }

    private static void boxBlur(float[] a, int w, int h, int radius) {
        float[] tmp = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int cnt = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx >= 0 && xx < w) { sum += a[y * w + xx]; cnt++; }
                }
                tmp[y * w + x] = sum / cnt;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int cnt = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy >= 0 && yy < h) { sum += tmp[yy * w + x]; cnt++; }
                }
                a[y * w + x] = sum / cnt;
            }
        }
    }

    private static void store(float[] r, float[] g, float[] b, int i, double[] c) {
        r[i] = (float) c[0]; g[i] = (float) c[1]; b[i] = (float) c[2];
    }

    static double smoothstep(double e0, double e1, double x) {
        double t = Math.clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double toLinear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double toSrgb(double v) {
        v = Math.clamp(v, 0, 1);
        return v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }

    private static int to8(double v) {
        return (int) Math.round(Math.clamp(v, 0, 1) * 255);
    }

    /** Sky radiance for a view direction: atmosphere, sun and moon discs, stars and clouds. */
    static final class Sky {
        private final Atmosphere atm;
        private final Vector3d cam;
        private final double coverage, starBrightness, overcast, windX, windZ;
        private final double[] up = new double[3];
        private static final double SUN_COS = Math.cos(Math.toRadians(0.75)), MOON_COS = Math.cos(Math.toRadians(0.65));

        Sky(Atmosphere atm, Vector3d cam, DailyCamsConfig.RealisticSettings st, double overcast, double starBrightness,
            long day, long timeOfDay) {
            this.atm = atm;
            this.cam = cam;
            this.overcast = overcast;
            this.starBrightness = starBrightness;
            // Cloud cover varies from day to day (and with the weather).
            double daily = Noise.hash((int) day, 17, 3);
            this.coverage = Math.clamp(st.clouds * (0.25 + 0.4 * daily) + overcast * 0.7, 0, 0.97);
            this.windX = day * 7919.0 + timeOfDay * 0.9;
            this.windZ = day * 3571.0 + timeOfDay * 0.35;
            atm.ambient(0, 1, 0, up);
        }

        /**
         * {@code full}: include sun and moon discs, stars and clouds. Haze uses only the atmosphere: with clouds,
         * every cloud near the horizon would tint the haze along its whole compass direction, which shows up as
         * streaks converging above the horizon.
         */
        /** Background colour for dimensions without a sky, instead of the atmosphere (null = normal sky). */
        double[] flat;
        /** The dimension's own faint light (the Nether has some, the overworld none). */
        double dimensionLight;

        void radiance(double dx, double dy, double dz, boolean full, double[] out) {
            if (flat != null) {
                out[0] = flat[0]; out[1] = flat[1]; out[2] = flat[2];
                return;
            }
            atm.sky(dx, dy, dz, out);
            if (full) {
                double cs = dx * atm.sx + dy * atm.sy + dz * atm.sz;
                if (cs > SUN_COS) {
                    double rr = Math.min(1, (1 - cs) / (1 - SUN_COS));
                    double limb = 1 - 0.6 * (1 - Math.sqrt(1 - rr));
                    for (int k = 0; k < 3; k++) out[k] += Math.min(60, atm.sunIrradiance[k] * 9000) * limb;
                }
                double cm = dx * atm.mx + dy * atm.my + dz * atm.mz;
                if (cm > MOON_COS && atm.my > -0.05) {
                    for (int k = 0; k < 3; k++) out[k] += Math.min(1.2, atm.moonIrradiance[k] * 1800);
                }
                if (starBrightness > 0 && dy > 0) {
                    double hsh = Noise.hash((int) Math.floor(dx * 420), (int) Math.floor(dy * 420), (int) Math.floor(dz * 420));
                    if (hsh > 0.9978) {
                        double st = (hsh - 0.9978) / 0.0022;
                        double v = starBrightness * 0.02 * st * st * (1 - overcast);
                        out[0] += v * 0.9; out[1] += v * 0.95; out[2] += v;
                    }
                }
            }
            if (full && dy > 0.005 && coverage > 0 && cam.y < CLOUD_ALTITUDE) {
                double t = (CLOUD_ALTITUDE - cam.y) / dy;
                if (t < 60000) {
                    double qx = cam.x + dx * t, qz = cam.z + dz * t;
                    double a = cloudAlpha(qx, qz);
                    if (a > 0) {
                        boolean sunUp = atm.sy > 0;
                        double lx = sunUp ? atm.sx : atm.mx, lz = sunUp ? atm.sz : atm.mz;
                        double[] irr = sunUp ? atm.sunIrradiance : atm.moonIrradiance;
                        double self = Math.exp(-3.2 * cloudAlpha(qx + lx * 180, qz + lz * 180));
                        double mu = dx * (sunUp ? atm.sx : atm.mx) + dy * (sunUp ? atm.sy : atm.my) + dz * (sunUp ? atm.sz : atm.mz);
                        double forward = 1 + 2.4 * Math.pow(Math.max(0, mu), 8) + 3.0 * Math.pow(Math.max(0, mu), 60) * (1 - a);
                        double fade = Math.exp(-t / 36000) * smoothstep(0.005, 0.09, dy);
                        double dim = 1 - 0.55 * overcast;
                        for (int k = 0; k < 3; k++) {
                            double cr = (irr[k] * (0.12 + 0.88 * self) * 0.34 * forward + up[k] * 0.3) * dim;
                            out[k] += (cr - out[k]) * a * fade;
                        }
                    }
                }
            }
        }

        /** Sunlight let through by the clouds above a point. */
        double cloudShadow(double x, double y, double z) {
            // With a low sun, cloud shadows would stretch for kilometres into streaks; fade them out instead.
            double strength = 0.7 * smoothstep(0.18, 0.45, atm.sy);
            if (coverage <= 0 || strength <= 0 || y >= CLOUD_ALTITUDE) return 1;
            double t = (CLOUD_ALTITUDE - y) / atm.sy;
            return 1 - strength * cloudAlpha(x + atm.sx * t, z + atm.sz * t);
        }

        private double cloudAlpha(double x, double z) {
            double px = (x + windX) / 1100.0, pz = (z + windZ) / 1100.0;
            // Domain warping breaks up the regular blobs into cumulus-like shapes.
            double wx = Noise.fbm(px * 0.7 + 3.1, pz * 0.7, 3) - 0.5, wz = Noise.fbm(px * 0.7, pz * 0.7 - 5.3, 3) - 0.5;
            double v = Noise.fbm(px + wx * 1.4, pz + wz * 1.4, 6);
            double edge = 1 - coverage;
            return smoothstep(edge - 0.03, edge + 0.13, v);
        }
    }

    /** Hash-based value noise. */
    static final class Noise {
        static double hash(int x, int y, int z) {
            int hsh = x * 374761393 + y * 668265263 + z * 1274126177;
            hsh = (hsh ^ (hsh >>> 13)) * 1103515245;
            hsh ^= hsh >>> 16;
            return (hsh & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
        }

        static double value(double x, double y) {
            int xi = Mth.floor(x), yi = Mth.floor(y);
            double fx = x - xi, fy = y - yi;
            double u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
            double a = hash(xi, yi, 0), b = hash(xi + 1, yi, 0), c = hash(xi, yi + 1, 0), d = hash(xi + 1, yi + 1, 0);
            return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
        }

        static double fbm(double x, double y, int octaves) {
            double sum = 0, amp = 0.5, norm = 0;
            for (int o = 0; o < octaves; o++) {
                sum += value(x, y) * amp;
                norm += amp;
                x = x * 2.03 + 17.1;
                y = y * 2.03 - 9.7;
                amp *= 0.5;
            }
            return sum / norm;
        }
    }

    /** AgX filmic tone mapping (Blender/Filament curve fit, as used by three.js). */
    static final class AgX {
        private static final double MIN_EV = -12.47393, MAX_EV = 4.026069;

        static void apply(double[] c, double saturation, double contrast) {
            // Linear sRGB -> Rec.2020 -> AgX inset.
            double r = 0.6274 * c[0] + 0.3293 * c[1] + 0.0433 * c[2];
            double g = 0.0691 * c[0] + 0.9195 * c[1] + 0.0113 * c[2];
            double b = 0.0164 * c[0] + 0.0880 * c[1] + 0.8956 * c[2];
            double ir = 0.856627153315983 * r + 0.0951212405381588 * g + 0.0482516061458583 * b;
            double ig = 0.137318972929847 * r + 0.761241990602591 * g + 0.101439036467562 * b;
            double ib = 0.11189821299995 * r + 0.0767994186031903 * g + 0.811302368396859 * b;
            ir = curve(ir); ig = curve(ig); ib = curve(ib);
            // Look: contrast (power) and saturation in the display-encoded space.
            if (contrast != 1) { ir = Math.pow(ir, contrast); ig = Math.pow(ig, contrast); ib = Math.pow(ib, contrast); }
            double luma = 0.2126 * ir + 0.7152 * ig + 0.0722 * ib;
            ir = luma + (ir - luma) * saturation; ig = luma + (ig - luma) * saturation; ib = luma + (ib - luma) * saturation;
            // AgX outset -> linear -> Rec.2020 -> linear sRGB.
            double or = 1.1271005818144368 * ir - 0.11060664309660323 * ig - 0.016493938717834573 * ib;
            double og = -0.1413297634984383 * ir + 1.157823702216272 * ig - 0.016493938717834257 * ib;
            double ob = -0.14132976349843826 * ir - 0.11060664309660294 * ig + 1.2519364065950405 * ib;
            or = Math.pow(Math.max(0, or), 2.2); og = Math.pow(Math.max(0, og), 2.2); ob = Math.pow(Math.max(0, ob), 2.2);
            c[0] = Math.clamp(1.6605 * or - 0.5876 * og - 0.0728 * ob, 0, 1);
            c[1] = Math.clamp(-0.1246 * or + 1.1329 * og - 0.0083 * ob, 0, 1);
            c[2] = Math.clamp(-0.0182 * or - 0.1006 * og + 1.1187 * ob, 0, 1);
        }

        private static double curve(double v) {
            v = Math.log(Math.max(v, 1e-10)) / Math.log(2);
            double x = Math.clamp((v - MIN_EV) / (MAX_EV - MIN_EV), 0, 1);
            double x2 = x * x, x4 = x2 * x2;
            return Math.clamp(15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232, 0, 1);
        }
    }
}

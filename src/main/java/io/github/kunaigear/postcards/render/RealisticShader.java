package io.github.kunaigear.postcards.render;

import io.github.kunaigear.postcards.config.PostcardsConfig;
import io.github.kunaigear.postcards.obscura.color.BlockColors;
import io.github.kunaigear.postcards.obscura.renderer.ChunkMeshCache;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The "realistic" shader: relights the frame from the {@link GBuffer} in high dynamic range, in the style of
 * modern shader packs:
 * <ul>
 *   <li>a physically based sky ({@link Atmosphere}) with sun, moon, stars and procedural clouds that change daily,</li>
 *   <li>sunlight coloured by the atmosphere, with soft voxel shadows, cloud shadows and light through leaves,</li>
 *   <li>leaves that glow when backlit, with a sheen and relief up close; plants lit like the ground they grow on,</li>
 *   <li>sky light from the actual sky colour, weakened by screen-space ambient occlusion, warm block light and a
 *   light floor, so nothing is pitch black,</li>
 *   <li>water with sky reflections, sun glints and depth-based absorption,</li>
 *   <li>aerial perspective, automatic exposure, bloom and AgX filmic tone mapping.</li>
 * </ul>
 * Runs on the render thread and yields through {@link Throttle}.
 */
final class RealisticShader {
    private RealisticShader() {}

    private static final int MAX_SHADOW_STEPS = 200;
    /** How strongly the relief of leaf textures bends their normals. */
    private static final double RELIEF = 6;
    /** Reach (blocks) and samples of the screen-space ambient occlusion. */
    private static final double AO_RADIUS = 3.5;
    private static final int AO_SAMPLES = 8;
    /** Light that gets through one leaf block. */
    private static final double LEAF_TRANSMITTANCE = 0.5;
    /** The layer of cumulus clouds, in blocks above sea level. */
    private static final double CLOUD_BOTTOM = 900, CLOUD_TOP = 1500;
    private static final double[] TORCH = {1.0, 0.56, 0.26};
    private static final double[] FLOOR_TINT = {0.86, 0.93, 1.1};
    /** How much of the sky's light reaches surfaces in shade. Below 1 for deeper, more contrasty shadows. */
    private static final double SHADE = 0.72;

    /**
     * {@code lod}: distant terrain beyond the render distance (or null), drawn up to {@code lodFar} blocks.
     */
    static void apply(BufferedImage image, SimpleShader.Frame f, GBuffer gb, Level level, PostcardsConfig.RealisticSettings st,
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
        int maxY = LoadedChunks.highestBlock(level);
        LightBlockers blockers = new LightBlockers(level);

        // Pass 1: positions and shadows for every opaque pixel.
        float[] dist = new float[n], shadow = new float[n], vdx = new float[n], vdy = new float[n], vdz = new float[n];
        byte[] mat = new byte[n];
        BlockState lastState = null;
        int lastMat = SOLID;
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
                int np = gb.normal[i];
                double nx = GBuffer.nx(np), ny = GBuffer.ny(np), nz = GBuffer.nz(np);
                if (nx * vdx[i] + ny * vdy[i] + nz * vdz[i] > 0) { nx = -nx; ny = -ny; nz = -nz; }
                BlockState state = blocks.getBlockState(cursor.set(Mth.floor(tmp.x - nx * 0.02), Mth.floor(tmp.y - ny * 0.02), Mth.floor(tmp.z - nz * 0.02)));
                if (state.isAir()) state = leavesAround(blocks, cursor, tmp.x, tmp.y, tmp.z, state);
                if (state != lastState) { lastState = state; lastMat = material(state); }
                mat[i] = (byte) lastMat;
                if (lightUp <= 0 || GBuffer.sky(gb.light[i]) <= 0.01) continue;
                double ndl = nx * lx + ny * ly + nz * lz;
                if (ndl <= 0 && lastMat == SOLID) continue;
                shadow[i] = (float) transmittanceToLight(blockers, tmp.x + nx * 0.03, tmp.y + ny * 0.03, tmp.z + nz * 0.03, lx, ly, lz, maxY);
            }
        }

        // Pass 2: soften the shadows (depth-aware blur), so edges look like a real sun's penumbra.
        int radius = (int) Math.round(Math.max(1, w / 1300.0) * st.shadowSoftness);
        if (radius > 0) shadow = softenShadows(shadow, dist, w, h, radius);
        float[] occlusion = ambientOcclusion(dist, vdx, vdy, vdz, gb, mat, w, h, f.sampleAngle() * scale);

        // Clouds behind everything that isn't solid near terrain, at half resolution (they're soft anyway).
        sky.prepareClouds(f, w, h, dist, depth, fbW, scale, fadeEnd * 0.55);

        // Pass 3: lighting.
        float[] hr = new float[n], hg = new float[n], hb = new float[n];
        boolean[] surface = new boolean[n];
        float[] meter = new float[n];
        // How much open sky each pixel shows, where the sun's light comes through (for sun rays).
        float[] open = new float[n];
        double[] c = new double[3], s = new double[3], amb = new double[3], opq = new double[3], haze = new double[3], d = new double[3];
        double[] scratch = new double[3], sd = new double[3], skyC = new double[3], c2 = new double[3], tint = new double[3], lodAlb = new double[3];
        double[] surfAlb = new double[3];
        Texels texels = new Texels(blocks, f.sampleAngle() * scale);
        // Angle covered by one supersample, for choosing how detailed distant textures are.
        double sampleAngle = f.sampleAngle();
        // From here on, dist holds the distance to whatever each shaded pixel shows (for reflections).
        Reflections ssr = new Reflections(f, w, h, hr, hg, hb, dist);
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            ssr.row = y;
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
                double lodMeter = 0, lodDist = Double.POSITIVE_INFINITY, skyFrac = 0;
                if (bg > 0 || !opaque) {
                    skyFrac = opaque ? bg / (double) samples : 1;
                    if (lod == null) {
                        sky.background(x, y, d[0], d[1], d[2], s);
                    } else {
                        boolean skyDone = false;
                        int traced = 0, hits = 0;
                        double sr = 0, sg = 0, sb = 0, msum = 0, dsum = 0;
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
                                    msum += shadeLod(x, y, lod, lodHit, shadowHit, sd, cam, atm, sky, st, lx, ly, lz, lightIrr, lightUp,
                                            overcast, fadeEnd, sampleAngle, ssr, amb, haze, scratch, tint, lodAlb, c2);
                                    dsum += lodHit.water ? lodHit.waterT : lodHit.t;
                                    hits++;
                                } else {
                                    if (!skyDone) {
                                        sky.background(x, y, d[0], d[1], d[2], skyC);
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
                        skyFrac *= (traced - hits) / (double) traced;
                        if (lodBg) lodDist = dsum / hits;
                    }
                    open[i] = (float) (skyFrac * sky.cloudTransmittance(x, y));
                }
                if (!opaque && !translucent) {
                    store(hr, hg, hb, i, s);
                    dist[i] = (float) lodDist;
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

                // Water in front of the opaque surface (or of the background).
                double td = gb.tDist[i];
                double wx = cam.x + d[0] * td, wy = cam.y + d[1] * td, wz = cam.z + d[2] * td;
                int tnp = gb.tNormal[i];
                double tnx = GBuffer.nx(tnp), tny = GBuffer.ny(tnp), tnz = GBuffer.nz(tnp);
                boolean water = translucent && blocks.getBlockState(cursor.set(Mth.floor(wx - tnx * 0.02), Mth.floor(wy - tny * 0.02),
                        Mth.floor(wz - tnz * 0.02))).getFluidState().is(FluidTags.WATER);

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
                    surfAlb[0] = toLinear(ar); surfAlb[1] = toLinear(ag); surfAlb[2] = toLinear(ab);
                    double cavity = 1;
                    if ((texels.footprint(gb.normal[i], d, dist[i]) > 1 || mat[i] == LEAVES) && texels.locate(ox, oy, oz)) {
                        texels.filter(surfAlb);
                        if (mat[i] == LEAVES) cavity = texels.relief();
                    }
                    // The gaps between leaves are shaded by the leaves around them.
                    double sun = shadow[i] * (0.7 + 0.3 * cavity);
                    if (water && sun > 0) sun *= caustics(ox, oz, wy - oy, lx, ly, lz);
                    shadeOpaque(gb, i, mat[i], texels.normal, Math.min(occlusion[i], 0.5 + 0.5 * cavity), d, ox, oy, oz, surfAlb[0], surfAlb[1],
                            surfAlb[2], sun, atm, sky, st, blocks, cursor, lx, ly, lz, lightIrr, lightUp, translucent, amb, opq);
                    surfDist = dist[i];
                } else {
                    // Glass or water with nothing near behind it: it's in front of the background.
                    opq[0] = s[0]; opq[1] = s[1]; opq[2] = s[2];
                    surfDist = gb.tDist[i];
                }

                if (translucent) {
                    if (water) {
                        shadeWater(gb.tAlbedo[i], GBuffer.sky(gb.tLight[i]), d, td, wx, wy, wz, tnx, tny, tnz,
                                opaque ? dist[i] - td : Double.POSITIVE_INFINITY, opq, atm, sky, st, ssr, c);
                    } else {
                        shadeGlass(gb, i, opq, atm, c);
                    }
                    surfDist = td;
                } else {
                    c[0] = opq[0]; c[1] = opq[1]; c[2] = opq[2];
                }

                meter[i] = (float) (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]);
                applyHaze(x, y, c, d, surfDist, cam, sky, st, overcast, fadeEnd, haze);

                // Background samples without a surface. Camera Obscura doesn't write depth for translucent surfaces,
                // so where there is only glass or water, its background samples are still covered by it.
                double cover = opaque ? bg / (double) samples : 0;
                for (int k = 0; k < 3; k++) c[k] += (s[k] - c[k]) * cover;
                store(hr, hg, hb, i, c);
                dist[i] = (float) (cover < 0.5 ? surfDist : lodDist);
                surface[i] = cover < 0.5 || lodBg;
            }
        }

        if (st.sunRays > 0 && lightUp > 0 && sky.flat == null) {
            sunRays(hr, hg, hb, open, vdx, vdy, vdz, w, h, f, lx, ly, lz, lightIrr, overcast, st);
        }
        finish(px, hr, hg, hb, surface, meter, w, h, st);
    }

    /**
     * Sun rays: sunlight scattered towards the camera by the haze, except where trees, terrain or clouds shade the
     * air in between. Done in screen space, as a blur of the open sky towards the sun (as in GPU Gems 3), so the sun
     * has to be in front of the camera.
     */
    private static void sunRays(float[] r, float[] g, float[] b, float[] open, float[] vdx, float[] vdy, float[] vdz,
                                int w, int h, SimpleShader.Frame f, double lx, double ly, double lz, double[] lightIrr,
                                double overcast, PostcardsConfig.RealisticSettings st) {
        Vector3d cam = f.camera();
        Matrix4d viewProjection = new Matrix4d(f.inverseViewProjection()).invert();
        Vector3d forward = f.inverseViewProjection().transformProject(new Vector3d(0, 0, 1)).sub(cam).normalize();
        if (forward.x * lx + forward.y * ly + forward.z * lz < 0.05) return;
        Vector3d p = viewProjection.transformProject(new Vector3d(cam.x + lx * 1e4, cam.y + ly * 1e4, cam.z + lz * 1e4));
        double sunX = (p.x + 1) * 0.5 * w, sunY = h - (p.y + 1) * 0.5 * h;
        // Fades out as the sun moves far off the picture.
        double off = Math.max(Math.max(-sunX, sunX - w) / w, Math.max(-sunY, sunY - h) / h);
        double visible = 1 - smoothstep(0, 0.7, off);
        if (visible <= 0) return;

        int q = 4, bw = Math.max(1, w / q), bh = Math.max(1, h / q);
        float[] mask = new float[bw * bh], rays = new float[bw * bh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) mask[Math.min(bh - 1, y / q) * bw + Math.min(bw - 1, x / q)] += open[y * w + x];
        }
        for (int i = 0; i < mask.length; i++) mask[i] /= q * q;
        int samples = 40;
        double decay = 0.965, sx = sunX / q - 0.5, sy = sunY / q - 0.5;
        for (int y = 0; y < bh; y++) {
            Throttle.checkpoint();
            for (int x = 0; x < bw; x++) {
                double stepX = (sx - x) / samples * 0.95, stepY = (sy - y) / samples * 0.95;
                double px = x, py = y, sum = 0, wt = 1, wsum = 0;
                for (int k = 0; k < samples; k++) {
                    px += stepX; py += stepY;
                    int ix = (int) Math.round(px), iy = (int) Math.round(py);
                    if (ix >= 0 && iy >= 0 && ix < bw && iy < bh) sum += mask[iy * bw + ix] * wt;
                    else sum += wt; // beyond the picture's edge, assume open sky
                    wsum += wt;
                    wt *= decay;
                }
                rays[y * bw + x] = (float) (sum / wsum);
            }
        }
        // Stronger with a low sun (a longer path through the haze) and in hazy weather.
        double k = 0.016 * st.sunRays * st.haze * (1 + 1.5 * overcast) * (1 + 2 * (1 - smoothstep(0.05, 0.4, ly))) * visible;
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            double fy = Math.clamp((y + 0.5) / q - 0.5, 0, bh - 1);
            int y0 = (int) fy, y1 = Math.min(bh - 1, y0 + 1);
            double ty = fy - y0;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double fx = Math.clamp((x + 0.5) / q - 0.5, 0, bw - 1);
                int x0 = (int) fx, x1 = Math.min(bw - 1, x0 + 1);
                double tx = fx - x0;
                double ray = (rays[y0 * bw + x0] * (1 - tx) + rays[y0 * bw + x1] * tx) * (1 - ty)
                        + (rays[y1 * bw + x0] * (1 - tx) + rays[y1 * bw + x1] * tx) * ty;
                double mu = vdx[i] * lx + vdy[i] * ly + vdz[i] * lz;
                double phase = Sky.hg(mu, 0.72);
                // The open sky already has its glow around the sun.
                double a = ray * phase * k * (1 - 0.6 * open[i]);
                r[i] += (float) (lightIrr[0] * a); g[i] += (float) (lightIrr[1] * a); b[i] += (float) (lightIrr[2] * a);
            }
        }
    }

    /**
     * Aerial perspective: distant surfaces fade into the light of the atmosphere in front of them, and near
     * {@code fadeEnd} (the end of what's loaded) into the sky behind pixel (x, y), clouds included.
     */
    private static void applyHaze(int x, int y, double[] c, double[] d, double surfDist, Vector3d cam, Sky sky,
                                  PostcardsConfig.RealisticSettings st, double overcast, double fadeEnd, double[] haze) {
        double py = cam.y + d[1] * surfDist;
        double height = Math.exp(-Math.max(0, (py + cam.y) * 0.5 - 64) / 140);
        double density = st.haze / 2200.0 * (1 + 3 * overcast) * height;
        double fogAmt = 1 - Math.exp(-surfDist * density);
        double hy = Math.max(d[1], Atmosphere.HAZE_ELEVATION), hl = Math.sqrt(1 - hy * hy), dh = Math.hypot(d[0], d[2]);
        sky.radiance(dh > 1e-6 ? d[0] / dh * hl : hl, hy, dh > 1e-6 ? d[2] / dh * hl : 0, false, haze);
        for (int k = 0; k < 3; k++) c[k] += (haze[k] - c[k]) * fogAmt;
        double border = smoothstep(fadeEnd * 0.55, fadeEnd * 0.99, surfDist);
        if (border > 0) {
            sky.background(x, y, d[0], d[1], d[2], haze);
            for (int k = 0; k < 3; k++) c[k] += (haze[k] - c[k]) * border;
        }
    }

    /**
     * Distant (LOD) terrain: the hit block's baked texture (biome-tinted, detail chosen by distance), its saved sky
     * and block light, corner shading, shadows traced through the distant blocks, and water over the ground beneath
     * it. Lit by {@link #lightSurface}, exactly like the near terrain. Writes the hazed radiance to {@code out} and
     * returns the brightness before haze (for metering).
     */
    private static double shadeLod(int x, int y, LodTerrain lod, LodTerrain.Hit hit, LodTerrain.Hit shadowHit, double[] d, Vector3d cam,
                                   Atmosphere atm, Sky sky, PostcardsConfig.RealisticSettings st, double lx, double ly,
                                   double lz, double[] lightIrr, double lightUp, double overcast, double fadeEnd,
                                   double sampleAngle, Reflections ssr, double[] amb, double[] haze, double[] behind,
                                   double[] tint, double[] alb, double[] out) {
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
            int material = material(hit.state);
            double ndl = hit.nx * lx + hit.ny * ly + hit.nz * lz;
            double shadow = 0;
            if (lightUp > 0 && skyL > 0.01 && (ndl > 0 || material != SOLID)) {
                boolean blocked = lod.trace(px + hit.nx * 0.01, py + hit.ny * 0.01, pz + hit.nz * 0.01, lx, ly, lz, 0.01, 400, shadowHit);
                shadow = blocked && !shadowHit.deep ? 0 : 1;
            }
            lightSurface(d, hit.nx, hit.ny, hit.nz, alb[0], alb[1], alb[2], skyL, blockL, ao, shadow, material,
                    hit.state.getLightEmission() / 15.0, hit.water, px, py, pz, atm, sky, st, lightIrr, lx, ly, lz, lightUp, amb, behind);
        }
        double dist = t;
        if (hit.water) {
            double wt = hit.waterT;
            double wx = cam.x + d[0] * wt, wy = cam.y + d[1] * wt, wz = cam.z + d[2] * wt;
            net.minecraft.world.level.biome.Biome biome = lod.biomeAt(hit.waterX, hit.waterY, hit.waterZ);
            int wc = biome != null ? biome.getWaterColor() : 0x3F76E4;
            // Water as Camera Obscura draws it up close: the water texture (about 0.75 grey) times the biome colour.
            int r = (int) (((wc >> 16) & 255) * 0.75), g = (int) (((wc >> 8) & 255) * 0.75), b = (int) ((wc & 255) * 0.75);
            double skyW = lod.skyAt(hit.waterX, hit.waterY + 1, hit.waterZ) / 15.0;
            shadeWater(0xB0000000 | r << 16 | g << 8 | b, skyW, d, wt, wx, wy, wz, 0, 1, 0,
                    hit.deep ? Double.POSITIVE_INFINITY : t - wt, behind, atm, sky, st, ssr, out);
            dist = wt;
        } else {
            out[0] = behind[0]; out[1] = behind[1]; out[2] = behind[2];
        }
        double lum = 0.2126 * out[0] + 0.7152 * out[1] + 0.0722 * out[2];
        applyHaze(x, y, out, d, dist, cam, sky, st, overcast, fadeEnd, haze);
        return lum;
    }

    /** Direct light, sky light, block light and a light floor on an opaque surface ({@code n}: facing the camera). */
    private static void shadeOpaque(GBuffer gb, int i, int material, double[] n, double occlusion, double[] d, double ox, double oy, double oz,
                                    double ar, double ag, double ab, double shadow, Atmosphere atm, Sky sky,
                                    PostcardsConfig.RealisticSettings st, ChunkMeshCache.FastBlockGetter blocks,
                                    BlockPos.MutableBlockPos cursor, double lx, double ly, double lz, double[] lightIrr,
                                    double lightUp, boolean covered, double[] amb, double[] out) {
        int lp = gb.light[i];
        double nx = n[0], ny = n[1], nz = n[2];
        // Minecraft's corner shading and the screen-space occlusion both darken corners: keep the darker, not both.
        double skyL = GBuffer.sky(lp), blockL = GBuffer.block(lp), ao = Math.min(GBuffer.ao(lp), occlusion);
        BlockState state = blocks.getBlockState(cursor.set(Mth.floor(ox - nx * 0.02), Mth.floor(oy - ny * 0.02), Mth.floor(oz - nz * 0.02)));
        lightSurface(d, nx, ny, nz, ar, ag, ab, skyL, blockL, ao, shadow, material, state.getLightEmission() / 15.0,
                covered, ox, oy, oz, atm, sky, st, lightIrr, lx, ly, lz, lightUp, amb, out);
    }

    /**
     * The lighting shared by near and distant terrain: sky light from the sky's colour (weakened in shade and in
     * corners), sunlight or moonlight with shadows (and through leaves), warm block light, a light floor, and glow
     * for light sources. Albedo is linear; {@code ao} is 0-1 corner brightness.
     */
    private static void lightSurface(double[] d, double nx, double ny, double nz, double ar, double ag, double ab,
                                     double skyL, double blockL, double aoRaw, double shadow, int material,
                                     double emission, boolean covered, double ox, double oy, double oz, Atmosphere atm,
                                     Sky sky, PostcardsConfig.RealisticSettings st, double[] lightIrr, double lx,
                                     double ly, double lz, double lightUp, double[] amb, double[] out) {
        // Grass and flowers are crossed quads facing every way: light them like the ground they grow on.
        if (material == PLANT) { nx = 0; ny = 1; nz = 0; }
        double ao = Math.pow(Math.clamp(aoRaw, 0, 1), 2.0);
        double skyVis = Math.pow(skyL, 2.4) * SHADE;
        atm.ambient(nx, ny, nz, amb);
        double ndl = nx * lx + ny * ly + nz * lz;
        double lit = lightUp * (skyL > 0.01 ? sky.cloudShadow(ox, oy, oz) : 0), sun = shadow * lit;
        // A leaf block is a cloud of small leaves facing every way, so it's lit from any side the light reaches it.
        double diffuse = material == LEAVES ? 0.3 + 0.7 * Math.max(0, ndl) : Math.max(0, ndl);
        double direct = diffuse * sun;
        // Light coming through leaves and blades towards the eye: mostly forward, so strongest looking into the sun.
        double through = 0, sheen = 0;
        if (material != SOLID && sun > 0) {
            double mu = d[0] * lx + d[1] * ly + d[2] * lz;
            through = lit * (material == LEAVES ? 0.4 : 0.15) * (0.3 + 0.7 * Math.max(0, -ndl)) * Sky.hg(mu, 0.4);
            if (material == LEAVES && ndl > 0) {
                // Seen edge-on, the leaves in front hide each other's highlights.
                double ndv = -(nx * d[0] + ny * d[1] + nz * d[2]);
                sheen = sun * 0.12 * smoothstep(0.1, 0.5, ndv) * ggx(d, nx, ny, nz, lx, ly, lz, 0.45, 0.04);
            }
        }
        double lum = Math.max(1e-4, 0.2126 * ar + 0.7152 * ag + 0.0722 * ab);
        double torch = 2.2 * Math.pow(blockL, 3.2);
        double floor = Math.PI * (st.minLight + 0.1 * sky.dimensionLight);
        // Light sources glow (and bloom), less so seen through water or glass.
        double glow = emission > 0 ? (covered ? 0.15 : 0.7) * emission * emission : 0;

        double[] alb = {ar, ag, ab};
        for (int k = 0; k < 3; k++) {
            // What comes through leaves has been coloured by them on the way: more saturated than what they reflect,
            // and the colour they let through (green) gets deeper into a canopy than the light that reaches it directly.
            double tint = Math.min(2, Math.sqrt(alb[k] / lum));
            double e = amb[k] * skyVis * ao + lightIrr[k] * (direct + through * tint * Math.pow(shadow, 1 - 0.6 * Math.min(1, tint)))
                    + TORCH[k] * torch * ao + FLOOR_TINT[k] * floor * ao;
            out[k] = alb[k] / Math.PI * e + alb[k] * glow + lightIrr[k] * sheen;
        }
    }

    /** GGX specular reflection (Smith visibility, Schlick Fresnel) of a light, times n·l. */
    private static double ggx(double[] d, double nx, double ny, double nz, double lx, double ly, double lz,
                              double roughness, double f0) {
        double hx = lx - d[0], hy = ly - d[1], hz = lz - d[2], hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (hl < 1e-6) return 0;
        hx /= hl; hy /= hl; hz /= hl;
        double ndl = Math.max(0, nx * lx + ny * ly + nz * lz), ndv = Math.max(1e-3, -(nx * d[0] + ny * d[1] + nz * d[2]));
        double ndh = Math.max(0, nx * hx + ny * hy + nz * hz), vdh = Math.max(0, lx * hx + ly * hy + lz * hz);
        double a2 = roughness * roughness * roughness * roughness;
        double q = ndh * ndh * (a2 - 1) + 1;
        double dist = a2 / (Math.PI * q * q);
        double vis = 0.5 / (ndl * Math.sqrt(ndv * ndv * (1 - a2) + a2) + ndv * Math.sqrt(ndl * ndl * (1 - a2) + a2) + 1e-6);
        double fresnel = f0 + (1 - f0) * Math.pow(1 - vdh, 5);
        return dist * vis * fresnel * ndl;
    }

    /**
     * Reflections (Fresnel) of the terrain on screen and of the sky, and light absorbed by the water body.
     * {@code tintArgb}: the water's colour as drawn (texture and biome tint); {@code skyLight}: 0-1 at the surface.
     */
    private static void shadeWater(int tintArgb, double skyLight, double[] d, double viewDist, double wx, double wy, double wz,
                                   double nx, double ny, double nz, double depthBelow, double[] behind, Atmosphere atm, Sky sky,
                                   PostcardsConfig.RealisticSettings st, Reflections ssr, double[] out) {
        if (ny > 0.5) {
            // Gentle waves. Further away they average out, and the water turns into a calmer mirror.
            double e = 0.04, calm = 1 / (1 + viewDist / 70);
            double h0 = waveHeight(wx, wz);
            nx = -(waveHeight(wx + e, wz) - h0) / e * calm; nz = -(waveHeight(wx, wz + e) - h0) / e * calm; ny = 1;
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= l; ny /= l; nz /= l;
        }
        double cosV = Math.max(0.02, -(d[0] * nx + d[1] * ny + d[2] * nz));
        double fresnel = (0.02 + 0.98 * Math.pow(1 - cosV, 5)) * st.waterReflections;
        double dn = d[0] * nx + d[1] * ny + d[2] * nz;
        double rx = d[0] - 2 * dn * nx, ry = Math.max(0.02, d[1] - 2 * dn * ny), rz = d[2] - 2 * dn * nz;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl; ry /= rl; rz /= rl;
        double[] refl = new double[3], scene = new double[3];
        double skyVis = Math.pow(skyLight, 2);
        // What the reflected ray hits on screen (trees, hills, distant terrain), where it finds something.
        double hit = fresnel > 0.003 ? ssr.trace(wx, wy, wz, rx, ry, rz, scene) : 0;
        // The sky only shows in the reflection where that found nothing (its clouds are slow to compute).
        if (hit < 1) sky.radiance(rx, ry, rz, true, refl);
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
            double skyRefl = refl[k] * (fresnel * skyVis + (1 - skyVis) * 0.02 * fresnel);
            out[k] = body * (1 - fresnel) + skyRefl + (scene[k] * fresnel - skyRefl) * hit;
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

    /** Directional swells (direction x, z, wave number, height) of {@link #waveHeight}. */
    private static final double[][] WAVES = {
            {0.80, 0.60, 0.85, 0.040}, {-0.38, 0.92, 1.55, 0.024}, {0.97, -0.26, 2.70, 0.013}, {-0.71, -0.70, 4.40, 0.007}};

    /** Height of the water surface above its block level, in blocks: a few swells and fine ripples. */
    static double waveHeight(double x, double z) {
        double v = 0;
        for (double[] wv : WAVES) v += Math.sin((wv[0] * x + wv[1] * z) * wv[2] + wv[2] * 7.3) * wv[3];
        return v + (Noise.fbm(x * 1.7, z * 1.7, 3) - 0.5) * 0.05;
    }

    /**
     * Sunlight focused by the waves onto the ground under water: a network of bright lines, which blurs out with
     * depth. {@code depth}: water above the point. 1 = no change; averages to about 1.
     */
    static double caustics(double x, double z, double depth, double lx, double ly, double lz) {
        if (depth <= 0.05) return 1;
        // Where the light passed through the surface above this point.
        double k = depth / Math.max(0.2, ly);
        double px = (x + lx * k) * 0.42, pz = (z + lz * k) * 0.42;
        double a = 1 - Math.abs(Noise.value(px, pz) * 2 - 1), b = 1 - Math.abs(Noise.value(px * 1.37 + 5.2, pz * 1.37 - 3.1) * 2 - 1);
        double lines = Math.pow(a, 7) + Math.pow(b, 7);
        double strength = smoothstep(0.05, 1.2, depth) * Math.exp(-depth * 0.12);
        return Math.max(0, 1 + strength * (lines * 2.6 - 0.55));
    }

    /**
     * The block face texel each pixel shows, from the {@link LodBlocks} pictures, for two things Camera Obscura
     * doesn't do. Textures without flicker in the distance: it samples textures without mipmaps, so where a pixel
     * covers several texels it shows one of them more or less at random, and distant leaves and grass sparkle. There,
     * the albedo is blended towards the face's averaged texture, tinted with the biome colour Camera Obscura uses for
     * that block. And relief on leaves up close ({@link LodBlocks#relief}).
     */
    static final class Texels {
        private final ChunkMeshCache.FastBlockGetter blocks;
        private final double pixelAngle;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final double[] white = {1, 1, 1}, tint = new double[3], avg = new double[3];
        private BlockState tintState;
        private long tintPos = Long.MIN_VALUE;
        /** The surface's normal, facing the camera, and how many texels one pixel covers. */
        final double[] normal = new double[3];
        double footprint;
        private BlockState state;
        private LodBlocks.Baked baked;
        private int face;
        private double a, b;

        Texels(ChunkMeshCache.FastBlockGetter blocks, double pixelAngle) {
            this.blocks = blocks;
            this.pixelAngle = pixelAngle;
        }

        /** Sets {@link #normal} and returns {@link #footprint}. */
        double footprint(int packedNormal, double[] d, double dist) {
            double nx = GBuffer.nx(packedNormal), ny = GBuffer.ny(packedNormal), nz = GBuffer.nz(packedNormal);
            if (nx * d[0] + ny * d[1] + nz * d[2] > 0) { nx = -nx; ny = -ny; nz = -nz; } // two-sided plants
            normal[0] = nx; normal[1] = ny; normal[2] = nz;
            double cos = Math.max(0.15, -(nx * d[0] + ny * d[1] + nz * d[2]));
            return footprint = dist * pixelAngle * LodBlocks.RES / cos;
        }

        /** Finds the face and the spot on it after {@link #footprint}; false where there's no block (entities). */
        boolean locate(double ox, double oy, double oz) {
            double nx = normal[0], ny = normal[1], nz = normal[2];
            pos.set(Mth.floor(ox - nx * 0.02), Mth.floor(oy - ny * 0.02), Mth.floor(oz - nz * 0.02));
            state = blocks.getBlockState(pos);
            baked = LodBlocks.get(state, blocks);
            if (baked.empty) return false;
            double fx = Math.clamp(ox - pos.getX(), 0, 1), fy = Math.clamp(oy - pos.getY(), 0, 1), fz = Math.clamp(oz - pos.getZ(), 0, 1);
            double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
            if (ax >= ay && ax >= az) { face = nx > 0 ? 0 : 1; a = fz; b = fy; }
            else if (ay >= az) { face = ny > 0 ? 2 : 3; a = fx; b = fz; }
            else { face = nz > 0 ? 4 : 5; a = fx; b = fy; }
            return true;
        }

        /** {@code alb}: the surface's linear albedo, blended in place where the pixel covers several texels. */
        void filter(double[] alb) {
            double k = smoothstep(1, 2, footprint);
            if (k <= 0) return;
            if (baked.tint != LodBlocks.Tint.NONE && (state != tintState || pos.asLong() != tintPos)) {
                tintState = state;
                tintPos = pos.asLong();
                ChunkAccess chunk = blocks.getChunkAt(pos);
                int tc = chunk != null ? BlockColors.get(chunk, state, pos) : -1;
                tint[0] = toLinear(((tc >> 16) & 255) / 255.0); tint[1] = toLinear(((tc >> 8) & 255) / 255.0); tint[2] = toLinear((tc & 255) / 255.0);
            }
            LodBlocks.sample(baked, face, a, b, Math.log(footprint) / Math.log(2), baked.tint == LodBlocks.Tint.NONE ? white : tint, avg);
            if (avg[0] + avg[1] + avg[2] <= 0) return;
            for (int c = 0; c < 3; c++) alb[c] += (avg[c] - alb[c]) * k;
        }

        /**
         * Bends {@link #normal} by the slope of the leaf texel's relief, and returns how far out of the gaps the
         * texel lies (1 = on top). Fades out where a pixel covers more than about a texel, where it would only add
         * noise.
         */
        double relief() {
            float[] r = baked.relief[face];
            double fade = 1 - smoothstep(0.6, 1.5, footprint);
            if (r == null || fade <= 0) return 1;
            int t = (Math.min(LodBlocks.RES - 1, (int) (b * LodBlocks.RES)) * LodBlocks.RES + Math.min(LodBlocks.RES - 1, (int) (a * LodBlocks.RES))) * 3;
            double sa = r[t] * RELIEF * fade, sb = r[t + 1] * RELIEF * fade;
            // The picture's axes: X faces show (z, y), Y faces (x, z), Z faces (x, y).
            int axisA = face < 2 ? 2 : 0, axisB = face >= 2 && face < 4 ? 2 : 1;
            normal[axisA] -= sa;
            normal[axisB] -= sb;
            double l = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            normal[0] /= l; normal[1] /= l; normal[2] /= l;
            return 1 - fade * (1 - Math.clamp(r[t + 2] * 1.2, 0, 1));
        }
    }

    /**
     * Screen-space reflections: follows a reflected ray through the picture and returns what it runs into, from the
     * pixels already shaded (the rows above the current one; reflections on water mostly look upwards on screen).
     */
    static final class Reflections {
        private static final int STEPS = 64;
        private final Matrix4d viewProjection;
        private final Vector3d cam, q = new Vector3d(), p = new Vector3d();
        private final float[] r, g, b, dist;
        private final int w, h;
        private final double fx, fy, fz;
        /** Rows before this one are shaded. */
        int row;

        Reflections(SimpleShader.Frame f, int w, int h, float[] r, float[] g, float[] b, float[] dist) {
            this.viewProjection = new Matrix4d(f.inverseViewProjection()).invert();
            this.cam = f.camera();
            this.w = w; this.h = h;
            this.r = r; this.g = g; this.b = b; this.dist = dist;
            // The view direction, to stop at points behind the camera.
            Vector3d centre = f.inverseViewProjection().transformProject(new Vector3d(0, 0, 1)).sub(cam).normalize();
            fx = centre.x; fy = centre.y; fz = centre.z;
        }

        /** Returns how much of the reflection was found (0-1, fading towards the picture's edges) and its colour. */
        double trace(double ox, double oy, double oz, double dx, double dy, double dz, double[] out) {
            double t = 0, dt = 0.35, prevT = 0;
            for (int s = 0; s < STEPS; s++) {
                prevT = t;
                t += dt;
                dt *= 1.13;
                int j = project(ox + dx * t, oy + dy * t, oz + dz * t);
                if (j == -1) return 0;
                if (j == -2) continue;
                double sd = dist[j];
                if (!Double.isFinite(sd)) continue;
                double rd = q.length();
                if (rd < sd) continue;
                if (rd - sd > Math.max(1.5, sd * 0.04) + dt * 1.5) continue; // passed behind something
                // Narrow down where the ray meets the surface.
                double lo = prevT, hi = t;
                for (int k = 0; k < 5; k++) {
                    double mid = (lo + hi) * 0.5;
                    int m = project(ox + dx * mid, oy + dy * mid, oz + dz * mid);
                    if (m >= 0 && Double.isFinite(dist[m]) && q.length() >= dist[m]) { hi = mid; j = m; } else lo = mid;
                }
                out[0] = r[j]; out[1] = g[j]; out[2] = b[j];
                // Fade out towards the edges of the picture, where the reflected object may be cut off.
                int x = j % w, y = j / w;
                double edge = Math.min(Math.min(x, w - 1 - x) / (w * 0.06), y / (h * 0.06));
                return Math.clamp(edge, 0, 1);
            }
            return 0;
        }

        /**
         * Pixel a point is drawn at (q = the point relative to the camera), -1 when off screen or behind the camera,
         * -2 when that pixel isn't shaded yet.
         */
        private int project(double x, double y, double z) {
            q.set(x - cam.x, y - cam.y, z - cam.z);
            if (q.x * fx + q.y * fy + q.z * fz < 0.05) return -1;
            viewProjection.transformProject(x, y, z, p);
            if (p.x < -1 || p.x >= 1 || p.y < -1 || p.y >= 1) return -1;
            int px = (int) ((p.x + 1) * 0.5 * w), py = h - 1 - (int) ((p.y + 1) * 0.5 * h);
            if (py >= row) return -2;
            return py * w + px;
        }
    }

    /**
     * The leaf block next to a point in the air that it's nearest to, if there's one it could stick out of
     * ({@link BushyLeaves}), otherwise {@code air}. All 26 neighbours: the planes' corners cross block edges.
     */
    private static BlockState leavesAround(ChunkMeshCache.FastBlockGetter blocks, BlockPos.MutableBlockPos cursor,
                                           double x, double y, double z, BlockState air) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        BlockState best = air;
        double bestD = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockState n = blocks.getBlockState(cursor.set(bx + dx, by + dy, bz + dz));
                    if (!(n.getBlock() instanceof LeavesBlock)) continue;
                    // Squared distance from the point to that block.
                    double ex = Math.max(0, Math.max(bx + dx - x, x - (bx + dx + 1)));
                    double ey = Math.max(0, Math.max(by + dy - y, y - (by + dy + 1)));
                    double ez = Math.max(0, Math.max(bz + dz - z, z - (bz + dz + 1)));
                    double d2 = ex * ex + ey * ey + ez * ez;
                    if (d2 < bestD) { bestD = d2; best = n; }
                }
            }
        }
        return best;
    }

    /** How a surface is lit: solid, leaves (and vines, which keep their normal), or plants (lit with an up normal). */
    private static final int SOLID = 0, LEAVES = 1, PLANT = 2;

    private static int material(BlockState state) {
        // Air is in #replaceable too; what's found there is an entity, not a plant.
        if (state.isAir()) return SOLID;
        Block block = state.getBlock();
        if (block instanceof LeavesBlock || block instanceof VineBlock || block instanceof MultifaceBlock) return LEAVES;
        return state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CROPS) ? PLANT : SOLID;
    }

    /**
     * Fraction of light reaching a point (voxel walk; leaves let some light through). A point inside a leaf block
     * (an inner face, seen through the gaps of the leaves) gets the light through that block too.
     */
    private static double transmittanceToLight(LightBlockers blockers, double ox, double oy, double oz,
                                               double dx, double dy, double dz, int maxY) {
        int x = Mth.floor(ox), y = Mth.floor(oy), z = Mth.floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? x + 1 - ox : ox - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? y + 1 - oy : oy - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? z + 1 - oz : oz - z) * tDeltaZ);
        double t = blockers.at(x, y, z) == LightBlockers.LEAVES ? LEAF_TRANSMITTANCE : 1;
        for (int i = 0; i < MAX_SHADOW_STEPS; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
            else if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY; }
            else { z += stepZ; tMaxZ += tDeltaZ; }
            if (y > maxY) return t;
            byte kind = blockers.at(x, y, z);
            if (kind == LightBlockers.LEAVES) {
                t *= LEAF_TRANSMITTANCE;
                if (t < 0.04) return 0;
            } else if (kind == LightBlockers.OPAQUE) {
                return 0;
            }
        }
        return t;
    }

    /**
     * Screen-space ambient occlusion (after Alchemy AO), at half resolution: how much of the hemisphere above each
     * surface the geometry around it on screen covers, within {@link #AO_RADIUS} blocks. 1 = open, also where
     * there's no surface. Darkens canopy interiors, plants and the ground under trees, which Minecraft's corner
     * shading leaves alone.
     */
    private static float[] ambientOcclusion(float[] dist, float[] vdx, float[] vdy, float[] vdz, GBuffer gb, byte[] mat,
                                            int w, int h, double pixelAngle) {
        int hw = Math.max(1, w / 2), hh = Math.max(1, h / 2), m = hw * hh;
        float[] hd = new float[m], px = new float[m], py = new float[m], pz = new float[m], nx = new float[m], ny = new float[m], nz = new float[m];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int j = y * hw + x, i = Math.min(h - 1, y * 2) * w + Math.min(w - 1, x * 2);
                float d = dist[i];
                hd[j] = d;
                if (!Float.isFinite(d)) continue;
                px[j] = vdx[i] * d; py[j] = vdy[i] * d; pz[j] = vdz[i] * d;
                if (mat[i] == PLANT) { ny[j] = 1; continue; }
                int np = gb.normal[i];
                double sx = GBuffer.nx(np), sy = GBuffer.ny(np), sz = GBuffer.nz(np);
                double flip = sx * vdx[i] + sy * vdy[i] + sz * vdz[i] > 0 ? -1 : 1;
                nx[j] = (float) (sx * flip); ny[j] = (float) (sy * flip); nz[j] = (float) (sz * flip);
            }
        }
        float[] ao = new float[m];
        double r2 = AO_RADIUS * AO_RADIUS;
        for (int y = 0; y < hh; y++) {
            Throttle.checkpoint();
            for (int x = 0; x < hw; x++) {
                int j = y * hw + x;
                if (!Float.isFinite(hd[j])) { ao[j] = 1; continue; }
                double radius = Math.clamp(AO_RADIUS / (hd[j] * pixelAngle * 2), 1.5, 40);
                // A different rotation of the sample spiral for each pixel of a 4x4 tile; the blur averages them.
                double rot = ((x & 3) * 4 + (y & 3)) * (Math.PI * 2 / 16);
                double sum = 0;
                for (int k = 0; k < AO_SAMPLES; k++) {
                    double a = rot + k * 2.399963, rr = radius * Math.sqrt((k + 0.5) / AO_SAMPLES);
                    int sx = x + (int) Math.round(Math.cos(a) * rr), sy = y + (int) Math.round(Math.sin(a) * rr);
                    if (sx < 0 || sy < 0 || sx >= hw || sy >= hh) continue;
                    int s = sy * hw + sx;
                    if (!Float.isFinite(hd[s])) continue;
                    double vx = px[s] - px[j], vy = py[s] - py[j], vz = pz[s] - pz[j], vv = vx * vx + vy * vy + vz * vz;
                    if (vv < 1e-6 || vv > r2) continue;
                    double cos = (vx * nx[j] + vy * ny[j] + vz * nz[j]) / Math.sqrt(vv);
                    sum += Math.max(0, cos - 0.1) * (1 - vv / r2);
                }
                ao[j] = (float) Math.max(0.4, 1 - 2.0 * sum / AO_SAMPLES);
            }
        }
        ao = softenShadows(ao, hd, hw, hh, 2);
        // Back to full resolution, from the half-resolution neighbours at a similar distance.
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float d = dist[i];
                if (!Float.isFinite(d)) { out[i] = 1; continue; }
                double fx = Math.clamp(x / 2.0, 0, hw - 1), fy = Math.clamp(y / 2.0, 0, hh - 1);
                int x0 = (int) fx, y0 = (int) fy;
                double sum = 0, wsum = 0;
                for (int k = 0; k < 4; k++) {
                    int xx = Math.min(hw - 1, x0 + (k & 1)), yy = Math.min(hh - 1, y0 + (k >> 1));
                    int s = yy * hw + xx;
                    if (!Float.isFinite(hd[s])) continue;
                    double rel = (hd[s] - d) / (0.03 * d + 0.05);
                    double wt = ((k & 1) == 1 ? fx - x0 : 1 - (fx - x0)) * ((k >> 1) == 1 ? fy - y0 : 1 - (fy - y0)) + 1e-3;
                    wt *= Math.exp(-rel * rel);
                    sum += ao[s] * wt;
                    wsum += wt;
                }
                out[i] = wsum > 1e-6 ? (float) (sum / wsum) : 1;
            }
        }
        return out;
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
                               PostcardsConfig.RealisticSettings st) {
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
        io.github.kunaigear.postcards.Postcards.LOGGER.debug("Realistic shader: average luminance {}, exposure {}",
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
                // From here on r, g, b hold the finished picture (sRGB, 0-1).
                r[i] = (float) toSrgb(c[0] * v); g[i] = (float) toSrgb(c[1] * v); b[i] = (float) toSrgb(c[2] * v);
            }
        }
        sharpen(px, r, g, b, w, h, st.sharpness);
    }

    /**
     * Contrast-adaptive sharpening (after AMD's CAS): sharpens less where the picture already has strong edges, so
     * there are no halos. Then quantises to 8 bits, with a little noise that hides banding in smooth skies.
     */
    private static void sharpen(int[] px, float[] r, float[] g, float[] b, int w, int h, double sharpness) {
        float[][] ch = {r, g, b};
        int[] out = new int[3];
        for (int y = 0; y < h; y++) {
            Throttle.checkpoint();
            int up = Math.max(0, y - 1) * w, down = Math.min(h - 1, y + 1) * w, row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x, left = row + Math.max(0, x - 1), right = row + Math.min(w - 1, x + 1);
                double dither = (Noise.hash(x, y, 7) - 0.5) / 255.0;
                for (int k = 0; k < 3; k++) {
                    float[] a = ch[k];
                    double c = a[i], n = a[up + x], s = a[down + x], e = a[right], wv = a[left];
                    if (sharpness > 0) {
                        double mn = Math.min(c, Math.min(Math.min(n, s), Math.min(e, wv)));
                        double mx = Math.max(c, Math.max(Math.max(n, s), Math.max(e, wv)));
                        double amp = Math.sqrt(Math.clamp(Math.min(mn, 1 - mx) / Math.max(mx, 1e-5), 0, 1));
                        double wt = -0.2 * sharpness * amp;
                        c = (c + wt * (n + s + e + wv)) / (1 + 4 * wt);
                    }
                    out[k] = to8(c + dither);
                }
                px[i] = 0xFF000000 | out[0] << 16 | out[1] << 8 | out[2];
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

        Sky(Atmosphere atm, Vector3d cam, PostcardsConfig.RealisticSettings st, double overcast, double starBrightness,
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
            radiance(dx, dy, dz, full, out, full);
        }

        private void radiance(double dx, double dy, double dz, boolean full, double[] out, boolean withClouds) {
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
            if (withClouds && dy > 0.005 && coverage > 0 && cam.y < CLOUD_TOP) {
                cloud(dx, dy, dz, REFLECTION_STEPS, Noise.hash((int) (dx * 7919), (int) (dy * 6007), (int) (dz * 4721)), cl);
                for (int k = 0; k < 3; k++) out[k] = out[k] * cl[3] + cl[k];
            }
        }

        private final double[] cl = new double[4];
        /** Clouds over the picture at half resolution: light (r, g, b) and what's left of the sky behind (a). */
        private float[] cloudLight;
        private int cloudW, cloudH;

        /**
         * Sky behind the picture at a pixel: the sky as {@link #radiance} draws it, with clouds from the half
         * resolution layer made by {@link #prepareClouds}.
         */
        /** How much of the sky behind pixel (x, y) the clouds let through. */
        double cloudTransmittance(int x, int y) {
            if (cloudLight == null) return 1;
            float a = cloudLight[(Math.min(cloudH - 1, y / 2) * cloudW + Math.min(cloudW - 1, x / 2)) * 4 + 3];
            return a < 0 ? 1 : a;
        }

        void background(int x, int y, double dx, double dy, double dz, double[] out) {
            radiance(dx, dy, dz, true, out, false);
            if (cloudLight == null || dy <= 0.005) return;
            // Bilinear, from the cells that were computed.
            double fx = (x + 0.5) / 2 - 0.5, fy = (y + 0.5) / 2 - 0.5;
            int x0 = Mth.floor(fx), y0 = Mth.floor(fy);
            double tx = fx - x0, ty = fy - y0, wsum = 0, r = 0, g = 0, b = 0, a = 0;
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) {
                    int cx = Math.clamp(x0 + i, 0, cloudW - 1), cy = Math.clamp(y0 + j, 0, cloudH - 1), c = (cy * cloudW + cx) * 4;
                    if (cloudLight[c + 3] < 0) continue;
                    double wt = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty) + 1e-4;
                    r += cloudLight[c] * wt; g += cloudLight[c + 1] * wt; b += cloudLight[c + 2] * wt; a += cloudLight[c + 3] * wt;
                    wsum += wt;
                }
            }
            if (wsum <= 0) {
                cloud(dx, dy, dz, CLOUD_STEPS, 0.5, cl);
                r = cl[0]; g = cl[1]; b = cl[2]; a = cl[3];
            } else {
                r /= wsum; g /= wsum; b /= wsum; a /= wsum;
            }
            out[0] = out[0] * a + r; out[1] = out[1] * a + g; out[2] = out[2] * a + b;
        }

        /**
         * Marches the clouds for every pixel where the sky can show (also through terrain fading into it), at half resolution, then blurs them a little:
         * each ray starts at a slightly different depth (a dither), which the blur turns into smooth gradients.
         */
        void prepareClouds(SimpleShader.Frame f, int w, int h, float[] dist, double[] depth, int fbW, int scale, double fadeStart) {
            if (flat != null || coverage <= 0 || cam.y >= CLOUD_TOP) return;
            cloudW = (w + 1) / 2; cloudH = (h + 1) / 2;
            float[] c = new float[cloudW * cloudH * 4];
            Vector3d tmp = new Vector3d();
            for (int y = 0; y < cloudH; y++) {
                Throttle.checkpoint();
                for (int x = 0; x < cloudW; x++) {
                    int o = (y * cloudW + x) * 4;
                    // Not needed where the 2x2 pixels are all solid terrain, closer than where it fades into the sky.
                    boolean needed = false;
                    for (int j = 0; j < 2 && !needed; j++) {
                        for (int i = 0; i < 2 && !needed; i++) {
                            int px = Math.min(w - 1, x * 2 + i), py = Math.min(h - 1, y * 2 + j);
                            if (!(dist[py * w + px] < fadeStart)) needed = true;
                            else {
                                int row = (h - 1 - py) * scale;
                                for (int sy = 0; sy < scale && !needed; sy++)
                                    for (int sx = 0; sx < scale && !needed; sx++)
                                        if (Double.isInfinite(depth[(row + sy) * fbW + px * scale + sx])) needed = true;
                            }
                        }
                    }
                    if (!needed) { c[o + 3] = -1; continue; }
                    double ndcX = (x * 2 + 1.0) / w * 2 - 1, ndcY = 1 - (y * 2 + 1.0) / h * 2;
                    tmp.set(ndcX, ndcY, 1.0);
                    f.inverseViewProjection().transformProject(tmp);
                    double dx = tmp.x - cam.x, dy = tmp.y - cam.y, dz = tmp.z - cam.z, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= l; dy /= l; dz /= l;
                    if (dy <= 0.005) { c[o + 3] = 1; continue; }
                    // Interleaved gradient noise: a dither that blurs out evenly.
                    double ign = (52.9829189 * ((0.06711056 * x + 0.00583715 * y) % 1.0)) % 1.0;
                    cloud(dx, dy, dz, CLOUD_STEPS, ign, cl);
                    c[o] = (float) cl[0]; c[o + 1] = (float) cl[1]; c[o + 2] = (float) cl[2]; c[o + 3] = (float) cl[3];
                }
            }
            cloudLight = blurClouds(c, cloudW, cloudH);
        }

        /** 5-tap separable blur over the computed cells only. */
        private static float[] blurClouds(float[] c, int w, int h) {
            double[] k = {1, 4, 6, 4, 1};
            float[] tmp = new float[c.length], out = new float[c.length];
            for (int pass = 0; pass < 2; pass++) {
                float[] src = pass == 0 ? c : tmp, dst = pass == 0 ? tmp : out;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int o = (y * w + x) * 4;
                        if (src[o + 3] < 0) { dst[o + 3] = -1; continue; }
                        double r = 0, g = 0, b = 0, a = 0, ws = 0;
                        for (int t = -2; t <= 2; t++) {
                            int xx = pass == 0 ? x + t : x, yy = pass == 0 ? y : y + t;
                            if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                            int q = (yy * w + xx) * 4;
                            if (src[q + 3] < 0) continue;
                            double wt = k[t + 2];
                            r += src[q] * wt; g += src[q + 1] * wt; b += src[q + 2] * wt; a += src[q + 3] * wt;
                            ws += wt;
                        }
                        dst[o] = (float) (r / ws); dst[o + 1] = (float) (g / ws); dst[o + 2] = (float) (b / ws); dst[o + 3] = (float) (a / ws);
                    }
                }
            }
            return out;
        }

        /** Extinction of cloud at density 1, per block. */
        private static final double CLOUD_EXTINCTION = 0.045;
        private static final int CLOUD_STEPS = 32, REFLECTION_STEPS = 14;
        private static final double[] LIGHT_STEPS = {18, 40, 85, 170};

        /**
         * Volumetric cumulus: a ray marched through the cloud layer. At each step, light from the sun (or moon) is
         * followed a little way towards it to find how much cloud it passes through. Light that is scattered many
         * times keeps the shaded parts of clouds grey instead of black; a forward-scattering phase function gives
         * them bright edges around the sun. Writes the clouds' light to {@code out[0..2]} and the fraction of the
         * sky behind that still shows to {@code out[3]}. {@code jitter} (0-1) shifts where the steps start.
         */
        private void cloud(double dx, double dy, double dz, int steps, double jitter, double[] out) {
            out[0] = out[1] = out[2] = 0;
            out[3] = 1;
            double t0 = Math.max(0, (CLOUD_BOTTOM - cam.y) / dy), t1 = (CLOUD_TOP - cam.y) / dy;
            if (t0 > 40000) return;
            t1 = Math.min(t1, t0 + 5000);
            boolean sunUp = atm.sy > 0;
            double lx = sunUp ? atm.sx : atm.mx, ly = sunUp ? atm.sy : atm.my, lz = sunUp ? atm.sz : atm.mz;
            double[] irr = sunUp ? atm.sunIrradiance : atm.moonIrradiance;
            double mu = dx * lx + dy * ly + dz * lz;
            // Two Henyey-Greenstein lobes (relative to isotropic): mostly forward, a little back.
            double phase = 0.75 * hg(mu, 0.65) + 0.25 * hg(mu, -0.2);
            double ds = (t1 - t0) / steps;
            double t = t0 + ds * jitter;
            double trans = 1, lr = 0, lg = 0, lb = 0;
            for (int i = 0; i < steps && trans > 0.02; i++, t += ds) {
                double px = cam.x + dx * t, py = cam.y + dy * t, pz = cam.z + dz * t;
                double rho = cloudDensity(px, py, pz, true);
                if (rho <= 0) continue;
                // Cloud between this point and the light.
                double od = 0, prev = 0;
                for (double lt : LIGHT_STEPS) {
                    double ld = cloudDensity(px + lx * lt, py + ly * lt, pz + lz * lt, false);
                    od += ld * (lt - prev);
                    prev = lt;
                }
                od *= CLOUD_EXTINCTION;
                // Multiple scattering (Wrenninge's approximation): ever weaker, wider, less directional octaves.
                double sun = Math.exp(-od) * phase + 0.5 * Math.exp(-od * 0.25) * (0.5 + 0.5 * phase)
                        + 0.3 * Math.exp(-od * 0.06);
                // Edges facing the light are a little darker than the inside of a sunlit cloud ("powder" effect).
                sun *= 1 - 0.35 * Math.exp(-rho * 2.5);
                double hf = (py - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM);
                double amb = (0.5 + 0.5 * hf) * 1.05;
                double stepT = Math.exp(-rho * CLOUD_EXTINCTION * ds), k = trans * (1 - stepT);
                lr += k * (irr[0] * sun * 0.38 + up[0] / Math.PI * amb);
                lg += k * (irr[1] * sun * 0.38 + up[1] / Math.PI * amb);
                lb += k * (irr[2] * sun * 0.38 + up[2] / Math.PI * amb);
                trans *= stepT;
            }
            if (trans >= 1) return;
            // Far clouds fade into the sky near the horizon.
            double fade = Math.exp(-t0 / 22000) * smoothstep(0.005, 0.08, dy);
            double dim = 1 - 0.55 * overcast;
            out[0] = lr * dim * fade; out[1] = lg * dim * fade; out[2] = lb * dim * fade;
            out[3] = 1 - (1 - trans) * fade;
        }

        /** Henyey-Greenstein phase function, 1 = isotropic. */
        static double hg(double mu, double g) {
            double g2 = g * g;
            return (1 - g2) / Math.pow(1 + g2 - 2 * g * mu, 1.5);
        }

        /** Where clouds are, seen from above: 0 = clear sky, 1 = the thickest cloud. */
        private double cloudCover(double x, double z) {
            double px = (x + windX) / 1600.0, pz = (z + windZ) / 1600.0;
            // Domain warping breaks up the regular blobs into cumulus-like shapes.
            double wx = Noise.fbm(px * 0.7 + 3.1, pz * 0.7, 2) - 0.5, wz = Noise.fbm(px * 0.7, pz * 0.7 - 5.3, 2) - 0.5;
            double v = Noise.fbm(px * 2.2 + wx * 1.2, pz * 2.2 + wz * 1.2, 4);
            double edge = 1 - coverage;
            return Math.clamp((v - edge) / Math.max(0.05, 1 - edge) * 1.6, 0, 1);
        }

        /** Cloud density at a point: flat bases, rounded tops where the cloud is thickest, eroded edges. */
        private double cloudDensity(double x, double y, double z, boolean detail) {
            double hf = (y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM);
            if (hf <= 0 || hf >= 1) return 0;
            double c = cloudCover(x, z);
            double shape = c * 1.3 - hf * hf * 1.15 - 0.08;
            shape *= smoothstep(0, 0.07, hf);
            if (shape <= 0) return 0;
            if (detail) {
                double n = Noise.fbm3((x + windX * 1.3) / 140, y / 140, (z + windZ * 1.3) / 140, 2);
                shape -= (n - 0.25) * 0.45 * (1 - Math.min(1, shape * 1.5));
            }
            return Math.clamp(shape * 1.8, 0, 1);
        }

        /** Sunlight let through by the clouds above a point. */
        double cloudShadow(double x, double y, double z) {
            // With a low sun, cloud shadows would stretch for kilometres into streaks; fade them out instead.
            double strength = 0.75 * smoothstep(0.18, 0.45, atm.sy);
            if (coverage <= 0 || strength <= 0 || y >= CLOUD_BOTTOM) return 1;
            double t = ((CLOUD_BOTTOM + CLOUD_TOP) * 0.4 - y) / atm.sy;
            return 1 - strength * smoothstep(0.02, 0.35, cloudCover(x + atm.sx * t, z + atm.sz * t));
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

        static double value3(double x, double y, double z) {
            int xi = Mth.floor(x), yi = Mth.floor(y), zi = Mth.floor(z);
            double fx = x - xi, fy = y - yi, fz = z - zi;
            double u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy), w = fz * fz * (3 - 2 * fz);
            double a = lerp(u, hash(xi, yi, zi), hash(xi + 1, yi, zi)), b = lerp(u, hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi));
            double c = lerp(u, hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1)), d = lerp(u, hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1));
            return lerp(w, lerp(v, a, b), lerp(v, c, d));
        }

        private static double lerp(double t, double a, double b) {
            return a + (b - a) * t;
        }

        static double fbm3(double x, double y, double z, int octaves) {
            double sum = 0, amp = 0.5, norm = 0;
            for (int o = 0; o < octaves; o++) {
                sum += value3(x, y, z) * amp;
                norm += amp;
                x = x * 2.03 + 17.1;
                y = y * 2.03 + 4.3;
                z = z * 2.03 - 9.7;
                amp *= 0.5;
            }
            return sum / norm;
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

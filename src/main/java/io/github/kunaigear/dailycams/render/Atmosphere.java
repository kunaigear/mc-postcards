package io.github.kunaigear.dailycams.render;

/**
 * A physically based sky for the "realistic" shader: single scattering in an Earth-sized atmosphere with Rayleigh
 * scattering (blue sky), Mie scattering (bright haze around the sun) and an ozone layer (deep blue at twilight).
 * The scattered light for every viewing direction is precomputed once per picture into a small table, for the sun
 * and for the moon. Radiance units: a white surface facing the noon sun reflects about 1.
 */
final class Atmosphere {
    private static final double RG = 6_360_000, RT = 6_460_000;
    private static final double[] BETA_R = {5.802e-6, 13.558e-6, 33.1e-6};
    private static final double[] BETA_O = {0.650e-6, 1.881e-6, 0.085e-6};
    private static final double HR = 8000, HM = 1200, MIE_SCATTER = 3.0e-6, MIE_EXTINCT = 3.3e-6, MIE_G = 0.76;
    /** Light lost to single scattering that comes back as multiple scattering (rough approximation). */
    private static final double MULTI_SCATTER = 2.5;

    static final double SUN_ILLUMINANCE = 3.0;
    /** Faint light of the night sky itself (airglow, scattered starlight). */
    private static final double[] NIGHT_SKY = {0.0009, 0.0013, 0.0028};
    private static final double[] GROUND_ALBEDO = {0.17, 0.20, 0.13};

    private static final int AZ = 64, EL = 96, VIEW_STEPS = 24, LIGHT_STEPS = 8;
    /** Elevation (sine) whose sky colour stands in for haze over the horizon. */
    static final double HAZE_ELEVATION = 0.05;

    final double sx, sy, sz, mx, my, mz;
    final double[] sunIrradiance = new double[3], moonIrradiance = new double[3];
    /** Irradiance from sky and ground (no direct sun) arriving on surfaces facing +X, -X, +Y, -Y, +Z, -Z. */
    final double[][] ambient = new double[6][3];
    final double overcast;
    private final double[] sunChroma = new double[3];
    private double twilightWarmth;

    private final double camAlt;
    private final float[] sunTable, moonTable;
    private final double sunHx, sunHz, moonHx, moonHz;

    Atmosphere(double sx, double sy, double sz, double mx, double my, double mz, double moonBrightness,
               double cameraAltitude, double overcast) {
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.mx = mx; this.my = my; this.mz = mz;
        this.camAlt = cameraAltitude;
        this.overcast = overcast;
        double sh = Math.hypot(sx, sz), mh = Math.hypot(mx, mz);
        this.sunHx = sh > 1e-6 ? sx / sh : 1; this.sunHz = sh > 1e-6 ? sz / sh : 0;
        this.moonHx = mh > 1e-6 ? mx / mh : -1; this.moonHz = mh > 1e-6 ? mz / mh : 0;

        double moonIlluminance = SUN_ILLUMINANCE * 0.006 * moonBrightness;
        this.sunTable = sy > -0.3 ? table(sy, SUN_ILLUMINANCE) : null;
        this.moonTable = my > -0.3 ? table(my, moonIlluminance) : null;

        double[] t = new double[3];
        transmittance(0, RG + camAlt, 0, sx, sy, sz, t);
        for (int c = 0; c < 3; c++) sunIrradiance[c] = SUN_ILLUMINANCE * t[c] * (1 - 0.9 * overcast);
        transmittance(0, RG + camAlt, 0, mx, my, mz, t);
        for (int c = 0; c < 3; c++) moonIrradiance[c] = moonIlluminance * t[c] * (1 - 0.9 * overcast);
        double sunLum = 0.2126 * sunIrradiance[0] + 0.7152 * sunIrradiance[1] + 0.0722 * sunIrradiance[2];
        for (int c = 0; c < 3; c++) sunChroma[c] = sunLum > 1e-6 ? Math.min(3, sunIrradiance[c] / sunLum) : 1;
        twilightWarmth = 0.45 * RealisticShader.smoothstep(-0.12, 0.02, sy) * (1 - RealisticShader.smoothstep(0.2, 0.45, sy));
        computeAmbient();
        io.github.kunaigear.dailycams.DailyCams.LOGGER.debug("Atmosphere: sun {} {} {}, sky on top faces {} {} {}",
                String.format("%.3f", sunIrradiance[0]), String.format("%.3f", sunIrradiance[1]), String.format("%.3f", sunIrradiance[2]),
                String.format("%.3f", ambient[2][0]), String.format("%.3f", ambient[2][1]), String.format("%.3f", ambient[2][2]));
    }

    /** Sky radiance looking in direction {@code d} (unit vector), without sun, moon, stars or clouds. */
    void sky(double dx, double dy, double dz, double[] out) {
        double below = 0;
        if (dy < HAZE_ELEVATION) {
            // Beyond the loaded world: distant land hidden in haze. Use the sky just above the horizon (right at
            // the horizon the air path is so long that it turns grey), slightly darker further down.
            below = Math.min(1, Math.max(0, -dy) * 3);
            double hl = Math.hypot(dx, dz);
            double s = hl > 1e-6 ? Math.sqrt(1 - HAZE_ELEVATION * HAZE_ELEVATION) / hl : 0;
            dx *= s; dz *= s; dy = HAZE_ELEVATION;
        }
        out[0] = NIGHT_SKY[0]; out[1] = NIGHT_SKY[1]; out[2] = NIGHT_SKY[2];
        double hl = Math.hypot(dx, dz);
        double el = Math.asin(Math.clamp(dy, -1, 1));
        double u = Math.signum(el) * Math.sqrt(Math.abs(el) / (Math.PI / 2));
        double jf = (u + 1) * 0.5 * (EL - 1);
        if (sunTable != null) add(sunTable, azimuth(dx, dz, hl, sunHx, sunHz), jf, out);
        if (moonTable != null) add(moonTable, azimuth(dx, dz, hl, moonHx, moonHz), jf, out);
        if (overcast > 0) {
            // Under a cloud deck the sky is grey and a little darker.
            double lum = 0.2126 * out[0] + 0.7152 * out[1] + 0.0722 * out[2];
            double k = overcast * 0.85, dim = 1 - 0.35 * overcast;
            out[0] = (out[0] + (lum * 0.95 - out[0]) * k) * dim;
            out[1] = (out[1] + (lum * 1.0 - out[1]) * k) * dim;
            out[2] = (out[2] + (lum * 1.06 - out[2]) * k) * dim;
        }
        if (below > 0) {
            double f = 1 - 0.12 * below;
            out[0] *= f; out[1] *= f; out[2] *= f;
        }
        if (twilightWarmth > 0) {
            // Around sunrise and sunset, light near the horizon has travelled through so much air that it takes
            // on the colour of the low sun (single scattering alone leaves it grey-green).
            double f = twilightWarmth * (1 - RealisticShader.smoothstep(0, 0.3, dy));
            for (int c = 0; c < 3; c++) out[c] *= 1 - f + f * sunChroma[c];
        }
    }

    /** Sky and ground irradiance on a surface with normal {@code n}, from the ambient cube. */
    void ambient(double nx, double ny, double nz, double[] out) {
        double[] x = nx >= 0 ? ambient[0] : ambient[1], y = ny >= 0 ? ambient[2] : ambient[3], z = nz >= 0 ? ambient[4] : ambient[5];
        double wx = nx * nx, wy = ny * ny, wz = nz * nz;
        for (int c = 0; c < 3; c++) out[c] = x[c] * wx + y[c] * wy + z[c] * wz;
    }

    private static double azimuth(double dx, double dz, double hl, double hx, double hz) {
        if (hl < 1e-6) return 0;
        return Math.acos(Math.clamp((dx * hx + dz * hz) / hl, -1, 1)) / Math.PI * (AZ - 1);
    }

    private static void add(float[] table, double af, double jf, double[] out) {
        int a0 = (int) af, j0 = (int) jf;
        int a1 = Math.min(a0 + 1, AZ - 1), j1 = Math.min(j0 + 1, EL - 1);
        double ta = af - a0, tj = jf - j0;
        for (int c = 0; c < 3; c++) {
            double v00 = table[(j0 * AZ + a0) * 3 + c], v01 = table[(j0 * AZ + a1) * 3 + c];
            double v10 = table[(j1 * AZ + a0) * 3 + c], v11 = table[(j1 * AZ + a1) * 3 + c];
            out[c] += (v00 + (v01 - v00) * ta) * (1 - tj) + (v10 + (v11 - v10) * ta) * tj;
        }
    }

    /** Precomputes scattered light for every view direction, for a light at the given elevation (sin). */
    private float[] table(double lightY, double illuminance) {
        float[] t = new float[AZ * EL * 3];
        double ly = Math.clamp(lightY, -1, 1), lx = Math.sqrt(Math.max(0, 1 - ly * ly));
        double[] acc = new double[3];
        for (int j = 0; j < EL; j++) {
            double u = 2.0 * j / (EL - 1) - 1;
            double el = Math.signum(u) * u * u * Math.PI / 2;
            double cy = Math.sin(el), ch = Math.cos(el);
            for (int a = 0; a < AZ; a++) {
                double phi = Math.PI * a / (AZ - 1);
                scatter(ch * Math.cos(phi), cy, ch * Math.sin(phi), lx, ly, 0, acc);
                for (int c = 0; c < 3; c++) t[(j * AZ + a) * 3 + c] = (float) (acc[c] * illuminance);
            }
        }
        return t;
    }

    /** Light scattered towards the camera along a view ray, per unit of illuminance. */
    private void scatter(double dx, double dy, double dz, double lx, double ly, double lz, double[] out) {
        double oy = RG + camAlt;
        double tMax = farHit(0, oy, 0, dx, dy, dz, RT);
        double tg = nearHit(0, oy, 0, dx, dy, dz, RG);
        if (tg > 0) tMax = Math.min(tMax, tg);
        double ds = tMax / VIEW_STEPS;
        double mu = dx * lx + dy * ly + dz * lz;
        double pr = 3.0 / (16 * Math.PI) * (1 + mu * mu);
        double g2 = MIE_G * MIE_G;
        double pm = 3.0 / (8 * Math.PI) * ((1 - g2) * (1 + mu * mu)) / ((2 + g2) * Math.pow(1 + g2 - 2 * MIE_G * mu, 1.5));
        double tr = 0, tgOpt = 0, tb = 0;
        out[0] = out[1] = out[2] = 0;
        double[] tl = new double[3];
        for (int i = 0; i < VIEW_STEPS; i++) {
            double t = (i + 0.5) * ds;
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - RG;
            double dr = Math.exp(-h / HR), dm = Math.exp(-h / HM), dozone = Math.max(0, 1 - Math.abs(h - 25000) / 15000);
            double hr = 0.5 * ds;
            tr += (BETA_R[0] * dr + MIE_EXTINCT * dm + BETA_O[0] * dozone) * hr;
            tgOpt += (BETA_R[1] * dr + MIE_EXTINCT * dm + BETA_O[1] * dozone) * hr;
            tb += (BETA_R[2] * dr + MIE_EXTINCT * dm + BETA_O[2] * dozone) * hr;
            transmittance(px, py, pz, lx, ly, lz, tl);
            // Multiple scattering mostly brightens the diffuse Rayleigh sky, not the sharp Mie glow around the sun.
            double sm = MIE_SCATTER * dm * pm, ms = MULTI_SCATTER;
            out[0] += Math.exp(-tr) * tl[0] * (BETA_R[0] * dr * pr * ms + sm) * ds;
            out[1] += Math.exp(-tgOpt) * tl[1] * (BETA_R[1] * dr * pr * ms + sm) * ds;
            out[2] += Math.exp(-tb) * tl[2] * (BETA_R[2] * dr * pr * ms + sm) * ds;
            tr += (BETA_R[0] * dr + MIE_EXTINCT * dm + BETA_O[0] * dozone) * hr;
            tgOpt += (BETA_R[1] * dr + MIE_EXTINCT * dm + BETA_O[1] * dozone) * hr;
            tb += (BETA_R[2] * dr + MIE_EXTINCT * dm + BETA_O[2] * dozone) * hr;
        }
    }

    /** Transmittance from a point towards a light direction (0 inside the planet's shadow). */
    private static void transmittance(double px, double py, double pz, double lx, double ly, double lz, double[] out) {
        if (nearHit(px, py, pz, lx, ly, lz, RG) > 0) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        double tMax = farHit(px, py, pz, lx, ly, lz, RT);
        double ds = tMax / LIGHT_STEPS;
        double tr = 0, tg = 0, tb = 0;
        for (int i = 0; i < LIGHT_STEPS; i++) {
            double t = (i + 0.5) * ds;
            double x = px + lx * t, y = py + ly * t, z = pz + lz * t;
            double h = Math.sqrt(x * x + y * y + z * z) - RG;
            double dr = Math.exp(-h / HR), dm = Math.exp(-h / HM), dozone = Math.max(0, 1 - Math.abs(h - 25000) / 15000);
            tr += (BETA_R[0] * dr + MIE_EXTINCT * dm + BETA_O[0] * dozone) * ds;
            tg += (BETA_R[1] * dr + MIE_EXTINCT * dm + BETA_O[1] * dozone) * ds;
            tb += (BETA_R[2] * dr + MIE_EXTINCT * dm + BETA_O[2] * dozone) * ds;
        }
        out[0] = Math.exp(-tr);
        out[1] = Math.exp(-tg);
        out[2] = Math.exp(-tb);
    }

    /** Distance to where a ray from inside a sphere leaves it. */
    private static double farHit(double ox, double oy, double oz, double dx, double dy, double dz, double r) {
        double b = ox * dx + oy * dy + oz * dz, c = ox * ox + oy * oy + oz * oz - r * r;
        double disc = b * b - c;
        return disc < 0 ? 0 : -b + Math.sqrt(disc);
    }

    /** Distance to where a ray from outside a sphere first hits it, or -1. */
    private static double nearHit(double ox, double oy, double oz, double dx, double dy, double dz, double r) {
        double b = ox * dx + oy * dy + oz * dz, c = ox * ox + oy * oy + oz * oz - r * r;
        double disc = b * b - c;
        if (disc < 0) return -1;
        double t = -b - Math.sqrt(disc);
        return t > 0 ? t : -1;
    }

    /** Integrates sky (and ground, for downward directions) over each hemisphere of the ambient cube. */
    private void computeAmbient() {
        int k = 256;
        double[][] dirs = new double[k][];
        double[][] rad = new double[k][];
        double golden = Math.PI * (3 - Math.sqrt(5));
        double[] upSky = new double[3];
        double dw = 4 * Math.PI / k;
        for (int i = 0; i < k; i++) {
            double y = 1 - (i + 0.5) * 2.0 / k, r = Math.sqrt(1 - y * y), phi = i * golden;
            double[] d = {Math.cos(phi) * r, y, Math.sin(phi) * r};
            dirs[i] = d;
            if (y > 0) {
                double[] l = new double[3];
                sky(d[0], d[1], d[2], l);
                rad[i] = l;
                for (int c = 0; c < 3; c++) upSky[c] += l[c] * y * dw;
            }
        }
        double[] ground = new double[3];
        for (int c = 0; c < 3; c++) {
            double e = upSky[c] + sunIrradiance[c] * Math.max(0, sy) + moonIrradiance[c] * Math.max(0, my);
            ground[c] = GROUND_ALBEDO[c] * e / Math.PI;
        }
        double[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int a = 0; a < 6; a++) {
            double[] e = ambient[a];
            for (int i = 0; i < k; i++) {
                double[] d = dirs[i];
                double cos = d[0] * axes[a][0] + d[1] * axes[a][1] + d[2] * axes[a][2];
                if (cos <= 0) continue;
                double[] l = d[1] > 0 ? rad[i] : ground;
                for (int c = 0; c < 3; c++) e[c] += l[c] * cos * dw;
            }
        }
    }
}

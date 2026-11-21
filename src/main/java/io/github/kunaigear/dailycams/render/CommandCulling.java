package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.obscura.sore.model.Model;
import io.github.kunaigear.dailycams.obscura.sore.rasterizer.Framebuffer;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;

import java.util.Arrays;

/**
 * Finds draw commands that can't change a pixel, so Camera Obscura doesn't transform and rasterize them. It draws
 * every block of every chunk section in view, but most of them are off the screen or behind terrain drawn before
 * them, and their fragments fail the depth test one by one: in a typical picture only one in six covered pixels
 * survives it. A command is left out when all its vertices are beyond the same side of the screen, all of them in
 * front of the near plane (where Camera Obscura drops their triangles anyway), or when it's farther away than
 * everything already drawn wherever it could cover the screen.
 * <p>
 * For that last test, the depth buffer is kept as the farthest depth in each tile of 16 × 16 samples. Depth only
 * ever gets nearer during a frame, so a tile's value stays an upper bound even when it's out of date; tiles where a
 * fragment was drawn are measured again when next needed. Render thread only.
 */
public final class CommandCulling {
    /** Camera Obscura's near plane (its clipping drops vertices with a smaller clip w). */
    private static final double NEAR = 0.1f;
    /** Room for rounding, far less than the half pixel to the nearest pixel centre or a block's depth. */
    private static final double MARGIN = 1e-6, DEPTH_MARGIN = 1e-9;
    private static final int TILE_SHIFT = 4, TILE = 1 << TILE_SHIFT;
    private static final Matrix4d MVP = new Matrix4d();

    private static double[] depth = new double[0];
    private static int width, height, tilesX;
    private static double[] farthest = new double[0];
    private static boolean[] drawnSince = new boolean[0];

    private CommandCulling() {}

    /** Before a frame's draw commands. */
    public static void begin(Framebuffer framebuffer) {
        depth = framebuffer.getDepthBuffer();
        width = framebuffer.width;
        height = framebuffer.height;
        tilesX = (width + TILE - 1) >> TILE_SHIFT;
        int tiles = tilesX * ((height + TILE - 1) >> TILE_SHIFT);
        if (farthest.length != tiles) {
            farthest = new double[tiles];
            drawnSince = new boolean[tiles];
        }
        // Camera Obscura clears the depth buffer before drawing; measure every tile on first use.
        Arrays.fill(farthest, Double.POSITIVE_INFINITY);
        Arrays.fill(drawnSince, true);
    }

    /** After drawing the frame, also when it failed. Lets go of the depth buffer, which the next render doesn't need. */
    public static void end() {
        depth = new double[0];
    }

    /** A fragment was drawn at framebuffer index {@code idx}, so the depth there may have changed. */
    public static void drawn(int idx) {
        drawnSince[(idx / width >> TILE_SHIFT) * tilesX + (idx % width >> TILE_SHIFT)] = true;
    }

    /** {@code viewProjection} is the projection times the view matrix, as the pipeline multiplies them. */
    public static boolean culled(Model model, Matrix4dc viewProjection, Matrix4dc transform) {
        return test(model.positions(), viewProjection, transform, true);
    }

    /**
     * Whether {@link #culled} would leave out a model with these positions for being off the screen or in front of the
     * near plane, which doesn't depend on what was drawn before. Lets the meshing skip what would never be drawn.
     */
    public static boolean offScreen(float[] positions, Matrix4dc viewProjection, Matrix4dc transform) {
        return test(positions, viewProjection, transform, false);
    }

    private static boolean test(float[] p, Matrix4dc viewProjection, Matrix4dc transform, boolean behind) {
        if (p.length == 0) return false;
        Matrix4d m = MVP.set(viewProjection).mul(transform);
        boolean left = true, right = true, below = true, above = true, near = true, anyNear = false;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY,
                maxY = Double.NEGATIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        for (int i = 0; i < p.length; i += 3) {
            double x = p[i], y = p[i + 1], z = p[i + 2];
            double cx = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            double cy = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            double cz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
            double cw = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
            double slack = MARGIN * (1 + Math.abs(cw));
            left &= cx < -cw - slack;
            right &= cx > cw + slack;
            below &= cy < -cw - slack;
            above &= cy > cw + slack;
            near &= cw < NEAR - slack;
            if (cw < NEAR + slack) {
                anyNear = true;
            } else {
                double sx = (cx / cw * 0.5 + 0.5) * width, sy = (cy / cw * 0.5 + 0.5) * height;
                minX = Math.min(minX, sx);
                maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
                minZ = Math.min(minZ, cz / cw);
            }
        }
        if (left | right | below | above | near) return true;
        if (!behind) return false;
        // Clipped at the near plane, a triangle's corners move: only test what's wholly behind it.
        return !anyNear && behindDrawn(minX - 1, minY - 1, maxX + 1, maxY + 1, minZ - DEPTH_MARGIN * (1 + Math.abs(minZ)));
    }

    /** Whether every sample in the screen rectangle already holds something nearer than {@code z}. */
    private static boolean behindDrawn(double minX, double minY, double maxX, double maxY, double z) {
        int x0 = Math.max(0, (int) Math.floor(minX)) >> TILE_SHIFT, y0 = Math.max(0, (int) Math.floor(minY)) >> TILE_SHIFT;
        int x1 = Math.min(width - 1, (int) Math.ceil(maxX)) >> TILE_SHIFT, y1 = Math.min(height - 1, (int) Math.ceil(maxY)) >> TILE_SHIFT;
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int t = ty * tilesX + tx;
                if (drawnSince[t]) measure(t, tx, ty);
                if (!(farthest[t] < z)) return false;
            }
        }
        return true;
    }

    private static void measure(int t, int tx, int ty) {
        double max = Double.NEGATIVE_INFINITY;
        int xEnd = Math.min(width, (tx + 1) << TILE_SHIFT), yEnd = Math.min(height, (ty + 1) << TILE_SHIFT);
        for (int y = ty << TILE_SHIFT; y < yEnd; y++) {
            int row = y * width;
            for (int x = tx << TILE_SHIFT; x < xEnd; x++) max = Math.max(max, depth[row + x]);
        }
        farthest[t] = max;
        drawnSince[t] = false;
    }
}

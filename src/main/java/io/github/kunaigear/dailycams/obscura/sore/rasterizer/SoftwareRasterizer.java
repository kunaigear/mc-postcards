/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.sore.rasterizer;

import io.github.kunaigear.dailycams.obscura.sore.ScreenVertex;
import io.github.kunaigear.dailycams.obscura.sore.Triangle;
import io.github.kunaigear.dailycams.obscura.sore.Uniforms;
import io.github.kunaigear.dailycams.obscura.sore.shader.FragmentIn;
import io.github.kunaigear.dailycams.obscura.sore.shader.Shader;
import io.github.kunaigear.dailycams.obscura.sore.shader.VertexOut;

import io.github.kunaigear.dailycams.obscura.sore.shader.MatteShader;
import io.github.kunaigear.dailycams.render.CommandCulling;
import io.github.kunaigear.dailycams.render.GBuffer;
import io.github.kunaigear.dailycams.render.RenderFlags;
import io.github.kunaigear.dailycams.render.SeeThrough;

import java.util.Arrays;

public class SoftwareRasterizer implements Rasterizer {
    private final Framebuffer framebuffer;
    private final double[] attrWorldX = new double[3];
    private final double[] attrWorldY = new double[3];
    private final double[] attrWorldZ = new double[3];
    private final double[] attrNormalX = new double[3];
    private final double[] attrNormalY = new double[3];
    private final double[] attrNormalZ = new double[3];
    private final double[] attrLocalNormalX = new double[3];
    private final double[] attrLocalNormalY = new double[3];
    private final double[] attrLocalNormalZ = new double[3];
    private final double[] attrU = new double[3];
    private final double[] attrV = new double[3];
    private final double[] attrAO = new double[3];
    private final double[] attrSkyLight = new double[3];
    private final double[] attrBlockLight = new double[3];

    public SoftwareRasterizer(Framebuffer framebuffer) {
        this.framebuffer = framebuffer;
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static int blend(int src, int dst, int alpha) {
        int invAlpha = 255 - alpha;
        int sr = (src >> 16) & 0xFF;
        int sg = (src >> 8) & 0xFF;
        int sb = src & 0xFF;
        int dr = (dst >> 16) & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = dst & 0xFF;
        int r = (sr * alpha + dr * invAlpha) >> 8;
        int g = (sg * alpha + dg * invAlpha) >> 8;
        int b = (sb * alpha + db * invAlpha) >> 8;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    @Override
    public void clear(int color) {
        framebuffer.clearDepth(Float.POSITIVE_INFINITY);
        Arrays.fill(framebuffer.getColorBuffer(), color);
    }

    @Override
    public void drawTriangle(Shader shader, Triangle tri, FragmentIn fIn, boolean writeDepth, boolean doubleSided, Uniforms uniforms) {
        ScreenVertex a = tri.a;
        ScreenVertex b = tri.b;
        ScreenVertex c = tri.c;

        int minX = Math.max(0, (int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))));
        int maxX = Math.min(framebuffer.width - 1, (int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))));
        int maxY = Math.min(framebuffer.height - 1, (int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))));

        double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
        if (!doubleSided && area < 0) return;
        double invArea = 1.0f / area;

        double stepW0X = (b.y() - c.y()) * invArea;
        double stepW1X = (c.y() - a.y()) * invArea;
        double stepW2X = (a.y() - b.y()) * invArea;
        double stepW0Y = (c.x() - b.x()) * invArea;
        double stepW1Y = (a.x() - c.x()) * invArea;
        double stepW2Y = (b.x() - a.x()) * invArea;

        double a_z = a.z(), b_z = b.z(), c_z = c.z();
        double a_invW = a.invW(), b_invW = b.invW(), c_invW = c.invW();
        VertexOut v0 = a.v(), v1 = b.v(), v2 = c.v();

        attrWorldX[0] = v0.worldX * a_invW;
        attrWorldX[1] = v1.worldX * b_invW;
        attrWorldX[2] = v2.worldX * c_invW;

        attrWorldY[0] = v0.worldY * a_invW;
        attrWorldY[1] = v1.worldY * b_invW;
        attrWorldY[2] = v2.worldY * c_invW;

        attrWorldZ[0] = v0.worldZ * a_invW;
        attrWorldZ[1] = v1.worldZ * b_invW;
        attrWorldZ[2] = v2.worldZ * c_invW;

        boolean normals = uniforms.normals;
        attrNormalX[0] = v0.normalX * a_invW;
        attrNormalX[1] = v1.normalX * b_invW;
        attrNormalX[2] = v2.normalX * c_invW;

        attrNormalY[0] = v0.normalY * a_invW;
        attrNormalY[1] = v1.normalY * b_invW;
        attrNormalY[2] = v2.normalY * c_invW;

        attrNormalZ[0] = v0.normalZ * a_invW;
        attrNormalZ[1] = v1.normalZ * b_invW;
        attrNormalZ[2] = v2.normalZ * c_invW;

        attrLocalNormalX[0] = v0.localNormalX * a_invW;
        attrLocalNormalX[1] = v1.localNormalX * b_invW;
        attrLocalNormalX[2] = v2.localNormalX * c_invW;

        attrLocalNormalY[0] = v0.localNormalY * a_invW;
        attrLocalNormalY[1] = v1.localNormalY * b_invW;
        attrLocalNormalY[2] = v2.localNormalY * c_invW;

        attrLocalNormalZ[0] = v0.localNormalZ * a_invW;
        attrLocalNormalZ[1] = v1.localNormalZ * b_invW;
        attrLocalNormalZ[2] = v2.localNormalZ * c_invW;

        attrU[0] = v0.u * a_invW;
        attrU[1] = v1.u * b_invW;
        attrU[2] = v2.u * c_invW;

        attrV[0] = v0.v * a_invW;
        attrV[1] = v1.v * b_invW;
        attrV[2] = v2.v * c_invW;

        attrAO[0] = v0.ao * a_invW;
        attrAO[1] = v1.ao * b_invW;
        attrAO[2] = v2.ao * c_invW;

        attrSkyLight[0] = v0.skyLight * a_invW;
        attrSkyLight[1] = v1.skyLight * b_invW;
        attrSkyLight[2] = v2.skyLight * c_invW;

        attrBlockLight[0] = v0.blockLight * a_invW;
        attrBlockLight[1] = v1.blockLight * b_invW;
        attrBlockLight[2] = v2.blockLight * c_invW;

        double depthStepX = stepW0X * a_z + stepW1X * b_z + stepW2X * c_z;

        // row barycentrics
        double rowW0 = edge(b.x(), b.y(), c.x(), c.y(), minX + 0.5f, minY + 0.5f) * invArea;
        double rowW1 = edge(c.x(), c.y(), a.x(), a.y(), minX + 0.5f, minY + 0.5f) * invArea;
        double rowW2 = edge(a.x(), a.y(), b.x(), b.y(), minX + 0.5f, minY + 0.5f) * invArea;

        int fbWidth = framebuffer.width;
        // The realistic shader records what each pixel shows (GBuffer) and lights it again itself, and distant
        // terrain needs glass and water drawn over empty background (SeeThrough). Both turn off the fog here.
        GBuffer gbuffer = GBuffer.active();
        SeeThrough seeThrough = SeeThrough.active();
        boolean record = shader instanceof MatteShader && (gbuffer != null || RenderFlags.noFog());
        double[] depthBuf = framebuffer.getDepthBuffer();
        int[] colorBuf = framebuffer.getColorBuffer();

        for (int y = minY; y <= maxY; y++) {
            double w0 = rowW0;
            double w1 = rowW1;
            double w2 = rowW2;

            double depthRow = w0 * a_z + w1 * b_z + w2 * c_z;

            int rowStartIdx = y * fbWidth;

            for (int x = minX; x <= maxX; x++) {
                if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
                    int idx = rowStartIdx + x;

                    if (depthRow <= depthBuf[idx]) {
                        // perspective correction
                        double recip = 1.0f / (w0 * a_invW + w1 * b_invW + w2 * c_invW);

                        fIn.worldX = (w0 * attrWorldX[0] + w1 * attrWorldX[1] + w2 * attrWorldX[2]) * recip;
                        fIn.worldY = (w0 * attrWorldY[0] + w1 * attrWorldY[1] + w2 * attrWorldY[2]) * recip;
                        fIn.worldZ = (w0 * attrWorldZ[0] + w1 * attrWorldZ[1] + w2 * attrWorldZ[2]) * recip;

                        if (normals) {
                            fIn.normalX = (w0 * attrNormalX[0] + w1 * attrNormalX[1] + w2 * attrNormalX[2]) * recip;
                            fIn.normalY = (w0 * attrNormalY[0] + w1 * attrNormalY[1] + w2 * attrNormalY[2]) * recip;
                            fIn.normalZ = (w0 * attrNormalZ[0] + w1 * attrNormalZ[1] + w2 * attrNormalZ[2]) * recip;
                        }

                        fIn.localNormalX = (w0 * attrLocalNormalX[0] + w1 * attrLocalNormalX[1] + w2 * attrLocalNormalX[2]) * recip;
                        fIn.localNormalY = (w0 * attrLocalNormalY[0] + w1 * attrLocalNormalY[1] + w2 * attrLocalNormalY[2]) * recip;
                        fIn.localNormalZ = (w0 * attrLocalNormalZ[0] + w1 * attrLocalNormalZ[1] + w2 * attrLocalNormalZ[2]) * recip;

                        fIn.u = (w0 * attrU[0] + w1 * attrU[1] + w2 * attrU[2]) * recip;
                        fIn.v = (w0 * attrV[0] + w1 * attrV[1] + w2 * attrV[2]) * recip;

                        fIn.ao = (w0 * attrAO[0] + w1 * attrAO[1] + w2 * attrAO[2]) * recip;
                        fIn.skyLight = (w0 * attrSkyLight[0] + w1 * attrSkyLight[1] + w2 * attrSkyLight[2]) * recip;
                        fIn.blockLight = (w0 * attrBlockLight[0] + w1 * attrBlockLight[1] + w2 * attrBlockLight[2]) * recip;

                        fIn.texture = v0.texture;
                        fIn.tint = v0.tint;
                        fIn.shade = v0.shade;

                        CommandCulling.drawn(idx);
                        int src = record ? recordFragment(shader, fIn, uniforms, idx, gbuffer, seeThrough) : shader.fragment(fIn, uniforms);
                        int alpha = (src >>> 24) & 0xFF;

                        if (alpha == 255) {
                            if (writeDepth) depthBuf[idx] = depthRow;
                            colorBuf[idx] = src;
                        } else if (alpha != 0) {
                            colorBuf[idx] = blend(src, colorBuf[idx], alpha);
                        }
                    }
                }

                w0 += stepW0X;
                w1 += stepW1X;
                w2 += stepW2X;
                depthRow += depthStepX;
            }

            rowW0 += stepW0Y;
            rowW1 += stepW1Y;
            rowW2 += stepW2Y;
        }
    }

    private int recordFragment(Shader shader, FragmentIn in, Uniforms uniforms, int idx, GBuffer gbuffer, SeeThrough seeThrough) {
        double fogStart = uniforms.fogStart, fogEnd = uniforms.fogEnd;
        uniforms.fogStart = 1.0e9;
        uniforms.fogEnd = 2.0e9;
        int src;
        try {
            src = shader.fragment(in, uniforms);
        } finally {
            uniforms.fogStart = fogStart;
            uniforms.fogEnd = fogEnd;
        }
        if (gbuffer != null) gbuffer.record(idx, in, uniforms, src);
        int alpha = src >>> 24;
        if (seeThrough != null && alpha > 0 && alpha < 255) {
            // Called before the fragment is blended, so the colour buffer still holds what's behind it.
            if (Double.isInfinite(framebuffer.getDepthBuffer()[idx])) seeThrough.record(idx, alpha, framebuffer.getColorBuffer()[idx]);
        }
        return src;
    }

    @Override
    public ScreenVertex project(VertexOut v) {
        double invW = 1.0f / v.clipW;
        double ndcX = v.clipX * invW;
        double ndcY = v.clipY * invW;
        double ndcZ = v.clipZ * invW;
        double x = (ndcX * 0.5f + 0.5f) * framebuffer.width;
        double y = (ndcY * 0.5f + 0.5f) * framebuffer.height;
        return new ScreenVertex(v, x, y, ndcZ, invW);
    }
}
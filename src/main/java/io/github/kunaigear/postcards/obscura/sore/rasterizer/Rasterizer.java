/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.rasterizer;

import io.github.kunaigear.postcards.obscura.sore.ScreenVertex;
import io.github.kunaigear.postcards.obscura.sore.Triangle;
import io.github.kunaigear.postcards.obscura.sore.Uniforms;
import io.github.kunaigear.postcards.obscura.sore.shader.FragmentIn;
import io.github.kunaigear.postcards.obscura.sore.shader.Shader;
import io.github.kunaigear.postcards.obscura.sore.shader.VertexOut;

public interface Rasterizer {
    void clear(int color);

    void drawTriangle(Shader shader, Triangle tri, FragmentIn fIn, boolean writeDepth, boolean doubleSided, Uniforms uniforms);

    ScreenVertex project(VertexOut v);

    Framebuffer getFramebuffer();
}
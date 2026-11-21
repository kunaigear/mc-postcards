/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.pipeline;

import io.github.kunaigear.postcards.obscura.sore.shader.MatteShader;
import io.github.kunaigear.postcards.obscura.sore.shader.Shader;
import io.github.kunaigear.postcards.obscura.sore.shader.SkyShader;

public record RenderType(Shader shader, boolean transparent, boolean doubleSided, boolean writeDepth) {
    public static RenderType SKY = new RenderType(new SkyShader(), false, false, false);
    public static RenderType WATER = new RenderType(new MatteShader(), true, false, true);
    public static RenderType SOLID = new RenderType(new MatteShader(), false, false, true);
    public static RenderType TRANSLUCENT = new RenderType(new MatteShader(), true, false, false);

    public static RenderType ENTITY = new RenderType(new MatteShader(), true, true, true);
    public static RenderType ITEM = new RenderType(new MatteShader(), true, false, true);
}

/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.shader;

import io.github.kunaigear.postcards.obscura.sore.Texture;

public final class VertexOut {
    public double clipX, clipY, clipZ, clipW;
    public double worldX, worldY, worldZ;
    public double normalX, normalY, normalZ;
    public double localNormalX, localNormalY, localNormalZ;
    public double u, v, ao;
    public Texture texture;
    public double skyLight;
    public double blockLight;

    public int tint;
    public boolean shade;
}

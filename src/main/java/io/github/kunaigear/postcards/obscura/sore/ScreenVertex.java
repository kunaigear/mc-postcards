/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore;

import io.github.kunaigear.postcards.obscura.sore.shader.VertexOut;

public record ScreenVertex(VertexOut v, double x, double y, double z, double invW) {
}

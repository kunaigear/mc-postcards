/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.animation;

import org.joml.Vector3d;

public class KeyframeAnimations {
    public static Vector3d posVec(final double x, final double y, final double z) {
        return new Vector3d(x, -y, z);
    }

    public static Vector3d degreeVec(final double x, final double y, final double z) {
        return new Vector3d(x * (Math.PI / 180.0), y * (Math.PI / 180.0), z * (Math.PI / 180.0));
    }

    public static Vector3d scaleVec(final double x, final double y, final double z) {
        return new Vector3d((x - 1.0), (y - 1.0), (z - 1.0));
    }
}
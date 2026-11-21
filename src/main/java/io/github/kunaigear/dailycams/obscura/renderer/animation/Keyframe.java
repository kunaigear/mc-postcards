/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.renderer.animation;

import org.joml.Vector3dc;

public record Keyframe(double timestamp, Vector3dc preTarget, Vector3dc postTarget,
                       AnimationChannel.Interpolation interpolation) {
    public Keyframe(final double timestamp, final Vector3dc postTarget, final AnimationChannel.Interpolation interpolation) {
        this(timestamp, postTarget, postTarget, interpolation);
    }
}

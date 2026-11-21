/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.pipeline;

import io.github.kunaigear.postcards.obscura.sore.model.Model;
import io.github.kunaigear.postcards.obscura.util.Constants;
import it.unimi.dsi.fastutil.ints.IntList;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

public record DrawCommand(RenderType renderType, Model model, Matrix4dc transform, IntList tints) {
    public DrawCommand(RenderType renderType, Model model, Matrix4dc transform) {
        this(renderType, model, transform, IntList.of());
    }

    public DrawCommand(RenderType renderType, Model model) {
        this(renderType, model, Constants.ZERO_MATRIX);
    }

    public Vector3d worldPosition() {
        Vector3d pos = new Vector3d();
        transform.getTranslation(pos);
        return pos;
    }
}

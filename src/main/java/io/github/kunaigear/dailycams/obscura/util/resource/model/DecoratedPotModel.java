/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.util.resource.model;

import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
import net.minecraft.world.phys.Vec3;

public class DecoratedPotModel {
    public static RPModel.View get() {
        RPModel rpModel = RPHelper.loadModel(ChestModel.class.getResourceAsStream("/dailycams/obscura/builtin/decorated_pot.json"));
        return new RPModel.View(rpModel, Vec3.ZERO.toVector3f());
    }
}

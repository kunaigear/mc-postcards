/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.util.resource.model;

import io.github.kunaigear.postcards.obscura.model.resource.RPModel;
import io.github.kunaigear.postcards.obscura.util.resource.RPHelper;

public class BellModel {
    public static RPModel.View get() {
        RPModel rpModel = RPHelper.loadModel(BellModel.class.getResourceAsStream("/postcards/obscura/builtin/bell.json"));
        return new RPModel.View(rpModel);
    }
}

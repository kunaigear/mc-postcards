/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.renderer.entity.ModelBakery;
import net.minecraft.world.entity.animal.camel.Camel;

public class CamelHuskRenderer extends CamelRenderer {
    private static final String HUSK_TEXTURE = "entity/camel/camel_husk";
    private ModelBakery.BakedPart cachedHuskModel;

    @Override
    public ModelBakery.BakedPart buildRoot(Camel entity) {
        if (cachedHuskModel == null) {
            cachedHuskModel = buildAdultModel(HUSK_TEXTURE);
        }
        return cachedHuskModel;
    }

}
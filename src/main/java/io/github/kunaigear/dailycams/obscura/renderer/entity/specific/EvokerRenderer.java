/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.renderer.entity.specific;

import net.minecraft.world.entity.monster.illager.Evoker;

public class EvokerRenderer extends IllagerRenderer<Evoker> {
    @Override
    protected String getTexture(Evoker entity) {
        return "entity/illager/evoker";
    }
}

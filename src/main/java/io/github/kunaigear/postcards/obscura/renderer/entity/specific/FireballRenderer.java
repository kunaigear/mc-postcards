/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;

public class FireballRenderer extends BillboardItemEntityRenderer<Fireball> {
    public FireballRenderer(double scale) {
        super(Fireball::getItem, scale);
    }
}

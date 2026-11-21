/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;

public class WanderingTraderRenderer extends AbstractVillagerRenderer<WanderingTrader> {
    private static final String TEXTURE = "entity/wandering_trader/wandering_trader";

    @Override
    protected String getAdultTexture(WanderingTrader entity) {
        return TEXTURE;
    }

    @Override
    protected String getBabyTexture(WanderingTrader entity) {
        return TEXTURE;
    }
}
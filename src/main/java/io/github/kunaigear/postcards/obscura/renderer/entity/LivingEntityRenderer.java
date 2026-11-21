/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity;

import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import net.minecraft.world.entity.LivingEntity;

public interface LivingEntityRenderer<T extends LivingEntity> extends EntityRenderer<T> {
    void render(RenderPipeline pipeline, T obj);

    ModelBakery.BakedPart buildRoot(T cow);

    static int hurtTint(LivingEntity entity) {
        return entity.hurtTime > 0 ? 0xFF6666 : 0xFFFFFF;
    }
}

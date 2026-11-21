/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.renderer.entity.EntityRenderer;
import io.github.kunaigear.postcards.obscura.renderer.entity.ItemStackRenderer;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4d;

public class ItemEntityRenderer implements EntityRenderer<ItemEntity> {

    @Override
    public void render(RenderPipeline pipeline, ItemEntity entity) {
        var item = entity.getItem();
        if (!item.isEmpty()) {
            var block = entity.level().getBrightness(LightLayer.BLOCK, entity.blockPosition()) / 15f;
            var sky = entity.level().getBrightness(LightLayer.SKY, entity.blockPosition()) / 15f;

            var transform = new Matrix4d().translate(entity.position().toVector3f()).rotateY((entity.tickCount % 3600) * 2.5f * Mth.DEG_TO_RAD).translate(0, (double) Math.sin(entity.tickCount / 10f) * 0.1f + 0.25f, 0);
            ItemStackRenderer.render(pipeline, item, ItemDisplayContext.GROUND, transform, block, sky);
        }
    }
}

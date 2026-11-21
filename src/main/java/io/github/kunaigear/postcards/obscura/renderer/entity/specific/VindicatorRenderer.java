/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.renderer.entity.ItemStackRenderer;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4d;

public class VindicatorRenderer extends IllagerRenderer<Vindicator> {

    @Override
    protected String getTexture(Vindicator entity) {
        return "entity/illager/vindicator";
    }

    @Override
    public void render(RenderPipeline pipeline, Vindicator entity) {
        super.render(pipeline, entity);
        if (entity.isAggressive()) {
            ItemStack held = entity.getMainHandItem();
            if (!held.isEmpty()) {
                var block = entity.level().getBrightness(LightLayer.BLOCK, entity.blockPosition()) / 15f;
                var sky = entity.level().getBrightness(LightLayer.SKY, entity.blockPosition()) / 15f;

                Matrix4d base = computeBaseMatrix(entity);

                Matrix4d handMat = new Matrix4d(base)
                        .translate(-5f / 16f, 2f / 16f, 0)
                        .rotateX(-Math.PI / 5)
                        .translate(0, -0.55f, -0.15f)
                        .rotateX(-Math.PI / 2)
                        .rotateY(Math.PI);

                ItemStackRenderer.render(pipeline, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, handMat, block, sky);
            }
        }
    }
}
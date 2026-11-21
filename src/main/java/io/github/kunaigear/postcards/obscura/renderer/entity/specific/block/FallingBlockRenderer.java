/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific.block;

import io.github.kunaigear.postcards.obscura.renderer.BlockStateRenderer;
import io.github.kunaigear.postcards.obscura.renderer.entity.EntityRenderer;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4d;

public class FallingBlockRenderer implements EntityRenderer<FallingBlockEntity> {
    @Override
    public void render(RenderPipeline pipeline, FallingBlockEntity entity) {
        var block = entity.level().getBrightness(LightLayer.BLOCK, entity.blockPosition()) / 15f;
        var sky = entity.level().getBrightness(LightLayer.SKY, entity.blockPosition()) / 15f;

        Matrix4d transform = new Matrix4d().translate(entity.position().toVector3f());
        BlockStateRenderer.render(pipeline, entity.getBlockState(), transform, block, sky);
    }
}
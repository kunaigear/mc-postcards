/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.renderer.entity.ItemStackRenderer;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;

public class FireworkRocketRenderer extends BillboardItemEntityRenderer<FireworkRocketEntity> {
    public FireworkRocketRenderer() {
        super(FireworkRocketEntity::getItem, 1);
    }

    @Override
    public void render(RenderPipeline pipeline, FireworkRocketEntity entity) {
        ItemStack item = entity.getItem();
        if (item.isEmpty()) return;

        Matrix4fc viewMatrix = pipeline.getCamera().getViewMatrix();
        Matrix4d viewRot = new Matrix4d(viewMatrix);
        viewRot.setTranslation(0, 0, 0);
        Matrix4d cameraWorldRot = new Matrix4d(viewRot).invert();

        if (entity.isShotAtAngle()) {
            cameraWorldRot.rotateZ((double) Math.toRadians(180));
            cameraWorldRot.rotateY((double) Math.toRadians(180));
            cameraWorldRot.rotateX((double) Math.toRadians(90));
        }

        Matrix4d modelMatrix = new Matrix4d()
                .translate(entity.position().toVector3f())
                .mul(cameraWorldRot)
                .scale(scale);

        ItemStackRenderer.render(pipeline, item, ItemDisplayContext.FIXED, modelMatrix);
    }
}
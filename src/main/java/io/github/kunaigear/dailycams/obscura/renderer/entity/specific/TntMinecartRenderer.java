/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.renderer.entity.specific;

import io.github.kunaigear.dailycams.obscura.renderer.entity.specific.block.TntRenderer;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.RenderPipeline;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.RenderType;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import org.joml.Matrix4d;

public class TntMinecartRenderer extends MinecartRenderer<MinecartTNT> {
    @Override
    protected void renderDisplayBlock(RenderPipeline pipeline, MinecartTNT entity, Matrix4d parent, double block, double sky) {
        double fuse = entity.getFuse() > -1 ? entity.getFuse() - 1.0f + 1.0f : -1.0f;

        double fuseRemaining = fuse + 1;
        if (fuseRemaining < 10.0f) {
            double g = 1.0f - fuseRemaining / 10.0f;
            g = Mth.clamp(g, 0.0f, 1.0f);
            g *= g;
            g *= g;
            double scale = 0.75f + g * 0.3f;
            parent.scale(scale, scale, scale);
        }

        boolean white = (int) fuseRemaining / 5 % 2 == 0;

        if (!white) {
            Matrix4d blockMat = new Matrix4d(parent);
            blockMat.scale(0.75f);
            blockMat.translate(-0.5f, (entity.getDisplayOffset() - 8) / 16.0f, 0.5f);
            blockMat.rotateY(Mth.DEG_TO_RAD * 90.0f);

            pipeline.draw(new DrawCommand(RenderType.ENTITY, TntRenderer.get(), blockMat, IntList.of(0xFFFFFFFF)));
        } else {
            super.renderDisplayBlock(pipeline, entity, parent, block, sky);
        }
    }
}
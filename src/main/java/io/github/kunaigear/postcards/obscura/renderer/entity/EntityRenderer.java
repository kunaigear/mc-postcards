/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity;

import io.github.kunaigear.postcards.obscura.model.triangle.ModelTesselator;
import io.github.kunaigear.postcards.obscura.renderer.EntityRenderers;
import io.github.kunaigear.postcards.obscura.sore.model.Model;
import io.github.kunaigear.postcards.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderType;
import io.github.kunaigear.postcards.obscura.util.BuiltinEntityModels;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4d;

public interface EntityRenderer<T extends Entity> {
    void render(RenderPipeline pipeline, T obj);

    default void renderDefault(RenderPipeline pipeline, Matrix4d transform, T ent, double block, double sky) {
        if (ent.isInvisible()) return;

        var cachedModel = EntityRenderers.ENTITY_TYPE_MODELS.get(ent.getType());
        if (cachedModel == null) {
            var view = BuiltinEntityModels.getModel(ent.getType(), ent.getUUID());
            if (view != null) {
                ModelTesselator tri = new ModelTesselator(view);
                var raster = tri.build();
                if (raster != null) {
                    cachedModel = new Model(tri.build(), block, sky);
                    EntityRenderers.ENTITY_TYPE_MODELS.put(ent.getType(), cachedModel);
                }
            }
        }

        if (cachedModel != null) {
            cachedModel.blockLight = new double[]{block};
            cachedModel.skyLight = new double[]{sky};

            if (transform == null) transform = new Matrix4d()
                    .translate(ent.position().toVector3f())
                    .rotateY(Mth.DEG_TO_RAD * (180f - ent.getPreciseBodyRotation(0.5f)));

            pipeline.draw(new DrawCommand(RenderType.ENTITY, cachedModel, transform, IntList.of(0xFFFFFFFF)));
        }
    }
}

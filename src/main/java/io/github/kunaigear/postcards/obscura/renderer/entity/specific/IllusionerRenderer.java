/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;

public class IllusionerRenderer extends IllagerRenderer<Illusioner> {

    @Override
    protected String getTexture(Illusioner entity) {
        return "entity/illager/illusioner";
    }

    @Override
    protected boolean showHat() {
        return true;
    }

    @Override
    public void render(RenderPipeline pipeline, Illusioner entity) {
        if (entity.isInvisible()) {
            Vec3[] offsets = entity.getIllusionOffsets(1.0f);
            double ageInTicks = entity.tickCount + 1.0f;
            for (int i = 0; i < offsets.length; i++) {
                Matrix4d base = computeBaseMatrix(entity);

                base.translate(
                        (double) (offsets[i].x + Mth.cos(i + ageInTicks * 0.5f) * 0.025),
                        (double) (offsets[i].y + Mth.cos(i + ageInTicks * 0.75f) * 0.0125),
                        (double) (offsets[i].z + Mth.cos(i + ageInTicks * 0.7f) * 0.025)
                );
                renderSingle(pipeline, entity, base);
            }
        } else {
            super.render(pipeline, entity);
        }
    }
}
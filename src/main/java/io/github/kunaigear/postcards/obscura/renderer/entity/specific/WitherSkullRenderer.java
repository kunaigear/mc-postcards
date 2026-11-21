/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.renderer.entity.EntityRenderer;
import io.github.kunaigear.postcards.obscura.renderer.entity.ModelBakery;
import io.github.kunaigear.postcards.obscura.sore.model.Model;
import io.github.kunaigear.postcards.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderType;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import org.joml.Matrix4d;

import java.util.HashMap;
import java.util.Map;

public class WitherSkullRenderer implements EntityRenderer<WitherSkull> {
    private static final String NORMAL_TEXTURE = "entity/wither/wither";
    private static final String DANGEROUS_TEXTURE = "entity/wither/wither_invulnerable";

    private final Map<Boolean, ModelBakery.BakedPart> cache = new HashMap<>();

    @Override
    public void render(RenderPipeline pipeline, WitherSkull entity) {
        boolean dangerous = entity.isDangerous();
        ModelBakery.BakedPart head = cache.computeIfAbsent(dangerous, k -> buildHead(k ? DANGEROUS_TEXTURE : NORMAL_TEXTURE)).children.get("head");

        double yRot = entity.getYRot(1.0f);
        double xRot = entity.getXRot(1.0f);

        Matrix4d transform = new Matrix4d()
                .translate(entity.position().toVector3f())
                .scale(-1, -1, 1)
                .rotateY(Mth.DEG_TO_RAD * yRot)
                .rotateX(Mth.DEG_TO_RAD * xRot);

        if (head.mesh != null) {
            pipeline.draw(new DrawCommand(RenderType.ENTITY, new Model(head.mesh), transform, IntList.of(0xFFFFFFFF)));
        }
    }

    private ModelBakery.BakedPart buildHead(String texture) {
        ModelBakery bakery = new ModelBakery(texture, 64, 64);
        ModelBakery.ModelDefinition model = new ModelBakery.ModelDefinition(bakery);
        ModelBakery.PartDefinition root = model.root();

        root.addOrReplaceChild("head",
                ModelBakery.CubeListBuilder.create().texOffs(0, 35).addBox(-4, -8, -4, 8, 8, 8),
                ModelBakery.PartPose.ZERO);

        return root.bake();
    }
}

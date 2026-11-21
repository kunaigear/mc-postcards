/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer.entity.specific;

import io.github.kunaigear.postcards.obscura.renderer.entity.ModelBakery;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import org.joml.Matrix4d;

import java.util.Map;

public class VillagerRenderer extends AbstractVillagerRenderer<Villager> {
    private static final String ADULT_BASE = "entity/villager/villager";
    private static final String BABY_BASE = "entity/villager/villager_baby";

    private final Map<String, ModelBakery.BakedPart> overlayCache = new Object2ObjectOpenHashMap<>();

    private static String typeTextureKey(Holder<VillagerType> type) {
        return type.unwrapKey().map(key -> "entity/villager/type/" + key.identifier().getPath()).orElse("entity/villager/type/plains");
    }

    private static String professionTextureKey(Holder<VillagerProfession> prof) {
        return prof.unwrapKey().map(key -> "entity/villager/profession/" + key.identifier().getPath()).orElse("entity/villager/profession/none");
    }

    private static String levelTextureKey(int level) {
        return switch (level) {
            case 2 -> "entity/villager/profession_level/iron";
            case 3 -> "entity/villager/profession_level/gold";
            case 4 -> "entity/villager/profession_level/emerald";
            case 5 -> "entity/villager/profession_level/diamond";
            default -> "entity/villager/profession_level/stone";
        };
    }

    @Override
    protected String getAdultTexture(Villager entity) {
        return ADULT_BASE;
    }

    @Override
    protected String getBabyTexture(Villager entity) {
        return BABY_BASE;
    }

    @Override
    protected void renderAdditionalLayers(RenderPipeline pipeline, Villager entity, Matrix4d base,
                                          double headYaw, double headPitch, double headZRot,
                                          double rightLegXRot, double leftLegXRot,
                                          double block, double sky) {
        if (entity.isBaby()) return;

        VillagerData data = entity.getVillagerData();

        String typeTex = typeTextureKey(data.type());
        ModelBakery.BakedPart typeModel = overlayCache.computeIfAbsent(typeTex, t -> buildGenericModel(false, true, t));
        renderModel(pipeline, typeModel, base, headYaw, headPitch, headZRot, rightLegXRot, leftLegXRot, block, sky);

        Holder<VillagerProfession> prof = data.profession();
        if (!prof.is(VillagerProfession.NONE)) {
            String profTex = professionTextureKey(prof);
            ModelBakery.BakedPart profModel = overlayCache.computeIfAbsent(profTex, t -> buildGenericModel(false, true, t));
            renderModel(pipeline, profModel, base, headYaw, headPitch, headZRot, rightLegXRot, leftLegXRot, block, sky);

            if (!prof.is(VillagerProfession.NITWIT) && data.level() > 1) {
                String levelTex = levelTextureKey(data.level());
                ModelBakery.BakedPart levelModel = overlayCache.computeIfAbsent(levelTex, t -> buildGenericModel(false, true, t));
                renderModel(pipeline, levelModel, base, headYaw, headPitch, headZRot, rightLegXRot, leftLegXRot, block, sky);
            }
        }
    }
}
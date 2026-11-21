/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.util;

import io.github.kunaigear.postcards.obscura.json.CachedIdentifierDeserializer;
import io.github.kunaigear.postcards.obscura.model.resource.RPModel;
import io.github.kunaigear.postcards.obscura.util.resource.RPHelper;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;

public class BuiltinEntityModels {
    public static final Map<EntityType<?>, RPModel> modelMap = new Reference2ObjectArrayMap<>();

    public static @Nullable RPModel.View getModel(EntityType<?> entityType, @Nullable UUID uuid) {
        if (modelMap.containsKey(entityType)) {
            if (entityType == EntityTypes.ITEM_FRAME || entityType == EntityTypes.GLOW_ITEM_FRAME) {
                return new RPModel.View(modelMap.get(entityType), new Vector3f(), new Vector3f(0, 0, 0));
            } else {
                return new RPModel.View(modelMap.get(entityType), new Vector3f(), new Vector3f(-0.5f, 0, -0.5f));
            }
        }

        return null;
    }

    public static void initModels() {
        modelMap.put(EntityTypes.ITEM_FRAME, RPHelper.loadModel(CachedIdentifierDeserializer.get("block/item_frame")));
        modelMap.put(EntityTypes.GLOW_ITEM_FRAME, RPHelper.loadModel(CachedIdentifierDeserializer.get("block/glow_item_frame")));
    }
}

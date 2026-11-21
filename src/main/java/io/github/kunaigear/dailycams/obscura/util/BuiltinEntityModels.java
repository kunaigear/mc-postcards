package io.github.kunaigear.dailycams.obscura.util;

import io.github.kunaigear.dailycams.obscura.json.CachedIdentifierDeserializer;
import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
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

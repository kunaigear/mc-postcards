/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.json;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import io.github.kunaigear.postcards.obscura.model.resource.state.MultipartDefinition;
import io.github.kunaigear.postcards.obscura.model.resource.state.Variant;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.reflect.Type;
import java.util.List;

public class MultipartDefinitionDeserializer implements JsonDeserializer<MultipartDefinition> {

    @Override
    public MultipartDefinition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonObject()) {
            MultipartDefinition definition = new MultipartDefinition();

            JsonObject jsonObject = json.getAsJsonObject();
            if (jsonObject.has("apply") && jsonObject.get("apply").isJsonArray()) {
                definition.apply = context.deserialize(jsonObject.getAsJsonArray("apply"), TypeToken.getParameterized(List.class, Variant.class).getType());
            } else {
                ObjectArrayList<Variant> list = new ObjectArrayList<>();
                list.add(context.deserialize(jsonObject.getAsJsonObject("apply"), Variant.class));
                definition.apply = list;
            }
            if (jsonObject.has("when"))
                definition.when = context.deserialize(jsonObject.get("when"), MultipartDefinition.Condition.class);

            return definition;
        } else {
            throw new JsonParseException("Unexpected JSON element type for Variant: " + json);
        }
    }
}

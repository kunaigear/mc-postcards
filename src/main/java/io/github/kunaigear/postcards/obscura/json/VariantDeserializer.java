/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.json;

import com.google.gson.*;
import io.github.kunaigear.postcards.obscura.model.resource.state.Variant;

import java.lang.reflect.Type;

public class VariantDeserializer implements JsonDeserializer<Variant> {

    @Override
    public Variant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonObject()) {
            Variant variant = new Variant();

            JsonObject jsonObject = json.getAsJsonObject();
            if (jsonObject.has("model"))
                variant.model = CachedIdentifierDeserializer.get(jsonObject.get("model").getAsString());
            if (jsonObject.has("uvlock")) variant.uvlock = jsonObject.get("uvlock").getAsBoolean();
            if (jsonObject.has("x")) variant.x = jsonObject.get("x").getAsInt();
            if (jsonObject.has("y")) variant.y = jsonObject.get("y").getAsInt();
            if (jsonObject.has("z")) variant.z = jsonObject.get("z").getAsInt();

            return variant;
        } else if (json.isJsonArray()) {
            JsonArray jsonArray = json.getAsJsonArray();
            if (jsonArray.size() > 0) {
                return context.deserialize(jsonArray.get(0), Variant.class);
            } else {
                return null;
            }
        } else {
            throw new JsonParseException("Unexpected JSON element type for Variant: " + json);
        }
    }
}

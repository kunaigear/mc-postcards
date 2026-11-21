/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.json;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import io.github.kunaigear.dailycams.obscura.model.resource.state.MultipartDefinition;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class ConditionDeserializer implements JsonDeserializer<MultipartDefinition.Condition> {
    @Override
    public MultipartDefinition.Condition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonObject()) {
            MultipartDefinition.Condition condition = new MultipartDefinition.Condition();

            var obj = json.getAsJsonObject();
            if (obj.has("OR")) {
                condition.OR = new MultipartDefinition.OrCondition();
                Type listStringMapType = new TypeToken<List<Map<String, String>>>() {
                }.getType();

                condition.OR.blockStateValueList = context.deserialize(obj.get("OR").getAsJsonArray(), listStringMapType);
            } else if (obj.has("AND")) {
                condition.AND = new MultipartDefinition.AndCondition();
                Type listStringMapType = new TypeToken<List<Map<String, String>>>() {
                }.getType();

                condition.AND.blockStateValueList = context.deserialize(obj.get("AND").getAsJsonArray(), listStringMapType);
            } else {
                Type t = new TypeToken<Map<String, String>>() {
                }.getType();
                condition.blockStateValues = context.deserialize(json, t);
            }

            return condition;
        } else {
            throw new JsonParseException("Unexpected JSON element type for Variant: " + json);
        }
    }
}

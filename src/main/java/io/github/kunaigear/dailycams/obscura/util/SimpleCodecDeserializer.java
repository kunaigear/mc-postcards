/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.util;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import java.lang.reflect.Type;

public class SimpleCodecDeserializer<T> implements JsonDeserializer<T>, JsonSerializer<T> {

    private final Codec<T> codec;

    public SimpleCodecDeserializer(Codec<T> codec) {
        this.codec = codec;

    }

    @Override
    public T deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        return codec.parse(JsonOps.INSTANCE, json)
                .getOrThrow(error ->
                        new JsonParseException("Failed to deserialize using Codec: " + error)
                );
    }

    @Override
    public JsonElement serialize(T t, Type type, JsonSerializationContext jsonSerializationContext) {
        return codec.encodeStart(JsonOps.INSTANCE, t).getOrThrow(error ->
                new JsonParseException("Failed to serialize using Codec: " + error)
        );
    }
}
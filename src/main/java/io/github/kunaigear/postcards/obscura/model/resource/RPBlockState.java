/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.model.resource;

import com.google.gson.annotations.SerializedName;
import io.github.kunaigear.postcards.obscura.model.resource.state.MultipartDefinition;
import io.github.kunaigear.postcards.obscura.model.resource.state.Variant;

import java.util.List;
import java.util.Map;

public class RPBlockState {

    @SerializedName("variants")
    public Map<String, Variant> variants;

    @SerializedName("multipart")
    public List<MultipartDefinition> multipart;
}

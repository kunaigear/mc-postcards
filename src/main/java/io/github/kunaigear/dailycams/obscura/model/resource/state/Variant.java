/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.model.resource.state;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.Identifier;

public class Variant {
    @SerializedName("x")
    public int x;
    @SerializedName("y")
    public int y;
    @SerializedName("z")
    public int z;

    @SerializedName("uvlock")
    public boolean uvlock;

    @SerializedName("model")
    public Identifier model;
}

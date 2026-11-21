/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.util;

import net.minecraft.world.level.material.MapColor;

public class ImageUtils {
    public static int[] mapColorsToRGB(byte[] colors) {
        int width = 128;
        int height = 128;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = x + y * width;
                if (index < colors.length) {
                    byte packed = colors[index];

                    int baseId = (packed >> 2) & 0x3F;
                    int brightnessOrdinal = packed & 0x3;
                    MapColor.Brightness brightness = MapColor.Brightness.values()[brightnessOrdinal];

                    int argb = MapColor.byId(baseId).calculateARGBColor(brightness);

                    argb = 0xFF000000 | (argb & 0x00FFFFFF);

                    pixels[index] = argb;
                } else {
                    pixels[index] = 0x00000000;
                }
            }
        }
        return pixels;
    }
}

/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.sore;

public final class Triangle {
    public final ScreenVertex a, b, c;

    public Triangle(ScreenVertex a, ScreenVertex b, ScreenVertex c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }
}

/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.joml.Matrix4d;
import org.joml.Vector3d;


public class Uniforms {
    public final Matrix4d model = new Matrix4d();
    public final Matrix4d view = new Matrix4d();
    public final Matrix4d proj = new Matrix4d();
    public final Matrix4d mvp = new Matrix4d();
    public final Matrix4d normalTransform = new Matrix4d();

    public final Vector3d sunDir = new Vector3d();
    public final Vector3d cameraPos = new Vector3d();
    public long time;
    public double skyLight;
    public IntList tints = new IntArrayList();
    public double fogEnd;
    public double fogStart;
    public int fogColor;
    /** Whether anything reads the fragments' world-space normals (the realistic shader's GBuffer). */
    public boolean normals = true;
}
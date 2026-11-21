/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.model;

import io.github.kunaigear.postcards.obscura.sore.Texture;
import org.jetbrains.annotations.Nullable;

public final class Model {
    public static final Model SKY = new Model(Mesh.skybox(Texture.generateSolid(0x7f_FFFFFF)), 1, 1);
    public final Mesh mesh;
    public final double @Nullable [] ao;

    public double @Nullable [] skyLight;
    public double @Nullable [] blockLight;

    public Model(Mesh mesh, double @Nullable [] skyLight, double @Nullable [] blockLight, double @Nullable [] ao) {
        this.mesh = mesh;
        this.skyLight = skyLight;
        this.blockLight = blockLight;
        this.ao = ao;
    }

    public Model(Mesh mesh) {
        this.mesh = mesh;
        ao = null;
    }

    public Model(Mesh mesh, double block, double sky) {
        this.mesh = mesh;
        ao = null;
        skyLight = new double[]{sky};
        blockLight = new double[]{block};
    }

    public float[] positions() {
        return mesh.positions;
    }

    public float[] normals() {
        return mesh.normals;
    }

    public float[] uvs() {
        return mesh.uvs;
    }

    public int[] indices() {
        return mesh.indices;
    }

    public Texture[] vertexTextures() {
        return mesh.vertexTextures;
    }

    public Texture textureForVertex(int i) {
        return mesh.textureForVertex(i);
    }
}
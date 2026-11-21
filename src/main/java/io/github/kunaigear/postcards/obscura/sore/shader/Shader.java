/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.shader;

import io.github.kunaigear.postcards.obscura.sore.Uniforms;
import io.github.kunaigear.postcards.obscura.sore.model.Model;
import org.joml.Vector4d;

public interface Shader {
    /** Reused for every vertex: the render thread draws one frame at a time. */
    Vector4d WORLD = new Vector4d(), CLIP = new Vector4d(), NORMAL = new Vector4d();

    static int clamp255(double v) {
        return (int) (Math.clamp(v, 0f, 1f) * 255f);
    }

    static void populateVertexCommon(Model model, int index, Uniforms uniforms, VertexOut out, Vector4d localPos) {
        int p = index * 3;

        Vector4d worldPos = WORLD.set(localPos);
        uniforms.model.transform(worldPos);

        Vector4d clipPos = CLIP.set(localPos);
        uniforms.mvp.transform(clipPos);

        out.clipX = clipPos.x;
        out.clipY = clipPos.y;
        out.clipZ = clipPos.z;
        out.clipW = clipPos.w;

        out.worldX = worldPos.x;
        out.worldY = worldPos.y;
        out.worldZ = worldPos.z;

        if (uniforms.normals) {
            Vector4d normal = NORMAL.set(model.mesh.normals[p], model.mesh.normals[p + 1], model.mesh.normals[p + 2], 0.0f);
            uniforms.normalTransform.transform(normal);
            out.normalX = normal.x;
            out.normalY = normal.y;
            out.normalZ = normal.z;
        }

        out.localNormalX = model.mesh.normals[p];
        out.localNormalY = model.mesh.normals[p + 1];
        out.localNormalZ = model.mesh.normals[p + 2];

        out.u = model.mesh.uvs[index * 2];
        out.v = model.mesh.uvs[index * 2 + 1];
        out.texture = model.textureForVertex(index);

        out.ao = model.ao == null ? 1 : model.ao[index];
        out.skyLight = model.skyLight == null ? 1 : model.skyLight.length == 1 ? model.skyLight[0] : model.skyLight[index];
        out.blockLight = model.blockLight == null ? 1 : model.blockLight.length == 1 ? model.blockLight[0] : model.blockLight[index];

        out.tint = model.mesh.tintIndices == null || model.mesh.tintIndices[index] == -1 ? 0xFF__FF_FF_FF : uniforms.tints.getInt(model.mesh.tintIndices[index]);

        out.shade = model.mesh.shade[index];
    }

    void vertex(Model model, int index, Uniforms uniforms, VertexOut out);

    int fragment(FragmentIn in, Uniforms uniforms);
}

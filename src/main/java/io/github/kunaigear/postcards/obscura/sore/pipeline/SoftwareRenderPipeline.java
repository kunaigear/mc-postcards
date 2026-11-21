/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.sore.pipeline;

import io.github.kunaigear.postcards.obscura.sore.Camera;
import io.github.kunaigear.postcards.obscura.sore.Triangle;
import io.github.kunaigear.postcards.obscura.sore.Uniforms;
import io.github.kunaigear.postcards.obscura.sore.model.Model;
import io.github.kunaigear.postcards.obscura.sore.rasterizer.Rasterizer;
import io.github.kunaigear.postcards.obscura.sore.shader.FragmentIn;
import io.github.kunaigear.postcards.obscura.sore.shader.Shader;
import io.github.kunaigear.postcards.obscura.sore.shader.VertexOut;
import io.github.kunaigear.postcards.obscura.sore.shader.MatteShader;
import io.github.kunaigear.postcards.render.CommandCulling;
import io.github.kunaigear.postcards.render.DrawOrder;
import io.github.kunaigear.postcards.render.Throttle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Matrix4d;
import io.github.kunaigear.postcards.render.GBuffer;
import java.util.Arrays;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.List;

public class SoftwareRenderPipeline implements RenderPipeline {
    private final Rasterizer rasterizer;
    private final Uniforms uniforms;
    private final List<DrawCommand> opaqueCommands = new ObjectArrayList<>();
    private final List<DrawCommand> transparentCommands = new ObjectArrayList<>();
    private Camera camera;
    private final Matrix4d viewProjection = new Matrix4d();
    // Reused for every draw command.
    private VertexOut[] vertices = new VertexOut[0];
    private final List<Triangle> triangles = new ObjectArrayList<>();
    private final FragmentIn fIn = new FragmentIn();

    public SoftwareRenderPipeline(Rasterizer rasterizer, Uniforms uniforms, Camera camera) {
        this.rasterizer = rasterizer;
        this.uniforms = uniforms;
        this.camera = camera;
    }

    public Camera getCamera() {
        return camera;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    @Override
    public void beginFrame(FrameContext ctx) {
        opaqueCommands.clear();
        transparentCommands.clear();

        uniforms.time = ctx.time();
        uniforms.sunDir.set(ctx.sunDir());
        uniforms.skyLight = ctx.skyLight();
        uniforms.cameraPos.set(camera.getPosition());
        uniforms.view.set(camera.getViewMatrix());
        uniforms.proj.set(camera.getProjectionMatrix());

        rasterizer.clear(uniforms.fogColor);
    }

    @Override
    public void draw(DrawCommand cmd) {
        if (cmd.renderType().transparent()) {
            transparentCommands.add(cmd);
        } else {
            opaqueCommands.add(cmd);
        }
    }

    @Override
    public void endFrame() {
        Vector3d camPos = camera.getPosition();
        viewProjection.set(uniforms.proj).mul(uniforms.view);
        uniforms.normals = GBuffer.active() != null;
        CommandCulling.begin(rasterizer.getFramebuffer());

        DrawOrder.sortByDistance(opaqueCommands, camPos, false);

        for (DrawCommand cmd : opaqueCommands) {
            executeCommand(cmd);
        }

        DrawOrder.sortByDistance(transparentCommands, camPos, true);

        for (DrawCommand cmd : transparentCommands) {
            executeCommand(cmd);
        }
    }

    private void executeCommand(DrawCommand cmd) {
        Throttle.checkpoint();
        // Only terrain: MatteShader's vertices are the model's positions, other shaders may move them.
        if (cmd.renderType().shader() instanceof MatteShader && CommandCulling.culled(cmd.model(), viewProjection, cmd.transform())) return;
        Model model = cmd.model();
        Shader shader = cmd.renderType().shader();
        Matrix4dc transform = cmd.transform();
        boolean writeDepth = cmd.renderType().writeDepth();

        uniforms.tints = cmd.tints();
        uniforms.model.set(transform);
        uniforms.mvp.set(uniforms.proj).mul(uniforms.view).mul(transform);
        if (uniforms.normals) uniforms.normalTransform.set(transform).invert().transpose();

        int vertexCount = model.positions().length / 3;
        if (vertices.length < vertexCount) {
            VertexOut[] grown = Arrays.copyOf(vertices, Math.max(vertexCount, vertices.length * 2));
            for (int i = vertices.length; i < grown.length; i++) grown[i] = new VertexOut();
            vertices = grown;
        }
        for (int i = 0; i < vertexCount; i++) {
            shader.vertex(cmd.model(), i, uniforms, vertices[i]);
        }

        triangles.clear();
        for (int i = 0; i < model.indices().length; i += 3) {
            VertexOut v0 = vertices[model.indices()[i]];
            VertexOut v1 = vertices[model.indices()[i + 1]];
            VertexOut v2 = vertices[model.indices()[i + 2]];

            clipAndAdd(v0, v1, v2, triangles);
        }

        for (Triangle tri : triangles) {
            rasterizer.drawTriangle(shader, tri, fIn, writeDepth, cmd.renderType().doubleSided(), uniforms);
        }
    }

    // shoutout to tsoding
    private void clipAndAdd(VertexOut v0, VertexOut v1, VertexOut v2, List<Triangle> list) {
        boolean in0 = v0.clipW >= 0.1f;
        boolean in1 = v1.clipW >= 0.1f;
        boolean in2 = v2.clipW >= 0.1f;

        if (in0 && in1 && in2) {
            list.add(new Triangle(rasterizer.project(v0), rasterizer.project(v1), rasterizer.project(v2)));
        } else if (in0 || in1 || in2) {
            processClipped(v0, v1, v2, in0, in1, in2, list);
        }
    }

    private void processClipped(VertexOut v0, VertexOut v1, VertexOut v2, boolean in0, boolean in1, boolean in2, List<Triangle> list) {
        VertexOut[] verts = {v0, v1, v2};
        boolean[] ins = {in0, in1, in2};
        List<VertexOut> outVerts = new ObjectArrayList<>();

        for (int i = 0; i < 3; i++) {
            int next = (i + 1) % 3;
            if (ins[i]) outVerts.add(verts[i]);

            if (ins[i] != ins[next]) {
                double t = (0.1f - verts[i].clipW) / (verts[next].clipW - verts[i].clipW);
                outVerts.add(lerp(verts[i], verts[next], t));
            }
        }

        for (int i = 1; i < outVerts.size() - 1; i++) {
            list.add(new Triangle(
                    rasterizer.project(outVerts.get(0)),
                    rasterizer.project(outVerts.get(i)),
                    rasterizer.project(outVerts.get(i + 1))
            ));
        }
    }

    private VertexOut lerp(VertexOut a, VertexOut b, double t) {
        VertexOut res = new VertexOut();
        res.clipX = a.clipX + (b.clipX - a.clipX) * t;
        res.clipY = a.clipY + (b.clipY - a.clipY) * t;
        res.clipZ = a.clipZ + (b.clipZ - a.clipZ) * t;
        res.clipW = a.clipW + (b.clipW - a.clipW) * t;

        res.worldX = a.worldX + (b.worldX - a.worldX) * t;
        res.worldY = a.worldY + (b.worldY - a.worldY) * t;
        res.worldZ = a.worldZ + (b.worldZ - a.worldZ) * t;

        res.normalX = a.normalX + (b.normalX - a.normalX) * t;
        res.normalY = a.normalY + (b.normalY - a.normalY) * t;
        res.normalZ = a.normalZ + (b.normalZ - a.normalZ) * t;

        res.localNormalX = a.localNormalX + (b.localNormalX - a.localNormalX) * t;
        res.localNormalY = a.localNormalY + (b.localNormalY - a.localNormalY) * t;
        res.localNormalZ = a.localNormalZ + (b.localNormalZ - a.localNormalZ) * t;

        res.u = a.u + (b.u - a.u) * t;
        res.v = a.v + (b.v - a.v) * t;

        res.texture = a.texture;

        res.ao = a.ao + (b.ao - a.ao) * t;
        res.skyLight = a.skyLight + (b.skyLight - a.skyLight) * t;
        res.blockLight = a.blockLight + (b.blockLight - a.blockLight) * t;

        res.tint = a.tint;
        res.shade = a.shade;

        return res;
    }
}
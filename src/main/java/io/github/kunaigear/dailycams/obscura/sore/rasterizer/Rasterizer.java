package io.github.kunaigear.dailycams.obscura.sore.rasterizer;

import io.github.kunaigear.dailycams.obscura.sore.ScreenVertex;
import io.github.kunaigear.dailycams.obscura.sore.Triangle;
import io.github.kunaigear.dailycams.obscura.sore.Uniforms;
import io.github.kunaigear.dailycams.obscura.sore.shader.FragmentIn;
import io.github.kunaigear.dailycams.obscura.sore.shader.Shader;
import io.github.kunaigear.dailycams.obscura.sore.shader.VertexOut;

public interface Rasterizer {
    void clear(int color);

    void drawTriangle(Shader shader, Triangle tri, FragmentIn fIn, boolean writeDepth, boolean doubleSided, Uniforms uniforms);

    ScreenVertex project(VertexOut v);

    Framebuffer getFramebuffer();
}
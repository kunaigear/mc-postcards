package io.github.kunaigear.dailycams.obscura.sore;

import io.github.kunaigear.dailycams.obscura.sore.shader.VertexOut;

public record ScreenVertex(VertexOut v, double x, double y, double z, double invW) {
}

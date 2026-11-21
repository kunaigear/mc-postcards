package io.github.kunaigear.dailycams.obscura.sore.pipeline;

import io.github.kunaigear.dailycams.obscura.sore.Camera;

public interface RenderPipeline {
    void beginFrame(FrameContext ctx);

    void draw(DrawCommand cmd);

    void endFrame();

    Camera getCamera();

    void setCamera(Camera camera);
}

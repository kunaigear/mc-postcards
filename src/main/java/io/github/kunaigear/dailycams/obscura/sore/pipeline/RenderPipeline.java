/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.sore.pipeline;

import io.github.kunaigear.dailycams.obscura.sore.Camera;

public interface RenderPipeline {
    void beginFrame(FrameContext ctx);

    void draw(DrawCommand cmd);

    void endFrame();

    Camera getCamera();

    void setCamera(Camera camera);
}

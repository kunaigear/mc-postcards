package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.CommandCulling;
import io.github.kunaigear.dailycams.render.GBuffer;
import io.github.kunaigear.dailycams.render.RenderFlags;
import io.github.kunaigear.dailycams.render.SeeThrough;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import de.tomalbrc.cameraobscura.sore.Uniforms;
import de.tomalbrc.cameraobscura.sore.rasterizer.Framebuffer;
import de.tomalbrc.cameraobscura.sore.rasterizer.SoftwareRasterizer;
import de.tomalbrc.cameraobscura.sore.shader.FragmentIn;
import de.tomalbrc.cameraobscura.sore.shader.MatteShader;
import de.tomalbrc.cameraobscura.sore.shader.Shader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * For the "realistic" shader: records what Camera Obscura's terrain shader is drawing at each pixel into the
 * active {@link GBuffer}, and turns off Camera Obscura's own fog (the shader adds its own haze, and distant
 * terrain continues where the fog would end the picture). For distant terrain, also records glass and water drawn
 * over empty background ({@link SeeThrough}). Does nothing unless one of those is active on this thread. During our
 * renders, also tells {@link CommandCulling} where fragments were drawn.
 */
@Mixin(value = SoftwareRasterizer.class, remap = false)
public abstract class SoftwareRasterizerMixin {
    @org.spongepowered.asm.mixin.Shadow
    public abstract Framebuffer getFramebuffer();

    @WrapOperation(method = "drawTriangle", at = @At(value = "INVOKE",
            target = "Lde/tomalbrc/cameraobscura/sore/shader/Shader;fragment(Lde/tomalbrc/cameraobscura/sore/shader/FragmentIn;Lde/tomalbrc/cameraobscura/sore/Uniforms;)I"))
    private int dailycams$recordFragment(Shader shader, FragmentIn in, Uniforms uniforms, Operation<Integer> original,
                                         @Local(name = "idx") int idx) {
        if (RenderFlags.rendering()) CommandCulling.drawn(idx);
        GBuffer gbuffer = GBuffer.active();
        if ((gbuffer == null && !RenderFlags.noFog()) || !(shader instanceof MatteShader)) return original.call(shader, in, uniforms);
        double fogStart = uniforms.fogStart, fogEnd = uniforms.fogEnd;
        uniforms.fogStart = 1.0e9;
        uniforms.fogEnd = 2.0e9;
        int src;
        try {
            src = original.call(shader, in, uniforms);
        } finally {
            uniforms.fogStart = fogStart;
            uniforms.fogEnd = fogEnd;
        }
        if (gbuffer != null) gbuffer.record(idx, in, uniforms, src);
        SeeThrough seeThrough = SeeThrough.active();
        int alpha = src >>> 24;
        if (seeThrough != null && alpha > 0 && alpha < 255) {
            // Called before the fragment is blended, so the colour buffer still holds what's behind it.
            Framebuffer fb = getFramebuffer();
            if (Double.isInfinite(fb.getDepthBuffer()[idx])) seeThrough.record(idx, alpha, fb.getColorBuffer()[idx]);
        }
        return src;
    }
}

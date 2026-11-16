package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.GBuffer;
import io.github.kunaigear.dailycams.render.RenderFlags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import de.tomalbrc.cameraobscura.sore.Uniforms;
import de.tomalbrc.cameraobscura.sore.rasterizer.SoftwareRasterizer;
import de.tomalbrc.cameraobscura.sore.shader.FragmentIn;
import de.tomalbrc.cameraobscura.sore.shader.MatteShader;
import de.tomalbrc.cameraobscura.sore.shader.Shader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * For the "realistic" shader: records what Camera Obscura's terrain shader is drawing at each pixel into the
 * active {@link GBuffer}, and turns off Camera Obscura's own fog (the shader adds its own haze, and distant
 * terrain continues where the fog would end the picture). Does nothing unless one of those is active on this thread.
 */
@Mixin(value = SoftwareRasterizer.class, remap = false)
public abstract class SoftwareRasterizerMixin {
    @WrapOperation(method = "drawTriangle", at = @At(value = "INVOKE",
            target = "Lde/tomalbrc/cameraobscura/sore/shader/Shader;fragment(Lde/tomalbrc/cameraobscura/sore/shader/FragmentIn;Lde/tomalbrc/cameraobscura/sore/Uniforms;)I"))
    private int dailycams$recordFragment(Shader shader, FragmentIn in, Uniforms uniforms, Operation<Integer> original,
                                         @Local(name = "idx") int idx) {
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
        return src;
    }
}

package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.Throttle;
import de.tomalbrc.cameraobscura.sore.pipeline.DrawCommand;
import de.tomalbrc.cameraobscura.sore.pipeline.SoftwareRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets our render thread pause between draw commands, so drawing the frame is throttled too. */
@Mixin(value = SoftwareRenderPipeline.class, remap = false)
public abstract class SoftwareRenderPipelineMixin {
    @Inject(method = "executeCommand", at = @At("HEAD"))
    private void dailycams$throttle(DrawCommand cmd, boolean sortTriangles, CallbackInfo ci) {
        Throttle.checkpoint();
    }
}

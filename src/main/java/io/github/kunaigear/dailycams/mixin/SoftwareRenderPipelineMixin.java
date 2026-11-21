package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.CommandCulling;
import io.github.kunaigear.dailycams.render.DrawOrder;
import io.github.kunaigear.dailycams.render.RenderFlags;
import io.github.kunaigear.dailycams.render.Throttle;
import de.tomalbrc.cameraobscura.sore.Camera;
import de.tomalbrc.cameraobscura.sore.Uniforms;
import de.tomalbrc.cameraobscura.sore.pipeline.DrawCommand;
import de.tomalbrc.cameraobscura.sore.pipeline.SoftwareRenderPipeline;
import de.tomalbrc.cameraobscura.sore.rasterizer.Rasterizer;
import de.tomalbrc.cameraobscura.sore.shader.MatteShader;
import org.joml.Matrix4d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.List;

/**
 * Lets our render thread pause between draw commands, so drawing the frame is throttled too. While our render runs,
 * sorts the draw commands faster ({@link DrawOrder}) and leaves out those that can't change a pixel
 * ({@link CommandCulling}).
 */
@Mixin(value = SoftwareRenderPipeline.class, remap = false)
public abstract class SoftwareRenderPipelineMixin {
    @Shadow private Camera camera;
    @Shadow @Final private Uniforms uniforms;
    @Shadow @Final private Rasterizer rasterizer;

    @Unique private final Matrix4d dailycams$viewProjection = new Matrix4d();

    @Inject(method = "endFrame", at = @At("HEAD"))
    private void dailycams$prepareCulling(CallbackInfo ci) {
        if (!RenderFlags.rendering()) return;
        dailycams$viewProjection.set(uniforms.proj).mul(uniforms.view);
        CommandCulling.begin(rasterizer.getFramebuffer());
    }

    @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true)
    private void dailycams$beforeCommand(DrawCommand cmd, boolean sortTriangles, CallbackInfo ci) {
        Throttle.checkpoint();
        if (!RenderFlags.rendering()) return;
        // Only terrain: MatteShader's vertices are the model's positions, other shaders may move them.
        if (cmd.renderType().shader() instanceof MatteShader && CommandCulling.culled(cmd.model(), dailycams$viewProjection, cmd.transform())) {
            ci.cancel();
        }
    }

    @Redirect(method = "endFrame", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V", ordinal = 0))
    private void dailycams$sortOpaque(List<DrawCommand> commands, Comparator<DrawCommand> comparator) {
        if (RenderFlags.rendering()) DrawOrder.sortByDistance(commands, camera.getPosition(), false);
        else commands.sort(comparator);
    }

    @Redirect(method = "endFrame", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V", ordinal = 1))
    private void dailycams$sortTransparent(List<DrawCommand> commands, Comparator<DrawCommand> comparator) {
        if (RenderFlags.rendering()) DrawOrder.sortByDistance(commands, camera.getPosition(), true);
        else commands.sort(comparator);
    }
}

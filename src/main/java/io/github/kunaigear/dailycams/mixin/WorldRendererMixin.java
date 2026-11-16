package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.GBuffer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.tomalbrc.cameraobscura.renderer.WorldRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * For the "realistic" shader, which draws its own sky and clouds: skips Camera Obscura's sky dome and blocky
 * clouds, so every background pixel keeps an infinite depth.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;canHaveWeather()Z"))
    private boolean dailycams$skipSky(Level level, Operation<Boolean> original) {
        return GBuffer.active() == null && original.call(level);
    }
}

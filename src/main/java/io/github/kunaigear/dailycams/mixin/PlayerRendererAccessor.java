package io.github.kunaigear.dailycams.mixin;

import de.tomalbrc.cameraobscura.renderer.entity.ModelBakery;
import de.tomalbrc.cameraobscura.renderer.entity.specific.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = PlayerRenderer.class, remap = false)
public interface PlayerRendererAccessor {
    /** Baked player models by texture path plus "_wide" or "_slim". */
    @Accessor("PLAYER_CACHE")
    Map<String, ModelBakery.BakedPart> dailycams$cache();
}

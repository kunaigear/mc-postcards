package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.PlayerSkins;
import de.tomalbrc.cameraobscura.sore.Texture;
import de.tomalbrc.cameraobscura.util.resource.RPHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses the skin an online player joined with instead of looking it up on Mojang's session server. */
@Mixin(value = RPHelper.class, remap = false)
public abstract class RPHelperMixin {
    @Inject(method = "getPlayerTexture", at = @At("HEAD"), cancellable = true)
    private static void dailycams$knownSkin(String uuid, CallbackInfoReturnable<Texture> cir) {
        Texture skin = PlayerSkins.get(uuid);
        if (skin != null) cir.setReturnValue(skin);
    }
}

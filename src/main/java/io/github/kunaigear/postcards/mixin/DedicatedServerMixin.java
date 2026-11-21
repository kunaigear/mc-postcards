package io.github.kunaigear.postcards.mixin;

import io.github.kunaigear.postcards.Postcards;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dedicated server stops ticking after it has been empty for a while ("pause-when-empty-seconds").
 * Chunks don't load while paused, so keep the server awake while cameras are waiting to be rendered.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {
    @Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
    private void postcards$stayAwakeWhileRendering(CallbackInfoReturnable<Integer> cir) {
        Postcards mod = Postcards.get();
        if (mod != null && mod.isBusy()) {
            cir.setReturnValue(0);
        }
    }
}

package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.LodCache;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drops a chunk from the distant terrain cache once the server has handed its new state to the chunk IO. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("RETURN"))
    private void dailycams$dropCachedTerrain(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) LodCache.saved(level.dimension(), chunk.getPos());
    }
}

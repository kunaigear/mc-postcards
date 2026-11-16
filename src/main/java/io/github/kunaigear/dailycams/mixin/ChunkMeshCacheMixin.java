package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.BushyLeaves;
import io.github.kunaigear.dailycams.render.PinnedMeshes;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.sore.model.Model;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Camera Obscura evicts every cached chunk mesh that is not near a player once every 10 seconds.
 * Our cameras usually sit where no player is, so while one of our renders runs we serve the meshes
 * we built ourselves from {@link PinnedMeshes}, which that cleanup cannot touch.
 */
@Mixin(value = ChunkMeshCache.class, remap = false)
public abstract class ChunkMeshCacheMixin {
    @Inject(method = "getChunkMesh(Lnet/minecraft/world/level/Level;J)Lde/tomalbrc/cameraobscura/renderer/ChunkMeshCache$ChunkMesh;",
            at = @At("HEAD"), cancellable = true)
    private static void dailycams$servePinned(Level level, long key, CallbackInfoReturnable<ChunkMeshCache.ChunkMesh> cir) {
        ChunkMeshCache.ChunkMesh pinned = PinnedMeshes.get(level, key);
        if (pinned != null) {
            cir.setReturnValue(pinned);
        }
    }

    /** Leaf blocks with leaves sticking out of them, while a realistic render builds its chunks. */
    @Inject(method = "getModelForState", at = @At("HEAD"), cancellable = true)
    private static void dailycams$bushyLeaves(BlockAndLightGetter level, int x, int y, int z, BlockState state,
                                              CallbackInfoReturnable<List<Model>> cir) {
        List<Model> models = BushyLeaves.models(level, x, y, z, state);
        if (models != null) cir.setReturnValue(models);
    }
}

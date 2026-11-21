package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.BlockModelViews;
import io.github.kunaigear.dailycams.render.BushyLeaves;
import io.github.kunaigear.dailycams.render.PinnedMeshes;
import io.github.kunaigear.dailycams.render.RenderFlags;
import io.github.kunaigear.dailycams.render.SectionCache;
import de.tomalbrc.cameraobscura.model.resource.RPModel;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.sore.model.Model;
import de.tomalbrc.cameraobscura.util.resource.RPHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Hooks into Camera Obscura's chunk meshing. It evicts every cached chunk mesh that is not near a player once every
 * 10 seconds. Our cameras usually sit where no player is, so while one of our renders runs we serve the meshes we
 * built ourselves from {@link PinnedMeshes}, which that cleanup cannot touch. The rest makes meshing cheaper.
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

    /** While our render runs, block models come from {@link BlockModelViews}. */
    @Redirect(method = "getModelViewForState", at = @At(value = "INVOKE",
            target = "Lde/tomalbrc/cameraobscura/util/resource/RPHelper;loadBlockModelViews(Lnet/minecraft/world/level/block/state/BlockState;)Ljava/util/List;"))
    private static List<RPModel.View> dailycams$cachedViews(BlockState state) {
        return RenderFlags.rendering() ? BlockModelViews.of(state) : RPHelper.loadBlockModelViews(state);
    }

    /** Reuses positions while our render runs ({@link SectionCache#position}). */
    @Redirect(method = {"getSmoothLight", "isSolidForAO", "isOccluded", "getModelViewForState"},
            at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos dailycams$reusePosition(int x, int y, int z) {
        return RenderFlags.rendering() ? SectionCache.position(x, y, z) : new BlockPos(x, y, z);
    }

    @Redirect(method = {"isOccluded", "computeCulledFaces"}, at = @At(value = "NEW", target = "()Lnet/minecraft/core/BlockPos$MutableBlockPos;"))
    private static BlockPos.MutableBlockPos dailycams$reuseCursor() {
        return RenderFlags.rendering() ? SectionCache.cursor() : new BlockPos.MutableBlockPos();
    }

    /** {@code Direction.values()} copies the array, once per block meshed. */
    @Redirect(method = {"isOccluded", "computeCulledFaces"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private static Direction[] dailycams$directions() {
        return SectionCache.DIRECTIONS;
    }

    /** Shared arrays instead of a new one per vertex ({@link SectionCache#cornerSides}). */
    @Redirect(method = "buildModelWithLighting", at = @At(value = "INVOKE",
            target = "Lde/tomalbrc/cameraobscura/renderer/ChunkMeshCache;getAOSideDirections(Lnet/minecraft/core/Direction;DDD)[Lnet/minecraft/core/Direction;"))
    private static Direction[] dailycams$cornerSides(Direction face, double x, double y, double z) {
        return SectionCache.cornerSides(face, x, y, z);
    }
}

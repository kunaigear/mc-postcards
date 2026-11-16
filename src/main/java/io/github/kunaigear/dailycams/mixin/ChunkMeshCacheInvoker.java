package io.github.kunaigear.dailycams.mixin;

import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ChunkMeshCache.class, remap = false)
public interface ChunkMeshCacheInvoker {
    @Invoker("rebuildChunk")
    static void dailycams$rebuildChunk(Level level, ChunkMeshCache.ChunkMesh mesh) {
        throw new AssertionError();
    }

    /** The block model Camera Obscura uses for a block state (for baking distant terrain). */
    @Invoker("getModelViewForState")
    static java.util.List<de.tomalbrc.cameraobscura.model.resource.RPModel.View> dailycams$getModelViewForState(
            net.minecraft.world.level.BlockGetter level, int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {
        throw new AssertionError();
    }

    @Invoker("computeCulledFaces")
    static java.util.EnumSet<net.minecraft.core.Direction> dailycams$computeCulledFaces(
            net.minecraft.world.level.BlockGetter level, int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {
        throw new AssertionError();
    }

    @Invoker("buildModelWithLighting")
    static de.tomalbrc.cameraobscura.sore.model.Model dailycams$buildModelWithLighting(
            net.minecraft.world.level.BlockAndLightGetter level, int x, int y, int z, de.tomalbrc.cameraobscura.sore.model.Mesh mesh,
            net.minecraft.world.level.block.state.BlockState state) {
        throw new AssertionError();
    }
}

package io.github.kunaigear.dailycams.mixin;

import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The chunk a position is in, from Camera Obscura's cache (for biome colours, without asking the level). */
@Mixin(value = ChunkMeshCache.FastBlockGetter.class, remap = false)
public interface FastBlockGetterInvoker {
    @Invoker("getChunkAt")
    ChunkAccess dailycams$getChunkAt(BlockPos pos);
}

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
}

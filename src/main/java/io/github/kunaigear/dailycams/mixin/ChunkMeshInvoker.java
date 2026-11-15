package io.github.kunaigear.dailycams.mixin;

import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ChunkMeshCache.ChunkMesh.class, remap = false)
public interface ChunkMeshInvoker {
    @Invoker("<init>")
    static ChunkMeshCache.ChunkMesh dailycams$create(BlockPos origin, long key) {
        throw new AssertionError();
    }
}

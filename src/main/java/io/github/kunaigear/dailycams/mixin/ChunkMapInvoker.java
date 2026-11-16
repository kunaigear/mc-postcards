package io.github.kunaigear.dailycams.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Reads a saved chunk from disk (upgraded to the current format) without loading it into the world. */
@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> dailycams$readChunk(ChunkPos pos);
}

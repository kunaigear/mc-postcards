package io.github.kunaigear.dailycams.mixin;

import io.github.kunaigear.dailycams.render.SectionCache;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Camera Obscura looks up a chunk for every block it reads, and a third of the meshing time went into those hash map
 * lookups. Nearly all of them ask for the chunk asked for last, so that one is remembered. Blocks and light around
 * the section being meshed come from {@link SectionCache}.
 */
@Mixin(value = ChunkMeshCache.FastBlockGetter.class, remap = false)
public abstract class FastBlockGetterMixin {
    @Shadow @Final private Long2ReferenceOpenHashMap<ChunkAccess> chunkCache;
    @Shadow @Final private LevelLightEngine lightEngine;

    // No chunk has this position: it's outside the world border.
    @Unique private long dailycams$lastKey = ChunkPos.pack(Integer.MAX_VALUE, Integer.MAX_VALUE);
    @Unique private @Nullable ChunkAccess dailycams$lastChunk;

    /**
     * @author kunaigear
     * @reason Same lookup, with the last chunk remembered.
     */
    @Overwrite
    private @Nullable ChunkAccess getChunkAt(BlockPos pos) {
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (key != dailycams$lastKey) {
            dailycams$lastChunk = chunkCache.get(key);
            dailycams$lastKey = key;
        }
        return dailycams$lastChunk;
    }

    /**
     * @author kunaigear
     * @reason Same lookup, kept in {@link SectionCache} while a section is meshed.
     */
    @Overwrite
    public BlockState getBlockState(BlockPos pos) {
        int i = SectionCache.index(pos);
        if (i >= 0) {
            BlockState known = SectionCache.state(i);
            if (known != null) return known;
        }
        ChunkAccess chunk = getChunkAt(pos);
        BlockState state = chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        if (i >= 0) SectionCache.remember(i, state);
        return state;
    }

    /**
     * @author kunaigear
     * @reason A chunk's fluid is the fluid of its block, so this reads the block through {@link #getBlockState}.
     */
    @Overwrite
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    public int getBrightness(LightLayer layer, BlockPos pos) {
        return SectionCache.light(lightEngine, layer, pos);
    }
}

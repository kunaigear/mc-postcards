package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;

/** The loaded chunks as Camera Obscura sees them: its own copy, which any thread may read. */
public final class LoadedChunks {
    private LoadedChunks() {}

    /** Loaded chunks by {@link net.minecraft.world.level.ChunkPos#pack}. Don't modify. */
    public static Long2ReferenceOpenHashMap<ChunkAccess> of(Level level) {
        var ref = ChunkMeshCacheInvoker.dailycams$loadedChunks().get(level);
        return ref != null ? ref.get() : new Long2ReferenceOpenHashMap<>();
    }

    /** The top of the highest non-empty section of any loaded chunk: above it, every block Camera Obscura reads is air. */
    static int highestBlock(Level level) {
        int top = level.getMinY() - 1;
        for (ChunkAccess chunk : of(level).values()) {
            int section = chunk.getHighestFilledSectionIndex();
            if (section >= 0) top = Math.max(top, SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(section)) + 15);
        }
        return Math.min(top, level.getMaxY());
    }
}

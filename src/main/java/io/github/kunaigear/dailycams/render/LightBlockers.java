package io.github.kunaigear.dailycams.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * What stops sunlight, for the shaders' shadow rays: one byte per block, worked out for a chunk section the first
 * time a ray enters it. Rays take millions of steps, and looking each one up in its chunk was most of their cost.
 * Sees the blocks Camera Obscura sees, and lives for one render.
 */
final class LightBlockers {
    static final byte CLEAR = 0, LEAVES = 1, OPAQUE = 2;
    /** Sections made of one kind only. */
    private static final byte[][] UNIFORM = {{CLEAR}, {LEAVES}, {OPAQUE}};

    private final Long2ReferenceOpenHashMap<ChunkAccess> chunks;
    private final Long2ObjectOpenHashMap<byte[]> sections = new Long2ObjectOpenHashMap<>();
    private final int minSection, maxSection;
    private long lastKey = Long.MIN_VALUE;
    private byte[] last;

    LightBlockers(Level level) {
        chunks = LoadedChunks.of(level);
        minSection = level.getMinSectionY();
        maxSection = level.getMaxSectionY();
    }

    static byte kind(BlockState state) {
        if (state.isAir()) return CLEAR;
        if (state.getBlock() instanceof LeavesBlock) return LEAVES;
        return state.canOcclude() ? OPAQUE : CLEAR;
    }

    byte at(int x, int y, int z) {
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        if (key != lastKey) {
            last = sections.get(key);
            if (last == null) {
                last = section(x >> 4, y >> 4, z >> 4);
                sections.put(key, last);
            }
            lastKey = key;
        }
        return last.length == 1 ? last[0] : last[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    private byte[] section(int sx, int sy, int sz) {
        ChunkAccess chunk = chunks.get(ChunkPos.pack(sx, sz));
        // Camera Obscura reads unloaded chunks and the space outside the world as air.
        if (chunk == null || sy < minSection || sy > maxSection) return UNIFORM[CLEAR];
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
        if (section.hasOnlyAir()) return UNIFORM[CLEAR];
        for (byte k = CLEAR; k <= OPAQUE; k++) {
            byte kind = k;
            if (!section.maybeHas(state -> kind(state) != kind)) return UNIFORM[kind];
        }
        byte[] kinds = new byte[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) kinds[(y << 8) | (z << 4) | x] = kind(section.getBlockState(x, y, z));
            }
        }
        return kinds;
    }
}

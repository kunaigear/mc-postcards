package io.github.kunaigear.postcards.render;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Distant terrain read from disk, kept between renders: cameras near each other, and the same camera the next day,
 * share most of their distant chunks. Holds at most {@code lodCacheMegabytes}, dropping the chunks used least
 * recently. A chunk is dropped whenever the server saves it, so the cache only ever holds what's on disk.
 */
public final class LodCache {
    private record Key(ResourceKey<Level> dimension, long pos) {}

    private static final Map<Key, LodTerrain.Column> COLUMNS = new LinkedHashMap<>(256, 0.75f, true);
    /** Chunks being read. A save in the meantime takes them off, so what the read returns is older and not kept. */
    private static final Set<Key> READING = new HashSet<>();
    private static long bytes, maxBytes;

    private LodCache() {}

    /** Sets the size limit, dropping chunks above it. 0 turns the cache off. */
    public static synchronized void resize(long max) {
        maxBytes = Math.max(0, max);
        trim();
    }

    public static synchronized void clear() {
        COLUMNS.clear();
        READING.clear();
        bytes = 0;
    }

    /** Chunks kept, their size and the limit (bytes), for {@code /postcard status}. */
    public static synchronized long[] usage() {
        return new long[]{COLUMNS.size(), bytes, maxBytes};
    }

    /** The chunk as last read, {@link LodTerrain.Column#NONE} if there was nothing, or null if it isn't kept. */
    static synchronized @Nullable LodTerrain.Column get(ServerLevel level, ChunkPos pos) {
        return COLUMNS.get(new Key(level.dimension(), pos.pack()));
    }

    /** Call before asking the server to read a chunk. */
    static synchronized void reading(ServerLevel level, ChunkPos pos) {
        if (maxBytes > 0) READING.add(new Key(level.dimension(), pos.pack()));
    }

    /** Call when a read from {@link #reading} is done; {@code column} is null if it failed. */
    static synchronized void read(ServerLevel level, ChunkPos pos, LodTerrain.@Nullable Column column) {
        Key key = new Key(level.dimension(), pos.pack());
        if (!READING.remove(key) || column == null || column.bytes() > maxBytes) return;
        LodTerrain.Column old = COLUMNS.put(key, column);
        if (old != null) bytes -= old.bytes();
        bytes += column.bytes();
        trim();
    }

    /** The server has written this chunk to disk (server thread). */
    public static synchronized void saved(ResourceKey<Level> dimension, ChunkPos pos) {
        Key key = new Key(dimension, pos.pack());
        READING.remove(key);
        LodTerrain.Column old = COLUMNS.remove(key);
        if (old != null) bytes -= old.bytes();
    }

    private static void trim() {
        Iterator<LodTerrain.Column> it = COLUMNS.values().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().bytes();
            it.remove();
        }
    }
}

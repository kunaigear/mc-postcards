package io.github.kunaigear.dailycams.render;

import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.Level;

/** Chunk meshes that belong to the render currently in progress (at most one at a time). */
public final class PinnedMeshes {
    private record Pin(Level level, Long2ObjectMap<ChunkMeshCache.ChunkMesh> meshes) {}

    private static volatile Pin current;

    private PinnedMeshes() {}

    public static ChunkMeshCache.ChunkMesh get(Level level, long key) {
        Pin pin = current;
        if (pin == null || pin.level != level) return null;
        return pin.meshes.get(key);
    }

    static void pin(Level level, Long2ObjectMap<ChunkMeshCache.ChunkMesh> meshes) {
        current = new Pin(level, meshes);
    }

    static void clear() {
        current = null;
    }
}

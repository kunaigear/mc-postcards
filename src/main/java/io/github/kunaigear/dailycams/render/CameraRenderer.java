package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import io.github.kunaigear.dailycams.mixin.ChunkMeshInvoker;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.renderer.WorldRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.awt.image.BufferedImage;

/** Bridge to Camera Obscura's software renderer. Runs on the render thread, never on the server thread. */
final class CameraRenderer {
    private CameraRenderer() {}

    record Settings(int width, int height, int fov, int renderDistance, int ssaa) {}

    /** Chunk radius Camera Obscura looks at for a given view distance (mirrors WorldRenderer). */
    static int chunkRadius(int renderDistance) {
        return ChunkMeshCache.toChunkCoord(renderDistance + ChunkMeshCache.CHUNK_SIZE - 1);
    }

    static BufferedImage render(ServerLevel level, double x, double y, double z, float yaw, float pitch, Settings s) {
        int radius = chunkRadius(s.renderDistance());
        BlockPos camPos = BlockPos.containing(x, y, z);
        int cx = ChunkMeshCache.toChunkCoord(camPos.getX());
        int cy = ChunkMeshCache.toChunkCoord(camPos.getY());
        int cz = ChunkMeshCache.toChunkCoord(camPos.getZ());

        // Build fresh meshes for every section the renderer will look at. We never reuse Camera Obscura's
        // cached ones: a mesh built while its chunk was unloaded would be empty and is never marked dirty.
        Long2ObjectOpenHashMap<ChunkMeshCache.ChunkMesh> meshes = new Long2ObjectOpenHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long key = ChunkMeshCache.chunkKeyFromCoords(cx + dx, cy + dy, cz + dz);
                    ChunkMeshCache.ChunkMesh mesh = ChunkMeshInvoker.dailycams$create(ChunkMeshCache.chunkOriginFromKey(key), key);
                    ChunkMeshCacheInvoker.dailycams$rebuildChunk(level, mesh);
                    meshes.put(key, mesh);
                }
            }
        }

        PinnedMeshes.pin(level, meshes);
        try {
            Renderer renderer = new Renderer(level, s);
            renderer.updateCamera(x, y, z, pitch, yaw);
            // Every mesh is pinned and clean, so this only reads the sky/fog/sun values for the frame.
            renderer.updateChunksInRange(level, false);
            return renderer.render();
        } finally {
            PinnedMeshes.clear();
        }
    }

    /** WorldRenderer with a per-camera field of view instead of Camera Obscura's global one. */
    private static final class Renderer extends WorldRenderer {
        Renderer(ServerLevel level, Settings s) {
            super(level, s.width(), s.height(), s.renderDistance(), s.ssaa());
            camera.setPerspective(Math.toRadians(s.fov()), (double) s.width() / s.height(), 0.1f, s.renderDistance());
        }
    }
}

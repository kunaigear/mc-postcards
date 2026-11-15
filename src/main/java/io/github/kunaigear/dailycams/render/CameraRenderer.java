package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import io.github.kunaigear.dailycams.mixin.ChunkMeshInvoker;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.renderer.WorldRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

import java.awt.image.BufferedImage;
import java.util.List;

/** Bridge to Camera Obscura's software renderer. Runs on the render thread, never on the server thread. */
final class CameraRenderer {
    private CameraRenderer() {}

    /** What the render thread is doing right now (diagnostics only). */
    static volatile String stage = "building";

    /** {@code renderDistance} is in blocks. {@code cpuShare} limits how much of one core the render may use. */
    record Settings(int width, int height, int fov, int renderDistance, int ssaa, double cpuShare) {}

    /** Chunk radius Camera Obscura looks at for a given view distance (mirrors WorldRenderer). */
    static int chunkRadius(int renderDistance) {
        return ChunkMeshCache.toChunkCoord(renderDistance + ChunkMeshCache.CHUNK_SIZE - 1);
    }

    static BufferedImage render(ServerLevel level, double x, double y, double z, float yaw, float pitch, Settings s) {
        ViewVolume view = new ViewVolume(level, x, y, z, yaw, pitch, s);
        Throttle.begin(s.cpuShare());
        stage = "building";
        try {
            // Build fresh meshes for every section the renderer will look at. We never reuse Camera Obscura's
            // cached ones: a mesh built while its chunk was unloaded would be empty and is never marked dirty.
            // Sections that can't appear in the picture (outside the view cone, beyond the view distance or
            // outside the build height) get an empty mesh instead. Building is by far the most expensive step.
            long meshStart = System.nanoTime();
            int built = 0;
            Long2ObjectOpenHashMap<ChunkMeshCache.ChunkMesh> meshes = new Long2ObjectOpenHashMap<>();
            for (int dx = -view.radius; dx <= view.radius; dx++) {
                for (int dy = -view.radius; dy <= view.radius; dy++) {
                    for (int dz = -view.radius; dz <= view.radius; dz++) {
                        int sx = view.cx + dx, sy = view.cy + dy, sz = view.cz + dz;
                        long key = ChunkMeshCache.chunkKeyFromCoords(sx, sy, sz);
                        ChunkMeshCache.ChunkMesh mesh = ChunkMeshInvoker.dailycams$create(ChunkMeshCache.chunkOriginFromKey(key), key);
                        if (view.sectionVisible(sx, sy, sz)) {
                            ChunkMeshCacheInvoker.dailycams$rebuildChunk(level, mesh);
                            built++;
                            Throttle.checkpoint();
                        } else {
                            mesh.setDrawCommands(List.of());
                        }
                        meshes.put(key, mesh);
                    }
                }
            }
            long meshMs = (System.nanoTime() - meshStart) / 1_000_000;

            PinnedMeshes.pin(level, meshes);
            try {
                stage = "drawing";
                long drawStart = System.nanoTime();
                Renderer renderer = new Renderer(level, s);
                renderer.updateCamera(x, y, z, pitch, yaw);
                // Every mesh is pinned and clean, so this only reads the sky/fog/sun values for the frame.
                renderer.updateChunksInRange(level, false);
                BufferedImage image = renderer.render();
                long drawMs = (System.nanoTime() - drawStart) / 1_000_000;
                DailyCams.LOGGER.info("Built {} chunk sections in {} ms, drew the frame in {} ms ({} ms in total spent yielding to the server)",
                        built, meshMs, drawMs, Throttle.sleptMs());
                return image;
            } finally {
                PinnedMeshes.clear();
            }
        } finally {
            Throttle.end();
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

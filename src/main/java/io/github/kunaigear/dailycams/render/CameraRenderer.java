package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import io.github.kunaigear.dailycams.mixin.ChunkMeshInvoker;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.renderer.WorldRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.util.List;

/** Bridge to Camera Obscura's software renderer. Runs on the render thread, never on the server thread. */
final class CameraRenderer {
    private CameraRenderer() {}

    /** {@code renderDistance} is in blocks. */
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

        Renderer renderer = new Renderer(level, s);
        renderer.updateCamera(x, y, z, pitch, yaw);
        FrustumIntersection frustum = renderer.frustum();

        // Build fresh meshes for every section the renderer will look at. We never reuse Camera Obscura's
        // cached ones: a mesh built while its chunk was unloaded would be empty and is never marked dirty.
        // Sections that can't appear in the picture get an empty mesh instead of being built: outside the
        // build height, entirely beyond the view distance (hidden by fog), or outside the view cone (the
        // renderer culls those anyway). This is by far the most expensive step.
        long meshStart = System.nanoTime();
        int minSection = level.getMinY() >> 4;
        int maxSection = (level.getMaxY()) >> 4;
        double maxDistSq = (double) s.renderDistance() * s.renderDistance();
        int built = 0;
        Long2ObjectOpenHashMap<ChunkMeshCache.ChunkMesh> meshes = new Long2ObjectOpenHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    long key = ChunkMeshCache.chunkKeyFromCoords(cx + dx, cy + dy, cz + dz);
                    BlockPos origin = ChunkMeshCache.chunkOriginFromKey(key);
                    ChunkMeshCache.ChunkMesh mesh = ChunkMeshInvoker.dailycams$create(origin, key);
                    int sectionY = cy + dy;
                    if (sectionY < minSection || sectionY > maxSection
                            || distanceSqToSection(x, y, z, origin) > maxDistSq
                            || !frustum.testAab(origin.getX(), origin.getY(), origin.getZ(),
                                    origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16)) {
                        mesh.setDrawCommands(List.of());
                    } else {
                        ChunkMeshCacheInvoker.dailycams$rebuildChunk(level, mesh);
                        built++;
                    }
                    meshes.put(key, mesh);
                }
            }
        }
        long meshMs = (System.nanoTime() - meshStart) / 1_000_000;

        PinnedMeshes.pin(level, meshes);
        try {
            long drawStart = System.nanoTime();
            // Every mesh is pinned and clean, so this only reads the sky/fog/sun values for the frame.
            renderer.updateChunksInRange(level, false);
            BufferedImage image = renderer.render();
            DailyCams.LOGGER.info("Built {} chunk sections in {} ms, drew the frame in {} ms",
                    built, meshMs, (System.nanoTime() - drawStart) / 1_000_000);
            return image;
        } finally {
            PinnedMeshes.clear();
        }
    }

    /** Squared distance from the camera to the closest point of a 16³ section. */
    private static double distanceSqToSection(double x, double y, double z, BlockPos origin) {
        double dx = Math.max(0, Math.max(origin.getX() - x, x - (origin.getX() + 16)));
        double dy = Math.max(0, Math.max(origin.getY() - y, y - (origin.getY() + 16)));
        double dz = Math.max(0, Math.max(origin.getZ() - z, z - (origin.getZ() + 16)));
        return dx * dx + dy * dy + dz * dz;
    }

    /** WorldRenderer with a per-camera field of view instead of Camera Obscura's global one. */
    private static final class Renderer extends WorldRenderer {
        Renderer(ServerLevel level, Settings s) {
            super(level, s.width(), s.height(), s.renderDistance(), s.ssaa());
            camera.setPerspective(Math.toRadians(s.fov()), (double) s.width() / s.height(), 0.1f, s.renderDistance());
        }

        /** The same view-cone test the renderer applies to chunk sections. */
        FrustumIntersection frustum() {
            return new FrustumIntersection(new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix()));
        }
    }
}

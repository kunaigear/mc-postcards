package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import io.github.kunaigear.dailycams.mixin.ChunkMeshInvoker;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import de.tomalbrc.cameraobscura.renderer.WorldRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.util.List;

/** Bridge to Camera Obscura's software renderer. Runs on the render thread, never on the server thread. */
final class CameraRenderer {
    private CameraRenderer() {}

    /** What the render thread is doing right now (diagnostics only). */
    static volatile String stage = "building";

    /** {@code renderDistance} is in blocks. {@code cpuShare} limits how much of one core the render may use. */
    record Settings(int width, int height, int fov, int renderDistance, int ssaa, double cpuShare,
                    String shader, DailyCamsConfig.ShaderSettings simple, DailyCamsConfig.RealisticSettings realistic,
                    int lodDistance) {}

    /** Chunk radius Camera Obscura looks at for a given view distance (mirrors WorldRenderer). */
    static int chunkRadius(int renderDistance) {
        return ChunkMeshCache.toChunkCoord(renderDistance + ChunkMeshCache.CHUNK_SIZE - 1);
    }

    /**
     * {@code missingColumns} holds {@link ChunkPos#pack} keys of chunks that didn't load; they're left out.
     * {@code hidden} is left out of the picture (see {@link #entityAtCamera}).
     */
    static BufferedImage render(ServerLevel level, double x, double y, double z, float yaw, float pitch, Settings s,
                                LongSet missingColumns, @Nullable Entity hidden) {
        ViewVolume view = new ViewVolume(level, x, y, z, yaw, pitch, s);
        Throttle.begin(s.cpuShare());
        stage = "building";
        try {
            // Build fresh meshes for every section the renderer will look at. We never reuse Camera Obscura's
            // cached ones: a mesh built while its chunk was unloaded would be empty and is never marked dirty.
            // Sections that can't appear in the picture (outside the view cone, beyond the view distance or
            // outside the build height) get an empty mesh instead. Building is by far the most expensive step.
            long meshStart = System.nanoTime();
            int built = 0, skipped = 0;
            Long2ObjectOpenHashMap<ChunkMeshCache.ChunkMesh> meshes = new Long2ObjectOpenHashMap<>();
            for (int dx = -view.radius; dx <= view.radius; dx++) {
                for (int dy = -view.radius; dy <= view.radius; dy++) {
                    for (int dz = -view.radius; dz <= view.radius; dz++) {
                        int sx = view.cx + dx, sy = view.cy + dy, sz = view.cz + dz;
                        long key = ChunkMeshCache.chunkKeyFromCoords(sx, sy, sz);
                        ChunkMeshCache.ChunkMesh mesh = ChunkMeshInvoker.dailycams$create(ChunkMeshCache.chunkOriginFromKey(key), key);
                        boolean visible = view.sectionVisible(sx, sy, sz);
                        if (visible && missingColumns.contains(ChunkPos.pack(sx, sz))) {
                            visible = false;
                            skipped++;
                        }
                        if (visible) {
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
            if (skipped > 0) {
                DailyCams.LOGGER.warn("Left out {} chunk sections from {} chunks that didn't load", skipped, missingColumns.size());
            }

            PinnedMeshes.pin(level, meshes);
            try {
                stage = "drawing";
                long drawStart = System.nanoTime();
                Renderer renderer = new Renderer(level, s);
                renderer.updateCamera(x, y, z, pitch, yaw);
                // Someone standing where the camera is (typically its owner right after placing it) would
                // otherwise fill the picture with the inside of their own head.
                renderer.entity = hidden;
                // Every mesh is pinned and clean, so this only reads the sky/fog/sun values for the frame.
                renderer.updateChunksInRange(level, false);
                boolean realistic = "realistic".equals(s.shader());
                // The realistic shader relights the frame, so it records what each pixel shows while drawing.
                GBuffer gbuffer = realistic ? new GBuffer(s.width(), s.height(), s.ssaa(), s.width() * s.ssaa()) : null;
                // With distant terrain, Camera Obscura's fog would end the near terrain in a wall of fog.
                // Not where the world has a roof (the Nether): the saved height maps would show only the roof.
                boolean lodOn = s.lodDistance() > s.renderDistance() && !level.dimensionType().hasCeiling();
                // Without the realistic shader, distant terrain needs to know where it shows through glass and water.
                SeeThrough seeThrough = lodOn && gbuffer == null ? new SeeThrough() : null;
                BufferedImage image;
                if (gbuffer != null) gbuffer.activate();
                if (seeThrough != null) seeThrough.activate();
                RenderFlags.noFog(lodOn);
                try {
                    image = renderer.render();
                } finally {
                    GBuffer.deactivate();
                    SeeThrough.deactivate();
                    RenderFlags.noFog(false);
                }
                long drawMs = (System.nanoTime() - drawStart) / 1_000_000;

                LodTerrain lod = null;
                long lodMs = 0;
                if (lodOn) {
                    stage = "lod";
                    long lodStart = System.nanoTime();
                    // Half the angle across the picture's diagonal, plus a margin.
                    double aspect = (double) s.width() / s.height();
                    double halfAngle = Math.atan(Math.tan(Math.toRadians(s.fov()) / 2) * Math.sqrt(1 + aspect * aspect)) + Math.toRadians(4);
                    lod = LodTerrain.build(level, x, y, z, yaw, pitch, halfAngle, s.lodDistance());
                    lodMs = (System.nanoTime() - lodStart) / 1_000_000;
                }
                long shadeMs = 0;
                stage = "shading";
                long shadeStart = System.nanoTime();
                if (gbuffer != null) {
                    RealisticShader.apply(image, renderer.frame(), gbuffer, level, s.realistic(), lod, s.lodDistance());
                } else {
                    if (lod != null) LodSimple.apply(image, renderer.frame(), lod, seeThrough, level, s.simple(), !"none".equals(s.shader()), s.lodDistance());
                    if (!"none".equals(s.shader())) SimpleShader.apply(image, renderer.frame(), level, s.simple());
                }
                shadeMs = (System.nanoTime() - shadeStart) / 1_000_000;
                if (lodOn) DailyCams.LOGGER.info("Distant terrain: {} ms to read", lodMs);
                DailyCams.LOGGER.info("Built {} chunk sections in {} ms, drew the frame in {} ms, shader {} ms ({} ms in total spent yielding to the server)",
                        built, meshMs, drawMs, shadeMs, Throttle.sleptMs());
                return image;
            } finally {
                PinnedMeshes.clear();
            }
        } finally {
            Throttle.end();
        }
    }

    /**
     * The entity whose body contains the camera position, if any (closest eye wins). Call on the server thread:
     * entity lookups aren't safe from other threads.
     */
    static @Nullable Entity entityAtCamera(ServerLevel level, double x, double y, double z) {
        Vec3 cam = new Vec3(x, y, z);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, AABB.ofSize(cam, 4, 4, 4))) {
            if (!e.getBoundingBox().inflate(0.3).contains(cam)) continue;
            double d = e.getEyePosition().distanceToSqr(cam);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** WorldRenderer with a per-camera field of view instead of Camera Obscura's global one. */
    private static final class Renderer extends WorldRenderer {
        /**
         * Camera Obscura moves the camera to {@link #entity}'s eyes before drawing. We only use that field to
         * hide an entity standing at the camera, so keep our own position and rotation.
         */
        @Override
        public void updateCamera(Entity entity) {
        }

        Renderer(ServerLevel level, Settings s) {
            super(level, s.width(), s.height(), s.renderDistance(), s.ssaa());
            camera.setPerspective(Math.toRadians(s.fov()), (double) s.width() / s.height(), 0.1f, s.renderDistance());
        }

        /** What the post-processing shader needs from the frame that was just drawn. */
        SimpleShader.Frame frame() {
            Matrix4d inverseViewProjection = new Matrix4d(camera.getProjectionMatrix()).mul(new Matrix4d(camera.getViewMatrix())).invert();
            return new SimpleShader.Frame(rasterizer.getFramebuffer(), scale, inverseViewProjection,
                    new Vector3d(camera.getPosition()), uniforms.fogStart, uniforms.fogEnd, uniforms.fogColor);
        }
    }
}

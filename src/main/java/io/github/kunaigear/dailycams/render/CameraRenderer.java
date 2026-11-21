package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.obscura.renderer.ChunkMeshCache;
import io.github.kunaigear.dailycams.obscura.renderer.WorldRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.awt.image.BufferedImage;
import java.util.List;

/** Drives the software renderer for one picture. Runs on the render thread, never on the server thread. */
final class CameraRenderer {
    private CameraRenderer() {}

    /** What the render thread is doing right now (diagnostics only). */
    static volatile String stage = "building";

    /** {@code renderDistance} is in blocks. {@code cpuShare} limits how much of one core the render may use. */
    record Settings(int width, int height, int fov, int renderDistance, int ssaa, double cpuShare,
                    String shader, DailyCamsConfig.ShaderSettings simple, DailyCamsConfig.RealisticSettings realistic,
                    int lodDistance, boolean renderEntities) {}

    /** Chunk radius the renderer looks at for a given view distance (mirrors WorldRenderer). */
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
        RenderFlags.rendering(true);
        stage = "building";
        try {
            // Build meshes for every section the renderer will look at. Sections that can't appear in the picture
            // (outside the view cone, beyond the view distance or outside the build height) get an empty mesh.
            long meshStart = System.nanoTime();
            int built = 0, skipped = 0;
            boolean realistic = "realistic".equals(s.shader());
            RenderFlags.bushyLeaves(realistic);
            Long2ObjectOpenHashMap<ChunkMeshCache.ChunkMesh> meshes = new Long2ObjectOpenHashMap<>();
            Renderer renderer = new Renderer(level, s, meshes);
            renderer.updateCamera(x, y, z, pitch, yaw);
            ChunkMeshCache.meshFor(renderer.viewProjection());
            Long2ReferenceOpenHashMap<ChunkAccess> chunks = LoadedChunks.of(level);
            for (int dx = -view.radius; dx <= view.radius; dx++) {
                for (int dy = -view.radius; dy <= view.radius; dy++) {
                    for (int dz = -view.radius; dz <= view.radius; dz++) {
                        int sx = view.cx + dx, sy = view.cy + dy, sz = view.cz + dz;
                        long key = ChunkMeshCache.chunkKeyFromCoords(sx, sy, sz);
                        ChunkMeshCache.ChunkMesh mesh = new ChunkMeshCache.ChunkMesh(ChunkMeshCache.chunkOriginFromKey(key), key);
                        boolean visible = view.sectionVisible(sx, sy, sz);
                        if (visible && missingColumns.contains(ChunkPos.pack(sx, sz))) {
                            visible = false;
                            skipped++;
                        }
                        if (visible && emptyMesh(level, chunks, sx, sy, sz)) visible = false;
                        if (visible) {
                            SectionCache.begin(mesh.origin());
                            ChunkMeshCache.rebuildChunk(level, mesh);
                            SectionCache.end();
                            built++;
                            Throttle.checkpoint();
                        } else {
                            mesh.setDrawCommands(List.of());
                        }
                        meshes.put(key, mesh);
                    }
                }
            }
            RenderFlags.bushyLeaves(false);
            ChunkMeshCache.meshFor(null);
            long meshMs = (System.nanoTime() - meshStart) / 1_000_000;
            if (skipped > 0) {
                DailyCams.LOGGER.warn("Left out {} chunk sections from {} chunks that didn't load", skipped, missingColumns.size());
            }

            {
                stage = "drawing";
                long drawStart = System.nanoTime();
                // Someone standing where the camera is (typically its owner right after placing it) would
                // otherwise fill the picture with the inside of their own head.
                renderer.entity = hidden;
                // The realistic shader relights the frame, so it records what each pixel shows while drawing.
                GBuffer gbuffer = realistic ? new GBuffer(s.width(), s.height(), s.ssaa(), s.width() * s.ssaa()) : null;
                // With distant terrain, the renderer's fog would end the near terrain in a wall of fog.
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
                    CommandCulling.end();
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
            }
        } finally {
            RenderFlags.bushyLeaves(false);
            ChunkMeshCache.meshFor(null);
            RenderFlags.rendering(false);
            BlockModelViews.clear();
            Throttle.end();
        }
    }

    /**
     * Whether the renderer would build an empty mesh for a section, found without looking at each of its 4096 blocks
     * and their neighbours: it holds only air (an unloaded chunk reads as air too), or it's solid all through and so
     * are the blocks touching it, so every block is hidden.
     */
    private static boolean emptyMesh(Level level, Long2ReferenceOpenHashMap<ChunkAccess> chunks, int sx, int sy, int sz) {
        LevelChunkSection section = section(level, chunks, sx, sy, sz);
        if (section == null || section.hasOnlyAir()) return true;
        if (!allSolid(section)) return false;
        for (Direction dir : Direction.values()) {
            LevelChunkSection next = section(level, chunks, sx + dir.getStepX(), sy + dir.getStepY(), sz + dir.getStepZ());
            if (next == null) return false;
            if (allSolid(next)) continue;
            // The layer of the next section that touches this one.
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int x = switch (dir) { case EAST -> 0; case WEST -> 15; default -> a; };
                    int y = switch (dir) { case UP -> 0; case DOWN -> 15; default -> dir.getAxis() == Direction.Axis.X ? a : b; };
                    int z = switch (dir) { case SOUTH -> 0; case NORTH -> 15; default -> b; };
                    if (!next.getBlockState(x, y, z).isSolidRender()) return false;
                }
            }
        }
        return true;
    }

    /** A loaded section, or null outside the world or in an unloaded chunk (where the renderer sees air). */
    private static @Nullable LevelChunkSection section(Level level, Long2ReferenceOpenHashMap<ChunkAccess> chunks, int sx, int sy, int sz) {
        ChunkAccess chunk = chunks.get(ChunkPos.pack(sx, sz));
        if (chunk == null || sy < level.getMinSectionY() || sy > level.getMaxSectionY()) return null;
        return chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
    }

    /** Only solid blocks in the section's palette (which may also list blocks no longer in it, so false is a maybe). */
    private static boolean allSolid(LevelChunkSection section) {
        return !section.maybeHas(state -> !state.isSolidRender());
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

    private static final class Renderer extends WorldRenderer {
        Renderer(ServerLevel level, Settings s, Long2ObjectMap<ChunkMeshCache.ChunkMesh> meshes) {
            super(level, s.width(), s.height(), s.fov(), s.renderDistance(), s.ssaa(), s.renderEntities(), meshes);
        }

        /** What the post-processing shader needs from the frame that was just drawn. */
        SimpleShader.Frame frame() {
            Matrix4d inverseViewProjection = new Matrix4d(camera.getProjectionMatrix()).mul(new Matrix4d(camera.getViewMatrix())).invert();
            return new SimpleShader.Frame(rasterizer.getFramebuffer(), scale, inverseViewProjection,
                    new Vector3d(camera.getPosition()), uniforms.fogStart, uniforms.fogEnd, uniforms.fogColor);
        }
    }
}

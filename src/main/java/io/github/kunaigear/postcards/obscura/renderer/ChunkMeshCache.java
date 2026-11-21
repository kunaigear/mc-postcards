/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.postcards.obscura.renderer;

import io.github.kunaigear.postcards.obscura.color.BlockColors;
import io.github.kunaigear.postcards.render.BlockModelViews;
import io.github.kunaigear.postcards.render.BushyLeaves;
import io.github.kunaigear.postcards.render.CommandCulling;
import io.github.kunaigear.postcards.render.SectionCache;
import io.github.kunaigear.postcards.obscura.model.resource.RPModel;
import io.github.kunaigear.postcards.obscura.model.triangle.FluidMeshBuilder;
import io.github.kunaigear.postcards.obscura.model.triangle.ModelTesselator;
import io.github.kunaigear.postcards.obscura.platform.Platform;
import io.github.kunaigear.postcards.obscura.sore.model.Mesh;
import io.github.kunaigear.postcards.obscura.sore.model.Model;
import io.github.kunaigear.postcards.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.postcards.obscura.sore.pipeline.RenderType;
import io.github.kunaigear.postcards.obscura.util.BuiltinModels;
import io.github.kunaigear.postcards.obscura.util.Constants;
import io.github.kunaigear.postcards.obscura.util.resource.RPHelper;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class ChunkMeshCache {
    public static final int CHUNK_SIZE = 16;
    private static final int BIOME_BLEND = 1;
    /** For models without tint indices, which never read their draw command's tints. */
    private static final IntList NO_TINTS = IntList.of();
    private static final int CHUNK_SHIFT = 4;
    private static final int BITS = 21;
    private static final long MASK = (1L << BITS) - 1;

    private static final Object BLOCK_MESH_CACHE_LOCK = new Object();
    private static volatile Long2ObjectMap<List<Mesh>> BLOCK_MESH_CACHE = new Long2ObjectOpenHashMap<>();

    private static final ConcurrentHashMap<Level, AtomicReference<Long2ReferenceOpenHashMap<ChunkAccess>>> VANILLA_CHUNKS = new ConcurrentHashMap<>();

    /** The frame's view-projection while meshing for it, or null to mesh everything. Render thread only. */
    private static @Nullable Matrix4dc frameViewProjection;

    public static class FastBlockGetter implements BlockAndLightGetter {
        private final LevelLightEngine lightEngine;
        private final int minY;
        private final int height;
        private final Long2ReferenceOpenHashMap<ChunkAccess> chunkCache;
        // Nearly every lookup asks for the chunk asked for last. No chunk has this position: it's outside the border.
        private long lastKey = ChunkPos.pack(Integer.MAX_VALUE, Integer.MAX_VALUE);
        private @Nullable ChunkAccess lastChunk;

        public FastBlockGetter(Level level) {
            this.lightEngine = level.getLightEngine();
            this.minY = level.getMinY();
            this.height = level.getHeight();
            var ref = VANILLA_CHUNKS.get(level);
            this.chunkCache = ref == null ? new Long2ReferenceOpenHashMap<>() : ref.get();
        }

        @Override
        public @NonNull LevelLightEngine getLightEngine() {
            return lightEngine;
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(@NonNull BlockPos pos) {
            ChunkAccess chunk = getChunkAt(pos);
            if (chunk == null) return null;
            return chunk.getBlockEntity(pos);
        }

        @Override
        public @NonNull BlockState getBlockState(@NonNull BlockPos pos) {
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

        /** A chunk's fluid is the fluid of its block. */
        @Override
        public @NonNull FluidState getFluidState(@NonNull BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getBrightness(@NonNull LightLayer layer, @NonNull BlockPos pos) {
            return SectionCache.light(lightEngine, layer, pos);
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getMinY() {
            return minY;
        }

        public @Nullable ChunkAccess getChunkAt(BlockPos pos) {
            long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            if (key != lastKey) {
                lastChunk = chunkCache.get(key);
                lastKey = key;
            }
            return lastChunk;
        }
    }

    public static void onChunkLoaded(Level level, ChunkAccess chunk) {
        var ref = VANILLA_CHUNKS.computeIfAbsent(
                level,
                key -> new AtomicReference<>(new Long2ReferenceOpenHashMap<>()));
        long packed = chunk.getPos().pack();
        synchronized (ref) {
            var copy = new Long2ReferenceOpenHashMap<>(ref.get());
            copy.put(packed, chunk);
            ref.set(copy);
        }
    }

    public static void onChunkUnloaded(Level level, ChunkAccess chunk) {
        var ref = VANILLA_CHUNKS.get(level);
        if (ref == null) return;
        long packed = chunk.getPos().pack();
        synchronized (ref) {
            var old = ref.get();
            if (!old.containsKey(packed)) return;
            var copy = new Long2ReferenceOpenHashMap<>(old);
            copy.remove(packed);
            ref.set(copy);
        }
    }

    /** The loaded chunks by {@link ChunkPos#pack}, a copy that any thread may read. Don't modify. */
    public static Long2ReferenceOpenHashMap<ChunkAccess> loadedChunks(Level level) {
        var ref = VANILLA_CHUNKS.get(level);
        return ref != null ? ref.get() : new Long2ReferenceOpenHashMap<>();
    }

    private static long chunkKeyFromBlock(BlockPos pos) {
        long x = (long) (pos.getX() >> CHUNK_SHIFT) & MASK;
        long y = (long) (pos.getY() >> CHUNK_SHIFT) & MASK;
        long z = (long) (pos.getZ() >> CHUNK_SHIFT) & MASK;
        return x | (y << BITS) | (z << (BITS * 2));
    }

    public static BlockPos chunkOriginFromKey(long key) {
        int x = (int) (key & MASK);
        int y = (int) ((key >> BITS) & MASK);
        int z = (int) ((key >> (BITS * 2)) & MASK);

        x = (x << (32 - BITS)) >> (32 - BITS);
        y = (y << (32 - BITS)) >> (32 - BITS);
        z = (z << (32 - BITS)) >> (32 - BITS);

        return new BlockPos(x << CHUNK_SHIFT, y << CHUNK_SHIFT, z << CHUNK_SHIFT);
    }

    public static long chunkKeyFromCoords(int cx, int cy, int cz) {
        long lx = (long) cx & MASK;
        long ly = (long) cy & MASK;
        long lz = (long) cz & MASK;
        return lx | (ly << BITS) | (lz << (BITS * 2));
    }

    public static int toChunkCoord(int i) {
        return i >> CHUNK_SHIFT;
    }

    static boolean isOccluded(BlockGetter level, int wx, int wy, int wz) {
        BlockState state = level.getBlockState(SectionCache.position(wx, wy, wz));
        if (!state.isSolidRender()) return false;
        BlockPos.MutableBlockPos pos = SectionCache.cursor();
        for (Direction dir : SectionCache.DIRECTIONS) {
            pos.set(wx + dir.getStepX(), wy + dir.getStepY(), wz + dir.getStepZ());
            BlockState neighbor = level.getBlockState(pos);
            if (!neighbor.isSolidRender()) return false;
        }
        return true;
    }

    public static Model buildModelWithLighting(BlockAndLightGetter level, int wx, int wy, int wz, Mesh mesh, BlockState state) {
        int vertexCount = mesh.positions.length / 3;
        double[] ao = new double[vertexCount];
        double[] skyLight = new double[vertexCount];
        double[] blockLight = new double[vertexCount];

        boolean solidRender = state.isSolidRender();
        // A block that isn't solid takes its light from its own position, the same for every vertex.
        double flatSky = 0, flatBlock = 0;
        if (!solidRender) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos().set(wx, wy, wz);
            flatSky = level.getBrightness(LightLayer.SKY, pos) / 15.0f;
            flatBlock = level.getBrightness(LightLayer.BLOCK, pos) / 15.0f;
        }

        float[] positions = mesh.positions, normals = mesh.normals;
        for (int i = 0; i < vertexCount; i++) {
            // Triangles of a face repeat its corners: a vertex like one just before it gets the same values.
            int same = -1;
            for (int j = i - 1; j >= Math.max(0, i - 5); j--) {
                if (positions[j * 3] == positions[i * 3] && positions[j * 3 + 1] == positions[i * 3 + 1] && positions[j * 3 + 2] == positions[i * 3 + 2]
                        && normals[j * 3] == normals[i * 3] && normals[j * 3 + 1] == normals[i * 3 + 1] && normals[j * 3 + 2] == normals[i * 3 + 2]) {
                    same = j;
                    break;
                }
            }
            if (same >= 0) {
                ao[i] = ao[same];
                skyLight[i] = skyLight[same];
                blockLight[i] = blockLight[same];
                continue;
            }

            double x = positions[i * 3];
            double y = positions[i * 3 + 1];
            double z = positions[i * 3 + 2];
            double nx = normals[i * 3];
            double ny = normals[i * 3 + 1];
            double nz = normals[i * 3 + 2];

            Direction faceDir = Direction.getApproximateNearest(nx, ny, nz);
            Direction[] sides = SectionCache.cornerSides(faceDir, x, y, z);
            ao[i] = mesh.ao ? computeVertexAO(level, wx, wy, wz, faceDir, sides[0], sides[1]) : 1.f;

            if (solidRender) {
                double worldX = wx + x;
                double worldY = wy + y;
                double worldZ = wz + z;
                skyLight[i] = getSmoothLight(level, worldX, worldY, worldZ, LightLayer.SKY, faceDir) / 15.0f;
                blockLight[i] = getSmoothLight(level, worldX, worldY, worldZ, LightLayer.BLOCK, faceDir) / 15.0f;
            } else {
                skyLight[i] = flatSky;
                blockLight[i] = flatBlock;
            }
        }

        return new Model(mesh, skyLight, blockLight, ao);
    }

    private static int getSmoothLight(BlockAndLightGetter level, double wx, double wy, double wz, LightLayer layer, Direction face) {
        double eps = 0.001f;
        int fx = (int) Math.floor(wx + face.getStepX() * eps);
        int fy = (int) Math.floor(wy + face.getStepY() * eps);
        int fz = (int) Math.floor(wz + face.getStepZ() * eps);

        Direction[] tangents = Constants.FACE_TANGENTS[face.ordinal()];
        Direction t1 = tangents[0];
        Direction t2 = tangents[1];

        return (level.getBrightness(layer, SectionCache.position(fx, fy, fz)) +
                level.getBrightness(layer, SectionCache.position(fx - t1.getStepX(), fy - t1.getStepY(), fz - t1.getStepZ())) +
                level.getBrightness(layer, SectionCache.position(fx - t2.getStepX(), fy - t2.getStepY(), fz - t2.getStepZ())) +
                level.getBrightness(layer, SectionCache.position(fx - t1.getStepX() - t2.getStepX(), fy - t1.getStepY() - t2.getStepY(), fz - t1.getStepZ() - t2.getStepZ()))) / 4;
    }

    private static double computeVertexAO(BlockGetter level, int wx, int wy, int wz, Direction faceDir, Direction side1, Direction side2) {
        int ox = wx + faceDir.getStepX();
        int oy = wy + faceDir.getStepY();
        int oz = wz + faceDir.getStepZ();

        boolean s1 = isSolidForAO(level, ox + side1.getStepX(), oy + side1.getStepY(), oz + side1.getStepZ());
        boolean s2 = isSolidForAO(level, ox + side2.getStepX(), oy + side2.getStepY(), oz + side2.getStepZ());
        boolean c = isSolidForAO(level, ox + side1.getStepX() + side2.getStepX(), oy + side1.getStepY() + side2.getStepY(), oz + side1.getStepZ() + side2.getStepZ());

        int val = (s1 && s2) ? 3 : (s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0);
        return 1.0f - val * 0.025f;
    }

    private static boolean isSolidForAO(BlockGetter level, int x, int y, int z) {
        return level.getBlockState(SectionCache.position(x, y, z)).isSolidRender();
    }

    static List<Model> getModelForState(BlockAndLightGetter level, int wx, int wy, int wz, BlockState state, Matrix4dc transform) {
        List<Model> bushy = BushyLeaves.models(level, wx, wy, wz, state, transform);
        if (bushy != null) return bushy;
        List<RPModel.View> views = getModelViewForState(level, wx, wy, wz, state);
        if (views == null || views.isEmpty()) return null;

        EnumSet<Direction> culledFaces = computeCulledFaces(level, wx, wy, wz, state);

        long key = ((long) state.hashCode() << 32) | (culledFaces.hashCode() & 0xFFFFFFFFL);
        List<Mesh> meshes = getOrBuildMeshes(key, () -> {
            List<Mesh> list = new ObjectArrayList<>(views.size());
            for (RPModel.View view : views) {
                var m = new ModelTesselator(view, culledFaces).build();
                if (m != null) list.add(m);
            }
            return list;
        });

        List<Model> result = new ObjectArrayList<>(meshes.size());
        for (Mesh mesh : meshes) {
            if (onScreen(mesh, transform)) result.add(buildModelWithLighting(level, wx, wy, wz, mesh, state));
        }
        return result;
    }

    private static List<Mesh> getOrBuildMeshes(long key, Supplier<List<Mesh>> builder) {
        var snapshot = BLOCK_MESH_CACHE;
        var existing = snapshot.get(key);
        if (existing != null) return existing;

        var built = builder.get();
        synchronized (BLOCK_MESH_CACHE_LOCK) {
            var current = BLOCK_MESH_CACHE;
            var check = current.get(key);
            if (check != null) return check;
            var next = new Long2ObjectOpenHashMap<List<Mesh>>(current);
            next.put(key, built);
            BLOCK_MESH_CACHE = next;
            return built;
        }
    }

    static List<Model> getFluidModel(FastBlockGetter level, int wx, int wy, int wz, FluidState fluidState, Matrix4dc transform) {
        BlockState blockState = level.getBlockState(new BlockPos(wx, wy, wz));
        BlockPos pos = new BlockPos(wx, wy, wz);

        var mesh = FluidMeshBuilder.createFluidMesh(level, pos, fluidState);
        if (mesh == null || !onScreen(mesh, transform)) return List.of();

        Model model = buildModelWithLighting(level, wx, wy, wz, mesh, blockState);
        return List.of(model);
    }

    public static EnumSet<Direction> computeCulledFaces(BlockGetter level, int wx, int wy, int wz, BlockState state) {
        EnumSet<Direction> culled = EnumSet.noneOf(Direction.class);
        if (state.isSolidRender()) {
            BlockPos.MutableBlockPos pos = SectionCache.cursor();
            for (Direction dir : SectionCache.DIRECTIONS) {
                pos.set(wx + dir.getStepX(), wy + dir.getStepY(), wz + dir.getStepZ());
                BlockState neighbor = level.getBlockState(pos);
                if (neighbor.isSolidRender() && !neighbor.isAir()) {
                    culled.add(dir);
                }
            }
        }
        return culled;
    }

    public static List<RPModel.View> getModelViewForState(BlockGetter level, int wx, int wy, int wz, BlockState state) {
        BlockPos pos = SectionCache.position(wx, wy, wz);
        RPModel.View model = getSpecialModel(level, wx, wy, wz, pos, state);
        if (model != null)
            return List.of(model);

        return BlockModelViews.of(state);
    }

    private static RPModel.View getSpecialModel(BlockGetter level, int wx, int wy, int wz, BlockPos pos, BlockState state) {
        if (state.is(Blocks.END_PORTAL)) return BuiltinModels.portalModel(true);
        if (state.is(Blocks.END_GATEWAY)) return BuiltinModels.portalModel(false);
        if (state.getBlock() instanceof AbstractChestBlock<?>) return BuiltinModels.chestModel(state);
        if (state.getBlock() instanceof ShulkerBoxBlock shulkerBoxBlock)
            return BuiltinModels.shulkerModel(state, Optional.ofNullable(shulkerBoxBlock.getColor()));

        if (state.is(BlockTags.BEDS)) return BuiltinModels.bedModel(state, Optional.empty());
        if (state.is(Blocks.DECORATED_POT)) return BuiltinModels.decoratedPotModel();
        if (state.is(Blocks.CONDUIT)) return BuiltinModels.conduitModel();
        if (state.is(BlockTags.ALL_SIGNS)) return BuiltinModels.signModel(state);
        if (state.is(Blocks.BELL)) return BuiltinModels.bellModel(state);
        return null;
    }

    private static int blendBlockColor(FastBlockGetter level, BlockPos pos, BlockState state, int radius) {
        if (radius <= 0) {
            ChunkAccess chunk = level.getChunkAt(pos);
            return 0xFF000000 | BlockColors.get(chunk, state, pos);
        }
        long r = 0, g = 0, b = 0;
        int samples = 0;

        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                samplePos.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                ChunkAccess chunk = level.getChunkAt(samplePos);
                int color = BlockColors.get(chunk, state, samplePos);
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
                samples++;
            }
        }
        int avgR = (int) (r / samples);
        int avgG = (int) (g / samples);
        int avgB = (int) (b / samples);
        return 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
    }

    private static int blendWaterColor(FastBlockGetter level, BlockPos pos, int radius) {
        if (radius <= 0) {
            ChunkAccess chunk = level.getChunkAt(pos);
            return 0xFF000000 | BlockColors.biomeWaterColor(chunk, pos);
        }
        long r = 0, g = 0, b = 0;
        int samples = 0;
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                samplePos.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                ChunkAccess chunk = level.getChunkAt(samplePos);
                int color = BlockColors.biomeWaterColor(chunk, samplePos);
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
                samples++;
            }
        }
        int avgR = samples == 0 ? (int) r : (int) (r / samples);
        int avgG = samples == 0 ? (int) g : (int) (g / samples);
        int avgB = samples == 0 ? (int) b : (int) (b / samples);
        return 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
    }

    static List<DrawCommand> buildChunkCommands(Level level, ChunkMesh chunkMesh) {
        FastBlockGetter fastBlockGetter = new FastBlockGetter(level);

        List<DrawCommand> commands = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int minX = chunkMesh.origin().getX();
        int minY = chunkMesh.origin().getY();
        int minZ = chunkMesh.origin().getZ();

        for (int dx = 0; dx < CHUNK_SIZE; dx++) {
            for (int dy = 0; dy < CHUNK_SIZE; dy++) {
                for (int dz = 0; dz < CHUNK_SIZE; dz++) {
                    int wx = minX + dx;
                    int wy = minY + dy;
                    int wz = minZ + dz;
                    pos.set(wx, wy, wz);

                    BlockState state = fastBlockGetter.getBlockState(pos);
                    FluidState fluidState = state.getFluidState();

                    if (state.isAir() && fluidState.isEmpty()) continue;

                    if (isOccluded(fastBlockGetter, wx, wy, wz)) continue;

                    // Biome colours are only worked out for models that use them.
                    int radius = BIOME_BLEND;
                    int waterColor = 0;
                    boolean waterColorKnown = false;
                    Matrix4d transform = new Matrix4d().translate(wx, wy, wz);

                    if (!state.isAir()) {
                        if (state.getBlock() != Blocks.BARRIER && state.getBlock() != Blocks.STRUCTURE_BLOCK) {
                            List<Model> models = getModelForState(fastBlockGetter, wx, wy, wz, state, transform);
                            if (models != null && !models.isEmpty()) {
                                IntList tints = NO_TINTS;
                                for (Model model : models) {
                                    if (model.mesh.tinted) {
                                        int blockColor = blendBlockColor(fastBlockGetter, pos, state, radius);
                                        waterColor = fluidState.is(FluidTags.WATER) ? blendWaterColor(fastBlockGetter, pos, radius) : 0xFF_FFFFFF;
                                        waterColorKnown = true;
                                        tints = IntList.of(0xFF000000 | blockColor, 0xFF000000 | waterColor);
                                        break;
                                    }
                                }
                                for (Model model : models) {
                                    commands.add(new DrawCommand(model.mesh.translucent ? RenderType.TRANSLUCENT : RenderType.SOLID, model, transform, tints));
                                }
                            }
                        }

                        Platform.renderBlock(level, pos, commands);
                    }

                    if (!fluidState.isEmpty()) {
                        List<Model> fluidModels = getFluidModel(fastBlockGetter, wx, wy, wz, fluidState, transform);
                        if (!fluidModels.isEmpty() && !waterColorKnown) {
                            waterColor = fluidState.is(FluidTags.WATER) ? blendWaterColor(fastBlockGetter, pos, radius) : 0xFF_FFFFFF;
                        }
                        IntList fluidTints = IntList.of(0xFF000000 | waterColor);
                        RenderType fluidType = RenderType.WATER;
                        for (Model model : fluidModels) {
                            commands.add(new DrawCommand(fluidType, model, transform, fluidTints));
                        }
                    }
                }
            }
        }
        return commands;
    }

    /**
     * Meshes only what the frame drawn with {@code viewProjection} can show: a model the draw step would leave out as
     * off the screen ({@link CommandCulling#offScreen}) isn't lit or added. Null meshes everything.
     */
    public static void meshFor(@Nullable Matrix4dc viewProjection) {
        frameViewProjection = viewProjection;
    }

    /** Whether a model of this mesh drawn with {@code transform} can show in the frame being meshed for. */
    public static boolean onScreen(Mesh mesh, Matrix4dc transform) {
        Matrix4dc vp = frameViewProjection;
        return vp == null || !CommandCulling.offScreen(mesh.positions, vp, transform);
    }

    public static void rebuildChunk(Level level, ChunkMesh chunkMesh) {
        try {
            var commands = buildChunkCommands(level, chunkMesh);
            chunkMesh.setDrawCommands(commands);
        } catch (Exception e) {
            Platform.getLogger().error("Failed to build chunk {}", chunkMesh.key());
        }
    }

    public static void clear() {
        VANILLA_CHUNKS.clear();
        synchronized (BLOCK_MESH_CACHE_LOCK) {
            BLOCK_MESH_CACHE = new Long2ObjectOpenHashMap<>();
        }
    }

    public static class ChunkMesh {
        private final BlockPos origin;
        private final long key;
        private volatile List<DrawCommand> drawCommands = List.of();

        public ChunkMesh(BlockPos origin, long key) {
            this.origin = origin;
            this.key = key;
        }

        public BlockPos origin() {
            return origin;
        }

        public long key() {
            return key;
        }

        public List<DrawCommand> drawCommands() {
            return drawCommands;
        }

        public void setDrawCommands(List<DrawCommand> drawCommands) {
            this.drawCommands = drawCommands;
        }
    }
}

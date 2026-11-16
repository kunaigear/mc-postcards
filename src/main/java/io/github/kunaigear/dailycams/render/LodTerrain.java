package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.mixin.ChunkMapInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Distant terrain as a height map, in the spirit of Voxy and Distant Horizons: for every block column beyond the
 * normal render distance, the height of its top block, that block's colour and whether it's water. Built from the
 * chunks the server has saved to disk (without loading them into the world), so it covers everything that was ever
 * explored. Drawn by tracing rays through it; a pyramid of maximum heights lets rays skip empty space quickly.
 */
final class LodTerrain {
    static final short UNKNOWN = Short.MIN_VALUE;
    static final byte WATER = 1, FOLIAGE = 2;
    private static final int MAX_LEVEL = 6;
    private static final int IN_FLIGHT = 24;

    final int minX, minZ, sizeX, sizeZ;
    /** Y of the top block of each column (water counts), or UNKNOWN. */
    final short[] height;
    /** For water: Y of the top block under the water. */
    final short[] floor;
    /** sRGB colour of the top block (for water: the water's colour), and of the ground under water. */
    final int[] color, floorColor;
    final byte[] flags;
    private final short[][] maxHeight = new short[MAX_LEVEL + 1][];
    private final int[] levelX = new int[MAX_LEVEL + 1], levelZ = new int[MAX_LEVEL + 1];
    int chunks;

    private LodTerrain(int minX, int minZ, int sizeX, int sizeZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        int n = sizeX * sizeZ;
        height = new short[n];
        floor = new short[n];
        color = new int[n];
        floorColor = new int[n];
        flags = new byte[n];
        Arrays.fill(height, UNKNOWN);
    }

    /**
     * Reads the saved chunks in the camera's view between {@code near} and {@code far} blocks. Runs on the render
     * thread; reads go through the server's chunk IO, a few at a time, and wait while the server is busy.
     * Returns null if there's nothing to draw.
     */
    static @Nullable LodTerrain build(ServerLevel level, double camX, double camZ, float yaw, double horizontalFov,
                                      double near, double far) {
        long start = System.nanoTime();
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        double halfAngle = horizontalFov / 2 + Math.toRadians(8);
        int ccx = Mth.floor(camX) >> 4, ccz = Mth.floor(camZ) >> 4, r = (int) Math.ceil(far / 16) + 1;
        List<ChunkPos> wanted = new ArrayList<>();
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double vx = (ccx + dx) * 16 + 8 - camX, vz = (ccz + dz) * 16 + 8 - camZ;
                double d = Math.sqrt(vx * vx + vz * vz);
                if (d < near - 24 || d > far + 12) continue;
                double angle = Math.acos(Math.clamp((vx * fx + vz * fz) / Math.max(d, 1e-6), -1, 1));
                if (angle > halfAngle + Math.atan(12 / Math.max(d, 1))) continue;
                wanted.add(new ChunkPos(ccx + dx, ccz + dz));
                minCx = Math.min(minCx, ccx + dx); maxCx = Math.max(maxCx, ccx + dx);
                minCz = Math.min(minCz, ccz + dz); maxCz = Math.max(maxCz, ccz + dz);
            }
        }
        if (wanted.isEmpty()) return null;
        wanted.sort((a, b) -> Integer.compare(dist2(a, ccx, ccz), dist2(b, ccx, ccz)));

        LodTerrain lod = new LodTerrain(minCx * 16, minCz * 16, (maxCx - minCx + 1) * 16, (maxCz - minCz + 1) * 16);
        ChunkMapInvoker storage = (ChunkMapInvoker) level.getChunkSource().chunkMap;
        Deque<CompletableFuture<Optional<CompoundTag>>> inFlight = new ArrayDeque<>();
        Deque<ChunkPos> inFlightPos = new ArrayDeque<>();
        int next = 0;
        while (next < wanted.size() || !inFlight.isEmpty()) {
            while (next < wanted.size() && inFlight.size() < IN_FLIGHT) {
                if (DailyCams.serverBusy()) break; // don't compete with the server's own chunk IO
                ChunkPos p = wanted.get(next++);
                inFlight.add(storage.dailycams$readChunk(p));
                inFlightPos.add(p);
            }
            if (inFlight.isEmpty()) {
                sleep(50);
                continue;
            }
            CompletableFuture<Optional<CompoundTag>> f = inFlight.poll();
            ChunkPos p = inFlightPos.poll();
            try {
                Optional<CompoundTag> tag = f.join();
                if (tag.isPresent()) lod.add(level, p, tag.get());
            } catch (Exception e) {
                // A chunk that can't be read or parsed is simply left out.
            }
            Throttle.checkpoint();
        }
        lod.buildPyramid();
        DailyCams.LOGGER.info("LOD: read {} of {} chunks for distant terrain in {} ms", lod.chunks, wanted.size(),
                (System.nanoTime() - start) / 1_000_000);
        return lod.chunks > 0 ? lod : null;
    }

    private static int dist2(ChunkPos p, int cx, int cz) {
        int dx = p.x() - cx, dz = p.z() - cz;
        return dx * dx + dz * dz;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void add(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
        if (!data.chunkStatus().isOrAfter(ChunkStatus.FULL)) return; // still being generated: no proper surface yet
        long[] top = data.heightmaps().get(Heightmap.Types.MOTION_BLOCKING);
        long[] bottom = data.heightmaps().get(Heightmap.Types.OCEAN_FLOOR);
        if (top == null) return;
        int bits = Mth.ceillog2(level.getHeight() + 1), minY = level.getMinY();
        SimpleBitStorage topStorage = new SimpleBitStorage(bits, 256, top);
        SimpleBitStorage bottomStorage = bottom != null ? new SimpleBitStorage(bits, 256, bottom) : null;
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (SerializableChunkData.SectionData s : data.sectionData()) {
            int index = s.y() - level.getMinSectionY();
            if (index >= 0 && index < sections.length && s.chunkSection() != null) sections[index] = s.chunkSection();
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int topY = topStorage.get(x + z * 16) - 1 + minY;
                if (topY < minY) continue;
                BlockState state = blockAt(sections, level, x, topY, z);
                if (state == null || state.isAir()) continue;
                int wx = pos.getMinBlockX() + x, wz = pos.getMinBlockZ() + z;
                int i = (wz - minZ) * sizeX + (wx - minX);
                Biome biome = biomeAt(sections, level, x, topY, z);
                height[i] = (short) topY;
                cursor.set(wx, topY, wz);
                if (state.getFluidState().is(FluidTags.WATER)) {
                    flags[i] = WATER;
                    color[i] = biome != null ? biome.getWaterColor() : 0x3F76E4;
                    int floorY = bottomStorage != null ? bottomStorage.get(x + z * 16) - 1 + minY : topY - 8;
                    floor[i] = (short) floorY;
                    BlockState ground = blockAt(sections, level, x, floorY, z);
                    floorColor[i] = ground != null ? colorOf(ground, biome, wx, wz, level, cursor.set(wx, floorY, wz)) : 0x806040;
                } else {
                    if (state.getBlock() instanceof LeavesBlock) flags[i] = FOLIAGE;
                    color[i] = colorOf(state, biome, wx, wz, level, cursor);
                }
            }
        }
        chunks++;
    }

    private static @Nullable BlockState blockAt(LevelChunkSection[] sections, ServerLevel level, int x, int y, int z) {
        int index = (y >> 4) - level.getMinSectionY();
        if (index < 0 || index >= sections.length || sections[index] == null) return null;
        return sections[index].getBlockState(x, y & 15, z);
    }

    private static @Nullable Biome biomeAt(LevelChunkSection[] sections, ServerLevel level, int x, int y, int z) {
        int index = (y >> 4) - level.getMinSectionY();
        if (index < 0 || index >= sections.length || sections[index] == null) return null;
        return sections[index].getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2).value();
    }

    /** Roughly the average colour of the block's top as it appears in game (biome tints included). */
    private static int colorOf(BlockState state, @Nullable Biome biome, int x, int z, ServerLevel level, BlockPos pos) {
        if (biome != null) {
            if (state.is(Blocks.GRASS_BLOCK)) return scale(biome.getGrassColor(x, z), 0.62);
            if (state.getBlock() instanceof LeavesBlock && state.getMapColor(level, pos) == MapColor.PLANT) {
                return scale(biome.getFoliageColor(), 0.55);
            }
        }
        MapColor map = state.getMapColor(level, pos);
        return map == MapColor.NONE ? 0x808080 : scale(map.col, 0.86);
    }

    private static int scale(int rgb, double k) {
        int r = (int) (((rgb >> 16) & 255) * k), g = (int) (((rgb >> 8) & 255) * k), b = (int) ((rgb & 255) * k);
        return r << 16 | g << 8 | b;
    }

    /** Maximum heights over 2x2, 4x4 ... 64x64 blocks, for skipping empty space. */
    private void buildPyramid() {
        maxHeight[0] = height;
        levelX[0] = sizeX;
        levelZ[0] = sizeZ;
        for (int l = 1; l <= MAX_LEVEL; l++) {
            int px = levelX[l - 1], pz = levelZ[l - 1];
            int nx = (px + 1) / 2, nz = (pz + 1) / 2;
            short[] prev = maxHeight[l - 1], cur = new short[nx * nz];
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    short m = UNKNOWN;
                    for (int dz = 0; dz < 2; dz++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int sx = x * 2 + dx, sz = z * 2 + dz;
                            if (sx < px && sz < pz) m = (short) Math.max(m, prev[sz * px + sx]);
                        }
                    }
                    cur[z * nx + x] = m;
                }
            }
            maxHeight[l] = cur;
            levelX[l] = nx;
            levelZ[l] = nz;
        }
    }

    /** Where a ray hits the terrain. */
    static final class Hit {
        double t;
        int index;
        double nx, ny, nz;
    }

    /**
     * Traces a ray from {@code o} along unit vector {@code d} between distances t0 and t1. Fills {@code hit} and
     * returns true on a hit. Columns are solid from the bottom of the world up to the top of their top block.
     */
    boolean trace(double ox, double oy, double oz, double dx, double dy, double dz, double t0, double t1, Hit hit) {
        double t = t0;
        int lvl = MAX_LEVEL;
        int lastAxis = -1;
        for (int iter = 0; iter < 4096 && t <= t1; iter++) {
            double px = ox + dx * t, pz = oz + dz * t;
            int bx = Mth.floor(px) - minX, bz = Mth.floor(pz) - minZ;
            if (bx < 0 || bz < 0 || bx >= sizeX || bz >= sizeZ) {
                // Outside the map: only continue if the ray is still heading towards it.
                if ((bx < 0 && dx <= 0) || (bx >= sizeX && dx >= 0) || (bz < 0 && dz <= 0) || (bz >= sizeZ && dz >= 0)) return false;
            }
            int size = 1 << lvl;
            int cx = Math.floorDiv(bx, size), cz = Math.floorDiv(bz, size);
            double cellMinX = minX + (double) cx * size, cellMinZ = minZ + (double) cz * size;
            double tx = dx > 0 ? (cellMinX + size - ox) / dx : dx < 0 ? (cellMinX - ox) / dx : Double.POSITIVE_INFINITY;
            double tz = dz > 0 ? (cellMinZ + size - oz) / dz : dz < 0 ? (cellMinZ - oz) / dz : Double.POSITIVE_INFINITY;
            double tExit = Math.min(tx, tz);
            short top = UNKNOWN;
            if (cx >= 0 && cz >= 0 && cx < levelX[lvl] && cz < levelZ[lvl]) top = maxHeight[lvl][cz * levelX[lvl] + cx];
            double surface = top == UNKNOWN ? Double.NEGATIVE_INFINITY : top + 1;
            double yLow = Math.min(oy + dy * t, oy + dy * Math.min(tExit, t1));
            if (yLow >= surface) {
                t = tExit + 1e-4;
                lastAxis = tx < tz ? 0 : 2;
                if (lvl < MAX_LEVEL) lvl++;
                continue;
            }
            if (lvl > 0) {
                lvl--;
                continue;
            }
            // A block column: hit its side if the ray enters below its top, else its top.
            double yNow = oy + dy * t;
            hit.index = bz * sizeX + bx;
            if (yNow < surface) {
                hit.t = t;
                if (lastAxis == 0) { hit.nx = -Math.signum(dx); hit.ny = 0; hit.nz = 0; }
                else if (lastAxis == 2) { hit.nx = 0; hit.ny = 0; hit.nz = -Math.signum(dz); }
                else { hit.nx = 0; hit.ny = 1; hit.nz = 0; }
            } else {
                hit.t = (surface - oy) / dy;
                hit.nx = 0; hit.ny = 1; hit.nz = 0;
            }
            return true;
        }
        return false;
    }
}

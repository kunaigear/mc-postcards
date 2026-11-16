package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.mixin.ChunkMapInvoker;
import de.tomalbrc.cameraobscura.renderer.ChunkMeshCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Distant terrain, the way Voxy keeps it: the real blocks of each chunk (the saved chunk sections around the
 * surface, with their sky and block light), read from the chunks the server has saved to disk without loading them
 * into the world. Rays first skip empty air quickly with a pyramid of column heights, then walk block by block near
 * the surface. Blocks are drawn with their baked models ({@link LodBlocks}), so leaves and plants are partly
 * see-through, and rays go on through water to the ground beneath.
 */
final class LodTerrain {
    static final short UNKNOWN = Short.MIN_VALUE;
    private static final int MAX_LEVEL = 6;
    private static final int IN_FLIGHT = 24;
    /** How far a ray follows water downwards before calling it deep. */
    private static final int MAX_WATER_STEPS = 48;
    /** While the server is busy, still read one chunk this often, so a server that is always busy finishes too. */
    private static final long BUSY_READ_NANOS = 1_000_000_000L;
    /** Reading stops after this long; the picture then has the distant terrain read so far. */
    private static final long MAX_READ_NANOS = 300_000_000_000L;

    final int minX, minZ, sizeX, sizeZ, minCx, minCz, chunksX, chunksZ;
    private final int minY, minSection;
    /** Y of the highest non-air block of each column, or UNKNOWN. */
    final short[] height;
    private final short[][] maxHeight = new short[MAX_LEVEL + 1][];
    private final int[] levelX = new int[MAX_LEVEL + 1], levelZ = new int[MAX_LEVEL + 1];
    /** Per chunk: the kept sections (index = section - lowSection[chunk]) and their light. */
    private final LevelChunkSection[][] sections;
    private final DataLayer[][] skyLight, blockLight;
    private final int[] lowSection;
    private final BlockState fallback;
    final ServerLevel level;
    final ChunkMeshCache.FastBlockGetter getter;
    int chunks;
    /** The highest block anywhere in the distant terrain. */
    int maxTop = Integer.MIN_VALUE;

    private LodTerrain(ServerLevel level, int minCx, int minCz, int chunksX, int chunksZ) {
        this.level = level;
        this.minCx = minCx;
        this.minCz = minCz;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.minX = minCx * 16;
        this.minZ = minCz * 16;
        this.sizeX = chunksX * 16;
        this.sizeZ = chunksZ * 16;
        this.minY = level.getMinY();
        this.minSection = level.getMinSectionY();
        height = new short[sizeX * sizeZ];
        Arrays.fill(height, UNKNOWN);
        int n = chunksX * chunksZ;
        sections = new LevelChunkSection[n][];
        skyLight = new DataLayer[n][];
        blockLight = new DataLayer[n][];
        lowSection = new int[n];
        fallback = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        getter = new ChunkMeshCache.FastBlockGetter(level);
    }

    /**
     * Reads the saved chunks in the camera's view out to {@code far} blocks: every chunk column whose blocks, anywhere
     * between the bottom and the top of the world, are within {@code halfAngle} (radians) of the view direction.
     * Runs on the render thread; reads go through the server's chunk IO, a few at a time, and mostly wait while the
     * server is busy.
     */
    static @Nullable LodTerrain build(ServerLevel level, double camX, double camY, double camZ, float yaw, float pitch,
                                      double halfAngle, double far) {
        long start = System.nanoTime();
        double cosP = Math.cos(Math.toRadians(pitch));
        double fx = -Math.sin(Math.toRadians(yaw)) * cosP, fy = -Math.sin(Math.toRadians(pitch)), fz = Math.cos(Math.toRadians(yaw)) * cosP;
        double yLow = level.getMinY() - camY, yHigh = level.getMaxY() + 1 - camY;
        int ccx = Mth.floor(camX) >> 4, ccz = Mth.floor(camZ) >> 4, r = (int) Math.ceil(far / 16) + 1;
        List<ChunkPos> wanted = new ArrayList<>();
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double vx = (ccx + dx) * 16 + 8 - camX, vz = (ccz + dz) * 16 + 8 - camZ;
                double d = Math.sqrt(vx * vx + vz * vz);
                if (d > far + 12) continue;
                // The chunk column is wanted if some point of its centre line is in view (with the chunk's half
                // width as a margin). Along the line, the angle to the view direction is smallest at y = fy d² / a.
                double a = vx * fx + vz * fz;
                double best = Math.max(cosTo(a, fy, d, yLow), cosTo(a, fy, d, yHigh));
                if (a > 0) best = Math.max(best, cosTo(a, fy, d, Math.clamp(fy * d * d / a, yLow, yHigh)));
                double angle = Math.acos(Math.clamp(best, -1, 1));
                if (d > 12 && angle > halfAngle + Math.atan(12 / d)) continue;
                wanted.add(new ChunkPos(ccx + dx, ccz + dz));
                minCx = Math.min(minCx, ccx + dx); maxCx = Math.max(maxCx, ccx + dx);
                minCz = Math.min(minCz, ccz + dz); maxCz = Math.max(maxCz, ccz + dz);
            }
        }
        if (wanted.isEmpty()) return null;
        wanted.sort((a, b) -> Integer.compare(dist2(a, ccx, ccz), dist2(b, ccx, ccz)));

        LodTerrain lod = new LodTerrain(level, minCx, minCz, maxCx - minCx + 1, maxCz - minCz + 1);
        ChunkMapInvoker storage = (ChunkMapInvoker) level.getChunkSource().chunkMap;
        Deque<CompletableFuture<Optional<CompoundTag>>> inFlight = new ArrayDeque<>();
        Deque<ChunkPos> inFlightPos = new ArrayDeque<>();
        int next = 0;
        long lastRequest = 0;
        while (next < wanted.size() || !inFlight.isEmpty()) {
            long now = System.nanoTime();
            if (next < wanted.size() && now - start > MAX_READ_NANOS) {
                DailyCams.LOGGER.warn("LOD: reading distant terrain took over {} s; using the {} of {} chunks read so far",
                        MAX_READ_NANOS / 1_000_000_000L, next, wanted.size());
                next = wanted.size();
            }
            while (next < wanted.size() && inFlight.size() < IN_FLIGHT) {
                // Don't compete with the server's own chunk IO, but keep trickling while it's busy.
                if (DailyCams.serverBusy() && now - lastRequest < BUSY_READ_NANOS) break;
                ChunkPos p = wanted.get(next++);
                inFlight.add(storage.dailycams$readChunk(p));
                inFlightPos.add(p);
                lastRequest = now;
            }
            if (inFlight.isEmpty()) {
                sleep(50);
                continue;
            }
            CompletableFuture<Optional<CompoundTag>> f = inFlight.poll();
            ChunkPos p = inFlightPos.poll();
            try {
                Optional<CompoundTag> tag = f.join();
                if (tag.isPresent()) lod.add(p, tag.get());
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

    /** Cosine of the angle between the view direction and the point {@code y} above (below) the camera on a column. */
    private static double cosTo(double horizontalDot, double fy, double d, double y) {
        return (horizontalDot + fy * y) / Math.max(Math.sqrt(d * d + y * y), 1e-6);
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

    private void add(ChunkPos pos, CompoundTag tag) {
        SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
        if (!data.chunkStatus().isOrAfter(ChunkStatus.FULL)) return; // still being generated: no proper surface yet
        long[] top = data.heightmaps().get(Heightmap.Types.WORLD_SURFACE);
        long[] floor = data.heightmaps().get(Heightmap.Types.OCEAN_FLOOR);
        if (top == null) return;
        int bits = Mth.ceillog2(level.getHeight() + 1);
        SimpleBitStorage topStorage = new SimpleBitStorage(bits, 256, top);
        SimpleBitStorage floorStorage = floor != null ? new SimpleBitStorage(bits, 256, floor) : null;
        int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE;
        int ci = (pos.x() - minCx) + (pos.z() - minCz) * chunksX;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int t = topStorage.get(x + z * 16) - 1 + minY;
                int f = floorStorage != null ? floorStorage.get(x + z * 16) - 1 + minY : t - 16;
                if (t < minY) continue;
                height[(pos.getMinBlockZ() + z - minZ) * sizeX + (pos.getMinBlockX() + x - minX)] = (short) t;
                highY = Math.max(highY, t);
                lowY = Math.min(lowY, Math.min(t, f) - 6);
            }
        }
        if (highY == Integer.MIN_VALUE) return;
        maxTop = Math.max(maxTop, highY);
        // Keep the sections from a little below the lowest ground up to the highest block.
        int low = Math.max(minSection, lowY >> 4), high = (highY >> 4);
        LevelChunkSection[] keep = new LevelChunkSection[high - low + 1];
        DataLayer[] sky = new DataLayer[keep.length], blk = new DataLayer[keep.length];
        for (SerializableChunkData.SectionData s : data.sectionData()) {
            int i = s.y() - low;
            if (i < 0 || i >= keep.length) continue;
            keep[i] = s.chunkSection();
            sky[i] = s.skyLight();
            blk[i] = s.blockLight();
        }
        sections[ci] = keep;
        skyLight[ci] = sky;
        blockLight[ci] = blk;
        lowSection[ci] = low;
        chunks++;
    }

    /** Block at a position: null where nothing is known (outside the map), stone below what's kept. */
    @Nullable BlockState blockAt(int x, int y, int z) {
        int cx = (x >> 4) - minCx, cz = (z >> 4) - minCz;
        if (cx < 0 || cz < 0 || cx >= chunksX || cz >= chunksZ) return null;
        int ci = cx + cz * chunksX;
        LevelChunkSection[] s = sections[ci];
        if (s == null) return null;
        int i = (y >> 4) - lowSection[ci];
        if (i < 0) return fallback;
        if (i >= s.length) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        LevelChunkSection sec = s[i];
        return sec == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
    }

    /** Saved sky light (0-15) at a position. Where none was saved: 15 above the column's top, else 0. */
    int skyAt(int x, int y, int z) {
        DataLayer l = layer(skyLight, x, y, z);
        if (l != null && !l.isEmpty()) return l.get(x & 15, y & 15, z & 15);
        return y > topAt(x, z) ? 15 : 0;
    }

    int blockLightAt(int x, int y, int z) {
        DataLayer l = layer(blockLight, x, y, z);
        return l != null ? l.get(x & 15, y & 15, z & 15) : 0;
    }

    private @Nullable DataLayer layer(DataLayer[][] layers, int x, int y, int z) {
        int cx = (x >> 4) - minCx, cz = (z >> 4) - minCz;
        if (cx < 0 || cz < 0 || cx >= chunksX || cz >= chunksZ) return null;
        int ci = cx + cz * chunksX;
        DataLayer[] ls = layers[ci];
        if (ls == null) return null;
        int i = (y >> 4) - lowSection[ci];
        return i >= 0 && i < ls.length ? ls[i] : null;
    }

    @Nullable Biome biomeAt(int x, int y, int z) {
        int cx = (x >> 4) - minCx, cz = (z >> 4) - minCz;
        if (cx < 0 || cz < 0 || cx >= chunksX || cz >= chunksZ) return null;
        int ci = cx + cz * chunksX;
        LevelChunkSection[] s = sections[ci];
        if (s == null) return null;
        int i = Math.clamp((y >> 4) - lowSection[ci], 0, s.length - 1);
        LevelChunkSection sec = s[i];
        return sec == null ? null : sec.getNoiseBiome((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2).value();
    }

    int topAt(int x, int z) {
        int bx = x - minX, bz = z - minZ;
        if (bx < 0 || bz < 0 || bx >= sizeX || bz >= sizeZ) return UNKNOWN;
        return height[bz * sizeX + bx];
    }

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

    /** Where a ray hits a block, and the water it went through first (if any). */
    static final class Hit {
        double t;
        int x, y, z, face;
        double nx, ny, nz, a, b;
        BlockState state;
        LodBlocks.Baked baked;
        /** Position of the air (or water) block in front of the face, for light and corner shading. */
        int fx, fy, fz;
        boolean water, deep;
        double waterT;
        int waterX, waterY, waterZ;
    }

    /**
     * Traces a ray from {@code o} along unit vector {@code d} between distances t0 and t1. Returns true when it hits
     * a block (or deep water: {@code hit.deep}).
     */
    boolean trace(double ox, double oy, double oz, double dx, double dy, double dz, double t0, double t1, Hit hit) {
        hit.water = false;
        hit.deep = false;
        double t = t0;
        int lvl = MAX_LEVEL;
        int lastAxis = -1;
        for (int iter = 0; iter < 4096 && t <= t1; iter++) {
            double px = ox + dx * t, pz = oz + dz * t;
            int bx = Mth.floor(px) - minX, bz = Mth.floor(pz) - minZ;
            if (bx < 0 || bz < 0 || bx >= sizeX || bz >= sizeZ) {
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
            // Near the surface: walk block by block until the ray hits something or rises above everything again.
            double yNow = oy + dy * t;
            if (yNow >= surface && dy < 0) t = Math.max(t, (surface - oy) / dy - 1e-4);
            int r = walk(ox, oy, oz, dx, dy, dz, t, t1, lastAxis, hit);
            if (r > 0) return true;
            if (r < 0) return false;
            t = hit.t + 1e-4;
            lastAxis = -1;
        }
        return false;
    }

    /** Block-by-block walk (Amanatides-Woo). 1 = hit, 0 = left the surface (hit.t = where), -1 = past t1. */
    private int walk(double ox, double oy, double oz, double dx, double dy, double dz, double t, double t1, int lastAxis, Hit hit) {
        double px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
        int x = Mth.floor(px), y = Mth.floor(py), z = Mth.floor(pz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : t + ((dx > 0 ? x + 1 - px : px - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : t + ((dy > 0 ? y + 1 - py : py - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : t + ((dz > 0 ? z + 1 - pz : pz - z) * tDeltaZ);
        int axis = lastAxis < 0 ? dominantAxis(dx, dy, dz) : lastAxis;
        double tEnter = t;
        int waterSteps = 0;
        for (int i = 0; i < 1024; i++) {
            if (tEnter > t1) return -1;
            int top = topAt(x, z);
            if (top == UNKNOWN) {
                hit.t = tEnter;
                return 0; // nothing known here; the fast skipping handles leaving the map
            }
            // Above everything in this column: hand back to the fast skipping (not right at the start, where the
            // ray may sit exactly on the surface it's about to go below).
            if (y > top && i > 0 && (dy >= 0 || y > top + 2)) {
                hit.t = tEnter;
                return 0;
            }
            BlockState state = y > top ? null : blockAt(x, y, z);
            if (state != null && !state.isAir()) {
                boolean isWater = state.getFluidState().is(FluidTags.WATER) && !state.canOcclude();
                if (isWater) {
                    if (!hit.water) {
                        hit.water = true;
                        hit.waterT = tEnter;
                        hit.waterX = x; hit.waterY = y; hit.waterZ = z;
                    }
                    if (++waterSteps > MAX_WATER_STEPS) {
                        hit.deep = true;
                        hit.t = tEnter;
                        return 1;
                    }
                }
                if (!isWater || !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
                    LodBlocks.Baked baked = LodBlocks.get(state, getter);
                    if (!baked.empty) {
                        int face = faceFor(axis, stepX, stepY, stepZ);
                        double hx = ox + dx * tEnter, hy = oy + dy * tEnter, hz = oz + dz * tEnter;
                        double a, b;
                        switch (face) {
                            case 0, 1 -> { a = frac(hz); b = frac(hy); }
                            case 2, 3 -> { a = frac(hx); b = frac(hz); }
                            default -> { a = frac(hx); b = frac(hy); }
                        }
                        if (LodBlocks.coverage(baked, face, a, b) >= 0.5f) {
                            hit.t = tEnter;
                            hit.x = x; hit.y = y; hit.z = z;
                            hit.face = face;
                            hit.nx = LodBlocks.NORMALS[face][0]; hit.ny = LodBlocks.NORMALS[face][1]; hit.nz = LodBlocks.NORMALS[face][2];
                            hit.a = a; hit.b = b;
                            hit.state = state;
                            hit.baked = baked;
                            hit.fx = x + LodBlocks.NORMALS[face][0]; hit.fy = y + LodBlocks.NORMALS[face][1]; hit.fz = z + LodBlocks.NORMALS[face][2];
                            return 1;
                        }
                    }
                }
            }
            // Next block.
            if (tMaxX < tMaxY && tMaxX < tMaxZ) { x += stepX; tEnter = tMaxX; tMaxX += tDeltaX; axis = 0; }
            else if (tMaxY < tMaxZ) { y += stepY; tEnter = tMaxY; tMaxY += tDeltaY; axis = 1; }
            else { z += stepZ; tEnter = tMaxZ; tMaxZ += tDeltaZ; axis = 2; }
            if (y < minY) return -1;
        }
        hit.t = tEnter;
        return 0;
    }

    private static int dominantAxis(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        return ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
    }

    /** The face a ray moving in this direction enters a block through. */
    private static int faceFor(int axis, int stepX, int stepY, int stepZ) {
        return switch (axis) {
            case 0 -> stepX > 0 ? 1 : 0;
            case 1 -> stepY > 0 ? 3 : 2;
            default -> stepZ > 0 ? 5 : 4;
        };
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** Whether a block darkens the corners next to it, like vanilla smooth lighting. */
    private boolean occludes(int x, int y, int z) {
        BlockState s = blockAt(x, y, z);
        return s != null && s.canOcclude();
    }

    /**
     * Vanilla-style ambient occlusion on the hit face: each corner is darkened by the blocks touching it, and the
     * hit point blends the four corners. 1 = open, down to 0.4.
     */
    double ambientOcclusion(Hit hit) {
        int[] n = LodBlocks.NORMALS[hit.face];
        // In-face axes matching (a, b): X faces (z, y), Y faces (x, z), Z faces (x, y).
        int[] ua = n[0] != 0 ? new int[]{0, 0, 1} : new int[]{1, 0, 0};
        int[] ub = n[1] != 0 ? new int[]{0, 0, 1} : new int[]{0, 1, 0};
        double c00 = corner(hit, ua, ub, -1, -1), c10 = corner(hit, ua, ub, 1, -1);
        double c01 = corner(hit, ua, ub, -1, 1), c11 = corner(hit, ua, ub, 1, 1);
        double a = hit.a, b = hit.b;
        return (c00 * (1 - a) + c10 * a) * (1 - b) + (c01 * (1 - a) + c11 * a) * b;
    }

    private double corner(Hit hit, int[] ua, int[] ub, int sa, int sb) {
        int fx = hit.fx, fy = hit.fy, fz = hit.fz;
        boolean s1 = occludes(fx + ua[0] * sa, fy + ua[1] * sa, fz + ua[2] * sa);
        boolean s2 = occludes(fx + ub[0] * sb, fy + ub[1] * sb, fz + ub[2] * sb);
        if (s1 && s2) return 0.4;
        boolean c = occludes(fx + ua[0] * sa + ub[0] * sb, fy + ua[1] * sa + ub[1] * sb, fz + ua[2] * sa + ub[2] * sb);
        return 1 - 0.2 * ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0));
    }

    static boolean isFoliage(BlockState s) {
        return s.getBlock() instanceof LeavesBlock || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS);
    }
}

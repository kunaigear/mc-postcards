package io.github.kunaigear.dailycams.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The blocks and light around the chunk section being meshed. Camera Obscura reads each block dozens of times (for
 * hidden faces, corner shading and light, eight light values per vertex), and most of the meshing time went into
 * finding them in the chunk's palettes and the light engine's maps. Each is kept here the first time it's read.
 * Render thread only.
 */
public final class SectionCache {
    private static final int MARGIN = 2, SIZE = 16 + 2 * MARGIN;
    private static final byte UNKNOWN = -1;
    private static final BlockState[] STATES = new BlockState[SIZE * SIZE * SIZE];
    private static final byte[] SKY = new byte[SIZE * SIZE * SIZE], BLOCK = new byte[SIZE * SIZE * SIZE];
    private static boolean active;
    private static int minX, minY, minZ;
    private static final BlockPos.MutableBlockPos POSITION = new BlockPos.MutableBlockPos(), CURSOR = new BlockPos.MutableBlockPos();
    /** {@link Direction#values()} copies the array on every call. Don't modify. */
    public static final Direction[] DIRECTIONS = Direction.values();
    /** Every pair of sides a vertex's corner shading can look at, by face and the two halves of the block it's in. */
    private static final Direction[][] CORNER_SIDES = new Direction[DIRECTIONS.length * 4][];

    static {
        for (Direction face : DIRECTIONS) {
            for (int h = 0; h < 4; h++) {
                boolean first = (h & 2) != 0, second = (h & 1) != 0;
                Direction a, b;
                switch (face.getAxis()) {
                    case Y -> { a = first ? Direction.EAST : Direction.WEST; b = second ? Direction.SOUTH : Direction.NORTH; }
                    case Z -> { a = first ? Direction.EAST : Direction.WEST; b = second ? Direction.UP : Direction.DOWN; }
                    default -> { a = first ? Direction.SOUTH : Direction.NORTH; b = second ? Direction.UP : Direction.DOWN; }
                }
                CORNER_SIDES[face.ordinal() * 4 + h] = new Direction[]{a, b};
            }
        }
    }

    private SectionCache() {}

    static void begin(BlockPos origin) {
        minX = origin.getX() - MARGIN;
        minY = origin.getY() - MARGIN;
        minZ = origin.getZ() - MARGIN;
        Arrays.fill(STATES, null);
        Arrays.fill(SKY, UNKNOWN);
        Arrays.fill(BLOCK, UNKNOWN);
        active = true;
    }

    static void end() {
        active = false;
    }

    /** Where a position is kept, or -1 if it isn't. */
    public static int index(BlockPos pos) {
        if (!active || !RenderFlags.rendering()) return -1;
        int x = pos.getX() - minX, y = pos.getY() - minY, z = pos.getZ() - minZ;
        if ((x | y | z) < 0 || x >= SIZE || y >= SIZE || z >= SIZE) return -1;
        return (y * SIZE + z) * SIZE + x;
    }

    /** The block at an {@link #index}, or null if it hasn't been read yet. */
    public static @Nullable BlockState state(int index) {
        return STATES[index];
    }

    public static void remember(int index, BlockState state) {
        STATES[index] = state;
    }

    /** Same as {@code engine.getLayerListener(layer).getLightValue(pos)}. */
    public static int light(LevelLightEngine engine, LightLayer layer, BlockPos pos) {
        int i = index(pos);
        if (i < 0) return engine.getLayerListener(layer).getLightValue(pos);
        byte[] values = layer == LightLayer.SKY ? SKY : BLOCK;
        int v = values[i];
        if (v == UNKNOWN) {
            v = engine.getLayerListener(layer).getLightValue(pos);
            values[i] = (byte) v;
        }
        return v;
    }

    /**
     * A position to read one block or light value at, instead of a new one: Camera Obscura makes one for each value
     * it reads, a third of the memory a render allocates. Valid until the next call.
     */
    public static BlockPos position(int x, int y, int z) {
        return POSITION.set(x, y, z);
    }

    /** The same for a position a loop moves around. */
    public static BlockPos.MutableBlockPos cursor() {
        return CURSOR;
    }

    /**
     * The two sides next to a vertex that Camera Obscura's corner shading looks at, for a vertex at (x, y, z) within
     * its block. Camera Obscura makes a new array for each vertex. Don't modify.
     */
    public static Direction[] cornerSides(Direction face, double x, double y, double z) {
        boolean hX = x >= 0.5f, hY = y >= 0.5f, hZ = z >= 0.5f;
        boolean first = face.getAxis() == Direction.Axis.X ? hZ : hX;
        boolean second = face.getAxis() == Direction.Axis.Y ? hZ : hY;
        return CORNER_SIDES[face.ordinal() * 4 + (first ? 2 : 0) + (second ? 1 : 0)];
    }
}

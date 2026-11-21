package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.mixin.ChunkMeshCacheInvoker;
import de.tomalbrc.cameraobscura.model.resource.RPElement;
import de.tomalbrc.cameraobscura.model.resource.RPModel;
import de.tomalbrc.cameraobscura.model.triangle.ModelTesselator;
import de.tomalbrc.cameraobscura.sore.model.Mesh;
import de.tomalbrc.cameraobscura.sore.model.Model;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaves that stick out of their blocks, like the "bushy leaves" resource packs: four tilted planes through each
 * leaf block, 1.75 times as wide as the block and textured with its leaves, so canopies get a ragged outline and
 * bigger leaves instead of cubes. Only while the realistic shader's chunks are built ({@link RenderFlags}), and only
 * on leaf blocks with an open side, since inside a canopy they'd be hidden. Built and cached here, so Camera
 * Obscura's own meshes stay as they are.
 */
public final class BushyLeaves {
    /** Turns of the planes about the vertical, picked per block so a canopy doesn't repeat one pattern. */
    private static final int VARIANTS = 4;
    private static final Map<Key, List<Mesh>> CACHE = new ConcurrentHashMap<>();

    private record Key(BlockState state, EnumSet<Direction> culled, int variant) {}

    private BushyLeaves() {}

    /** Camera Obscura's models for a leaf block plus the leaves sticking out, or null to build it as usual. */
    public static @Nullable List<Model> models(BlockAndLightGetter level, int x, int y, int z, BlockState state) {
        if (!RenderFlags.bushyLeaves() || !(state.getBlock() instanceof LeavesBlock) || !exposed(level, x, y, z)) return null;
        List<RPModel.View> views = ChunkMeshCacheInvoker.dailycams$getModelViewForState(level, x, y, z, state);
        if (views == null || views.isEmpty()) return null;
        EnumSet<Direction> culled = ChunkMeshCacheInvoker.dailycams$computeCulledFaces(level, x, y, z, state);
        int variant = Math.min(VARIANTS - 1, (int) (RealisticShader.Noise.hash(x, y, z) * VARIANTS));
        List<Mesh> meshes = CACHE.computeIfAbsent(new Key(state, EnumSet.copyOf(culled), variant), k -> build(views, k));
        List<Model> models = new ArrayList<>(meshes.size());
        for (Mesh mesh : meshes) models.add(ChunkMeshCacheInvoker.dailycams$buildModelWithLighting(level, x, y, z, mesh, state));
        return models;
    }

    private static boolean exposed(BlockAndLightGetter level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Direction d : SectionCache.DIRECTIONS) {
            BlockState n = level.getBlockState(pos.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()));
            if (!(n.getBlock() instanceof LeavesBlock) && !n.canOcclude()) return true;
        }
        return false;
    }

    private static List<Mesh> build(List<RPModel.View> views, Key key) {
        List<Mesh> out = new ArrayList<>(views.size() + 1);
        for (RPModel.View view : views) {
            Mesh mesh = new ModelTesselator(view, key.culled).build();
            if (mesh != null) out.add(mesh);
        }
        Map<String, Identifier> textures = views.getFirst().model().collectTextures();
        Identifier texture = textures.getOrDefault("all", textures.get("particle"));
        if (texture != null) {
            RPModel.View planes = new RPModel.View(planes(texture.toString()), new Vector3f(0, key.variant * 90f / VARIANTS, 0));
            Mesh mesh = new ModelTesselator(planes, EnumSet.noneOf(Direction.class)).build();
            if (mesh != null) out.add(mesh);
        }
        return out;
    }

    /** Two planes tilted each way about x and about z, 28 pixels across, through the block's centre. */
    private static RPModel planes(String texture) {
        RPModel model = new RPModel();
        model.textures = Map.of("all", RPModel.TextureEntry.of(texture));
        model.elements = new ArrayList<>();
        for (String axis : new String[]{"x", "z"}) {
            for (float angle : new float[]{45, -45}) {
                RPElement e = new RPElement();
                e.from = new Vector3f(-6, 8, -6);
                e.to = new Vector3f(22, 8, 22);
                e.rotation = new RPElement.Rotation();
                e.rotation.axis = axis;
                e.rotation.angle = angle;
                e.rotation.origin = new Vector3f(8, 8, 8);
                e.faces = new EnumMap<>(Direction.class);
                // Tint index 0, like the leaves' own faces, so they get the same biome colour.
                e.faces.put(Direction.UP, new RPElement.TextureInfo("#all", new Vector4f(0, 0, 16, 16), 0, 0));
                e.faces.put(Direction.DOWN, new RPElement.TextureInfo("#all", new Vector4f(0, 0, 16, 16), 0, 0));
                model.elements.add(e);
            }
        }
        return model;
    }
}

package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.obscura.model.resource.RPModel;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The block models of each block state, kept for one render. Camera Obscura looks them up again for every block it
 * meshes, and matching a state against its blockstate file parses block state strings, so a fifth of the meshing
 * time went there. Render thread only.
 */
public final class BlockModelViews {
    private static final List<RPModel.View> NONE = List.of();
    private static final Reference2ObjectOpenHashMap<BlockState, List<RPModel.View>> VIEWS = new Reference2ObjectOpenHashMap<>();

    private BlockModelViews() {}

    public static @Nullable List<RPModel.View> of(BlockState state) {
        List<RPModel.View> views = VIEWS.get(state);
        if (views == null) {
            views = RPHelper.loadBlockModelViews(state);
            VIEWS.put(state, views == null ? NONE : views);
        }
        return views == NONE ? null : views;
    }

    static void clear() {
        VIEWS.clear();
    }
}

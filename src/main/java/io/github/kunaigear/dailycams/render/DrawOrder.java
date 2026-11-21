package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.obscura.sore.pipeline.DrawCommand;
import org.joml.Vector3dc;

import java.util.Arrays;
import java.util.List;

/**
 * Sorts the draw commands by their distance from the camera, with each distance worked out once: comparing
 * {@code worldPosition()} on both sides of every comparison was a seventh of the drawing time with our renders'
 * hundreds of thousands of commands.
 */
public final class DrawOrder {
    private DrawOrder() {}

    private record ByDistance(DrawCommand command, double distanceSquared) {}

    /** A stable sort, so commands at the same distance keep the order they were added in. */
    public static void sortByDistance(List<DrawCommand> commands, Vector3dc camera, boolean farthestFirst) {
        ByDistance[] keyed = new ByDistance[commands.size()];
        for (int i = 0; i < keyed.length; i++) {
            DrawCommand cmd = commands.get(i);
            keyed[i] = new ByDistance(cmd, cmd.worldPosition().distanceSquared(camera));
        }
        Arrays.sort(keyed, farthestFirst
                ? (a, b) -> Double.compare(b.distanceSquared, a.distanceSquared)
                : (a, b) -> Double.compare(a.distanceSquared, b.distanceSquared));
        for (int i = 0; i < keyed.length; i++) commands.set(i, keyed[i].command);
    }
}

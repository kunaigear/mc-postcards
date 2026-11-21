package io.github.kunaigear.dailycams.render;

import de.tomalbrc.cameraobscura.sore.pipeline.DrawCommand;
import org.joml.Vector3dc;

import java.util.Arrays;
import java.util.List;

/**
 * Camera Obscura sorts the draw commands by their distance from the camera, working the distance out again, into a
 * new vector, for both sides of every comparison. With our renders' hundreds of thousands of commands, that was a
 * seventh of the drawing time. This sorts them the same way with each distance worked out once.
 */
public final class DrawOrder {
    private DrawOrder() {}

    private record ByDistance(DrawCommand command, double distanceSquared) {}

    /** A stable sort on the same keys, so the order comes out exactly as Camera Obscura's. */
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

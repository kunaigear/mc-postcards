package io.github.kunaigear.dailycams.camera;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/** One virtual camera as stored in {@code cameras.json}. */
public final class CameraSpec {
    public String name;
    /** Null when created from the server console. */
    public @Nullable UUID owner;
    public String ownerName;
    /** Dimension id, e.g. "minecraft:overworld". */
    public String dimension;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    /** Null = config default. */
    public @Nullable Integer fov;
    /** Null = config default. */
    public @Nullable String webhookUrl;
    public long createdAt;

    public CameraSpec copy() {
        CameraSpec c = new CameraSpec();
        c.name = name;
        c.owner = owner;
        c.ownerName = ownerName;
        c.dimension = dimension;
        c.x = x;
        c.y = y;
        c.z = z;
        c.yaw = yaw;
        c.pitch = pitch;
        c.fov = fov;
        c.webhookUrl = webhookUrl;
        c.createdAt = createdAt;
        return c;
    }

    public String describePosition() {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f (yaw %.1f, pitch %.1f) in %s", x, y, z, yaw, pitch, dimension);
    }
}

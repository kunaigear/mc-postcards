package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.obscura.renderer.ChunkMeshCache;
import io.github.kunaigear.dailycams.obscura.sore.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a camera can see: the chunk sections inside its view frustum, within the view distance and
 * inside the build height. Used both to decide which chunks to load and which sections to build.
 */
final class ViewVolume {
    final int radius;
    final int cx;
    final int cy;
    final int cz;
    private final double x;
    private final double y;
    private final double z;
    private final double maxDistSq;
    private final int minSection;
    private final int maxSection;
    private final FrustumIntersection frustum;

    ViewVolume(Level level, double x, double y, double z, float yaw, float pitch, CameraRenderer.Settings s) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = CameraRenderer.chunkRadius(s.renderDistance());
        BlockPos camPos = BlockPos.containing(x, y, z);
        this.cx = ChunkMeshCache.toChunkCoord(camPos.getX());
        this.cy = ChunkMeshCache.toChunkCoord(camPos.getY());
        this.cz = ChunkMeshCache.toChunkCoord(camPos.getZ());
        this.maxDistSq = (double) s.renderDistance() * s.renderDistance();
        this.minSection = level.getMinY() >> 4;
        this.maxSection = level.getMaxY() >> 4;

        // Same camera setup as WorldRenderer.updateCamera + our per-camera perspective.
        Camera camera = new Camera();
        camera.setPerspective(Math.toRadians(s.fov()), (double) s.width() / s.height(), 0.1f, s.renderDistance());
        camera.setPosition(x, y, z);
        camera.setTargetFromYawPitch(yaw, Math.clamp(pitch, -89.9f, 89.9f));
        camera.setUp(0, 1, 0);
        this.frustum = new FrustumIntersection(new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix()));
    }

    boolean sectionVisible(int sx, int sy, int sz) {
        if (sy < minSection || sy > maxSection) return false;
        int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        double dx = Math.max(0, Math.max(ox - x, x - (ox + 16)));
        double dy = Math.max(0, Math.max(oy - y, y - (oy + 16)));
        double dz = Math.max(0, Math.max(oz - z, z - (oz + 16)));
        if (dx * dx + dy * dy + dz * dz > maxDistSq) return false;
        return frustum.testAab(ox, oy, oz, ox + 16, oy + 16, oz + 16);
    }

    /**
     * Chunk columns that contain a visible section, plus a one-chunk margin so lighting and ambient
     * occlusion at the edges have neighbours. Nearest first.
     */
    List<ChunkPos> columnsToLoad() {
        Set<Long> visible = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (sectionVisible(cx + dx, cy + dy, cz + dz)) {
                        visible.add(ChunkPos.pack(cx + dx, cz + dz));
                        break;
                    }
                }
            }
        }
        Set<Long> withMargin = new LinkedHashSet<>();
        for (long packed : visible) {
            ChunkPos p = ChunkPos.unpack(packed);
            for (int mx = -1; mx <= 1; mx++) {
                for (int mz = -1; mz <= 1; mz++) withMargin.add(ChunkPos.pack(p.x() + mx, p.z() + mz));
            }
        }
        List<ChunkPos> out = new ArrayList<>();
        for (long packed : withMargin) out.add(ChunkPos.unpack(packed));
        out.sort(Comparator.comparingInt(p -> (p.x() - cx) * (p.x() - cx) + (p.z() - cz) * (p.z() - cz)));
        return out;
    }
}

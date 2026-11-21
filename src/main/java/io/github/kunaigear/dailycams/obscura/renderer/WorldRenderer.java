/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Daily Cams by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE-AGPL in the repository root.
 */
package io.github.kunaigear.dailycams.obscura.renderer;

import io.github.kunaigear.dailycams.obscura.json.CachedIdentifierDeserializer;
import io.github.kunaigear.dailycams.obscura.model.triangle.ModelTesselator;
import io.github.kunaigear.dailycams.obscura.platform.Platform;
import io.github.kunaigear.dailycams.obscura.renderer.entity.EntityRenderer;
import io.github.kunaigear.dailycams.obscura.sore.Camera;
import io.github.kunaigear.dailycams.obscura.sore.Uniforms;
import io.github.kunaigear.dailycams.obscura.sore.model.Model;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.FrameContext;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.RenderType;
import io.github.kunaigear.dailycams.obscura.sore.pipeline.SoftwareRenderPipeline;
import io.github.kunaigear.dailycams.obscura.sore.rasterizer.Framebuffer;
import io.github.kunaigear.dailycams.obscura.sore.rasterizer.SoftwareRasterizer;
import io.github.kunaigear.dailycams.obscura.util.BuiltinModels;
import io.github.kunaigear.dailycams.obscura.util.Constants;
import io.github.kunaigear.dailycams.obscura.util.resource.RPHelper;
import io.github.kunaigear.dailycams.render.GBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.*;

import java.awt.image.BufferedImage;
import java.lang.Math;
import java.util.List;

public class WorldRenderer {
    private static final Matrix4d skyTransform = new Matrix4d().scale(512);
    private static Model cloudModel;

    protected final SoftwareRasterizer rasterizer;
    protected final Uniforms uniforms;
    protected final Camera camera;
    protected final Level level;
    protected final SoftwareRenderPipeline pipeline;
    protected final int renderDistance;
    protected final int scale;
    private final boolean renderEntities;
    private final Long2ObjectMap<ChunkMeshCache.ChunkMesh> meshes;

    /** Left out of the picture, typically someone standing where the camera is. */
    public Entity entity = null;

    protected EnvironmentValues environmentValues;

    /** {@code meshes} holds a built mesh for every chunk section within the view distance, by section key. */
    public WorldRenderer(Level level, int width, int height, double fovDegrees, int renderDistance, int ssaa, boolean renderEntities,
                         Long2ObjectMap<ChunkMeshCache.ChunkMesh> meshes) {
        this.level = level;
        this.scale = ssaa;
        this.renderDistance = renderDistance;
        this.renderEntities = renderEntities;
        this.meshes = meshes;
        this.rasterizer = new SoftwareRasterizer(new Framebuffer(width * scale, height * scale));
        this.uniforms = new Uniforms();

        this.camera = new Camera();
        camera.setPerspective(Math.toRadians(fovDegrees), (double) width / height, 0.1f, renderDistance);

        this.pipeline = new SoftwareRenderPipeline(rasterizer, uniforms, camera);
    }

    public void updateCamera(double x, double y, double z, double pitch, double yaw) {
        camera.setPosition(x, y, z);
        camera.setTargetFromYawPitch(yaw, Math.clamp(pitch, -89.9f, 89.9f));
        camera.setUp(0, 1, 0);
    }

    /** The projection times the view matrix, exactly as the pipeline works it out for the frame. */
    public Matrix4d viewProjection() {
        return new Matrix4d().set(new Matrix4d().set(camera.getProjectionMatrix())).mul(new Matrix4d().set(camera.getViewMatrix()));
    }

    record EnvironmentValues(
            double sunAngle,
            double lightFactor,
            int fogColor,
            double cloudHeight
    ) {
        public static EnvironmentValues fromLevel(Level level, Vec3 cameraPos) {
            double sunAngle = level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, cameraPos) * Mth.DEG_TO_RAD;
            double lightFactor = level.environmentAttributes().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, cameraPos);
            var fogVec = level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, cameraPos).mul(255, new Vector3f());
            int fogColor = (int)fogVec.x << 16 | (int)fogVec.y << 8 | (int)fogVec.z;
            double cloudHeight = level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_HEIGHT, cameraPos);
            return new EnvironmentValues(sunAngle, lightFactor, fogColor, cloudHeight);
        }
    }

    public BufferedImage render() {
        this.environmentValues = EnvironmentValues.fromLevel(level, camera.position());
        long time = level.getGameTime();
        double sunAngle = this.environmentValues.sunAngle;
        double lightFactor = this.environmentValues.lightFactor;
        int fogColor = this.environmentValues.fogColor;
        double cloudHeight = this.environmentValues.cloudHeight;

        Vector3d sunDir = new Vector3d(
                -Math.sin(sunAngle),
                Math.cos(sunAngle),
                0
        ).normalize();

        uniforms.fogStart = renderDistance * 0.85f;
        uniforms.fogEnd = renderDistance;
        uniforms.fogColor = fogColor;

        pipeline.beginFrame(new FrameContext(time, sunDir, lightFactor));

        if (cloudModel == null)
            cloudModel = new Model(new ModelTesselator(BuiltinModels.cloudModel()).build());

        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(camera.getProjectionMatrix()).mul(camera.getViewMatrix()));

        BlockPos cameraPos = camera.blockPosition();
        int chunkRadius = ChunkMeshCache.toChunkCoord(renderDistance + ChunkMeshCache.CHUNK_SIZE - 1);
        int camChunkX = ChunkMeshCache.toChunkCoord(cameraPos.getX());
        int camChunkY = ChunkMeshCache.toChunkCoord(cameraPos.getY());
        int camChunkZ = ChunkMeshCache.toChunkCoord(cameraPos.getZ());

        List<DrawCommand> allCommands = new ObjectArrayList<>();

        if (renderEntities) {
            AABB entityBounds = AABB.ofSize(camera.position(), renderDistance * 2, renderDistance * 2, renderDistance * 2);
            var entities = level.getEntities(entity, entityBounds);
            for (Entity ent : entities) {
                if (ent instanceof EnderDragonPart)
                    continue;

                EntityRenderer r = EntityRenderers.RENDERER.get(ent.getType());
                if (r != null) {
                    try {
                        r.render(pipeline, ent);
                    } catch (Exception e) {
                        Platform.getLogger().error("Error rendering entity", e);
                    }
                    continue;
                }

                if (ent.getType() == EntityTypes.PLAYER) {
                    try {
                        RPHelper.loadTexture(CachedIdentifierDeserializer.get(Constants.DYNAMIC_PLAYER_TEXTURE + ":" + ent.getUUID()));
                    } catch (Exception e) {
                        continue;
                    }
                }

                var isItemDisplay = ent.getType() == EntityTypes.ITEM_DISPLAY;
                if (isItemDisplay) {
                    EntityRenderers.renderItemDisplay(pipeline, ent);
                    continue;
                }

                Platform.renderEntity(ent, allCommands);
            }
        }

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dy = -chunkRadius; dy <= chunkRadius; dy++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    int chunkX = camChunkX + dx;
                    int chunkY = camChunkY + dy;
                    int chunkZ = camChunkZ + dz;
                    long key = ChunkMeshCache.chunkKeyFromCoords(chunkX, chunkY, chunkZ);
                    ChunkMeshCache.ChunkMesh chunkMesh = meshes.get(key);
                    if (chunkMesh == null) continue;

                    // cull chunk
                    BlockPos origin = chunkMesh.origin();
                    if (!frustum.testAab(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + ChunkMeshCache.CHUNK_SIZE, origin.getY() + ChunkMeshCache.CHUNK_SIZE, origin.getZ() + ChunkMeshCache.CHUNK_SIZE)) {
                        continue;
                    }

                    allCommands.addAll(chunkMesh.drawCommands());
                }
            }
        }

        // The realistic shader draws its own sky and clouds: every background pixel keeps an infinite depth.
        if (GBuffer.active() == null && level.canHaveWeather()) {
            allCommands.add(new DrawCommand(RenderType.SKY, Model.SKY, skyTransform));

            double CLOUD_TILE_SIZE = 2048;
            int CLOUD_RENDER_RANGE = renderDistance;

            int camTileX = Mth.floor(camera.position().x / CLOUD_TILE_SIZE);
            int camTileZ = Mth.floor(camera.position().z / CLOUD_TILE_SIZE);
            int halfRange = (int) Math.ceil(CLOUD_RENDER_RANGE / CLOUD_TILE_SIZE);

            for (int tx = camTileX - halfRange; tx <= camTileX + halfRange; tx++) {
                for (int tz = camTileZ - halfRange; tz <= camTileZ + halfRange; tz++) {
                    double worldX = tx * CLOUD_TILE_SIZE;
                    double worldZ = tz * CLOUD_TILE_SIZE;

                    Matrix4d cloudTransform = new Matrix4d()
                            .translate(worldX, cloudHeight, worldZ)
                            .scale(256);

                    allCommands.add(new DrawCommand(
                            RenderType.ENTITY,
                            cloudModel,
                            cloudTransform,
                            IntList.of()
                    ));
                }
            }
        }

        for (DrawCommand cmd : allCommands) {
            pipeline.draw(cmd);
        }

        pipeline.endFrame();

        return rasterizer.getFramebuffer().toImage(scale);
    }
}

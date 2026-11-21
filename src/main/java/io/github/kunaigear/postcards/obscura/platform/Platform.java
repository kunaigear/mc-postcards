/*
 * Derived from Camera Obscura 2.1.4+26.3 by tomalbrc (https://github.com/tomalbrc/camera-obscura).
 * Copyright (c) 2026 tomalbrc. Modified for Postcards by kunaigear, November 2026.
 * Licensed under the GNU Affero General Public License, version 3: see LICENSE in the repository root.
 */
package io.github.kunaigear.postcards.obscura.platform;

import io.github.kunaigear.postcards.Postcards;
import io.github.kunaigear.postcards.obscura.PolymerAssetFetcher;
import io.github.kunaigear.postcards.obscura.PolymerHolderRenderer;
import io.github.kunaigear.postcards.obscura.color.BlockColors;
import io.github.kunaigear.postcards.obscura.renderer.ChunkMeshCache;
import io.github.kunaigear.postcards.obscura.sore.pipeline.DrawCommand;
import io.github.kunaigear.postcards.obscura.util.BuiltinEntityModels;
import io.github.kunaigear.postcards.obscura.util.MojangAssetFetcher;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.List;

/** What the renderer needs from the running server: game assets, registries, loaded chunks and Polymer's models. */
public final class Platform {
    private static volatile AssetFetcher assets;
    private static volatile MinecraftServer server;

    private Platform() {}

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("polymer-autohost") || !PolymerAssetFetcher.isEnabled()) {
            var fetcher = new MojangAssetFetcher(FabricLoader.getInstance().getRawGameVersion(),
                    FabricLoader.getInstance().getConfigDir().resolve(Postcards.MOD_ID), logger());
            assets = fetcher;
            fetcher.initialize().thenRun(() -> {
                BlockColors.init();
                BuiltinEntityModels.initModels();
            });
        }
        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(builder -> {
            assets = new PolymerAssetFetcher(builder);
            BlockColors.init();
            BuiltinEntityModels.initModels();
        });
        ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> ChunkMeshCache.onChunkLoaded(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register(ChunkMeshCache::onChunkUnloaded);
    }

    public static Logger getLogger() {
        return logger();
    }

    private static Logger logger() {
        return Postcards.LOGGER;
    }

    public static AssetFetcher getAssetFetcher() {
        return assets;
    }

    public static MinecraftServer getMinecraftServer() {
        return server;
    }

    public static RegistryAccess getRegistryAccess() {
        return server.registryAccess();
    }

    /**
     * Polymer finds the chunk with {@code Level.getChunk}, which off the server thread hands the lookup to the server
     * thread and waits for it: one round trip per block. Take the chunk from our own map of loaded chunks instead.
     */
    public static void renderBlock(Level level, BlockPos.MutableBlockPos pos, List<DrawCommand> commands) {
        var attachment = ChunkMeshCache.loadedChunks(level).get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4)) instanceof LevelChunk chunk
                ? BlockAwareAttachment.get(chunk, pos) : null;
        if (attachment != null && attachment.holder() != null) {
            PolymerHolderRenderer.render(attachment.holder(), commands, 0);
        }
    }

    public static void renderEntity(Entity ent, List<DrawCommand> allCommands) {
        var holders = ((HolderAttachmentHolder) ent).polymerVE$getHolders();
        if (holders != null) {
            for (HolderAttachment holder : holders) {
                if (holder.holder() != null)
                    PolymerHolderRenderer.render(holder.holder(), allCommands, ent.getBbHeight());
            }
        }
    }
}

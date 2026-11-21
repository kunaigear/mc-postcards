package io.github.kunaigear.postcards.render;

import io.github.kunaigear.postcards.Postcards;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import io.github.kunaigear.postcards.obscura.json.CachedIdentifierDeserializer;
import io.github.kunaigear.postcards.obscura.renderer.EntityRenderers;
import io.github.kunaigear.postcards.obscura.renderer.entity.specific.PlayerRenderer;
import io.github.kunaigear.postcards.obscura.sore.Texture;
import io.github.kunaigear.postcards.obscura.util.resource.RPHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skins of online players, taken from the profile they joined with. Camera Obscura looks every player up on
 * Mojang's session server instead, and a rate-limited lookup leaves the player with the default skin until the
 * server restarts.
 */
public final class PlayerSkins {
    private record Skin(String url, Texture texture) {}

    private static final Map<UUID, Skin> SKINS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PlayerSkins() {}

    /** Skin URLs of the players within {@code radius} blocks of the camera. Server thread only. */
    static Map<UUID, String> near(ServerLevel level, double x, double y, double z, double radius) {
        var sessions = level.getServer().services().sessionService();
        Map<UUID, String> urls = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) > radius * radius) continue;
            try {
                // Reads the signed textures property the player joined with; no request to Mojang.
                MinecraftProfileTexture skin = sessions.getTextures(player.getGameProfile()).skin();
                if (skin != null) urls.put(player.getUUID(), skin.getUrl());
            } catch (RuntimeException e) {
                // Checking the signature needs Mojang's public keys, which are missing while their services are down.
                Postcards.LOGGER.warn("Could not read the skin of player {}: {}", player.getUUID(), e.toString());
            }
        }
        return urls;
    }

    /** Downloads the skins that are new or changed. Runs on the render thread, before drawing. */
    static void prepare(Map<UUID, String> urls) {
        urls.forEach((uuid, url) -> {
            Skin known = SKINS.get(uuid);
            if (known != null && known.url.equals(url)) return;
            try {
                HttpResponse<byte[]> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    Postcards.LOGGER.warn("Could not download the skin of player {}: HTTP {}", uuid, response.statusCode());
                    return;
                }
                SKINS.put(uuid, new Skin(url, Texture.fromPng(new ByteArrayInputStream(response.body()))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Postcards.LOGGER.warn("Could not download the skin of player {}: {}", uuid, e.toString());
                return;
            }
            // Camera Obscura caches the texture and the baked player model that holds it: without this, a
            // default skin from a failed lookup (or an old skin) would be used for good.
            RPHelper.removeCached(CachedIdentifierDeserializer.get("dyn.p:" + uuid));
            var models = ((PlayerRenderer) EntityRenderers.RENDERER.get(EntityTypes.PLAYER)).PLAYER_CACHE;
            models.remove("dyn.p:" + uuid + "_wide");
            models.remove("dyn.p:" + uuid + "_slim");
        });
    }

    /** The skin for Camera Obscura's player texture lookup, or null to let it ask Mojang. */
    public static @Nullable Texture get(String uuid) {
        try {
            Skin skin = SKINS.get(UUID.fromString(uuid));
            return skin == null ? null : skin.texture;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

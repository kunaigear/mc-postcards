package io.github.kunaigear.postcards.client;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Fills in what only the client knows when a player places a camera with {@code /postcard set}:
 * <ul>
 *   <li>the player's field of view setting, unless the command gives one, so the picture shows as much as the
 *       player saw on screen;</li>
 *   <li>with a freecam mod, the position and direction of the view on screen. The server only knows where the
 *       player's body is, so it would otherwise place the camera there.</li>
 * </ul>
 * Before the command is sent, it's rewritten into the explicit form
 * {@code /postcard set <name> <x y z> <yaw pitch> <fov>}. That's a normal command, checked with the normal
 * permissions, so the server needs nothing extra.
 * <p>
 * Older servers don't accept everything (the FOV argument is new in 1.2.1), so the helper first looks at the
 * command list the server sent to this client and only adds what that server understands.
 */
public final class PostcardsClient implements ClientModInitializer {
    /** The server's command, and the subcommand that places a camera. */
    private static final String COMMAND = "postcard";
    private static final String SET = "set";

    /** Token counts: "postcard set [name]" has no position, "postcard set name x y z yaw pitch" has one but no fov. */
    private static final int MAX_WITHOUT_POSITION = 3, WITH_POSITION = 8;

    /** A camera that has moved further than this from the player's eyes counts as detached from the body. */
    private static final double AWAY_DISTANCE = 1.5;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.MODIFY_COMMAND.register(PostcardsClient::rewrite);
    }

    static String rewrite(String command) {
        String[] args = command.trim().split("\\s+");
        if (args.length < 2 || !args[0].equals(COMMAND) || !args[1].equals(SET)) {
            return command;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return command;
        }
        ServerSupport server = ServerSupport.of(mc, args[0]);
        if (!server.position()) {
            return command; // not a Postcards server we know how to talk to
        }
        int fov = mc.options.fov().get();

        // A position is given, only the fov is missing.
        if (args.length == WITH_POSITION) {
            return server.fov() ? String.join(" ", args) + " " + fov : command;
        }
        if (args.length > MAX_WITHOUT_POSITION) {
            return command; // Already has a fov, or isn't a valid command; the server will say so.
        }

        String name = args.length == 3 ? args[2] : player.getGameProfile().name();
        Camera camera = mc.gameRenderer.mainCamera();
        boolean freecam = isFreecam(mc, player, camera);
        if (!freecam && !server.fov()) {
            return command; // nothing to add: the server knows where the player's eyes are
        }
        Vec3 pos;
        float yaw, pitch;
        if (freecam) {
            pos = camera.position();
            yaw = camera.yRot();
            pitch = camera.xRot();
            player.sendSystemMessage(Component.literal("[Postcards] Using your freecam view.").withStyle(ChatFormatting.GRAY));
        } else {
            pos = player.getEyePosition();
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
        String rewritten = String.format(Locale.ROOT, "%s %s %s %.3f %.3f %.3f %.2f %.2f", args[0], SET, name,
                pos.x, pos.y, pos.z, Mth.wrapDegrees(yaw), pitch);
        return server.fov() ? rewritten + " " + fov : rewritten;
    }

    /** What the server's {@code /postcard set} accepts, read from the command list the server sent. */
    record ServerSupport(boolean position, boolean fov) {
        private static final ServerSupport NONE = new ServerSupport(false, false);

        static ServerSupport of(Minecraft mc, String root) {
            ClientPacketListener connection = mc.getConnection();
            if (connection == null) return NONE;
            CommandNode<?> node = connection.getCommands().getRoot().getChild(root);
            CommandNode<?> rotation = child(child(child(child(node, SET), "name"), "pos"), "rotation");
            return new ServerSupport(rotation != null, child(rotation, "fov") != null);
        }

        private static @Nullable CommandNode<?> child(@Nullable CommandNode<?> node, String name) {
            return node == null ? null : node.getChild(name);
        }
    }

    /**
     * True if the view on screen isn't the player's own eyes. Freecam (and spectating a mob) switches the camera
     * to another entity; other freecam mods move the camera itself, which the distance check catches. Third-person
     * view also moves the camera, but it still shows your own surroundings, so it's left alone.
     */
    private static boolean isFreecam(Minecraft mc, LocalPlayer player, Camera camera) {
        if (mc.getCameraEntity() != player) {
            return true;
        }
        return !camera.isDetached() && camera.position().distanceTo(player.getEyePosition()) > AWAY_DISTANCE;
    }
}

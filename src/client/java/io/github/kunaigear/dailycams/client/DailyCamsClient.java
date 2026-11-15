package io.github.kunaigear.dailycams.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;

/**
 * Fills in what only the client knows when a player places a camera with {@code /dailycam set}:
 * <ul>
 *   <li>the player's field of view setting, unless the command gives one, so the picture shows as much as the
 *       player saw on screen;</li>
 *   <li>with a freecam mod, the position and direction of the view on screen. The server only knows where the
 *       player's body is, so it would otherwise place the camera there.</li>
 * </ul>
 * Before the command is sent, it's rewritten into the explicit form
 * {@code /dailycam set <name> <x y z> <yaw pitch> <fov>}. That's a normal command, checked with the normal
 * permissions, so the server needs nothing extra.
 */
public final class DailyCamsClient implements ClientModInitializer {
    /** The server's command and its alias, and the subcommand that places a camera. */
    private static final Set<String> COMMANDS = Set.of("dailycam", "dcam");
    private static final String SET = "set";

    /** Token counts: "dailycam set [name]" has no position, "dailycam set name x y z yaw pitch" has one but no fov. */
    private static final int MAX_WITHOUT_POSITION = 3, WITH_POSITION = 8;

    /** A camera that has moved further than this from the player's eyes counts as detached from the body. */
    private static final double AWAY_DISTANCE = 1.5;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.MODIFY_COMMAND.register(DailyCamsClient::rewrite);
    }

    static String rewrite(String command) {
        String[] args = command.trim().split("\\s+");
        if (args.length < 2 || !COMMANDS.contains(args[0]) || !args[1].equals(SET)) {
            return command;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return command;
        }
        int fov = mc.options.fov().get();

        // A position is given, only the fov is missing.
        if (args.length == WITH_POSITION) {
            return String.join(" ", args) + " " + fov;
        }
        if (args.length > MAX_WITHOUT_POSITION) {
            return command; // Already has a fov, or isn't a valid command; the server will say so.
        }

        String name = args.length == 3 ? args[2] : player.getGameProfile().name();
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 pos;
        float yaw, pitch;
        if (isFreecam(mc, player, camera)) {
            pos = camera.position();
            yaw = camera.yRot();
            pitch = camera.xRot();
            player.sendSystemMessage(Component.literal("[Daily Cams] Using your freecam view.").withStyle(ChatFormatting.GRAY));
        } else {
            pos = player.getEyePosition();
            yaw = player.getYRot();
            pitch = player.getXRot();
        }
        return String.format(Locale.ROOT, "%s %s %s %.3f %.3f %.3f %.2f %.2f %d", args[0], SET, name,
                pos.x, pos.y, pos.z, Mth.wrapDegrees(yaw), pitch, fov);
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

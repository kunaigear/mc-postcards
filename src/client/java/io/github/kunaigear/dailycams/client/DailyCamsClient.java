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
 * The server only knows where a player's body is. With a freecam mod, the view on screen is somewhere else, so a
 * plain {@code /dailycam set [name]} would place the camera at the body. This rewrites such a command, before it's
 * sent, into the explicit form {@code /dailycam set <name> <x y z> <yaw pitch>} using the view on screen. The
 * server needs no changes: the explicit form is a normal command, checked with the normal permissions.
 */
public final class DailyCamsClient implements ClientModInitializer {
    /** The server's command and its alias, and the subcommand that places a camera. */
    private static final Set<String> COMMANDS = Set.of("dailycam", "dcam");
    private static final String SET = "set";

    /** A camera that has moved further than this from the player's eyes counts as detached from the body. */
    private static final double AWAY_DISTANCE = 1.5;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.MODIFY_COMMAND.register(DailyCamsClient::rewrite);
    }

    static String rewrite(String command) {
        String[] args = command.trim().split("\s+");
        // Only the forms without a position: "dailycam set" and "dailycam set <name>".
        if (!COMMANDS.contains(args[0]) || args.length < 2 || !args[1].equals(SET) || args.length > 3) {
            return command;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return command;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        if (!isFreecam(mc, player, camera)) {
            return command;
        }

        String name = args.length == 3 ? args[2] : player.getGameProfile().name();
        Vec3 pos = camera.position();
        String rewritten = String.format(Locale.ROOT, "%s %s %s %.3f %.3f %.3f %.2f %.2f", args[0], SET, name,
                pos.x, pos.y, pos.z, Mth.wrapDegrees(camera.yRot()), camera.xRot());
        player.sendSystemMessage(Component.literal("[Daily Cams] Using your freecam view.").withStyle(ChatFormatting.GRAY));
        return rewritten;
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

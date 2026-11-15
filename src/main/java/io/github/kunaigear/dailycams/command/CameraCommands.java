package io.github.kunaigear.dailycams.command;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.camera.CameraSpec;
import io.github.kunaigear.dailycams.camera.CameraStore;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import io.github.kunaigear.dailycams.render.RenderQueue;
import io.github.kunaigear.dailycams.webhook.WebhookSender;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * /mc_setcamera &lt;name&gt; [&lt;x y z&gt; &lt;yaw pitch&gt;]
 * /mc_camera list|info|remove|test|fov|webhook|runall|reload|status
 */
public final class CameraCommands {
    private static final Identifier PERM_SET = perm("command.setcamera");
    private static final Identifier PERM_TEST = perm("command.test");
    private static final Identifier PERM_ADMIN = perm("admin");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm z", Locale.ENGLISH);
    private static final Map<UUID, Instant> lastTest = new HashMap<>();

    private CameraCommands() {}

    private static Identifier perm(String path) {
        return Identifier.fromNamespaceAndPath(DailyCams.MOD_ID, path);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mc_setcamera")
                .requires(PermissionPredicates.<CommandSourceStack>require(PERM_SET, PermissionLevel.ALL))
                .then(nameArg(CameraCommands::suggestManageable)
                        .executes(CameraCommands::setFromView)
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("rotation", RotationArgument.rotation())
                                        .executes(CameraCommands::setExplicit)))));

        dispatcher.register(Commands.literal("mc_camera")
                .requires(PermissionPredicates.<CommandSourceStack>require(PERM_SET, PermissionLevel.ALL))
                .executes(CameraCommands::help)
                .then(Commands.literal("list").executes(CameraCommands::list))
                .then(Commands.literal("status").executes(CameraCommands::status))
                .then(Commands.literal("info").then(nameArg(CameraCommands::suggestVisible).executes(CameraCommands::info)))
                .then(Commands.literal("remove").then(nameArg(CameraCommands::suggestManageable).executes(CameraCommands::remove)))
                .then(Commands.literal("test")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_TEST, PermissionLevel.ALL))
                        .then(nameArg(CameraCommands::suggestManageable).executes(CameraCommands::test)))
                .then(Commands.literal("fov").then(nameArg(CameraCommands::suggestManageable)
                        .then(Commands.literal("default").executes(ctx -> setFov(ctx, null)))
                        .then(Commands.argument("fov", IntegerArgumentType.integer(30, 110))
                                .executes(ctx -> setFov(ctx, IntegerArgumentType.getInteger(ctx, "fov"))))))
                .then(Commands.literal("webhook")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_ADMIN, PermissionLevel.GAMEMASTERS))
                        .then(nameArg(CameraCommands::suggestManageable)
                                .then(Commands.literal("default").executes(ctx -> setWebhook(ctx, null)))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> setWebhook(ctx, StringArgumentType.getString(ctx, "url"))))))
                .then(Commands.literal("runall")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_ADMIN, PermissionLevel.GAMEMASTERS))
                        .executes(CameraCommands::runAll))
                .then(Commands.literal("reload")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_ADMIN, PermissionLevel.GAMEMASTERS))
                        .executes(CameraCommands::reload)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> nameArg(SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("name", StringArgumentType.word()).suggests(suggestions);
    }

    // ------------------------------------------------------------------ set

    private static int setFromView(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Entity entity = src.getEntity();
        if (entity == null) {
            src.sendFailure(Component.literal("From the console, give a position and rotation: /mc_setcamera <name> <x y z> <yaw pitch>"));
            return 0;
        }
        return set(src, StringArgumentType.getString(ctx, "name"), entity.getEyePosition(), entity.getYRot(), entity.getXRot());
    }

    private static int setExplicit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        Vec2 rot = RotationArgument.getRotation(ctx, "rotation").getRotation(src); // x = pitch, y = yaw
        return set(src, StringArgumentType.getString(ctx, "name"), pos, rot.y, rot.x);
    }

    private static int set(CommandSourceStack src, String name, Vec3 pos, float yaw, float pitch) {
        DailyCams mod = DailyCams.get();
        CameraStore store = mod.cameras();
        DailyCamsConfig cfg = mod.config();
        if (!CameraStore.VALID_NAME.matcher(name).matches()) {
            src.sendFailure(Component.literal("Camera names may only use letters, digits, _ and - (max 32 characters)."));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        CameraSpec existing = store.get(name);
        if (existing != null && !canManage(src, existing)) {
            src.sendFailure(Component.literal("A camera named '" + existing.name + "' already exists and belongs to " + existing.ownerName + "."));
            return 0;
        }
        if (existing == null && player != null && !isAdmin(src)) {
            int owned = store.ownedBy(player.getUUID()).size();
            if (owned >= cfg.maxCamerasPerPlayer) {
                src.sendFailure(Component.literal("You already have " + owned + " camera(s), the limit is " + cfg.maxCamerasPerPlayer
                        + ". Remove one with /mc_camera remove <name>."));
                return 0;
            }
        }

        CameraSpec cam = existing != null ? existing : new CameraSpec();
        if (existing == null) {
            cam.name = name;
            cam.owner = player != null ? player.getUUID() : null;
            cam.ownerName = player != null ? player.getName().getString() : "Server";
            cam.createdAt = System.currentTimeMillis();
        }
        cam.dimension = src.getLevel().dimension().identifier().toString();
        cam.x = pos.x;
        cam.y = pos.y;
        cam.z = pos.z;
        cam.yaw = Mth.wrapDegrees(yaw);
        cam.pitch = Mth.clamp(pitch, -90f, 90f);
        store.put(cam);

        var next = mod.scheduler().nextSlot(cfg);
        src.sendSuccess(() -> Component.literal("Camera '" + cam.name + "' " + (existing == null ? "placed" : "moved") + " at " + cam.describePosition() + ".")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(next == null ? "" : "\nNext picture: " + WHEN.format(next) + ".").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\nPreview it now with /mc_camera test " + cam.name).withStyle(ChatFormatting.GRAY)), true);
        return 1;
    }

    // ------------------------------------------------------------------ manage

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<CameraSpec> cams = visibleTo(src);
        if (cams.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No cameras yet. Look at something and run /mc_setcamera <name>."), false);
            return 0;
        }
        MutableComponent out = Component.literal(cams.size() + " camera(s):").withStyle(ChatFormatting.GOLD);
        for (CameraSpec c : cams) {
            out.append(Component.literal("\n  " + c.name).withStyle(ChatFormatting.YELLOW))
               .append(Component.literal(String.format(Locale.ROOT, "  %d %d %d %s  by %s",
                       Math.round(c.x), Math.round(c.y), Math.round(c.z), c.dimension, c.ownerName)).withStyle(ChatFormatting.GRAY));
        }
        src.sendSuccess(() -> out, false);
        return cams.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        CameraSpec cam = find(ctx, false);
        if (cam == null) return 0;
        DailyCamsConfig cfg = DailyCams.get().config();
        MutableComponent out = Component.literal("Camera " + cam.name).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n  Owner: " + cam.ownerName))
                .append(Component.literal("\n  Position: " + cam.describePosition()))
                .append(Component.literal("\n  FOV: " + (cam.fov != null ? cam.fov : cfg.fov + " (default)")));
        if (isAdmin(src)) {
            out.append(Component.literal("\n  Webhook: " + (cam.webhookUrl != null ? redact(cam.webhookUrl) : "default")));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        CameraSpec cam = find(ctx, true);
        if (cam == null) return 0;
        DailyCams.get().cameras().remove(cam.name);
        ctx.getSource().sendSuccess(() -> Component.literal("Removed camera '" + cam.name + "'.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setFov(CommandContext<CommandSourceStack> ctx, @Nullable Integer fov) {
        CameraSpec cam = find(ctx, true);
        if (cam == null) return 0;
        cam.fov = fov;
        DailyCams.get().cameras().put(cam);
        ctx.getSource().sendSuccess(() -> Component.literal("FOV of '" + cam.name + "' is now "
                + (fov == null ? "the default (" + DailyCams.get().config().fov + ")" : fov) + "."), false);
        return 1;
    }

    private static int setWebhook(CommandContext<CommandSourceStack> ctx, @Nullable String url) {
        CameraSpec cam = find(ctx, true);
        if (cam == null) return 0;
        if (url != null && !WebhookSender.isValidUrl(url.trim())) {
            ctx.getSource().sendFailure(Component.literal("That doesn't look like an http(s) URL."));
            return 0;
        }
        cam.webhookUrl = url == null ? null : url.trim();
        DailyCams.get().cameras().put(cam);
        ctx.getSource().sendSuccess(() -> Component.literal("Webhook of '" + cam.name + "' is now "
                + (url == null ? "the default." : redact(cam.webhookUrl) + ".")), false);
        return 1;
    }

    // ------------------------------------------------------------------ render

    private static int test(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        CameraSpec cam = find(ctx, true);
        if (cam == null) return 0;
        RenderQueue queue = DailyCams.get().queue();
        if (queue == null) return 0;
        ServerPlayer player = src.getPlayer();
        DailyCamsConfig cfg = DailyCams.get().config();

        if (player != null && !isAdmin(src) && cfg.testCooldownSeconds > 0) {
            Instant last = lastTest.get(player.getUUID());
            if (last != null) {
                long wait = cfg.testCooldownSeconds - Duration.between(last, Instant.now()).toSeconds();
                if (wait > 0) {
                    src.sendFailure(Component.literal("Please wait " + wait + "s before testing again."));
                    return 0;
                }
            }
        }
        if (!queue.submit(cam, cfg, result -> reply(src, player == null ? null : player.getUUID(), result))) {
            src.sendFailure(Component.literal("Camera '" + cam.name + "' is already being rendered."));
            return 0;
        }
        if (player != null) lastTest.put(player.getUUID(), Instant.now());
        int ahead = queue.size() - 1;
        src.sendSuccess(() -> Component.literal("Rendering '" + cam.name + "'" + (ahead > 0 ? " (" + ahead + " ahead in queue)" : "")
                + "... you'll get a message when it's sent.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int runAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        UUID id = player == null ? null : player.getUUID();
        int n = DailyCams.get().runAll(src.getServer(), result -> reply(src, id, result));
        src.sendSuccess(() -> Component.literal("Queued " + n + " camera(s)."), true);
        return n;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        DailyCams mod = DailyCams.get();
        RenderQueue queue = mod.queue();
        var next = mod.scheduler().nextSlot(mod.config());
        String active = queue == null ? null : queue.activeCamera();
        ctx.getSource().sendSuccess(() -> Component.literal("Daily Cams").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n  Cameras: " + mod.cameras().all().size()))
                .append(Component.literal("\n  Next run: " + (next == null ? "none (no scheduleTimes)" : WHEN.format(next))))
                .append(Component.literal("\n  Queue: " + (queue == null ? 0 : queue.size())
                        + (active != null ? " (rendering '" + active + "')" : ""))), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        DailyCams.get().reload();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded Daily Cams config and cameras."), true);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("""
                Daily Cams
                  /mc_setcamera <name> - camera at your eyes, looking where you look
                  /mc_setcamera <name> <x y z> <yaw pitch>
                  /mc_camera list | status | info <name> | remove <name>
                  /mc_camera test <name> - render and send now
                  /mc_camera fov <name> <30-110|default>"""), false);
        return 1;
    }

    private static void reply(CommandSourceStack src, @Nullable UUID playerId, RenderQueue.Result result) {
        DailyCams.logResult(result);
        Component msg = Component.literal("Camera '" + result.camera().name + "': " + result.message())
                .withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
        if (playerId != null) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(playerId);
            if (p != null) p.sendSystemMessage(msg);
        } else if (src.getPlayer() == null && src.getEntity() == null) {
            src.sendSystemMessage(msg);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static @Nullable CameraSpec find(CommandContext<CommandSourceStack> ctx, boolean mustManage) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        CameraSpec cam = DailyCams.get().cameras().get(name);
        if (cam == null) {
            src.sendFailure(Component.literal("No camera named '" + name + "'."));
            return null;
        }
        if (mustManage && !canManage(src, cam)) {
            src.sendFailure(Component.literal("Camera '" + cam.name + "' belongs to " + cam.ownerName + "."));
            return null;
        }
        return cam;
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.checkPermission(PERM_ADMIN, PermissionLevel.GAMEMASTERS);
    }

    private static boolean canManage(CommandSourceStack src, CameraSpec cam) {
        if (isAdmin(src)) return true;
        ServerPlayer p = src.getPlayer();
        return p != null && Objects.equals(p.getUUID(), cam.owner);
    }

    /** Listings show your own cameras, or all of them for admins. Any camera can be looked up by name. */
    private static List<CameraSpec> visibleTo(CommandSourceStack src) {
        CameraStore store = DailyCams.get().cameras();
        if (isAdmin(src)) return List.copyOf(store.all());
        ServerPlayer p = src.getPlayer();
        return p == null ? List.of() : store.ownedBy(p.getUUID());
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestManageable(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        CommandSourceStack src = ctx.getSource();
        return SharedSuggestionProvider.suggest(DailyCams.get().cameras().all().stream()
                .filter(c -> canManage(src, c)).map(c -> c.name).toList(), builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestVisible(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(visibleTo(ctx.getSource()).stream().map(c -> c.name).toList(), builder);
    }

    /** Webhook URLs contain a secret token; never echo it in full. */
    private static String redact(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash >= url.length() - 5) return url;
        return url.substring(0, slash + 1) + url.substring(slash + 1, slash + 5) + "...";
    }
}

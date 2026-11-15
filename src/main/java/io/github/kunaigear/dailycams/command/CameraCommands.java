package io.github.kunaigear.dailycams.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.camera.CameraSpec;
import io.github.kunaigear.dailycams.camera.CameraStore;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import io.github.kunaigear.dailycams.render.RenderQueue;
import io.github.kunaigear.dailycams.webhook.WebhookSender;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Every player owns one personal camera named after them. Higher permission tiers may add named cameras.
 *
 * <pre>
 * /mc_setcamera                               your personal camera, at your eyes
 * /mc_setcamera &lt;name&gt; [&lt;x y z&gt; &lt;yaw pitch&gt;]   a named camera (or your personal one, by your name)
 * /mc_camera list | status
 * /mc_camera info|test|remove [name]          name defaults to your personal camera
 * /mc_camera fov &lt;30-110|default&gt; [name]
 * /mc_camera webhook &lt;name&gt; &lt;url|default&gt;     admin
 * /mc_camera runall | reload                  admin
 * </pre>
 */
public final class CameraCommands {
    /** Personal camera, listing, info. Everyone. */
    private static final Identifier PERM_USE = perm("command.setcamera");
    /** Preview renders of your own cameras. Everyone. */
    private static final Identifier PERM_TEST = perm("command.test");
    /** Up to {@code namedCameraLimit} extra named cameras. */
    private static final Identifier PERM_NAMED = perm("camera.named");
    /** Any number of named cameras. */
    private static final Identifier PERM_UNLIMITED = perm("camera.unlimited");
    /** Manage everyone's cameras, webhooks, runall, reload. */
    private static final Identifier PERM_ADMIN = perm("admin");

    private static final PermissionLevel LEVEL_NAMED = PermissionLevel.GAMEMASTERS;  // op level 2
    private static final PermissionLevel LEVEL_ADMIN = PermissionLevel.ADMINS;       // op level 3
    private static final PermissionLevel LEVEL_UNLIMITED = PermissionLevel.OWNERS;   // op level 4

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE HH:mm z", Locale.ENGLISH);
    private static final Map<UUID, Instant> lastTest = new HashMap<>();

    private CameraCommands() {}

    private static Identifier perm(String path) {
        return Identifier.fromNamespaceAndPath(DailyCams.MOD_ID, path);
    }

    // ================================================================== registration

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mc_setcamera")
                .requires(PermissionPredicates.<CommandSourceStack>require(PERM_USE, PermissionLevel.ALL))
                .executes(ctx -> setFromView(ctx, null))
                .then(nameArg(CameraCommands::suggestSettable)
                        .executes(ctx -> setFromView(ctx, name(ctx)))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("rotation", RotationArgument.rotation())
                                        .executes(CameraCommands::setExplicit)))));

        dispatcher.register(Commands.literal("mc_camera")
                .requires(PermissionPredicates.<CommandSourceStack>require(PERM_USE, PermissionLevel.ALL))
                .executes(CameraCommands::help)
                .then(Commands.literal("help").executes(CameraCommands::help))
                .then(Commands.literal("list").executes(CameraCommands::list))
                .then(Commands.literal("status").executes(CameraCommands::status))
                .then(optionalName(Commands.literal("info"), CameraCommands::suggestAll, CameraCommands::info, false))
                .then(optionalName(Commands.literal("remove"), CameraCommands::suggestManageable, CameraCommands::remove, true))
                .then(optionalName(Commands.literal("test")
                                .requires(PermissionPredicates.<CommandSourceStack>require(PERM_TEST, PermissionLevel.ALL)),
                        CameraCommands::suggestManageable, CameraCommands::test, true))
                .then(Commands.literal("fov")
                        .then(optionalName(Commands.literal("default"), CameraCommands::suggestManageable,
                                (ctx, cam) -> setFov(ctx, cam, null), true))
                        .then(optionalName(Commands.argument("fov", IntegerArgumentType.integer(30, 110)),
                                CameraCommands::suggestManageable,
                                (ctx, cam) -> setFov(ctx, cam, IntegerArgumentType.getInteger(ctx, "fov")), true)))
                .then(Commands.literal("webhook")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_ADMIN, LEVEL_ADMIN))
                        .then(nameArg(CameraCommands::suggestAll)
                                .then(Commands.literal("default").executes(ctx -> setWebhook(ctx, null)))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> setWebhook(ctx, StringArgumentType.getString(ctx, "url"))))))
                .then(Commands.literal("runall")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_ADMIN, LEVEL_ADMIN))
                        .executes(CameraCommands::runAll))
                .then(Commands.literal("reload")
                        .requires(PermissionPredicates.<CommandSourceStack>require(PERM_ADMIN, LEVEL_ADMIN))
                        .executes(CameraCommands::reload)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> nameArg(SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("name", StringArgumentType.word()).suggests(suggestions);
    }

    /** {@code node} runs on your personal camera, {@code node <name>} on the named one. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T optionalName(
            T node, SuggestionProvider<CommandSourceStack> suggestions,
            BiFunction<CommandContext<CommandSourceStack>, CameraSpec, Integer> action, boolean mustManage) {
        return node
                .executes(ctx -> {
                    CameraSpec cam = ownPersonal(ctx.getSource());
                    return cam == null ? 0 : action.apply(ctx, cam);
                })
                .then(nameArg(suggestions).executes(ctx -> {
                    CameraSpec cam = find(ctx.getSource(), name(ctx), mustManage);
                    return cam == null ? 0 : action.apply(ctx, cam);
                }));
    }

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name");
    }

    // ================================================================== set

    private static int setFromView(CommandContext<CommandSourceStack> ctx, @Nullable String name) {
        CommandSourceStack src = ctx.getSource();
        Entity entity = src.getEntity();
        if (entity == null) {
            src.sendFailure(Component.literal("From the console, give a name, position and rotation: /mc_setcamera <name> <x y z> <yaw pitch>"));
            return 0;
        }
        return set(src, name, entity.getEyePosition(), entity.getYRot(), entity.getXRot());
    }

    private static int setExplicit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        Vec2 rot = RotationArgument.getRotation(ctx, "rotation").getRotation(src); // x = pitch, y = yaw
        return set(src, name(ctx), pos, rot.y, rot.x);
    }

    private static int set(CommandSourceStack src, @Nullable String name, Vec3 pos, float yaw, float pitch) {
        CameraStore store = DailyCams.get().cameras();
        ServerPlayer player = src.getPlayer();

        // Your own name (or no name at all) means your personal camera.
        if (player != null && (name == null || name.equalsIgnoreCase(playerName(player)))) {
            return setPersonal(src, player, pos, yaw, pitch);
        }
        if (name == null) {
            src.sendFailure(Component.literal("Give the camera a name."));
            return 0;
        }

        CameraSpec existing = store.get(name);
        if (existing != null) {
            if (!canManage(src, existing)) {
                src.sendFailure(Component.literal("Camera '" + existing.name + "' belongs to " + existing.ownerName + "."));
                return 0;
            }
            place(existing, src, pos, yaw, pitch);
            store.put(existing);
            confirm(src, existing, false);
            return 1;
        }

        // A new named camera.
        int limit = namedLimit(src);
        if (player != null && limit == 0) {
            src.sendFailure(Component.literal("You can have one camera, and it's named after you. "
                    + "Run /mc_setcamera without a name to place or move it."));
            return 0;
        }
        if (!CameraStore.VALID_NAME.matcher(name).matches()) {
            src.sendFailure(Component.literal("Camera names may only use letters, digits, _ and - (up to 32 characters)."));
            return 0;
        }
        if (isPlayerName(src.getServer(), name)) {
            src.sendFailure(Component.literal("'" + name + "' is a player's name and is reserved for their personal camera. Pick another name."));
            return 0;
        }
        if (player != null) {
            long used = store.namedCount(player.getUUID());
            if (used >= limit) {
                src.sendFailure(Component.literal("You already have " + used + " of " + limit
                        + " named cameras. Remove one with /mc_camera remove <name> first."));
                return 0;
            }
        }

        CameraSpec cam = new CameraSpec();
        cam.name = name;
        cam.personal = false;
        cam.owner = player != null ? player.getUUID() : null;
        cam.ownerName = player != null ? playerName(player) : "Server";
        cam.createdAt = System.currentTimeMillis();
        place(cam, src, pos, yaw, pitch);
        store.put(cam);
        confirm(src, cam, true);
        return 1;
    }

    private static int setPersonal(CommandSourceStack src, ServerPlayer player, Vec3 pos, float yaw, float pitch) {
        CameraStore store = DailyCams.get().cameras();
        String name = playerName(player);
        CameraSpec cam = store.personalOf(player.getUUID());
        CameraSpec clash = store.get(name);
        if (clash != null && clash != cam) {
            src.sendFailure(Component.literal("Another camera already uses your name ('" + clash.name + "' by " + clash.ownerName
                    + "). Ask an admin to remove it."));
            return 0;
        }

        boolean created = cam == null;
        if (created) {
            cam = new CameraSpec();
            cam.name = name;
            cam.personal = true;
            cam.owner = player.getUUID();
            cam.createdAt = System.currentTimeMillis();
        }
        cam.ownerName = name;
        place(cam, src, pos, yaw, pitch);
        if (!cam.name.equals(name)) {
            store.rename(cam, name); // the player changed their username
        } else {
            store.put(cam);
        }
        confirm(src, cam, created);
        return 1;
    }

    private static void place(CameraSpec cam, CommandSourceStack src, Vec3 pos, float yaw, float pitch) {
        cam.dimension = src.getLevel().dimension().identifier().toString();
        cam.x = pos.x;
        cam.y = pos.y;
        cam.z = pos.z;
        cam.yaw = Mth.wrapDegrees(yaw);
        cam.pitch = Mth.clamp(pitch, -90f, 90f);
    }

    private static void confirm(CommandSourceStack src, CameraSpec cam, boolean created) {
        DailyCams mod = DailyCams.get();
        var next = mod.scheduler().nextSlot(mod.config());
        MutableComponent msg = Component.literal((cam.personal ? "Your camera" : "Camera '" + cam.name + "'")
                        + (created ? " is set up" : " was moved") + ".").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" " + coords(cam)).withStyle(ChatFormatting.GRAY));
        if (next != null) {
            msg.append(Component.literal("\nNext picture: " + WHEN.format(next) + ". ").withStyle(ChatFormatting.GRAY));
        } else {
            msg.append(Component.literal("\n"));
        }
        msg.append(button("Preview now", "/mc_camera test " + cam.name, "Render it and send it to the webhook now", true));
        ServerPlayer player = src.getPlayer();
        if (!cam.personal && player != null && Objects.equals(cam.owner, player.getUUID())) {
            msg.append(Component.literal("  " + namedUsage(src, player)).withStyle(ChatFormatting.DARK_GRAY));
        }
        src.sendSuccess(() -> msg, false);
    }

    // ================================================================== manage

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        CameraStore store = DailyCams.get().cameras();
        ServerPlayer player = src.getPlayer();
        boolean admin = isAdmin(src);

        List<CameraSpec> cams = new ArrayList<>(admin ? store.all() : player == null ? List.of() : store.ownedBy(player.getUUID()));
        cams.sort(Comparator.comparing((CameraSpec c) -> !c.personal).thenComparing(c -> c.name.toLowerCase(Locale.ROOT)));

        MutableComponent out = Component.literal(admin ? "All cameras (" + cams.size() + ")" : "Your cameras").withStyle(ChatFormatting.GOLD);
        if (player != null && namedLimit(src) > 0) {
            out.append(Component.literal("  " + namedUsage(src, player)).withStyle(ChatFormatting.GRAY));
        }
        if (cams.isEmpty()) {
            out.append(Component.literal("\n  None yet. Look at something nice and run ").withStyle(ChatFormatting.GRAY))
               .append(button("/mc_setcamera", "/mc_setcamera", "Place your camera here", false));
        }
        for (CameraSpec c : cams) {
            out.append(Component.literal("\n  " + c.name).withStyle(c.personal ? ChatFormatting.YELLOW : ChatFormatting.AQUA)
                            .withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                    (c.personal ? "Personal camera of " : "Named camera by ") + c.ownerName + "\n" + c.describePosition())))))
               .append(Component.literal("  " + coords(c) + (admin && !c.personal ? "  by " + c.ownerName : "") + "  ").withStyle(ChatFormatting.GRAY));
            if (canManage(src, c)) {
                out.append(button("test", "/mc_camera test " + c.name, "Render and send now", true))
                   .append(Component.literal(" "))
                   .append(button("remove", "/mc_camera remove " + c.name, "Puts the command in your chat box", false));
            }
        }
        src.sendSuccess(() -> out, false);
        return cams.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx, CameraSpec cam) {
        CommandSourceStack src = ctx.getSource();
        DailyCamsConfig cfg = DailyCams.get().config();
        MutableComponent out = Component.literal("Camera " + cam.name).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(cam.personal ? "  (personal)" : "  (named)").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n  Owner: " + cam.ownerName))
                .append(Component.literal("\n  Position: " + cam.describePosition()))
                .append(Component.literal("\n  FOV: " + (cam.fov != null ? cam.fov : cfg.fov + " (default)")));
        if (isAdmin(src)) {
            out.append(Component.literal("\n  Webhook: " + (cam.webhookUrl != null ? redact(cam.webhookUrl) : "default")));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, CameraSpec cam) {
        DailyCams.get().cameras().remove(cam.name);
        ctx.getSource().sendSuccess(() -> Component.literal("Removed camera '" + cam.name + "'.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setFov(CommandContext<CommandSourceStack> ctx, CameraSpec cam, @Nullable Integer fov) {
        cam.fov = fov;
        DailyCams.get().cameras().put(cam);
        ctx.getSource().sendSuccess(() -> Component.literal("Field of view of '" + cam.name + "' is now "
                + (fov == null ? "the default (" + DailyCams.get().config().fov + ")" : fov) + "."), false);
        return 1;
    }

    private static int setWebhook(CommandContext<CommandSourceStack> ctx, @Nullable String url) {
        CommandSourceStack src = ctx.getSource();
        CameraSpec cam = find(src, name(ctx), true);
        if (cam == null) return 0;
        if (url != null && !WebhookSender.isValidUrl(url.trim())) {
            src.sendFailure(Component.literal("That doesn't look like an http(s) URL."));
            return 0;
        }
        cam.webhookUrl = url == null ? null : url.trim();
        DailyCams.get().cameras().put(cam);
        src.sendSuccess(() -> Component.literal("Webhook of '" + cam.name + "' is now "
                + (url == null ? "the default." : redact(cam.webhookUrl) + ".")), false);
        return 1;
    }

    // ================================================================== render

    private static int test(CommandContext<CommandSourceStack> ctx, CameraSpec cam) {
        CommandSourceStack src = ctx.getSource();
        RenderQueue queue = DailyCams.get().queue();
        if (queue == null) return 0;
        ServerPlayer player = src.getPlayer();
        DailyCamsConfig cfg = DailyCams.get().config();

        if (player != null && !isAdmin(src) && cfg.testCooldownSeconds > 0) {
            Instant last = lastTest.get(player.getUUID());
            if (last != null) {
                long wait = cfg.testCooldownSeconds - Duration.between(last, Instant.now()).toSeconds();
                if (wait > 0) {
                    src.sendFailure(Component.literal("Please wait " + formatDuration(wait) + " before the next preview."));
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
        src.sendSuccess(() -> Component.literal("Taking a picture with '" + cam.name + "'"
                + (ahead > 0 ? " (" + ahead + " ahead in the queue)" : "")
                + ". You'll get a message when it's sent.").withStyle(ChatFormatting.GRAY), false);
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
        CommandSourceStack src = ctx.getSource();
        DailyCams mod = DailyCams.get();
        RenderQueue queue = mod.queue();
        var next = mod.scheduler().nextSlot(mod.config());
        String active = queue == null ? null : queue.activeCamera();
        MutableComponent out = Component.literal("Daily Cams").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n  Next pictures: " + (next == null ? "not scheduled" : WHEN.format(next))))
                .append(Component.literal("\n  Cameras: " + mod.cameras().all().size()))
                .append(Component.literal("\n  Queue: " + (queue == null ? 0 : queue.size())
                        + (active != null ? " (now: '" + active + "')" : "")));
        ServerPlayer player = src.getPlayer();
        if (player != null) {
            out.append(Component.literal("\n  You: " + (mod.cameras().personalOf(player.getUUID()) != null ? "personal camera set" : "no camera yet")
                    + (namedLimit(src) > 0 ? ", " + namedUsage(src, player) : "")));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        DailyCams.get().reload();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded Daily Cams config and cameras."), true);
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        MutableComponent out = Component.literal("Daily Cams").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" - a picture from your camera is posted every day.").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n  /mc_setcamera").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" - put your camera at your eyes, looking where you look"));
        if (namedLimit(src) > 0) {
            out.append(Component.literal("\n  /mc_setcamera <name> [<x y z> <yaw pitch>]").withStyle(ChatFormatting.YELLOW))
               .append(Component.literal(" - an extra named camera"));
        }
        out.append(Component.literal("\n  /mc_camera list | status").withStyle(ChatFormatting.YELLOW))
           .append(Component.literal("\n  /mc_camera test | info | remove [name]").withStyle(ChatFormatting.YELLOW))
           .append(Component.literal("\n  /mc_camera fov <30-110|default> [name]").withStyle(ChatFormatting.YELLOW));
        if (isAdmin(src)) {
            out.append(Component.literal("\n  /mc_camera webhook <name> <url|default> | runall | reload").withStyle(ChatFormatting.RED));
        }
        src.sendSuccess(() -> out, false);
        return 1;
    }

    private static void reply(CommandSourceStack src, @Nullable UUID playerId, RenderQueue.Result result) {
        DailyCams.logResult(result);
        Component msg = Component.literal("Camera '" + result.camera().name + "': " + result.message())
                .withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
        if (playerId != null) {
            ServerPlayer p = src.getServer().getPlayerList().getPlayer(playerId);
            if (p != null) p.sendSystemMessage(msg);
        } else if (src.getEntity() == null) {
            src.sendSystemMessage(msg);
        }
    }

    // ================================================================== permissions

    private static boolean isAdmin(CommandSourceStack src) {
        return src.checkPermission(PERM_ADMIN, LEVEL_ADMIN);
    }

    /** How many named cameras (besides the personal one) this source may own. */
    private static int namedLimit(CommandSourceStack src) {
        if (src.getPlayer() == null || src.checkPermission(PERM_UNLIMITED, LEVEL_UNLIMITED)) return Integer.MAX_VALUE;
        if (src.checkPermission(PERM_NAMED, LEVEL_NAMED)) return DailyCams.get().config().namedCameraLimit;
        return 0;
    }

    private static String namedUsage(CommandSourceStack src, ServerPlayer player) {
        int limit = namedLimit(src);
        long used = DailyCams.get().cameras().namedCount(player.getUUID());
        return "Named cameras: " + used + (limit == Integer.MAX_VALUE ? " (no limit)" : " of " + limit);
    }

    private static boolean canManage(CommandSourceStack src, CameraSpec cam) {
        if (isAdmin(src)) return true;
        ServerPlayer p = src.getPlayer();
        return p != null && Objects.equals(p.getUUID(), cam.owner);
    }

    // ================================================================== lookup

    private static @Nullable CameraSpec ownPersonal(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Give a camera name."));
            return null;
        }
        CameraSpec cam = DailyCams.get().cameras().personalOf(player.getUUID());
        if (cam == null) {
            src.sendFailure(Component.literal("You don't have a camera yet. Look at something nice and run /mc_setcamera."));
        }
        return cam;
    }

    private static @Nullable CameraSpec find(CommandSourceStack src, String name, boolean mustManage) {
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

    /**
     * Named cameras may not take a player's name, so that player can still get their personal camera.
     * Checks online players, existing personal cameras and the server's local user cache (no network lookups).
     */
    private static boolean isPlayerName(MinecraftServer server, String name) {
        if (server.getPlayerList().getPlayerByName(name) != null) return true;
        if (DailyCams.get().cameras().isPersonalName(name)) return true;
        try {
            Path cache = server.getServerDirectory().resolve("usercache.json");
            if (Files.exists(cache)) {
                JsonArray entries = JsonParser.parseString(Files.readString(cache)).getAsJsonArray();
                for (JsonElement e : entries) {
                    JsonElement n = e.getAsJsonObject().get("name");
                    if (n != null && n.getAsString().equalsIgnoreCase(name)) return true;
                }
            }
        } catch (Exception e) {
            DailyCams.LOGGER.debug("Could not read usercache.json", e);
        }
        return false;
    }

    private static String playerName(ServerPlayer player) {
        return player.getName().getString();
    }

    // ================================================================== suggestions

    private static CompletableFuture<Suggestions> suggestSettable(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSourceStack src = ctx.getSource();
        Set<String> names = new LinkedHashSet<>();
        ServerPlayer player = src.getPlayer();
        if (player != null) names.add(playerName(player));
        DailyCams.get().cameras().all().stream().filter(c -> canManage(src, c)).forEach(c -> names.add(c.name));
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestManageable(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CommandSourceStack src = ctx.getSource();
        return SharedSuggestionProvider.suggest(DailyCams.get().cameras().all().stream()
                .filter(c -> canManage(src, c)).map(c -> c.name).toList(), builder);
    }

    private static CompletableFuture<Suggestions> suggestAll(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(DailyCams.get().cameras().all().stream().map(c -> c.name).toList(), builder);
    }

    // ================================================================== formatting

    /** A clickable [label]. {@code run} = run immediately, otherwise put the command in the chat box. */
    private static MutableComponent button(String label, String command, String hover, boolean run) {
        ClickEvent click = run ? new ClickEvent.RunCommand(command) : new ClickEvent.SuggestCommand(command);
        return Component.literal("[" + label + "]").withStyle(ChatFormatting.AQUA)
                .withStyle(s -> s.withClickEvent(click).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static String coords(CameraSpec c) {
        return String.format(Locale.ROOT, "%d %d %d%s", Math.round(c.x), Math.round(c.y), Math.round(c.z),
                c.dimension.equals("minecraft:overworld") ? "" : " (" + c.dimension.replace("minecraft:", "") + ")");
    }

    private static String formatDuration(long seconds) {
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    /** Webhook URLs contain a secret token; never echo it in full. */
    private static String redact(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash >= url.length() - 5) return url;
        return url.substring(0, slash + 1) + url.substring(slash + 1, slash + 5) + "...";
    }
}

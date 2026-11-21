package io.github.kunaigear.postcards.devtest;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** `pctest` console command: runs Postcards commands as fake players at different op levels and logs the replies. */
public final class DevTest implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PCTEST");

    @Override
    public void onInitialize() {
        TickProfiler.register();
        ChunkGenerator.register();
        Benchmark.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) ->
                dispatcher.register(Commands.literal("pctest").executes(ctx -> {
                    run(ctx.getSource().getServer());
                    return 1;
                })));
    }

    private record Actor(String name, UUID id, PermissionLevel level) {}

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Actor alice = new Actor("Alice", uuid("Alice"), PermissionLevel.ALL);
        Actor bob = new Actor("Bob", uuid("Bob"), PermissionLevel.GAMEMASTERS);
        Actor carol = new Actor("Carol", uuid("Carol"), PermissionLevel.OWNERS);
        Actor alicia = new Actor("Alicia", alice.id, PermissionLevel.ALL); // Alice after a name change

        LOG.info("===== tier 0: Alice =====");
        as(server, level, alice, "postcard set");
        as(server, level, alice, "postcard set");
        as(server, level, alice, "postcard set tower");
        as(server, level, alice, "postcard set Alice 10 100 10 90 0");
        as(server, level, alice, "postcard fov 90");
        as(server, level, alice, "postcard info");
        as(server, level, alice, "postcard list");
        as(server, level, alice, "dcam list");
        as(server, level, alice, "dcam set Alice 10 100 10 90 0");
        as(server, level, alice, "dcam");
        as(server, level, alice, "dailycam list");
        as(server, level, alice, "postcard set Alice 10 100 10 90 0 85");
        as(server, level, alice, "postcard set Alice 10 100 10 90 0 200");
        as(server, level, alice, "postcard info");
        as(server, level, alice, "postcard webhook Alice http://example.com/x");

        LOG.info("===== tier 4: Carol =====");
        as(server, level, carol, "postcard set");
        for (int i = 1; i <= 5; i++) as(server, level, carol, "postcard set c" + i);

        LOG.info("===== tier 2: Bob =====");
        as(server, level, bob, "postcard set");
        as(server, level, bob, "postcard set b1");
        as(server, level, bob, "postcard set b2");
        as(server, level, bob, "postcard set b3");
        as(server, level, bob, "postcard set b4");
        as(server, level, bob, "postcard set Alice");
        as(server, level, bob, "postcard set Carol");
        as(server, level, bob, "postcard set bad.name");
        as(server, level, bob, "postcard remove c1");
        as(server, level, bob, "postcard remove b3");
        as(server, level, bob, "postcard set b4");
        as(server, level, bob, "postcard list");
        as(server, level, bob, "postcard status");

        LOG.info("===== tier 0 again =====");
        as(server, level, alice, "postcard remove b1");
        as(server, level, alice, "postcard test");
        as(server, level, alice, "postcard test");
        as(server, level, alicia, "postcard set");
        as(server, level, alicia, "postcard info");

        LOG.info("===== tier 4 admin =====");
        as(server, level, carol, "postcard remove b1");
        as(server, level, carol, "postcard list");

        LOG.info("===== console =====");
        exec(server, server.createCommandSourceStack().withSource(capture("console")), "console", "postcard set");
        exec(server, server.createCommandSourceStack().withSource(capture("console")), "console", "postcard set srvcam 0 100 0 0 0");
        exec(server, server.createCommandSourceStack().withSource(capture("console")), "console", "postcard info");
    }

    private static void as(MinecraftServer server, ServerLevel level, Actor actor, String command) {
        FakePlayer player = FakePlayer.get(level, new GameProfile(actor.id, actor.name));
        player.snapTo(5, 90, 5, 45, 10);
        CommandSourceStack src = player.createCommandSourceStack()
                .withSource(capture(actor.name))
                .withPermission(LevelBasedPermissionSet.forLevel(actor.level));
        exec(server, src, actor.name, command);
    }

    private static void exec(MinecraftServer server, CommandSourceStack src, String who, String command) {
        LOG.info("{} > /{}", who, command);
        server.getCommands().performPrefixedCommand(src, command);
    }

    private static CommandSource capture(String who) {
        return new CommandSource() {
            @Override public void sendSystemMessage(Component message) {
                for (String line : message.getString().split("\n")) LOG.info("    {} | {}", who, line);
            }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}

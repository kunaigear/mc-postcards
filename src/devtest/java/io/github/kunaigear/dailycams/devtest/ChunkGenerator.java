package io.github.kunaigear.dailycams.devtest;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * `dcgen <x> <z> <yaw> <radius>` (console): generates the chunks in a 140° wedge in front of a point, out to
 * radius chunks, so distant-terrain renders have something to show. Test servers only.
 */
final class ChunkGenerator {
    private static final Logger LOG = LoggerFactory.getLogger("DCGEN");
    private static final Deque<ChunkPos> queue = new ArrayDeque<>();
    private static final java.util.List<ChunkPos> inFlight = new java.util.ArrayList<>();
    private static net.minecraft.server.level.TicketType ticket;
    private static int done, total;
    private static long lastLog;

    private ChunkGenerator() {}

    static void register() {
        ticket = net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.TICKET_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("dailycams-devtest", "generate"),
                new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT, net.minecraft.server.level.TicketType.FLAG_LOADING));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) ->
                dispatcher.register(Commands.literal("dcgen")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                        .executes(ctx -> {
                                                            start(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"),
                                                                    FloatArgumentType.getFloat(ctx, "yaw"), IntegerArgumentType.getInteger(ctx, "radius"));
                                                            return 1;
                                                        })))))));
        ServerTickEvents.END_SERVER_TICK.register(server -> tick(server.overworld()));
    }

    private static void start(int x, int z, float yaw, int radius) {
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        int cx = x >> 4, cz = z >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) continue;
                double angle = d < 1 ? 0 : Math.acos(Math.clamp((dx * fx + dz * fz) / d, -1, 1));
                if (angle <= Math.toRadians(70)) queue.add(new ChunkPos(cx + dx, cz + dz));
            }
        }
        total += queue.size();
        LOG.info("Generating {} chunks", queue.size());
    }

    private static void tick(ServerLevel level) {
        var source = level.getChunkSource();
        inFlight.removeIf(p -> {
            if (source.getChunkNow(p.x(), p.z()) == null) return false;
            source.removeTicketWithRadius(ticket, p, 0); // generated; it unloads and is saved later
            return true;
        });
        while (!queue.isEmpty() && inFlight.size() < 96) {
            ChunkPos p = queue.poll();
            source.addTicketWithRadius(ticket, p, 0);
            inFlight.add(p);
            done++;
        }
        if (total > 0 && System.currentTimeMillis() - lastLog > 5000) {
            lastLog = System.currentTimeMillis();
            LOG.info("Generated {}/{}", done - inFlight.size(), total);
        }
        if (total > 0 && queue.isEmpty() && inFlight.isEmpty()) {
            LOG.info("dcgen done: {} chunks", total);
            total = 0;
            done = 0;
        }
    }
}

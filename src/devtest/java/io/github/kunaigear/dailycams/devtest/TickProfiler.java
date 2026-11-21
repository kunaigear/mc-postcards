package io.github.kunaigear.dailycams.devtest;

import io.github.kunaigear.dailycams.DailyCams;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Writes one CSV line per server tick to run/dctest-ticks.csv:
 * wall-clock ms, tick duration ms, ms since the previous tick started, and what Daily Cams is doing.
 * A tick that takes longer than 50 ms (or starts more than 50 ms after the last one) costs TPS.
 */
final class TickProfiler {
    record Tick(long startNanos, double durationMs, double intervalMs, String state) {}

    /** Gets every tick, on the server thread. */
    static volatile Consumer<Tick> listener = tick -> {};

    private static BufferedWriter out;
    private static long tickStart;
    private static long lastStart;

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                out = Files.newBufferedWriter(Path.of("dctest-ticks.csv"));
                out.write("wall_ms,duration_ms,interval_ms,state\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
            out = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long end = System.nanoTime();
            double duration = (end - tickStart) / 1e6;
            double interval = lastStart == 0 ? 50 : (tickStart - lastStart) / 1e6;
            lastStart = tickStart;
            var queue = DailyCams.get().queue();
            String state = queue == null || queue.activeStage() == null ? "idle" : queue.activeStage();
            listener.accept(new Tick(tickStart, duration, interval, state));
            if (out == null) return;
            try {
                out.write(System.currentTimeMillis() + "," + String.format(java.util.Locale.ROOT, "%.3f,%.3f", duration, interval) + "," + state + "\n");
                if (server.getTickCount() % 100 == 0) out.flush();
            } catch (IOException ignored) {
            }
        });
    }
}

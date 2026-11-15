package io.github.kunaigear.dailycams;

import io.github.kunaigear.dailycams.camera.CameraSpec;
import io.github.kunaigear.dailycams.camera.CameraStore;
import io.github.kunaigear.dailycams.command.CameraCommands;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import io.github.kunaigear.dailycams.render.RenderQueue;
import io.github.kunaigear.dailycams.schedule.DailyScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DailyCams implements ModInitializer {
    public static final String MOD_ID = "dailycams";
    public static final Logger LOGGER = LoggerFactory.getLogger("DailyCams");

    private static DailyCams instance;
    private static volatile long lastTickNanos;
    private static volatile long busyUntilNanos;
    private static long tickStartNanos;
    private static long previousTickStartNanos;

    /** A tick slower than this, or a gap between tick starts longer than this, counts as the server struggling. */
    private static final long SLOW_TICK_NANOS = 25_000_000;
    /**
     * 30 ms later than the normal 50 ms. Windows sleeps in steps of 15.6 ms by default, so on a Windows host a
     * perfectly healthy server often starts ticks after 62.5 ms. That must not count as struggling.
     */
    private static final long TICK_GAP_NANOS = 80_000_000;
    /** After the server struggled, background work stays paused this long. */
    private static final long BUSY_COOLDOWN_NANOS = 1_000_000_000;

    private Path dir;
    private TicketType ticketType;
    private DailyCamsConfig config;
    private CameraStore cameras;
    private DailyScheduler scheduler;
    private @Nullable RenderQueue queue;
    private @Nullable ScheduledExecutorService clock;

    public static DailyCams get() {
        return instance;
    }

    /**
     * True while the server is struggling or recently was: its last tick was slow, or a tick started late.
     * Background work (chunk requests, the render thread) waits while this is true.
     */
    public static boolean serverBusy() {
        return System.nanoTime() < busyUntilNanos;
    }

    @Override
    public void onInitialize() {
        instance = this;
        dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        // Loads chunks around a camera while it renders. Not saved with the world.
        ticketType = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "render"),
                new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

        config = DailyCamsConfig.load(dir.resolve("config.json"), null, new ArrayList<>());
        cameras = new CameraStore(dir.resolve("cameras.json"));
        cameras.load(new ArrayList<>());
        scheduler = new DailyScheduler(dir.resolve("state.json"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> CameraCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            queue = new RenderQueue(ticketType, dir.resolve("renders"));
            scheduler.load(config);
            if (config.webhookUrl.isBlank()) {
                LOGGER.warn("No webhookUrl set in {}; renders will only be saved locally", dir.resolve("config.json"));
            }
            LOGGER.info("{} camera(s) loaded, next daily run: {}", cameras.all().size(), scheduler.nextSlot(config));
            resumeUnfinishedRun(server);

            // The schedule is checked from a wall-clock timer instead of the tick loop, because an empty
            // dedicated server pauses ticking. Tasks handed to server.execute() still run while paused.
            clock = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DailyCams-Clock");
                t.setDaemon(true);
                return t;
            });
            clock.scheduleAtFixedRate(() -> server.execute(() -> checkSchedule(server)), 5, 10, TimeUnit.SECONDS);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (clock != null) clock.shutdownNow();
            clock = null;
            if (queue != null) queue.shutdown();
            queue = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            previousTickStartNanos = tickStartNanos;
            tickStartNanos = System.nanoTime();
            // A late tick means something (GC, world generation, our render) starved the server thread.
            if (previousTickStartNanos != 0 && tickStartNanos - previousTickStartNanos > TICK_GAP_NANOS) {
                busyUntilNanos = tickStartNanos + BUSY_COOLDOWN_NANOS;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (queue != null) queue.tick(server);
            long end = System.nanoTime();
            lastTickNanos = end - tickStartNanos;
            if (lastTickNanos > SLOW_TICK_NANOS) busyUntilNanos = end + BUSY_COOLDOWN_NANOS;
        });
    }

    private void checkSchedule(MinecraftServer server) {
        if (queue == null || !scheduler.poll(config)) return;
        List<String> started = runCameras(cameras.all(), dailyCallback(server));
        scheduler.startRun(started);
        LOGGER.info("Daily run started for {} camera(s)", started.size());
    }

    /** A daily run that a stop or crash interrupted is finished after the restart (if missed runs are caught up). */
    private void resumeUnfinishedRun(MinecraftServer server) {
        List<String> unfinished = scheduler.unfinished();
        if (unfinished.isEmpty()) return;
        if (!config.catchUpMissedRuns) {
            LOGGER.info("Not finishing the interrupted daily run ({} camera(s) left): catchUpMissedRuns is off", unfinished.size());
            scheduler.clearUnfinished();
            return;
        }
        List<CameraSpec> left = new ArrayList<>();
        for (String name : unfinished) {
            CameraSpec cam = cameras.get(name);
            if (cam != null) left.add(cam);
        }
        List<String> started = runCameras(left, dailyCallback(server));
        scheduler.startRun(started);
        LOGGER.info("Finishing the interrupted daily run: {} camera(s) left", started.size());
    }

    private Consumer<RenderQueue.Result> dailyCallback(MinecraftServer server) {
        return result -> {
            logResult(result);
            notifyOwner(server, result);
            scheduler.finished(result.camera().name);
        };
    }

    private void notifyOwner(MinecraftServer server, RenderQueue.Result result) {
        CameraSpec cam = result.camera();
        if (!config.notifyOwners || !result.success() || cam.owner == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(cam.owner);
        if (owner == null) return;
        owner.sendSystemMessage(Component.literal(cam.personal
                ? "Today's picture from your camera was posted."
                : "Today's picture from your camera '" + cam.name + "' was posted.").withStyle(ChatFormatting.GREEN));
    }

    /** True while renders are queued; keeps an empty server from pausing. */
    public boolean isBusy() {
        return queue != null && queue.size() > 0;
    }

    /** Queues every camera. Returns how many were queued. */
    public int runAll(MinecraftServer server, Consumer<RenderQueue.Result> callback) {
        return runCameras(cameras.all(), callback).size();
    }

    /** Queues the given cameras (skipping ones already queued) and returns the names of those queued. */
    private List<String> runCameras(Collection<CameraSpec> cams, Consumer<RenderQueue.Result> callback) {
        List<String> queued = new ArrayList<>();
        if (queue == null) return queued;
        for (CameraSpec cam : List.copyOf(cams)) {
            if (queue.submit(cam, config, callback)) queued.add(cam.name);
        }
        return queued;
    }

    public static void logResult(RenderQueue.Result result) {
        if (result.success()) {
            LOGGER.info("Camera '{}': {}", result.camera().name, result.message());
        } else {
            LOGGER.warn("Camera '{}': {}", result.camera().name, result.message());
        }
    }

    /**
     * Reloads the config and cameras. A file that can't be read keeps the settings that were loaded before.
     * Returns the problems, for the command to show.
     */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        config = DailyCamsConfig.load(dir.resolve("config.json"), config, problems);
        cameras.load(problems);
        scheduler.load(config);
        return problems;
    }

    public DailyCamsConfig config() {
        return config;
    }

    public CameraStore cameras() {
        return cameras;
    }

    public DailyScheduler scheduler() {
        return scheduler;
    }

    public @Nullable RenderQueue queue() {
        return queue;
    }
}

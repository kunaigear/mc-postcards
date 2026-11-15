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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DailyCams implements ModInitializer {
    public static final String MOD_ID = "dailycams";
    public static final Logger LOGGER = LoggerFactory.getLogger("DailyCams");

    private static DailyCams instance;

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

    @Override
    public void onInitialize() {
        instance = this;
        dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        // Loads chunks around a camera while it renders. Not saved with the world.
        ticketType = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "render"),
                new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));

        config = DailyCamsConfig.load(dir.resolve("config.json"));
        cameras = new CameraStore(dir.resolve("cameras.json"));
        cameras.load();
        scheduler = new DailyScheduler(dir.resolve("state.json"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registries, env) -> CameraCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            queue = new RenderQueue(ticketType, dir.resolve("renders"));
            scheduler.load(config);
            if (config.webhookUrl.isBlank()) {
                LOGGER.warn("No webhookUrl set in {}; renders will only be saved locally", dir.resolve("config.json"));
            }
            LOGGER.info("{} camera(s) loaded, next daily run: {}", cameras.all().size(), scheduler.nextSlot(config));

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
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (queue != null) queue.tick(server);
        });
    }

    private void checkSchedule(MinecraftServer server) {
        if (queue == null || !scheduler.poll(config)) return;
        int n = runAll(server, result -> {
            logResult(result);
            notifyOwner(server, result);
        });
        LOGGER.info("Daily run started for {} camera(s)", n);
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
        if (queue == null) return 0;
        int n = 0;
        for (CameraSpec cam : List.copyOf(cameras.all())) {
            if (queue.submit(cam, config, callback)) n++;
        }
        return n;
    }

    public static void logResult(RenderQueue.Result result) {
        if (result.success()) {
            LOGGER.info("Camera '{}': {}", result.camera().name, result.message());
        } else {
            LOGGER.warn("Camera '{}': {}", result.camera().name, result.message());
        }
    }

    public void reload() {
        config = DailyCamsConfig.load(dir.resolve("config.json"));
        cameras.load();
        scheduler.load(config);
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

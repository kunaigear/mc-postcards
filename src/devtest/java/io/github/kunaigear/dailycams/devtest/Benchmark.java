package io.github.kunaigear.dailycams.devtest;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.camera.CameraSpec;
import io.github.kunaigear.dailycams.render.LodCache;
import io.github.kunaigear.dailycams.render.RenderQueue;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.ThreadMXBean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The render benchmark behind {@code ./gradlew runBenchmark} (see docs/development.md). On a server started with
 * {@code -Ddailycams.benchmark=true}, it sets up a fixed scene, renders each camera with each shader, reports how
 * well the server kept up, and stops the server. {@code benchmark-result.txt} says PASS or why it failed.
 */
final class Benchmark {
    private static final Logger LOG = LoggerFactory.getLogger("DCBENCH");

    private record Camera(String name, double x, double y, double z, float yaw, float pitch) {}

    /** From the documentation pictures, in a world with seed 12345. */
    private static final List<Camera> CAMERAS = List.of(
            new Camera("village", -169.5, 81, -859.5, -30, 25),
            new Camera("peaks", -303.5, 120, -79.5, 135, -4));
    private static final List<String> SHADERS = List.of("none", "simple", "realistic");
    /** Chunks generated in front of each camera, for distant terrain. */
    private static final int GENERATE_RADIUS = 64;
    /** A closed glass box in the sky, far from the cameras. Its chunks are force-loaded so the mobs always tick. */
    private static final int PEN_MIN = 512, PEN_MAX = 543, PEN_FLOOR = 250;
    private static final String MOB_TAG = "dcbench";
    private static final int SETTLE_SECONDS = 10, IDLE_SECONDS = 30;
    /** A tick that starts this long after the previous one (two ticks' worth) is a hitch players can notice. */
    private static final double HITCH_MS = 100;
    /** Water and clouds move with the game time, so a frozen world pins it. */
    private static final long FROZEN_GAME_TIME = 1_000_000;
    /** Present once the world has its terrain; delete the world to prepare it again. */
    private static final String PREPARED_MARKER = "world/dailycams-benchmark-prepared";

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private final MinecraftServer server;
    private int mobs, maxHitches, minTps, rounds;
    private final String label = System.getProperty("dailycams.benchmark.label", "");
    private final boolean freeze = Boolean.getBoolean("dailycams.benchmark.freeze");
    private volatile Stage current;
    private volatile long renderThreadId = -1;

    private Benchmark(MinecraftServer server) {
        this.server = server;
    }

    static void register() {
        if (!Boolean.getBoolean("dailycams.benchmark")) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Thread t = new Thread(() -> new Benchmark(server).run(), "DailyCams-Benchmark");
            t.setDaemon(true);
            t.start();
        });
    }

    private static final class Stage {
        final String name;
        final int round;
        final @Nullable Camera camera;
        final @Nullable String shader;
        final List<TickProfiler.Tick> ticks = Collections.synchronizedList(new ArrayList<>());
        final List<Long> gcPausesMs = Collections.synchronizedList(new ArrayList<>());
        /** Render thread CPU time per render stage, sampled at the end of each tick. */
        final Map<String, Long> cpuByState = Collections.synchronizedMap(new LinkedHashMap<>());
        long startNanos, nanos;
        @Nullable Path picture;
        /** Of the picture's decoded pixels, in a frozen world. */
        @Nullable String pictureHash;
        /** On the render thread. */
        long cpuNanos, allocatedBytes, lastTickCpuNanos;

        Stage(String name, int round, @Nullable Camera camera, @Nullable String shader) {
            this.name = name;
            this.round = round;
            this.camera = camera;
            this.shader = shader;
        }

        boolean render() {
            return camera != null;
        }

        int hitches() {
            synchronized (ticks) {
                return (int) ticks.stream().filter(t -> t.intervalMs() > HITCH_MS).count();
            }
        }

        /** Ticks in the slowest full second, or -1 when the stage is shorter than that. */
        int worstSecondTps() {
            synchronized (ticks) {
                if (ticks.isEmpty()) return -1;
                // Ticks are recorded when they end, so only the seconds before the last recorded tick began are complete.
                int full = (int) ((ticks.getLast().startNanos() - startNanos) / 1_000_000_000L);
                if (full < 1) return -1;
                int[] perSecond = new int[full + 1];
                for (TickProfiler.Tick t : ticks) {
                    long since = t.startNanos() - startNanos;
                    if (since >= 0) perSecond[(int) Math.min(full, since / 1_000_000_000L)]++;
                }
                int worst = Integer.MAX_VALUE;
                for (int s = 0; s < full; s++) worst = Math.min(worst, perSecond[s]);
                return worst;
            }
        }

        double longestTickMs() {
            synchronized (ticks) {
                return ticks.stream().mapToDouble(TickProfiler.Tick::durationMs).max().orElse(0);
            }
        }

        /** Wall time per render stage ("loading", "building", ...), in ms. */
        Map<String, Double> renderStages() {
            Map<String, Double> out = new LinkedHashMap<>();
            synchronized (ticks) {
                for (TickProfiler.Tick t : ticks) {
                    if (!t.state().equals("idle")) out.merge(t.state(), t.intervalMs(), Double::sum);
                }
            }
            return out;
        }
    }

    private void run() {
        String result;
        StringBuilder report = new StringBuilder();
        List<Stage> stages = new ArrayList<>();
        try {
            mobs = intProperty("mobs", 200);
            maxHitches = intProperty("maxHitches", -1);
            minTps = intProperty("minTps", -1);
            rounds = intProperty("rounds", 1);
            if (rounds < 1) throw new IllegalArgumentException("-PbenchRounds must be at least 1");
            TickProfiler.listener = tick -> {
                Stage s = current;
                if (s == null) return;
                s.ticks.add(tick);
                if (tick.state().equals("idle") || tick.state().equals("loading")) return;
                long cpu = renderThreadUsage()[0];
                s.cpuByState.merge(tick.state(), cpu - s.lastTickCpuNanos, Long::sum);
                s.lastTickCpuNanos = cpu;
            };
            listenForGc();
            setScene();
            if (!Files.exists(Path.of(PREPARED_MARKER))) prepare();
            spawnMobs();
            if (freeze) {
                command("tick freeze");
                onServer(() -> {
                    ((ServerLevelData) server.overworld().getLevelData()).setGameTime(FROZEN_GAME_TIME);
                    return null;
                });
            }
            Thread.sleep(SETTLE_SECONDS * 1000L);

            // A frozen server has nothing to compare against.
            if (!freeze) stages.add(measure("idle", 0, null, null, s -> sleep(IDLE_SECONDS)));
            // Killed cows saved by an earlier run stay in the pen, mid-death, while the world is frozen.
            int mobsAlive = onServer(() -> server.overworld().getEntities(EntityTypes.COW,
                    new AABB(PEN_MIN, PEN_FLOOR, PEN_MIN, PEN_MAX + 1, PEN_FLOOR + 5, PEN_MAX + 1), e -> e.isAlive()).size());
            for (int round = 1; round <= rounds; round++) {
                // Every round starts like the first, reading the distant terrain of the first shader from disk.
                LodCache.clear();
                for (String shader : SHADERS) {
                    for (Camera cam : CAMERAS) {
                        String name = cam.name + " " + shader + (rounds > 1 ? " #" + round : "");
                        stages.add(measure(name, round, cam, shader, s -> s.picture = render(cam, shader)));
                    }
                }
            }
            command("kill @e[tag=" + MOB_TAG + "]");
            if (freeze) {
                command("tick unfreeze");
                for (Stage s : stages) if (s.render()) s.pictureHash = pixelHash(s.picture);
            }

            result = verdict(stages);
            report.append(header(mobsAlive)).append(table(stages)).append("\n");
            if (freeze) {
                report.append("Pictures (hash of the pixels, file):\n");
                for (Stage s : stages) {
                    if (s.render()) report.append(String.format(Locale.ROOT, "%-21s %s %s%n", s.name, s.pictureHash, s.picture));
                }
                report.append("\n");
            }
            report.append(result).append("\n");
        } catch (Throwable e) {
            LOG.error("Benchmark failed", e);
            result = "FAIL: " + e;
            report.append(result).append("\n");
        } finally {
            TickProfiler.listener = tick -> {};
        }
        for (String line : report.toString().split("\n")) LOG.info(line);
        try {
            String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(LocalDateTime.now());
            Path file = Path.of("reports", stamp + (label.isBlank() ? "" : "_" + label.replaceAll("[^A-Za-z0-9._-]", "_")) + ".txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, report);
            if (!stages.isEmpty()) Files.writeString(file.resolveSibling(file.getFileName().toString().replace(".txt", ".csv")), csv(stages));
            Files.writeString(Path.of("benchmark-result.txt"), result.startsWith("PASS") ? "PASS" : result);
            LOG.info("Report saved to {}", file.toAbsolutePath());
        } catch (Exception e) {
            LOG.error("Could not save the benchmark report", e);
        }
        server.halt(false);
    }

    /** Fixed time and weather, and the cameras. Run every time, so a changed camera is picked up. */
    private void setScene() {
        command("gamerule advance_time false");
        command("gamerule advance_weather false");
        command("weather clear");
        command("time set noon");
        for (Camera c : CAMERAS) {
            command(String.format(Locale.ROOT, "dailycam set %s %s %s %s %s %s", c.name, c.x, c.y, c.z, c.yaw, c.pitch));
        }
    }

    /**
     * Generates the land the cameras see, out to distant terrain, and renders each camera once, which also generates
     * the few chunks beside the camera that the wedge misses. Only on a fresh world, and not measured.
     */
    private void prepare() throws Exception {
        LOG.info("Preparing the benchmark world: generating terrain. This happens once.");
        for (Camera c : CAMERAS) {
            onServer(() -> ChunkGenerator.generate((int) Math.floor(c.x), (int) Math.floor(c.z), c.yaw, GENERATE_RADIUS)).join();
        }
        for (Camera c : CAMERAS) render(c, "none");
        // Later runs start with an empty distant-terrain cache, so this one must too.
        LodCache.clear();
        command("save-all flush");
        Files.writeString(Path.of(PREPARED_MARKER), "");
    }

    /** Replaces the mobs in the pen. They are spread out: on one block, entity cramming would kill them. */
    private void spawnMobs() {
        command(String.format(Locale.ROOT, "forceload add %d %d %d %d", PEN_MIN, PEN_MIN, PEN_MAX, PEN_MAX));
        onServer(() -> {
            for (int cx = PEN_MIN >> 4; cx <= PEN_MAX >> 4; cx++) {
                for (int cz = PEN_MIN >> 4; cz <= PEN_MAX >> 4; cz++) server.overworld().getChunk(cx, cz);
            }
            return null;
        });
        command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass hollow",
                PEN_MIN, PEN_FLOOR, PEN_MIN, PEN_MAX, PEN_FLOOR + 4, PEN_MAX));
        command("kill @e[tag=" + MOB_TAG + "]");
        Random random = new Random(12345);
        int inside = PEN_MAX - PEN_MIN - 1;
        // Quietly; the report counts the mobs that are there.
        onServer(() -> {
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (int i = 0; i < mobs; i++) {
                double x = PEN_MIN + 1 + random.nextDouble() * inside, z = PEN_MIN + 1 + random.nextDouble() * inside;
                server.getCommands().performPrefixedCommand(source,
                        String.format(Locale.ROOT, "summon minecraft:cow %.2f %d %.2f {Tags:[\"%s\"]}", x, PEN_FLOOR + 1, z, MOB_TAG));
            }
            return null;
        });
    }

    /** Returns the saved picture. */
    private Path render(Camera cam, String shader) {
        var done = new CompletableFuture<RenderQueue.Result>();
        boolean queued = onServer(() -> {
            CameraSpec spec = DailyCams.get().cameras().get(cam.name).copy();
            spec.shader = shader;
            var queue = DailyCams.get().queue();
            return queue != null && queue.submit(spec, DailyCams.get().config(), done::complete);
        });
        if (!queued) throw new IllegalStateException("Could not queue camera " + cam.name);
        RenderQueue.Result result = done.join();
        if (!result.success()) throw new IllegalStateException("Camera " + cam.name + ": " + result.message());
        if (result.localFile() == null) throw new IllegalStateException("Camera " + cam.name + ": no picture was saved");
        return result.localFile().toAbsolutePath();
    }

    /** PNG only: a JPEG can hide a small change in the pixels. */
    private static String pixelHash(Path picture) {
        if (!picture.toString().endsWith(".png")) throw new IllegalStateException(picture + " isn't a PNG");
        try {
            var image = ImageIO.read(picture.toFile());
            int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            ByteBuffer bytes = ByteBuffer.allocate(8 + pixels.length * 4);
            bytes.putInt(image.getWidth()).putInt(image.getHeight()).asIntBuffer().put(pixels);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()), 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + picture, e);
        }
    }

    private Stage measure(String name, int round, @Nullable Camera camera, @Nullable String shader, Consumer<Stage> work) {
        LOG.info("Measuring: {}", name);
        Stage stage = new Stage(name, round, camera, shader);
        long[] before = renderThreadUsage();
        stage.lastTickCpuNanos = before[0];
        stage.startNanos = System.nanoTime();
        current = stage;
        try {
            work.accept(stage);
        } finally {
            current = null;
            stage.nanos = System.nanoTime() - stage.startNanos;
            long[] after = renderThreadUsage();
            stage.cpuNanos = after[0] - before[0];
            stage.allocatedBytes = after[1] - before[1];
        }
        return stage;
    }

    /**
     * CPU time and allocated bytes of the render thread so far, or zeros before its first render. Rendering work
     * on other threads, such as the server reading chunks and distant terrain from disk, isn't counted.
     */
    private long[] renderThreadUsage() {
        if (renderThreadId < 0) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("DailyCams-Render")) renderThreadId = t.threadId();
            }
            if (renderThreadId < 0) return new long[]{0, 0};
        }
        return new long[]{THREADS.getThreadCpuTime(renderThreadId), THREADS.getThreadAllocatedBytes(renderThreadId)};
    }

    /** Collectors whose events are whole cycles rather than pauses (ZGC, Shenandoah) are left out. */
    private void listenForGc() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().contains("Cycles")) continue;
            ((NotificationEmitter) gc).addNotificationListener((notification, handback) -> {
                if (!notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) return;
                var info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                Stage s = current;
                if (s != null) s.gcPausesMs.add(info.getGcInfo().getDuration());
            }, null, null);
        }
    }

    private String verdict(List<Stage> stages) {
        List<Stage> renders = stages.stream().filter(Stage::render).toList();
        int hitches = renders.stream().mapToInt(Stage::hitches).sum();
        int worst = renders.stream().mapToInt(Stage::worstSecondTps).filter(t -> t >= 0).min().orElse(-1);
        List<String> failures = new ArrayList<>();
        if (maxHitches >= 0 && hitches > maxHitches) failures.add(hitches + " hitches during renders (budget " + maxHitches + ")");
        if (minTps >= 0 && worst < minTps) failures.add("worst second " + worst + " TPS (budget " + minTps + ")");
        if (!failures.isEmpty()) return "FAIL: " + String.join(", ", failures);
        if (maxHitches < 0 && minTps < 0) return "PASS (no budget set)";
        return "PASS (budget: " + (maxHitches >= 0 ? "at most " + maxHitches + " hitches" : "")
                + (maxHitches >= 0 && minTps >= 0 ? ", " : "") + (minTps >= 0 ? "worst second at least " + minTps + " TPS" : "") + ")";
    }

    private String header(int mobsAlive) {
        var runtime = Runtime.getRuntime();
        String gcs = ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", "));
        String version = FabricLoader.getInstance().getModContainer(DailyCams.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        return "Daily Cams render benchmark" + (label.isBlank() ? "" : ": " + label) + "\n"
                + "Build:  daily-cams " + version + ", " + commit() + "\n"
                + "Host:   " + runtime.availableProcessors() + " CPUs seen by the JVM, CPU quota " + cpuQuota()
                + ", heap " + runtime.maxMemory() / (1024 * 1024) + " MiB, " + gcs + ", Java " + Runtime.version() + "\n"
                + "Scene:  seed " + server.overworld().getSeed() + ", " + mobsAlive + " cows in force-loaded chunks, cameras "
                + CAMERAS.stream().map(Camera::name).collect(Collectors.joining(", ")) + ", renderCpuShare "
                + DailyCams.get().config().renderCpuShare + (rounds > 1 ? ", " + rounds + " rounds" : "") + "\n"
                + (freeze ? "Frozen: ticks frozen and game time " + FROZEN_GAME_TIME + ", for comparing pictures.\n" : "")
                + "cpu and alloc are the render thread's; chunk loading runs on the server thread and isn't in them.\n"
                + "A hitch is a tick that started more than " + (int) HITCH_MS + " ms after the one before it.\n\n";
    }

    private static String table(List<Stage> stages) {
        Set<String> renderStages = new LinkedHashSet<>();
        for (Stage s : stages) renderStages.addAll(s.renderStages().keySet());
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%-21s %8s %7s %8s %7s %7s %8s %15s", "stage", "time", "cpu", "alloc", "hitches", "worst s", "longest", "GC pauses"));
        for (String r : renderStages) out.append(String.format(Locale.ROOT, " %9s", r));
        out.append("\n");
        out.append(String.format(Locale.ROOT, "%-21s %8s %7s %8s %7s %7s %8s %15s", "", "", "", "", "", "TPS", "tick", "n / max / total"));
        out.append("\n");
        for (Stage s : stages) {
            out.append(row(s.name, s.nanos / 1e9, s.cpuNanos / 1e9, s.allocatedBytes, s.hitches(), s.worstSecondTps(), s.longestTickMs(), s.gcPausesMs));
            Map<String, Double> times = s.renderStages();
            if (s.render()) for (String r : renderStages) out.append(String.format(Locale.ROOT, " %8.1fs", times.getOrDefault(r, 0.0) / 1000));
            out.append("\n");
        }
        List<Stage> renders = stages.stream().filter(Stage::render).toList();
        List<Long> gc = renders.stream().flatMap(s -> s.gcPausesMs.stream()).toList();
        out.append(row("all renders", renders.stream().mapToDouble(s -> s.nanos / 1e9).sum(), renders.stream().mapToDouble(s -> s.cpuNanos / 1e9).sum(),
                renders.stream().mapToLong(s -> s.allocatedBytes).sum(), renders.stream().mapToInt(Stage::hitches).sum(),
                renders.stream().mapToInt(Stage::worstSecondTps).filter(t -> t >= 0).min().orElse(-1),
                renders.stream().mapToDouble(Stage::longestTickMs).max().orElse(0), gc));
        for (String r : renderStages) {
            double ms = renders.stream().mapToDouble(s -> s.renderStages().getOrDefault(r, 0.0)).sum();
            out.append(String.format(Locale.ROOT, " %8.1fs", ms / 1000));
        }
        return out.append("\n").toString();
    }

    private static String row(String name, double seconds, double cpuSeconds, long allocated, int hitches, int worstTps, double longestMs, List<Long> gcPauses) {
        List<Long> gc;
        synchronized (gcPauses) {
            gc = List.copyOf(gcPauses);
        }
        String pauses = gc.size() + " / " + gc.stream().mapToLong(Long::longValue).max().orElse(0) + " / "
                + gc.stream().mapToLong(Long::longValue).sum();
        return String.format(Locale.ROOT, "%-21s %7.1fs %6.2fs %6.2fGB %7d %7s %6.0fms %15s", name, seconds, cpuSeconds, allocated / 1e9, hitches,
                worstTps < 0 ? "-" : String.valueOf(worstTps), longestMs, pauses);
    }

    /** One line per render, for scripts such as scripts/bench-compare.py. Times in s, CPU of the render thread. */
    private static String csv(List<Stage> stages) {
        List<Stage> renders = stages.stream().filter(Stage::render).toList();
        Set<String> states = new LinkedHashSet<>();
        for (Stage s : renders) states.addAll(s.renderStages().keySet());
        StringBuilder out = new StringBuilder("round,camera,shader,picture,file,time,cpu,alloc_mb,hitches,worst_tps,longest_tick_ms,gc_pauses,gc_max_ms,gc_total_ms");
        for (String st : states) out.append(",").append(st).append(",").append(st).append("_cpu");
        out.append("\n");
        for (Stage s : renders) {
            List<Long> gc;
            synchronized (s.gcPausesMs) {
                gc = List.copyOf(s.gcPausesMs);
            }
            out.append(String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%.3f,%.3f,%.1f,%d,%d,%.1f,%d,%d,%d", s.round, s.camera.name, s.shader,
                    s.pictureHash == null ? "" : s.pictureHash, s.picture,
                    s.nanos / 1e9, s.cpuNanos / 1e9, s.allocatedBytes / 1e6, s.hitches(), s.worstSecondTps(), s.longestTickMs(),
                    gc.size(), gc.stream().mapToLong(Long::longValue).max().orElse(0), gc.stream().mapToLong(Long::longValue).sum()));
            Map<String, Double> wall = s.renderStages();
            for (String st : states) {
                out.append(String.format(Locale.ROOT, ",%.3f,%.3f", wall.getOrDefault(st, 0.0) / 1000, s.cpuByState.getOrDefault(st, 0L) / 1e9));
            }
            out.append("\n");
        }
        return out.toString();
    }

    /** Strict, so a typo in a budget fails the run instead of turning the budget off. */
    private static int intProperty(String name, int fallback) {
        String value = System.getProperty("dailycams.benchmark." + name);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("-Pbench" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                    + " must be a whole number, not '" + value + "'");
        }
    }

    /** The git commit of the checkout the server runs in. */
    private static String commit() {
        try {
            Process git = new ProcessBuilder("git", "describe", "--always", "--dirty").redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes()).trim();
            return git.waitFor() == 0 ? "git " + out : "unknown commit";
        } catch (Exception e) {
            return "unknown commit";
        }
    }

    /** The tightest cgroup v2 CPU limit on this process, in cores. */
    private static String cpuQuota() {
        try {
            String own = Files.readAllLines(Path.of("/proc/self/cgroup")).stream()
                    .filter(l -> l.startsWith("0::")).findFirst().orElse(null);
            if (own == null) return "unknown";
            Path root = Path.of("/sys/fs/cgroup");
            double cores = Double.POSITIVE_INFINITY;
            for (Path dir = root.resolve(own.substring(4)); dir != null && dir.startsWith(root); dir = dir.getParent()) {
                Path max = dir.resolve("cpu.max");
                if (!Files.exists(max)) continue;
                String[] parts = Files.readString(max).trim().split(" ");
                if (!parts[0].equals("max")) cores = Math.min(cores, Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]));
            }
            return Double.isInfinite(cores) ? "none" : String.format(Locale.ROOT, "%.2f cores", cores);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void command(String command) {
        onServer(() -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            return null;
        });
    }

    private <T> T onServer(Supplier<T> task) {
        return server.submit(task).join();
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

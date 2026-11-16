package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.camera.CameraSpec;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import io.github.kunaigear.dailycams.webhook.WebhookSender;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Renders cameras one after another. Chunk loading is driven from the server tick; mesh building,
 * rasterizing, encoding and uploading all happen on background threads.
 */
public final class RenderQueue {
    public record Result(CameraSpec camera, boolean success, String message, @Nullable Path localFile) {}

    private record Request(CameraSpec camera, DailyCamsConfig config, Consumer<Result> callback) {}

    private enum Phase { LOADING, SETTLING, RENDERING }

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    /** Ticks to wait after chunks are loaded so lighting can finish. */
    private static final int SETTLE_TICKS = 20;

    private final TicketType ticketType;
    private final Path rendersDir;
    private final Deque<Request> pending = new ArrayDeque<>();
    private @Nullable Active active;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "DailyCams-Render"));
    private final ExecutorService deliveryExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "DailyCams-Upload"));

    public RenderQueue(TicketType ticketType, Path rendersDir) {
        this.ticketType = ticketType;
        this.rendersDir = rendersDir;
    }

    /** Server thread only. The callback also runs on the server thread. */
    public boolean submit(CameraSpec camera, DailyCamsConfig config, Consumer<Result> callback) {
        if (isQueued(camera.name)) return false;
        pending.add(new Request(camera.copy(), config, callback));
        return true;
    }

    public boolean isQueued(String name) {
        if (active != null && active.request.camera.name.equalsIgnoreCase(name)) return true;
        return pending.stream().anyMatch(r -> r.camera.name.equalsIgnoreCase(name));
    }

    public int size() {
        return pending.size() + (active != null ? 1 : 0);
    }

    public @Nullable String activeCamera() {
        return active == null ? null : active.request.camera.name;
    }

    /** "loading", "building", "drawing", "shading" or null when idle. For diagnostics. */
    public @Nullable String activeStage() {
        if (active == null) return null;
        return active.phase == Phase.RENDERING ? CameraRenderer.stage : "loading";
    }

    public void tick(MinecraftServer server) {
        if (active == null) {
            Request next = pending.poll();
            if (next == null) return;
            start(server, next);
        }
        if (active != null) active.tick(server);
    }

    public void shutdown() {
        if (active != null) active.releaseTicket();
        active = null;
        pending.clear();
        renderExecutor.shutdownNow();
        deliveryExecutor.shutdownNow();
    }

    private void start(MinecraftServer server, Request request) {
        CameraSpec cam = request.camera;
        Identifier dimId = Identifier.tryParse(cam.dimension);
        ServerLevel level = dimId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (level == null) {
            request.callback.accept(new Result(cam, false, "Dimension " + cam.dimension + " does not exist", null));
            return;
        }
        active = new Active(request, level, server.getTickCount());
    }

    private final class Active {
        /** Never request more chunks at once than this, so loading/generation can't flood the server. */
        static final int MAX_IN_FLIGHT = 4;
        static final int MAX_NEW_PER_TICK = 2;

        final Request request;
        final ServerLevel level;
        final CameraRenderer.Settings settings;
        final List<ChunkPos> toLoad;
        final List<ChunkPos> ticketed = new ArrayList<>();
        final List<ChunkPos> pending = new ArrayList<>();
        int nextToLoad;
        int lastProgressTick;
        int lastRequestTick;
        Phase phase = Phase.LOADING;
        int settleUntil;

        Active(Request request, ServerLevel level, int tick) {
            this.request = request;
            this.level = level;
            DailyCamsConfig cfg = request.config;
            CameraSpec cam = request.camera;
            this.settings = new CameraRenderer.Settings(cfg.width, cfg.height,
                    cam.fov != null ? cam.fov : cfg.fov, cfg.renderDistanceBlocks(), cfg.ssaa, cfg.renderCpuShare,
                    cam.shader != null && DailyCamsConfig.SHADERS.contains(cam.shader) ? cam.shader : cfg.shader,
                    cfg.shaderSettings, cfg.realisticSettings, cfg.lodDistanceBlocks());
            // Only the chunks the camera can actually see (plus a margin), nearest first.
            this.toLoad = new ViewVolume(level, cam.x, cam.y, cam.z, cam.yaw, cam.pitch, settings).columnsToLoad();
            this.lastProgressTick = tick;
            DailyCams.LOGGER.info("Rendering camera '{}' at {} ({} chunks to load)", cam.name, cam.describePosition(), toLoad.size());
        }

        void tick(MinecraftServer server) {
            int now = server.getTickCount();
            switch (phase) {
                case LOADING -> {
                    var source = level.getChunkSource();
                    if (pending.removeIf(p -> source.getChunkNow(p.x(), p.z()) != null)) lastProgressTick = now;
                    // Request chunks a few at a time while the server has time to spare. While it is busy,
                    // still trickle one chunk per second so a permanently loaded server finishes eventually.
                    boolean busy = DailyCams.serverBusy();
                    int budget = !busy ? MAX_NEW_PER_TICK : (now - lastRequestTick >= 20 ? 1 : 0);
                    for (int i = 0; i < budget && pending.size() < MAX_IN_FLIGHT && nextToLoad < toLoad.size(); i++) {
                        ChunkPos p = toLoad.get(nextToLoad++);
                        source.addTicketWithRadius(ticketType, p, 0);
                        ticketed.add(p);
                        lastRequestTick = now;
                        if (source.getChunkNow(p.x(), p.z()) == null) pending.add(p);
                    }
                    if (nextToLoad >= toLoad.size() && pending.isEmpty()) {
                        phase = Phase.SETTLING;
                        settleUntil = now + SETTLE_TICKS;
                    } else if (now - lastProgressTick > request.config.chunkLoadTimeoutSeconds * 20) {
                        DailyCams.LOGGER.warn("Chunk loading for camera '{}' made no progress for {}s; rendering with what is loaded. "
                                        + "Chunks that didn't load are left out of the picture.",
                                request.camera.name, request.config.chunkLoadTimeoutSeconds);
                        phase = Phase.SETTLING;
                        settleUntil = now;
                    }
                }
                case SETTLING -> {
                    if (now >= settleUntil) {
                        phase = Phase.RENDERING;
                        CameraSpec c = request.camera;
                        // The render thread can't ask the chunk system, so tell it which chunks didn't load.
                        LongOpenHashSet missing = new LongOpenHashSet();
                        var source = level.getChunkSource();
                        for (ChunkPos p : toLoad) {
                            if (source.getChunkNow(p.x(), p.z()) == null) missing.add(p.pack());
                        }
                        // Entities can only be queried safely here, on the server thread.
                        Entity hidden = CameraRenderer.entityAtCamera(level, c.x, c.y, c.z);
                        DailyCamsConfig cfg = request.config;
                        long started = System.nanoTime();
                        // Encoding happens on the render thread too, so a slow webhook only ever holds the small
                        // encoded file in memory, never the raw image.
                        CompletableFuture
                                .supplyAsync(() -> encode(c, cfg, CameraRenderer.render(level, c.x, c.y, c.z, c.yaw, c.pitch, settings, missing, hidden)), renderExecutor)
                                .whenComplete((encoded, error) -> server.execute(() -> onRendered(encoded, error, started)));
                    }
                }
                case RENDERING -> { /* waiting for the render thread */ }
            }
        }

        void releaseTicket() {
            var source = level.getChunkSource();
            for (ChunkPos p : ticketed) source.removeTicketWithRadius(ticketType, p, 0);
            ticketed.clear();
            pending.clear();
        }

        void onRendered(@Nullable Encoded encoded, @Nullable Throwable error, long startedNanos) {
            releaseTicket();
            if (active == this) active = null;
            CameraSpec cam = request.camera;
            if (error != null || encoded == null) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                DailyCams.LOGGER.error("Rendering camera '{}' failed", cam.name, cause);
                request.callback.accept(new Result(cam, false, "Render failed: " + (cause == null ? "no image" : cause), null));
                return;
            }
            long ms = (System.nanoTime() - startedNanos) / 1_000_000;
            DailyCams.LOGGER.info("Rendered camera '{}' ({}x{}) in {} ms", cam.name, encoded.width(), encoded.height(), ms);
            MinecraftServer server = level.getServer();
            CompletableFuture
                    .supplyAsync(() -> deliver(cam, request.config, encoded), deliveryExecutor)
                    .exceptionally(t -> new Result(cam, false, "Delivery failed: " + t, null))
                    .thenAccept(result -> server.execute(() -> request.callback.accept(result)));
        }
    }

    /** A rendered picture, already encoded. */
    record Encoded(WebhookSender.Image image, Instant takenAt, int width, int height) {}

    /** Runs on the render thread. */
    private static Encoded encode(CameraSpec cam, DailyCamsConfig cfg, BufferedImage raw) {
        Instant takenAt = Instant.now();
        String stamp = FILE_TIME.format(takenAt.atZone(cfg.zone()));
        try {
            long encodeStart = System.nanoTime();
            WebhookSender.Image image = encode(toRgb(raw), cfg, cam.name + "_" + stamp);
            DailyCams.LOGGER.info("Encoded camera '{}' as {} ({} KiB) in {} ms", cam.name, image.mimeType(),
                    image.bytes().length / 1024, (System.nanoTime() - encodeStart) / 1_000_000);
            return new Encoded(image, takenAt, raw.getWidth(), raw.getHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode image", e);
        }
    }

    private Result deliver(CameraSpec cam, DailyCamsConfig cfg, Encoded encoded) {
        Instant takenAt = encoded.takenAt();
        WebhookSender.Image image = encoded.image();

        Path local = null;
        if (cfg.keepLocalCopies) {
            try {
                Path dir = rendersDir.resolve(cam.name);
                Files.createDirectories(dir);
                local = dir.resolve(image.fileName());
                Files.write(local, image.bytes());
                pruneOldCopies(dir, cfg.localCopyRetentionDays);
            } catch (IOException e) {
                DailyCams.LOGGER.warn("Could not save local copy of camera '{}': {}", cam.name, e.getMessage());
                local = null;
            }
        }

        String url = cam.webhookUrl != null && !cam.webhookUrl.isBlank() ? cam.webhookUrl : cfg.webhookUrl;
        if (url == null || url.isBlank()) {
            return new Result(cam, true, "Rendered, but no webhook is configured", local);
        }
        try {
            WebhookSender.send(url, cfg, cam, takenAt, image);
            DailyCams.LOGGER.info("Sent camera '{}' to webhook ({} KiB)", cam.name, image.bytes().length / 1024);
            return new Result(cam, true, "Sent to webhook", local);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(cam, false, "Upload interrupted", local);
        } catch (WebhookSender.HttpStatusException e) {
            // The response body goes to the log only: a receiver could echo the request URL and its token.
            DailyCams.LOGGER.error("Sending camera '{}' to webhook failed: HTTP {}: {}", cam.name, e.status,
                    e.responseBody.replace(url, WebhookSender.redact(url)));
            return new Result(cam, false, "Webhook failed: HTTP " + e.status, local);
        } catch (Exception e) {
            // Exception messages can contain the URL (an invalid URL, for example), so players only see the type.
            String detail = e.getMessage() != null ? e.getMessage().replace(url, WebhookSender.redact(url)) : "";
            DailyCams.LOGGER.error("Sending camera '{}' to webhook failed: {} {}", cam.name, e.getClass().getSimpleName(), detail);
            return new Result(cam, false, "Webhook failed: " + e.getClass().getSimpleName(), local);
        }
    }

    private static WebhookSender.Image encode(BufferedImage image, DailyCamsConfig cfg, String baseName) throws IOException {
        if (cfg.imageFormat.equals("png")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            if (out.size() <= cfg.maxUploadBytes) {
                return new WebhookSender.Image(out.toByteArray(), baseName + ".png", "image/png");
            }
            DailyCams.LOGGER.info("PNG is {} KiB, above maxUploadBytes; sending JPEG instead", out.size() / 1024);
        }
        float quality = cfg.jpegQuality;
        byte[] jpg = jpeg(image, quality);
        while (jpg.length > cfg.maxUploadBytes && quality > 0.35f) {
            quality -= 0.15f;
            jpg = jpeg(image, quality);
        }
        return new WebhookSender.Image(jpg, baseName + ".jpg", "image/jpeg");
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
            disableChromaSubsampling(metadata);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * JPEG normally stores colour at half resolution (4:2:0), which smears the hard colour edges of
     * Minecraft's pixel-art textures. Keep full colour resolution (4:4:4) instead.
     */
    private static void disableChromaSubsampling(IIOMetadata metadata) {
        try {
            String format = "javax_imageio_jpeg_image_1.0";
            Node root = metadata.getAsTree(format);
            NodeList components = ((Element) root).getElementsByTagName("componentSpec");
            for (int i = 0; i < components.getLength(); i++) {
                Element c = (Element) components.item(i);
                c.setAttribute("HsamplingFactor", "1");
                c.setAttribute("VsamplingFactor", "1");
            }
            metadata.setFromTree(format, root);
        } catch (Exception e) {
            DailyCams.LOGGER.debug("Could not disable JPEG chroma subsampling", e);
        }
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static void pruneOldCopies(Path dir, int retentionDays) {
        if (retentionDays <= 0) return;
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) Files.delete(f);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }
}

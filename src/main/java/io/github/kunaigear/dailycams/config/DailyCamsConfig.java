package io.github.kunaigear.dailycams.config;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.util.Json;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code config/dailycams/config.json}. Field names are the JSON keys. */
public final class DailyCamsConfig {
    /** Default webhook for all cameras. A camera can override it with /mc_camera webhook. */
    public String webhookUrl = "";
    /** "discord" (embed + attachment) or "generic" (multipart: "metadata" JSON + "file"). */
    public String webhookFormat = "discord";
    public String discordUsername = "Daily Cams";
    public String discordAvatarUrl = "";
    /** Optional plain text sent with every Discord post, e.g. a role mention. */
    public String discordMessage = "";

    /** Local times (HH:mm) at which all cameras are rendered. */
    public List<String> scheduleTimes = new ArrayList<>(List.of("12:00"));
    /** IANA zone like "Europe/Berlin". Empty = the server's system zone. */
    public String timeZone = "";
    /** If the server was offline at a scheduled time, run as soon as it is up again. */
    public boolean catchUpMissedRuns = true;

    public int width = 1280;
    public int height = 720;
    public int fov = 70;
    /** View distance in blocks. Higher = slower render and more chunks loaded. */
    public int renderDistance = 96;
    /** Supersampling factor (1 = off). */
    public int ssaa = 2;

    /** "png" or "jpg". PNG falls back to JPEG automatically when it is larger than maxUploadBytes. */
    public String imageFormat = "png";
    public float jpegQuality = 0.9f;
    /** Discord's default webhook upload limit is 10 MiB. */
    public long maxUploadBytes = 10L * 1024 * 1024 - 64 * 1024;

    public boolean keepLocalCopies = true;
    /** Delete local copies older than this. 0 = keep forever. */
    public int localCopyRetentionDays = 30;

    /**
     * Every player gets one personal camera named after them. Players with dailycams:camera.named
     * (default: op level 2) may create this many named cameras on top of it; players with
     * dailycams:camera.unlimited (default: op level 4) have no limit.
     */
    public int namedCameraLimit = 3;
    /** Tell owners in chat when the daily picture from their camera has been posted. */
    public boolean notifyOwners = true;
    public int testCooldownSeconds = 300;
    public int chunkLoadTimeoutSeconds = 60;

    // ---- derived, not serialized ----
    private transient List<LocalTime> parsedTimes = List.of();
    private transient ZoneId zone = ZoneId.systemDefault();

    public static DailyCamsConfig load(Path file) {
        DailyCamsConfig cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = Json.GSON.fromJson(Files.readString(file), DailyCamsConfig.class);
            } catch (Exception e) {
                DailyCams.LOGGER.error("Could not read {}, using defaults: {}", file, e.getMessage());
            }
        }
        if (cfg == null) cfg = new DailyCamsConfig();
        cfg.validate();
        try {
            Json.writeAtomically(file, Json.GSON.toJson(cfg)); // writes defaults / adds new keys
        } catch (IOException e) {
            DailyCams.LOGGER.error("Could not write {}", file, e);
        }
        return cfg;
    }

    private void validate() {
        width = Mth.clamp(width, 16, 3840);
        height = Mth.clamp(height, 16, 2160);
        fov = Mth.clamp(fov, 30, 110);
        renderDistance = Mth.clamp(renderDistance, 16, 256);
        ssaa = Mth.clamp(ssaa, 1, 4);
        jpegQuality = Mth.clamp(jpegQuality, 0.1f, 1f);
        namedCameraLimit = Math.max(0, namedCameraLimit);
        testCooldownSeconds = Math.max(0, testCooldownSeconds);
        chunkLoadTimeoutSeconds = Mth.clamp(chunkLoadTimeoutSeconds, 5, 600);
        localCopyRetentionDays = Math.max(0, localCopyRetentionDays);
        if (webhookUrl == null) webhookUrl = "";
        if (discordUsername == null) discordUsername = "";
        if (discordAvatarUrl == null) discordAvatarUrl = "";
        if (discordMessage == null) discordMessage = "";
        webhookFormat = webhookFormat == null ? "discord" : webhookFormat.toLowerCase(Locale.ROOT);
        if (!webhookFormat.equals("discord") && !webhookFormat.equals("generic")) {
            DailyCams.LOGGER.warn("Unknown webhookFormat '{}', using 'discord'", webhookFormat);
            webhookFormat = "discord";
        }
        imageFormat = imageFormat == null ? "png" : imageFormat.toLowerCase(Locale.ROOT);
        if (imageFormat.equals("jpeg")) imageFormat = "jpg";
        if (!imageFormat.equals("png") && !imageFormat.equals("jpg")) imageFormat = "png";

        if (scheduleTimes == null) scheduleTimes = new ArrayList<>();
        List<LocalTime> times = new ArrayList<>();
        for (String s : scheduleTimes) {
            try {
                times.add(LocalTime.parse(s.trim()));
            } catch (DateTimeParseException | NullPointerException e) {
                DailyCams.LOGGER.warn("Ignoring invalid schedule time '{}' (expected HH:mm)", s);
            }
        }
        parsedTimes = times.stream().distinct().sorted().toList();

        zone = ZoneId.systemDefault();
        if (timeZone != null && !timeZone.isBlank()) {
            try {
                zone = ZoneId.of(timeZone.trim());
            } catch (Exception e) {
                DailyCams.LOGGER.warn("Unknown timeZone '{}', using system zone {}", timeZone, zone);
            }
        }
    }

    public List<LocalTime> times() {
        return parsedTimes;
    }

    public ZoneId zone() {
        return zone;
    }
}

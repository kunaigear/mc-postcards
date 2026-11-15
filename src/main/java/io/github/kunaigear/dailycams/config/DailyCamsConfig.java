package io.github.kunaigear.dailycams.config;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.util.Json;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

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
    /** Default webhook for all cameras. A camera can override it with /dailycam webhook. */
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

    public int width = 1920;
    public int height = 1080;
    public int fov = 70;
    /** View distance in chunks, like Minecraft's own setting. Higher = slower render and more chunks loaded. */
    public int renderDistance = 16;
    /** Supersampling factor (1 = off). */
    public int ssaa = 2;
    /**
     * How much of one CPU core a render may use (0.05-1.0). The render also pauses for a second whenever a
     * server tick takes longer than 25 ms or starts late. Lower = slower renders, less competition.
     */
    public double renderCpuShare = 0.25;

    /** "simple" (sun shadows, warm light, haze, bloom, colour grade) or "none" (plain Camera Obscura output). */
    public String shader = "simple";
    public ShaderSettings shaderSettings = new ShaderSettings();

    /** Knobs for the "simple" shader. */
    public static final class ShaderSettings {
        /** How dark shadows from the sun get (0 = none, 1 = black). */
        public double shadowStrength = 0.42;
        /** Warm tint on sunlit surfaces. */
        public double sunWarmth = 0.10;
        /** How much distant terrain fades towards the sky colour. */
        public double haze = 0.18;
        /** Glow around bright areas. */
        public double bloom = 0.22;
        public double saturation = 1.12;
        public double contrast = 1.06;
        /** Darkening towards the corners. */
        public double vignette = 0.22;
    }

    /** "png" or "jpg". PNG falls back to JPEG automatically when it is larger than maxUploadBytes. */
    public String imageFormat = "jpg";
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
    public int chunkLoadTimeoutSeconds = 120;

    // ---- derived, not serialized ----
    private transient List<LocalTime> parsedTimes = List.of();
    private transient ZoneId zone = ZoneId.systemDefault();

    /**
     * Loads the config. If the file exists but can't be read (say, a typo in the JSON), it's backed up and left
     * alone, never overwritten, and {@code previous} is kept (or the defaults on first load). The problem is
     * added to {@code problems}.
     */
    public static DailyCamsConfig load(Path file, @Nullable DailyCamsConfig previous, List<String> problems) {
        DailyCamsConfig cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = Json.GSON.fromJson(Files.readString(file), DailyCamsConfig.class);
            } catch (Exception e) {
                Path copy = Json.backupBroken(file);
                String problem = file.getFileName() + " has an error and wasn't loaded (" + e.getMessage() + "). "
                        + (previous != null ? "Kept the previous settings." : "Using the defaults until it's fixed.")
                        + (copy != null ? " A copy is in " + copy.getFileName() + "." : "");
                DailyCams.LOGGER.error(problem);
                problems.add(problem);
                if (previous != null) return previous;
                DailyCamsConfig defaults = new DailyCamsConfig();
                defaults.validate();
                return defaults; // don't write: the broken file is what the admin wants to fix
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
        renderDistance = Mth.clamp(renderDistance, 2, 32);
        ssaa = Mth.clamp(ssaa, 1, 4);
        renderCpuShare = Mth.clamp(renderCpuShare, 0.05, 1.0);
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
        imageFormat = imageFormat == null ? "jpg" : imageFormat.toLowerCase(Locale.ROOT);
        if (imageFormat.equals("jpeg")) imageFormat = "jpg";
        if (!imageFormat.equals("png") && !imageFormat.equals("jpg")) imageFormat = "jpg";

        shader = shader == null ? "simple" : shader.toLowerCase(Locale.ROOT);
        if (!shader.equals("simple") && !shader.equals("none")) {
            DailyCams.LOGGER.warn("Unknown shader '{}', using 'simple'", shader);
            shader = "simple";
        }
        if (shaderSettings == null) shaderSettings = new ShaderSettings();
        ShaderSettings ss = shaderSettings;
        ss.shadowStrength = Mth.clamp(ss.shadowStrength, 0, 1);
        ss.sunWarmth = Mth.clamp(ss.sunWarmth, 0, 0.5);
        ss.haze = Mth.clamp(ss.haze, 0, 1);
        ss.bloom = Mth.clamp(ss.bloom, 0, 2);
        ss.saturation = Mth.clamp(ss.saturation, 0, 2);
        ss.contrast = Mth.clamp(ss.contrast, 0.5, 2);
        ss.vignette = Mth.clamp(ss.vignette, 0, 1);

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

    public int renderDistanceBlocks() {
        return renderDistance * 16;
    }

    public List<LocalTime> times() {
        return parsedTimes;
    }

    public ZoneId zone() {
        return zone;
    }
}

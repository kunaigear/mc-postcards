package io.github.kunaigear.dailycams.webhook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.camera.CameraSpec;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Uploads one rendered image to a webhook. Blocking; call from a background thread. */
public final class WebhookSender {
    private static final int MAX_ATTEMPTS = 4;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private WebhookSender() {}

    public record Image(byte[] bytes, String fileName, String mimeType) {}

    /**
     * The webhook answered with an error status. The message only has the status, so it's safe to show players;
     * {@link #responseBody} is for the server log.
     */
    public static final class HttpStatusException extends IOException {
        public final int status;
        public final String responseBody;

        HttpStatusException(int status, String responseBody) {
            super("HTTP " + status);
            this.status = status;
            this.responseBody = responseBody;
        }
    }

    /**
     * A webhook URL for display: only the scheme and host, because a credential can be anywhere in the path or
     * query (for Discord, the path holds the token).
     */
    public static String redact(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() != null && uri.getHost() != null) return uri.getScheme() + "://" + uri.getHost() + "/…";
        } catch (Exception ignored) {
            // fall through
        }
        return "(hidden)";
    }

    public static boolean isValidUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return false;
        }
    }

    public static void send(String url, DailyCamsConfig cfg, CameraSpec cam, Instant takenAt, Image image)
            throws IOException, InterruptedException {
        String boundary = "dailycams-" + UUID.randomUUID();
        Multipart body = new Multipart(boundary);
        if (cfg.webhookFormat.equals("generic")) {
            body.json("metadata", metadata(cam, takenAt));
            body.file("file", image);
        } else {
            body.json("payload_json", discordPayload(cfg, cam, takenAt, image));
            body.file("files[0]", image);
        }
        byte[] bytes = body.finish();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", "DailyCams (Minecraft Fabric mod)")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return;

            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= MAX_ATTEMPTS) {
                throw new HttpStatusException(status, abbreviate(response.body()));
            }
            long waitMs = status == 429 ? retryAfterMs(response) : 2000L * attempt;
            DailyCams.LOGGER.warn("Webhook for camera '{}' answered HTTP {}, retrying in {} ms", cam.name, status, waitMs);
            Thread.sleep(waitMs);
        }
    }

    private static JsonObject discordPayload(DailyCamsConfig cfg, CameraSpec cam, Instant takenAt, Image image) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "📷 " + cam.name);
        embed.addProperty("description", String.format(Locale.ROOT, "`%d %d %d` in `%s`\nBy **%s**",
                Math.round(cam.x), Math.round(cam.y), Math.round(cam.z), cam.dimension, cam.ownerName));
        embed.addProperty("color", 0x5B8DEF);
        embed.addProperty("timestamp", takenAt.toString());
        JsonObject img = new JsonObject();
        img.addProperty("url", "attachment://" + image.fileName());
        embed.add("image", img);

        JsonObject payload = new JsonObject();
        if (!cfg.discordUsername.isBlank()) payload.addProperty("username", cfg.discordUsername);
        if (!cfg.discordAvatarUrl.isBlank()) payload.addProperty("avatar_url", cfg.discordAvatarUrl);
        JsonObject mentions = new JsonObject();
        JsonArray parse = new JsonArray();
        if (!cfg.discordMessage.isBlank()) {
            payload.addProperty("content", cfg.discordMessage);
            parse.add("roles");
            parse.add("users");
            parse.add("everyone");
        }
        mentions.add("parse", parse);
        payload.add("allowed_mentions", mentions);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        JsonArray attachments = new JsonArray();
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", 0);
        attachment.addProperty("filename", image.fileName());
        attachments.add(attachment);
        payload.add("attachments", attachments);
        return payload;
    }

    private static JsonObject metadata(CameraSpec cam, Instant takenAt) {
        JsonObject o = new JsonObject();
        o.addProperty("camera", cam.name);
        o.addProperty("owner", cam.ownerName);
        if (cam.owner != null) o.addProperty("ownerUuid", cam.owner.toString());
        o.addProperty("dimension", cam.dimension);
        o.addProperty("x", cam.x);
        o.addProperty("y", cam.y);
        o.addProperty("z", cam.z);
        o.addProperty("yaw", cam.yaw);
        o.addProperty("pitch", cam.pitch);
        o.addProperty("takenAt", takenAt.toString());
        return o;
    }

    private static long retryAfterMs(HttpResponse<String> response) {
        double seconds = 5;
        try {
            var header = response.headers().firstValue("Retry-After");
            if (header.isPresent()) {
                seconds = Double.parseDouble(header.get());
            } else {
                seconds = JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
            }
        } catch (Exception ignored) {
        }
        return (long) (Math.min(Math.max(seconds, 0.5), 60) * 1000);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static final class Multipart {
        private final String boundary;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Multipart(String boundary) {
            this.boundary = boundary;
        }

        void json(String name, JsonObject json) {
            header("Content-Disposition: form-data; name=\"" + name + "\"", "application/json");
            out.writeBytes(json.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        void file(String name, Image image) {
            header("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + image.fileName() + "\"", image.mimeType());
            out.writeBytes(image.bytes());
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }

        private void header(String disposition, String contentType) {
            String h = "--" + boundary + "\r\n" + disposition + "\r\nContent-Type: " + contentType + "\r\n\r\n";
            out.writeBytes(h.getBytes(StandardCharsets.UTF_8));
        }

        byte[] finish() {
            out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        }
    }
}

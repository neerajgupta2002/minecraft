package ai.xelta.craft.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.config.XeltaCraftConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * XeltaGenAvatarClient — Talking Avatar Generation (Phase 7a)
 *
 * Real endpoint: https://stgavatar.xelta.ai/api/v1
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  Full Flow:                                                      ║
 * ║                                                                  ║
 * ║  1. (Optional) GET /voices/list?language=en&gender=female        ║
 * ║     → List of available TTS voices with UUIDs                   ║
 * ║                                                                  ║
 * ║  2. POST /avatar/talking-avatar-upload (multipart/form-data)    ║
 * ║     Fields:                                                      ║
 * ║       user_message:  (string) — what the avatar says             ║
 * ║       avatar_image:  (File PNG) — character image                ║
 * ║       voice_type:    (string UUID) — TTS voice ID                ║
 * ║       emotion:       "neutral"                                   ║
 * ║       input_type:    "direct"                                    ║
 * ║     → Returns: { "job_id": "..." }                              ║
 * ║                                                                  ║
 * ║  3. Poll GET /job/{job_id}                                       ║
 * ║     → { "status": "queued|processing|done|failed",              ║
 * ║          "data": { "video_url": "https://cdn.../avatar.mp4" } } ║
 * ║                                                                  ║
 * ║  In-game usage:                                                  ║
 * ║    Player types message in Terminal prompt field                 ║
 * ║    AvatarScroll item in output slot → triggers GenAvatar mode   ║
 * ║    Optional: Photo Slide in input slot as character image        ║
 * ║    Default: downloads player's skin from mc-heads.net            ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class XeltaGenAvatarClient {

    private static XeltaGenAvatarClient INSTANCE;
    public static XeltaGenAvatarClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaGenAvatarClient();
        return INSTANCE;
    }

    // Polling constants
    private static final int POLL_INTERVAL_MS = 4_000;   // 4 seconds
    private static final int MAX_ATTEMPTS     = 90;       // 6 minutes

    private final HttpClient httpClient;

    private XeltaGenAvatarClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // =========================================================
    // Full pipeline: submit → poll → return video URL
    // =========================================================

    /**
     * Generates a talking avatar video.
     *
     * @param message          The text the avatar will speak
     * @param avatarImageBytes PNG bytes of the character image (from Photo Slide or player skin)
     * @param token            JWT auth token
     * @param onProgress       Progress callback (runs on virtual thread)
     * @return CompletableFuture resolving to the video URL
     */
    public CompletableFuture<String> generateAvatar(
            String message,
            byte[] avatarImageBytes,
            String token,
            Consumer<String> onProgress) {

        return submitJob(message, avatarImageBytes, token, onProgress)
            .thenCompose(jobId -> {
                onProgress.accept("Avatar processing (job: " + jobId.substring(0, Math.min(8, jobId.length())) + "...)");
                return pollForResult(jobId, token, onProgress);
            });
    }

    // =========================================================
    // Step 1 — Submit job
    // POST /avatar/talking-avatar-upload (multipart)
    // =========================================================

    private CompletableFuture<String> submitJob(
            String message, byte[] avatarImageBytes, String token, Consumer<String> onProgress) {

        String baseUrl = XeltaCraftConfig.AVATAR_API_URL.get();
        int timeout    = XeltaCraftConfig.TIMEOUT_SECONDS.get();
        String voiceId = XeltaCraftConfig.AVATAR_VOICE_ID.get();

        onProgress.accept("Uploading avatar image...");

        MultipartBodyBuilder mp = new MultipartBodyBuilder()
            .addField("user_message",  message)
            .addFile("avatar_image",   "avatar.png", avatarImageBytes, "image/png")
            .addField("voice_type",    voiceId.isBlank() ? "default" : voiceId)
            .addField("emotion",       "neutral")
            .addField("input_type",    "direct");

        byte[] body = mp.build();

        XeltaCraftMod.LOGGER.info("[GenAvatar] POST {}/avatar/talking-avatar-upload", baseUrl);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/avatar/talking-avatar-upload"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", mp.getContentType())
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .timeout(Duration.ofSeconds(timeout + 30))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                int status = response.statusCode();
                if (status != 200 && status != 201 && status != 202) {
                    throw new XeltaException("GenAvatar submit failed (HTTP " + status + "): " + extractError(response.body()));
                }
                String sanitized = response.body().replace("'", "\"");
                JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                JsonObject data  = root.has("data") ? root.getAsJsonObject("data") : root;

                for (String key : new String[]{"job_id", "jobId", "id"}) {
                    if (data.has(key)) {
                        String jobId = data.get(key).getAsString();
                        XeltaCraftMod.LOGGER.info("[GenAvatar] Job submitted: {}", jobId);
                        return jobId;
                    }
                }
                throw new XeltaApiClient.XeltaResponseException("No job_id in GenAvatar response: " + response.body());
            });
    }

    // =========================================================
    // Step 2 — Poll job status
    // GET /job/{job_id}
    // =========================================================

    private CompletableFuture<String> pollForResult(String jobId, String token, Consumer<String> onProgress) {
        String baseUrl = XeltaCraftConfig.AVATAR_API_URL.get();
        AtomicInteger attempts = new AtomicInteger(0);

        return CompletableFuture.supplyAsync(() -> {
            while (attempts.getAndIncrement() < MAX_ATTEMPTS) {
                int pct = (attempts.get() * 100) / MAX_ATTEMPTS;
                onProgress.accept("Avatar generating... " + pct + "%");

                try {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/job/" + jobId))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                    HttpResponse<String> response =
                        httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                    String sanitized = response.body().replace("'", "\"");
                    JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                    JsonObject data  = root.has("data") ? root.getAsJsonObject("data") : root;

                    String status = data.has("status") ? data.get("status").getAsString().toLowerCase() : "processing";

                    switch (status) {
                        case "done", "completed", "success" -> {
                            // Extract video URL
                            for (String key : new String[]{"video_url", "output_url", "result_url", "url"}) {
                                if (data.has(key) && !data.get(key).getAsString().isBlank()) {
                                    String url = data.get(key).getAsString();
                                    XeltaCraftMod.LOGGER.info("[GenAvatar] ✅ Video ready: {}", url);
                                    return url;
                                }
                            }
                            throw new XeltaException("[GenAvatar] Done status but no video_url found");
                        }
                        case "failed", "error" -> {
                            String msg = data.has("message") ? data.get("message").getAsString() : "Unknown";
                            throw new XeltaException("GenAvatar job failed: " + msg);
                        }
                        default -> {
                            XeltaCraftMod.LOGGER.debug("[GenAvatar] Job {} status: {} (attempt {}/{})",
                                jobId, status, attempts.get(), MAX_ATTEMPTS);
                        }
                    }
                } catch (XeltaException xe) {
                    throw xe;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new XeltaException("GenAvatar poll interrupted");
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[GenAvatar] Poll error (attempt {}): {}", attempts.get(), e.getMessage());
                }

                try { Thread.sleep(POLL_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            throw new XeltaException("GenAvatar timed out after " + (MAX_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "s");
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // =========================================================
    // Voice List — GET /voices/list
    // Returns list of VoiceInfo records for command output
    // =========================================================

    public record VoiceInfo(String id, String name, String language, String gender) {
        @Override public String toString() {
            return String.format("§b%s §7[%s/%s] id: §e%s", name, language, gender, id);
        }
    }

    public CompletableFuture<List<VoiceInfo>> fetchVoices(String token,
            String language, String gender) {

        String baseUrl = XeltaCraftConfig.AVATAR_API_URL.get();
        StringBuilder urlBuilder = new StringBuilder(baseUrl + "/voices/list");
        if (language != null && !language.isBlank()) urlBuilder.append("?language=").append(language);
        if (gender   != null && !gender.isBlank())   {
            urlBuilder.append(urlBuilder.toString().contains("?") ? "&" : "?").append("gender=").append(gender);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlBuilder.toString()))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                List<VoiceInfo> voices = new ArrayList<>();
                if (response.statusCode() != 200) return voices;
                try {
                    String sanitized = response.body().replace("'", "\"");
                    JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                    JsonArray arr    = root.has("data") ? root.getAsJsonArray("data")
                                    : (root.has("voices") ? root.getAsJsonArray("voices") : new JsonArray());
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject v = arr.get(i).getAsJsonObject();
                        voices.add(new VoiceInfo(
                            getStr(v, "id", ""),
                            getStr(v, "name", "Unknown"),
                            getStr(v, "language", "en"),
                            getStr(v, "gender", "neutral")
                        ));
                    }
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[GenAvatar] Could not parse voices: {}", e.getMessage());
                }
                return voices;
            })
            .exceptionally(e -> List.of());
    }

    /**
     * Downloads any URL and returns its bytes.
     * Used to turn a Photo Slide image URL into bytes for avatar upload.
     */
    public java.util.concurrent.CompletableFuture<byte[]> downloadUrl(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new XeltaException("Failed to download image (HTTP " + response.statusCode() + ")");
                }
                return response.body();
            });
    }

    /**
     * Downloads player skin as PNG bytes from mc-heads.net.
     * Used as the avatar image when no Photo Slide is in the input slot.
     *
     * @param username  Minecraft player username
     * @return PNG bytes, or the 1×1 transparent placeholder on failure
     */
    public java.util.concurrent.CompletableFuture<byte[]> downloadPlayerSkin(String username) {
        String url = "https://mc-heads.net/skin/" + username;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    XeltaCraftMod.LOGGER.warn("[GenAvatar] Failed to download skin for {}, using placeholder", username);
                    return MultipartBodyBuilder.TRANSPARENT_1X1_PNG;
                }
                XeltaCraftMod.LOGGER.debug("[GenAvatar] Skin downloaded ({} bytes) for {}", response.body().length, username);
                return response.body();
            })
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.warn("[GenAvatar] Skin download error: {}", e.getMessage());
                return MultipartBodyBuilder.TRANSPARENT_1X1_PNG;
            });
    }

    private String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private String extractError(String body) {
        try {
            String sanitized = body.replace("'", "\"");
            JsonObject root = JsonParser.parseString(sanitized).getAsJsonObject();
            for (String k : new String[]{"message", "detail", "msg", "error"}) {
                if (root.has(k)) return root.get(k).getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 150 ? body.substring(0, 150) : body;
    }
}

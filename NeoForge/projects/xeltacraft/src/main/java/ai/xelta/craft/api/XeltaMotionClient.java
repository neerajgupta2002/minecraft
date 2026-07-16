package ai.xelta.craft.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.config.XeltaCraftConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * XeltaMotionClient — Motion Control Video Generation
 *
 * Real endpoint: https://advavatar.stg.xelta.ai
 *
 * ╔═══════════════════════════════════════════════════════════════╗
 * ║  Flow:                                                        ║
 * ║  1. (Optional) POST /api/v1/motion-control/estimate           ║
 * ║     → { "estimated_credits": 500, "estimated_seconds": 30 }   ║
 * ║                                                               ║
 * ║  2. POST /api/v1/motion-control/generate (multipart)          ║
 * ║     Fields:                                                   ║
 * ║       character_image: (File PNG/JPG)                         ║
 * ║       reference_video: (File MP4)                             ║
 * ║       character_orientation: "image" | "video"                ║
 * ║       keep_original_sound: "true" | "false"                   ║
 * ║     Returns HTTP 202 + { "job_id": "..." }                    ║
 * ║                                                               ║
 * ║  3. Poll GET /api/v1/motion-control/results/{job_id}          ║
 * ║     → { "status": "done", "data": { "video_url": "..." } }    ║
 * ╚═══════════════════════════════════════════════════════════════╝
 *
 * In-game usage:
 *   Player puts Photo Slide (has image URL) in input slot + Film Roll in output
 *   The mod downloads the image from the URL, then uploads it + reference video
 *   Reference video URL is configured via /xeltacraft setrefvideo <url>
 */
public class XeltaMotionClient {

    private static XeltaMotionClient INSTANCE;
    public static XeltaMotionClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaMotionClient();
        return INSTANCE;
    }

    private final HttpClient httpClient;

    private XeltaMotionClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    // =========================================================
    // Full pipeline: estimate → submit → poll → return video URL
    // =========================================================

    /**
     * Generates a video using Motion Control.
     *
     * @param characterImageBytes  PNG bytes of the character image (from Photo Slide)
     * @param referenceVideoBytes  MP4 bytes of the reference video (downloaded from config URL)
     * @param token                JWT auth token
     * @param onProgress           Progress callback
     * @return CompletableFuture resolving to the final video URL
     */
    public CompletableFuture<String> generateVideo(
            byte[] characterImageBytes,
            byte[] referenceVideoBytes,
            String token,
            Consumer<String> onProgress) {

        return submitJob(characterImageBytes, referenceVideoBytes, token, onProgress)
            .thenCompose(jobId -> {
                onProgress.accept("Video submitted (job: " + jobId.substring(0, Math.min(8, jobId.length())) + "...)");
                XeltaJobPoller poller = new XeltaJobPoller(httpClient);
                return poller.pollMotionControlJob(jobId, token, onProgress);
            });
    }

    // =========================================================
    // Step 1 — Optional cost estimate
    // POST /api/v1/motion-control/estimate
    // =========================================================

    /**
     * Estimates cost before generation. Optional — mod shows estimate in GUI.
     * @return estimated credit cost, or -1 if estimate unavailable
     */
    public CompletableFuture<Integer> estimateCost(byte[] referenceVideoBytes, String token) {
        String baseUrl = XeltaCraftConfig.MOTION_CONTROL_URL.get();

        MultipartBodyBuilder mpBuilder = new MultipartBodyBuilder();
        if (referenceVideoBytes != null) {
            mpBuilder.addFile("reference_video", "reference.mp4", referenceVideoBytes, "video/mp4");
        }
        byte[] body = mpBuilder.build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v1/motion-control/estimate"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", mpBuilder.getContentType())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .timeout(Duration.ofSeconds(15))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) return -1;
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
                    return data.has("estimated_credits") ? data.get("estimated_credits").getAsInt() : -1;
                } catch (Exception e) {
                    return -1;
                }
            })
            .exceptionally(e -> -1);
    }

    // =========================================================
    // Step 2 — Submit job
    // POST /api/v1/motion-control/generate
    // Returns job_id (HTTP 202)
    // =========================================================

    private CompletableFuture<String> submitJob(
            byte[] characterImageBytes,
            byte[] referenceVideoBytes,
            String token,
            Consumer<String> onProgress) {

        String baseUrl = XeltaCraftConfig.MOTION_CONTROL_URL.get();
        int timeout    = XeltaCraftConfig.TIMEOUT_SECONDS.get();

        onProgress.accept("Uploading character image...");

        MultipartBodyBuilder mpBuilder = new MultipartBodyBuilder()
            .addFile("character_image", "character.png", characterImageBytes, "image/png")
            .addFile("reference_video", "reference.mp4", referenceVideoBytes, "video/mp4")
            .addField("character_orientation", "image")
            .addField("keep_original_sound", "false");

        byte[] body = mpBuilder.build();

        XeltaCraftMod.LOGGER.info("[MotionControl] POST {}/api/v1/motion-control/generate", baseUrl);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v1/motion-control/generate"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", mpBuilder.getContentType())
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .timeout(Duration.ofSeconds(timeout + 30))  // larger timeout for video upload
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                int status = response.statusCode();
                // Motion Control returns 202 Accepted on success
                if (status != 200 && status != 201 && status != 202) {
                    String msg = extractError(response.body());
                    throw new XeltaException("Motion Control submit failed (HTTP " + status + "): " + msg);
                }

                String sanitized = response.body().replace("'", "\"");
                JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                JsonObject data  = root.has("data") ? root.getAsJsonObject("data") : root;

                for (String key : new String[]{"job_id", "jobId", "id"}) {
                    if (data.has(key)) {
                        String jobId = data.get(key).getAsString();
                        XeltaCraftMod.LOGGER.info("[MotionControl] Job submitted: {}", jobId);
                        return jobId;
                    }
                }
                throw new XeltaApiClient.XeltaResponseException("No job_id in Motion Control response: " + response.body());
            });
    }

    // =========================================================
    // Download helper — fetches image from URL (Photo Slide → bytes)
    // Used to turn a stored image URL into bytes for upload
    // =========================================================

    /**
     * Downloads a URL and returns its bytes. Used to get the character image
     * from the URL stored in a Photo Slide before uploading to Motion Control.
     */
    public CompletableFuture<byte[]> downloadUrl(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new XeltaException("Failed to download character image (HTTP " + response.statusCode() + ")");
                }
                XeltaCraftMod.LOGGER.debug("[MotionControl] Downloaded {} bytes from {}", response.body().length, url);
                return response.body();
            });
    }

    // =========================================================
    // Helper
    // =========================================================

    private String extractError(String body) {
        try {
            String sanitized = body.replace("'", "\"");
            JsonObject root = JsonParser.parseString(sanitized).getAsJsonObject();
            for (String key : new String[]{"message", "detail", "msg", "error"}) {
                if (root.has(key)) return root.get(key).getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 150 ? body.substring(0, 150) : body;
    }
}

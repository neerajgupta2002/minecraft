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

/**
 * XeltaApiClient — Photo Lab (Image Generation)
 *
 * Real Xelta Photo Lab endpoint: https://bgremover.xelta.ai
 *
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  Generation Flow:                                           ║
 * ║  1. POST /api/ai-backgrounds/generate (multipart/form-data) ║
 * ║     Fields: file (PNG), prompt, negative_prompt             ║
 * ║     Returns: { "task_id": "..." }                           ║
 * ║                                                             ║
 * ║  2. Poll GET /api/tasks/status/{task_id}                    ║
 * ║     Returns: { "status": "processing|done|failed",          ║
 * ║               "progress": 0-100 }                           ║
 * ║                                                             ║
 * ║  3. Fetch GET /api/tasks/result/{task_id}                   ║
 * ║     Returns: { "data": { "result_url": "https://..." } }    ║
 * ║     ⚠️  Result is DELETED from server after this call!      ║
 * ╚══════════════════════════════════════════════════════════════╝
 */
public class XeltaApiClient {

    private static XeltaApiClient INSTANCE;
    public static XeltaApiClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaApiClient();
        return INSTANCE;
    }

    private final HttpClient httpClient;

    private XeltaApiClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // =========================================================
    // Step 1 — Submit generation job
    // POST /api/ai-backgrounds/generate
    // Returns task_id for polling
    // =========================================================

    /**
     * Submits an image generation request to Photo Lab.
     * Returns a task_id — pass it to {@link #pollTaskResult} to get the final URL.
     *
     * @param prompt        Text prompt for the AI background
     * @param token         JWT auth token
     * @param sourceImage   Optional source image bytes (null → sends 1×1 transparent placeholder)
     * @return CompletableFuture resolving to task_id string
     */
    public CompletableFuture<String> submitImageGeneration(
            String prompt, String token, byte[] sourceImage) {

        String baseUrl = XeltaCraftConfig.PHOTO_LAB_URL.get();
        int timeout    = XeltaCraftConfig.TIMEOUT_SECONDS.get();

        // Build multipart body
        byte[] imageData = (sourceImage != null && sourceImage.length > 0)
            ? sourceImage
            : MultipartBodyBuilder.TRANSPARENT_1X1_PNG;

        MultipartBodyBuilder mpBuilder = new MultipartBodyBuilder()
            .addFile("file", "image.png", imageData, "image/png")
            .addField("prompt", prompt)
            .addField("negative_prompt", XeltaCraftConfig.NEGATIVE_PROMPT.get());

        byte[] body = mpBuilder.build();

        if (XeltaCraftConfig.DEBUG_MODE.get()) {
            XeltaCraftMod.LOGGER.debug("[PhotoLab] POST {}/api/ai-backgrounds/generate", baseUrl);
            XeltaCraftMod.LOGGER.debug("[PhotoLab] prompt=\"{}\"", prompt);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/ai-backgrounds/generate"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", mpBuilder.getContentType())
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .timeout(Duration.ofSeconds(timeout))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                handleHttpErrors(response, "submitImageGeneration");

                JsonObject root = parseJson(response.body(), "submitImageGeneration");
                // Try common task_id field names
                for (String key : new String[]{"task_id", "taskId", "id", "job_id"}) {
                    if (root.has(key)) {
                        String taskId = root.get(key).getAsString();
                        XeltaCraftMod.LOGGER.info("[PhotoLab] Task submitted: {}", taskId);
                        return taskId;
                    }
                }
                // Sometimes it's nested under "data"
                if (root.has("data") && root.get("data").isJsonObject()) {
                    JsonObject data = root.getAsJsonObject("data");
                    for (String key : new String[]{"task_id", "taskId", "id"}) {
                        if (data.has(key)) return data.get(key).getAsString();
                    }
                }
                throw new XeltaResponseException("No task_id in response: " + response.body());
            });
    }

    // =========================================================
    // Step 2 — Poll task status
    // GET /api/tasks/status/{task_id}
    // =========================================================

    /**
     * Polls task status. Returns a string: "processing", "done", "failed", etc.
     * Also returns progress % if available (embedded in the string as JSON).
     *
     * Callers (XeltaJobPoller) should check for "done" before calling {@link #fetchTaskResult}.
     */
    public CompletableFuture<TaskStatusResponse> pollTaskStatus(String taskId, String token) {
        String baseUrl = XeltaCraftConfig.PHOTO_LAB_URL.get();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/tasks/status/" + taskId))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 404) {
                    return new TaskStatusResponse("failed", 0, "Task not found: " + taskId);
                }
                handleHttpErrors(response, "pollTaskStatus");
                return parseTaskStatus(response.body());
            })
            .exceptionally(e -> new TaskStatusResponse("failed", 0, e.getMessage()));
    }

    /** Parses the status response, tolerating various field layouts. */
    private TaskStatusResponse parseTaskStatus(String body) {
        try {
            // Handle Python-style single-quoted JSON that backend occasionally returns
            String sanitized = body.replace("'", "\"");
            JsonObject root = JsonParser.parseString(sanitized).getAsJsonObject();

            // Unwrap "data" if present
            JsonObject obj = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data")
                : root;

            String status   = getStringField(obj, "status", "processing");
            int    progress = obj.has("progress") ? obj.get("progress").getAsInt() : 0;
            String message  = getStringField(obj, "message", "");
            String error    = getStringField(obj, "error", "");

            return new TaskStatusResponse(status, progress,
                !error.isBlank() ? error : message);
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.warn("[PhotoLab] Could not parse status: {}", body);
            return new TaskStatusResponse("processing", 0, "");
        }
    }

    /** Immutable status snapshot. */
    public record TaskStatusResponse(String status, int progress, String message) {
        public boolean isDone()   { return "done".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status); }
        public boolean isFailed() { return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status); }
    }

    // =========================================================
    // Step 3 — Fetch task result
    // GET /api/tasks/result/{task_id}
    // ⚠️  DELETES the result from server — call only once!
    // =========================================================

    /**
     * Fetches the final image URL for a completed task.
     *
     * ⚠️ The Xelta backend deletes the result after this call.
     * Store the returned URL in the Photo Slide NBT immediately.
     */
    public CompletableFuture<String> fetchTaskResult(String taskId, String token) {
        String baseUrl = XeltaCraftConfig.PHOTO_LAB_URL.get();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/tasks/result/" + taskId))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                handleHttpErrors(response, "fetchTaskResult");
                XeltaCraftMod.LOGGER.debug("[PhotoLab] Result body: {}",
                    response.body().length() > 300 ? response.body().substring(0, 300) + "..." : response.body());

                JsonObject root = parseJson(response.body(), "fetchTaskResult");
                JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data")
                    : root;

                // Try all known URL field names
                for (String key : new String[]{"result_url", "output_url", "image_url", "url", "output"}) {
                    if (data.has(key) && !data.get(key).getAsString().isBlank()) {
                        String url = data.get(key).getAsString();
                        XeltaCraftMod.LOGGER.info("[PhotoLab] ✅ Got image URL: {}", url);
                        return url;
                    }
                }

                throw new XeltaResponseException("No image URL in result response: " + response.body());
            });
    }

    // =========================================================
    // Convenience — full flow (submit + built-in polling)
    // Delegates polling to XeltaJobPoller
    // =========================================================

    /**
     * Full generation pipeline: submit → poll → fetch result.
     * Progress updates are sent via the {@code onProgress} callback.
     *
     * @return CompletableFuture resolving to the final image URL
     */
    public CompletableFuture<String> generateImage(
            String prompt, String token, byte[] sourceImage,
            java.util.function.Consumer<String> onProgress) {

        return submitImageGeneration(prompt, token, sourceImage)
            .thenCompose(taskId -> {
                onProgress.accept("Submitted (task: " + taskId.substring(0, Math.min(8, taskId.length())) + "...)");
                XeltaJobPoller poller = new XeltaJobPoller(httpClient);
                return poller.pollPhotoLabTask(taskId, token, onProgress);
            })
            .thenCompose(taskId -> {
                onProgress.accept("Fetching result...");
                return fetchTaskResult(taskId, token);
            });
    }

    // =========================================================
    // Error handling helpers
    // =========================================================

    private void handleHttpErrors(HttpResponse<String> response, String context) {
        int status = response.statusCode();
        if (status == 200 || status == 201 || status == 202) return;

        String body = response.body();
        // Sanitize Python-style errors
        String sanitized = body.replace("'", "\"");
        String message = extractErrorMessage(sanitized);

        switch (status) {
            case 401 -> throw new XeltaApiClient.XeltaAuthException("Session expired. Run /xeltacraft settoken");
            case 402 -> throw new XeltaApiClient.XeltaCreditsException("Insufficient credits: " + message);
            case 403 -> throw new XeltaApiClient.XeltaAuthException("Access denied: " + message);
            case 404 -> throw new XeltaException("Endpoint not found [" + context + "]: " + message);
            case 429 -> {
                java.util.OptionalLong retry = response.headers().firstValueAsLong("Retry-After");
                int sec = retry.isPresent() ? (int) retry.getAsLong() : 10;
                throw new XeltaApiClient.XeltaRateLimitException(sec);
            }
            case 500, 502, 503 -> throw new XeltaApiClient.XeltaServerException("Xelta server error (HTTP " + status + ")");
            default -> throw new XeltaException("HTTP " + status + " from " + context + ": " + message);
        }
    }

    private String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (String key : new String[]{"message", "detail", "msg", "error"}) {
                if (root.has(key)) return root.get(key).getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 100 ? body.substring(0, 100) : body;
    }

    private JsonObject parseJson(String body, String context) {
        try {
            String sanitized = body.replace("'", "\"");
            return JsonParser.parseString(sanitized).getAsJsonObject();
        } catch (Exception e) {
            throw new XeltaResponseException("Non-JSON response in " + context + ": " +
                (body.length() > 200 ? body.substring(0, 200) : body));
        }
    }

    private String getStringField(JsonObject obj, String key, String defaultVal) {
        return obj.has(key) ? obj.get(key).getAsString() : defaultVal;
    }

    // =========================================================
    // Error Messages
    // =========================================================

    public static String toUserMessage(Throwable cause) {
        if (cause == null) return "Unknown error";
        if (cause instanceof XeltaAuthException)          return "Token expired/invalid. Run /xeltacraft settoken";
        if (cause instanceof XeltaRateLimitException rle) return "Rate limited. Retrying in " + rle.getRetryAfterSeconds() + "s";
        if (cause instanceof XeltaCreditsException)       return "Not enough credits. Visit xelta.ai/pricing";
        if (cause instanceof XeltaResponseException)      return "Server returned invalid response";
        if (cause instanceof XeltaServerException)        return "Xelta servers are down. Try again later.";
        if (cause instanceof java.net.http.HttpTimeoutException) return "Request timed out. Check internet.";
        if (cause instanceof java.net.UnknownHostException)      return "Cannot reach Xelta. Check internet.";
        if (cause instanceof java.net.ConnectException)          return "Connection refused. Xelta may be offline.";
        return "Error: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
    }

    // =========================================================
    // Exception Types
    // =========================================================
    public static class XeltaAuthException extends XeltaException {
        public XeltaAuthException(String msg) { super(msg); }
    }
    public static class XeltaRateLimitException extends XeltaException {
        private final int retryAfterSeconds;
        public XeltaRateLimitException(int s) { super("Rate limited"); retryAfterSeconds = s; }
        public int getRetryAfterSeconds() { return retryAfterSeconds; }
    }
    public static class XeltaCreditsException extends XeltaException {
        public XeltaCreditsException(String msg) { super(msg); }
    }
    public static class XeltaResponseException extends XeltaException {
        public XeltaResponseException(String msg) { super(msg); }
        public XeltaResponseException(String msg, Throwable cause) { super(msg, cause); }
    }
    public static class XeltaServerException extends XeltaException {
        public XeltaServerException(String msg) { super(msg); }
    }
}

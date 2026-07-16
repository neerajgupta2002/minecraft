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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * XeltaJobPoller
 *
 * Polls Xelta async jobs to completion. Supports two modes:
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │ PHOTO LAB MODE (for image generation)                   │
 * │   GET https://bgremover.xelta.ai/api/tasks/status/{id}  │
 * │   Poll every 3s, max 3 minutes                          │
 * │   → returns task_id when done (for result fetch)        │
 * │                                                         │
 * │ MOTION CONTROL MODE (for video generation)              │
 * │   GET https://advavatar.stg.xelta.ai/                   │
 * │       api/v1/motion-control/results/{job_id}            │
 * │   Poll every 5s, max 6 minutes                          │
 * │   → returns video_url directly when done                │
 * └─────────────────────────────────────────────────────────┘
 */
public class XeltaJobPoller {

    // Photo Lab task polling config
    private static final int PHOTO_INTERVAL_MS   = 3_000;
    private static final int PHOTO_MAX_ATTEMPTS  = 60;   // 3 minutes

    // Motion Control job polling config
    private static final int MOTION_INTERVAL_MS  = 5_000;
    private static final int MOTION_MAX_ATTEMPTS = 72;   // 6 minutes

    private static final int MAX_NETWORK_RETRIES = 3;

    private final HttpClient httpClient;

    public XeltaJobPoller(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // =========================================================
    // Photo Lab Task Polling
    // GET /api/tasks/status/{task_id}
    // Returns task_id when done (caller then calls fetchTaskResult)
    // =========================================================

    public CompletableFuture<String> pollPhotoLabTask(
            String taskId, String token, Consumer<String> onProgress) {

        AtomicInteger attempts = new AtomicInteger(0);

        return CompletableFuture.supplyAsync(() -> {
            while (attempts.getAndIncrement() < PHOTO_MAX_ATTEMPTS) {
                int attempt = attempts.get();
                int pct     = (attempt * 100) / PHOTO_MAX_ATTEMPTS;

                // Poll with network retry
                XeltaApiClient.TaskStatusResponse status =
                    pollPhotoWithRetry(taskId, token, MAX_NETWORK_RETRIES);

                if (status == null) {
                    throw new XeltaException("Lost connection to Photo Lab during polling");
                }

                onProgress.accept("Generating... " + Math.max(pct, status.progress()) + "%");

                if (status.isDone()) {
                    XeltaCraftMod.LOGGER.info("[PhotoLab] Task {} completed after {}s",
                        taskId, attempt * (PHOTO_INTERVAL_MS / 1000));
                    return taskId; // caller fetches result
                }

                if (status.isFailed()) {
                    String msg = status.message().isBlank() ? "Generation failed" : status.message();
                    throw new XeltaException("Photo Lab task failed: " + msg);
                }

                sleep(PHOTO_INTERVAL_MS);
            }

            throw new XeltaJobPoller.XeltaTaskTimeoutException(taskId, "photo");
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private XeltaApiClient.TaskStatusResponse pollPhotoWithRetry(
            String taskId, String token, int maxRetries) {

        for (int i = 0; i <= maxRetries; i++) {
            try {
                return XeltaApiClient.getInstance()
                    .pollTaskStatus(taskId, token)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new XeltaException("Polling interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.warn("[PhotoLab] Poll retry {}/{}: {}", i + 1, maxRetries + 1, e.getMessage());
                if (i < maxRetries) sleep(2_000);
            }
        }
        return null;
    }

    // =========================================================
    // Motion Control Job Polling
    // GET /api/v1/motion-control/results/{job_id}
    // Returns video_url when done
    // =========================================================

    public CompletableFuture<String> pollMotionControlJob(
            String jobId, String token, Consumer<String> onProgress) {

        AtomicInteger attempts = new AtomicInteger(0);
        String baseUrl = XeltaCraftConfig.MOTION_CONTROL_URL.get();

        return CompletableFuture.supplyAsync(() -> {
            while (attempts.getAndIncrement() < MOTION_MAX_ATTEMPTS) {
                int attempt = attempts.get();
                int pct     = (attempt * 100) / MOTION_MAX_ATTEMPTS;
                onProgress.accept("Processing video... " + pct + "%");

                JsonObject result = pollMotionWithRetry(baseUrl, jobId, token, MAX_NETWORK_RETRIES);
                if (result == null) {
                    throw new XeltaException("Lost connection to Motion Control during polling");
                }

                // Unwrap data if present
                JsonObject data = result.has("data") && result.get("data").isJsonObject()
                    ? result.getAsJsonObject("data")
                    : result;

                String status = data.has("status") ? data.get("status").getAsString() : "processing";

                switch (status.toLowerCase()) {
                    case "done", "completed", "success" -> {
                        // Extract video URL from known field names
                        for (String key : new String[]{"video_url", "output_url", "result_url", "url"}) {
                            if (data.has(key) && !data.get(key).getAsString().isBlank()) {
                                String url = data.get(key).getAsString();
                                XeltaCraftMod.LOGGER.info("[MotionControl] ✅ Video ready: {}", url);
                                return url;
                            }
                        }
                        throw new XeltaException("Video done but no URL in response");
                    }
                    case "failed", "error" -> {
                        String msg = data.has("message") ? data.get("message").getAsString() : "Unknown";
                        throw new XeltaException("Motion Control job failed: " + msg);
                    }
                    default -> {
                        XeltaCraftMod.LOGGER.debug("[MotionControl] Job {} status: {} (attempt {}/{})",
                            jobId, status, attempt, MOTION_MAX_ATTEMPTS);
                        sleep(MOTION_INTERVAL_MS);
                    }
                }
            }

            throw new XeltaJobPoller.XeltaTaskTimeoutException(jobId, "motion-control");
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private JsonObject pollMotionWithRetry(String baseUrl, String jobId, String token, int maxRetries) {
        String url = baseUrl + "/api/v1/motion-control/results/" + jobId;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 202) {
                    String sanitized = response.body().replace("'", "\"");
                    return JsonParser.parseString(sanitized).getAsJsonObject();
                }

                XeltaCraftMod.LOGGER.warn("[MotionControl] Poll HTTP {} for job {}", response.statusCode(), jobId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new XeltaException("Motion Control poll interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.warn("[MotionControl] Poll retry {}/{}: {}", i + 1, maxRetries + 1, e.getMessage());
                if (i < maxRetries) sleep(2_000);
            }
        }
        return null;
    }

    // =========================================================
    // Utility
    // =========================================================

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================
    // Timeout Exception
    // =========================================================

    public static class XeltaTaskTimeoutException extends XeltaException {
        private final String taskId;
        private final String service;

        public XeltaTaskTimeoutException(String taskId, String service) {
            super("Task timed out [" + service + "]: " + taskId);
            this.taskId  = taskId;
            this.service = service;
        }

        public String getTaskId()  { return taskId; }
        public String getService() { return service; }
    }
}

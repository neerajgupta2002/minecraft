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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * XeltaMixClient — Style Transfer / Mixboard Generation (Phase 7b)
 *
 * Real endpoint: https://mxboard.stg.xelta.ai
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  Full Flow:                                                      ║
 * ║                                                                  ║
 * ║  1. POST /api/mixboards/  (JSON)                                 ║
 * ║     Body: { "command": "style prompt", "num_images": 8 }        ║
 * ║     → Returns board_id                                           ║
 * ║                                                                  ║
 * ║  2. Poll GET /api/mixboards/{board_id}/progress                  ║
 * ║     → { "progress_percent": 0-100 }                              ║
 * ║     When 100 → fetch result                                      ║
 * ║                                                                  ║
 * ║  3. GET /api/mixboards/{board_id}                                ║
 * ║     → { "data": { "images": [ {"url": "..."}, ... ] } }         ║
 * ║     Up to 8 style-transfer variations                            ║
 * ║                                                                  ║
 * ║  4. (Optional) Refine one image:                                 ║
 * ║     POST /api/mixboards/{board_id}/refine/{position}            ║
 * ║     Body: { "refinement_text": "...", "num_variations": 3 }     ║
 * ║                                                                  ║
 * ║  In-game usage:                                                  ║
 * ║    MixboardItem in output slot → triggers Xelta Mix mode        ║
 * ║    Prompt field = style command                                  ║
 * ║    Optional: Photo Slide in input = source image reference       ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class XeltaMixClient {

    private static XeltaMixClient INSTANCE;
    public static XeltaMixClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaMixClient();
        return INSTANCE;
    }

    // Polling constants
    private static final int POLL_INTERVAL_MS  = 3_000;  // 3 seconds
    private static final int MAX_ATTEMPTS      = 100;    // 5 minutes

    private final HttpClient httpClient;

    private XeltaMixClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // =========================================================
    // Full pipeline: submit → poll progress → fetch all images
    // =========================================================

    /**
     * Runs the full Xelta Mix pipeline.
     *
     * @param command     Style prompt / command
     * @param numImages   How many variations to generate (typically 8)
     * @param token       JWT auth token
     * @param onProgress  Progress callback
     * @return CompletableFuture resolving to a List of image URLs (up to numImages)
     */
    public CompletableFuture<List<String>> generateMixboard(
            String command, int numImages, String token, Consumer<String> onProgress) {

        return submitBoard(command, numImages, token, onProgress)
            .thenCompose(boardId -> {
                onProgress.accept("Board submitted (" + boardId.substring(0, Math.min(8, boardId.length())) + "...)");
                return pollProgress(boardId, token, onProgress)
                    .thenCompose(done -> fetchBoardImages(boardId, token));
            });
    }

    // =========================================================
    // Step 1 — Submit board
    // POST /api/mixboards/  (application/json)
    // =========================================================

    private CompletableFuture<String> submitBoard(
            String command, int numImages, String token, Consumer<String> onProgress) {

        String baseUrl = XeltaCraftConfig.XELTA_MIX_URL.get();
        int timeout    = XeltaCraftConfig.TIMEOUT_SECONDS.get();

        onProgress.accept("Submitting style board...");

        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("command",    command);
        bodyObj.addProperty("num_images", numImages);
        String bodyJson = bodyObj.toString();

        XeltaCraftMod.LOGGER.info("[XeltaMix] POST {}/api/mixboards/ command=\"{}\"", baseUrl, command);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/mixboards/"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(timeout))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                int status = response.statusCode();
                if (status != 200 && status != 201 && status != 202) {
                    throw new XeltaException("XeltaMix submit failed (HTTP " + status + "): " + extractError(response.body()));
                }

                String sanitized = response.body().replace("'", "\"");
                JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                JsonObject data  = root.has("data") ? root.getAsJsonObject("data") : root;

                for (String key : new String[]{"board_id", "boardId", "id"}) {
                    if (data.has(key)) {
                        String boardId = data.get(key).getAsString();
                        XeltaCraftMod.LOGGER.info("[XeltaMix] Board submitted: {}", boardId);
                        return boardId;
                    }
                }
                throw new XeltaApiClient.XeltaResponseException("No board_id in XeltaMix response: " + response.body());
            });
    }

    // =========================================================
    // Step 2 — Poll progress
    // GET /api/mixboards/{board_id}/progress
    // =========================================================

    private CompletableFuture<Void> pollProgress(String boardId, String token, Consumer<String> onProgress) {
        String baseUrl = XeltaCraftConfig.XELTA_MIX_URL.get();
        AtomicInteger attempts = new AtomicInteger(0);

        return CompletableFuture.runAsync(() -> {
            while (attempts.getAndIncrement() < MAX_ATTEMPTS) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/mixboards/" + boardId + "/progress"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                    HttpResponse<String> response =
                        httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        String sanitized = response.body().replace("'", "\"");
                        JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                        JsonObject data  = root.has("data") ? root.getAsJsonObject("data") : root;

                        int pct = data.has("progress_percent") ? data.get("progress_percent").getAsInt()
                                : data.has("progress")         ? data.get("progress").getAsInt()
                                : (attempts.get() * 100 / MAX_ATTEMPTS);

                        onProgress.accept("Generating style variations... " + pct + "%");

                        if (pct >= 100) {
                            XeltaCraftMod.LOGGER.info("[XeltaMix] Board {} complete", boardId);
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new XeltaException("XeltaMix poll interrupted");
                } catch (XeltaException xe) {
                    throw xe;
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[XeltaMix] Progress poll error: {}", e.getMessage());
                }

                try { Thread.sleep(POLL_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            // If we exceed MAX_ATTEMPTS just continue — board might still be usable
            XeltaCraftMod.LOGGER.warn("[XeltaMix] Progress polling timed out for board {}, fetching anyway", boardId);
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // =========================================================
    // Step 3 — Fetch complete board
    // GET /api/mixboards/{board_id}
    // =========================================================

    public CompletableFuture<List<String>> fetchBoardImages(String boardId, String token) {
        String baseUrl = XeltaCraftConfig.XELTA_MIX_URL.get();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/mixboards/" + boardId))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                List<String> urls = new ArrayList<>();
                if (response.statusCode() != 200) {
                    XeltaCraftMod.LOGGER.warn("[XeltaMix] Board fetch HTTP {}", response.statusCode());
                    return urls;
                }
                try {
                    String sanitized = response.body().replace("'", "\"");
                    JsonObject root  = JsonParser.parseString(sanitized).getAsJsonObject();
                    JsonObject data  = root.has("data") ? root.getAsJsonObject("data") : root;

                    // Try common image array field names
                    JsonArray images = null;
                    for (String key : new String[]{"images", "results", "variations", "items"}) {
                        if (data.has(key) && data.get(key).isJsonArray()) {
                            images = data.getAsJsonArray(key);
                            break;
                        }
                    }

                    if (images != null) {
                        for (int i = 0; i < images.size(); i++) {
                            var elem = images.get(i);
                            String url = elem.isJsonObject()
                                ? getFirstUrl(elem.getAsJsonObject())
                                : elem.getAsString();
                            if (url != null && !url.isBlank()) urls.add(url);
                        }
                    }

                    XeltaCraftMod.LOGGER.info("[XeltaMix] ✅ Board {} has {} images", boardId, urls.size());
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[XeltaMix] Board parse error: {}", e.getMessage());
                }
                return urls;
            });
    }

    // =========================================================
    // Step 4 (Optional) — Refine a specific image
    // POST /api/mixboards/{board_id}/refine/{position}
    // =========================================================

    /**
     * Refines a specific image within an existing Mixboard.
     * Call this when the player wants variations of a particular style result.
     *
     * @param boardId       The board containing the image to refine
     * @param position      0-indexed position of the image to refine
     * @param refinementText  What to change about the image
     * @param numVariations  How many variations to produce (max 3)
     * @param token         JWT auth token
     * @return CompletableFuture resolving to list of refined image URLs
     */
    public CompletableFuture<List<String>> refineImage(
            String boardId, int position, String refinementText,
            int numVariations, String token) {

        String baseUrl = XeltaCraftConfig.XELTA_MIX_URL.get();

        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("refinement_text", refinementText);
        bodyObj.addProperty("num_variations",  Math.min(numVariations, 3));
        String bodyJson = bodyObj.toString();

        XeltaCraftMod.LOGGER.info("[XeltaMix] Refining board {} position {} with: {}", boardId, position, refinementText);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/mixboards/" + boardId + "/refine/" + position))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(30))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                List<String> urls = new ArrayList<>();
                if (response.statusCode() != 200 && response.statusCode() != 202) {
                    XeltaCraftMod.LOGGER.warn("[XeltaMix] Refine HTTP {}", response.statusCode());
                    return urls;
                }
                try {
                    JsonObject root = JsonParser.parseString(response.body().replace("'", "\"")).getAsJsonObject();
                    JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
                    for (String key : new String[]{"images", "results", "variations"}) {
                        if (data.has(key) && data.get(key).isJsonArray()) {
                            JsonArray arr = data.getAsJsonArray(key);
                            for (int i = 0; i < arr.size(); i++) {
                                String u = arr.get(i).isJsonObject()
                                    ? getFirstUrl(arr.get(i).getAsJsonObject())
                                    : arr.get(i).getAsString();
                                if (u != null && !u.isBlank()) urls.add(u);
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[XeltaMix] Refine parse error: {}", e.getMessage());
                }
                return urls;
            })
            .exceptionally(e -> List.of());
    }

    // =========================================================
    // Helpers
    // =========================================================

    private String getFirstUrl(JsonObject obj) {
        for (String key : new String[]{"url", "image_url", "src", "href"}) {
            if (obj.has(key)) return obj.get(key).getAsString();
        }
        return null;
    }

    private String extractError(String body) {
        try {
            JsonObject root = JsonParser.parseString(body.replace("'", "\"")).getAsJsonObject();
            for (String k : new String[]{"message", "detail", "msg", "error"}) {
                if (root.has(k)) return root.get(k).getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 150 ? body.substring(0, 150) : body;
    }
}

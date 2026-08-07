package ai.xelta.craft.sdk;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.MultipartBodyBuilder;
import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.infra.cache.XeltaFileStorage;
import ai.xelta.craft.infra.http.XeltaHttpClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * XeltaUploadClient — SDK Layer
 *
 * Unified upload abstraction extracted from inline API client code.
 *
 * Supports:
 *  1. Upload a local file path → returns asset ID / URL
 *  2. Upload from a URL (download first, then upload)
 *  3. Direct multipart upload using MultipartBodyBuilder
 *
 * Upload endpoint (Photo Lab): POST https://bgremover.xelta.ai/api/ai-backgrounds/upload
 * Upload endpoint (Motion Control): POST https://advavatar.stg.xelta.ai/api/v1/assets/upload
 *
 * This client handles the raw upload; generation job creation is handled by
 * the workflow classes.
 */
public class XeltaUploadClient {

    private final XeltaHttpClient     http;
    private final XeltaSessionManager session;
    private final XeltaFileStorage    storage;
    private final XeltaAssetCache     cache;

    public XeltaUploadClient(XeltaHttpClient http, XeltaSessionManager session,
                             XeltaFileStorage storage, XeltaAssetCache cache) {
        this.http    = http;
        this.session = session;
        this.storage = storage;
        this.cache   = cache;
    }

    // ── Upload a local image file ─────────────────────────────────────────────

    /**
     * Uploads a local image file to the Xelta Photo Lab upload endpoint.
     *
     * @param localPath Absolute path to the image file on disk
     * @param baseUrl   Service base URL (e.g., "https://bgremover.xelta.ai")
     * @return future resolving to the UploadResult
     */
    public CompletableFuture<UploadResult> uploadLocalImage(Path localPath, String baseUrl) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.exists(localPath)) {
                return UploadResult.failure("File not found: " + localPath);
            }

            String token = session.getAccessToken();
            String uploadId = UUID.randomUUID().toString();

            try {
                byte[] imageBytes = Files.readAllBytes(localPath);
                String filename = localPath.getFileName().toString();
                String mimeType = guessMimeType(filename);

                MultipartBodyBuilder builder = new MultipartBodyBuilder()
                    .addFile("file", filename, imageBytes, mimeType);

                byte[] body = builder.build();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai-backgrounds/upload"))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "XeltaCraft-Mod/1.0.0 (NeoForge)")
                    .header("Content-Type", builder.getContentType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

                HttpResponse<String> response = http.raw()
                    .send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    return parseUploadResponse(response.body());
                } else {
                    XeltaCraftMod.LOGGER.warn("[Upload] HTTP {} from upload: {}", response.statusCode(), response.body());
                    return UploadResult.failure("Upload failed (HTTP " + response.statusCode() + ")");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return UploadResult.failure("Upload interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[Upload] Error uploading {}: {}", localPath, e.getMessage());
                return UploadResult.failure("Upload error: " + e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Convenience overload for String path.
     */
    public CompletableFuture<UploadResult> uploadLocalImage(String path, String baseUrl) {
        return uploadLocalImage(Path.of(path), baseUrl);
    }

    // ── Download a remote URL and then upload it ──────────────────────────────

    /**
     * Downloads a remote asset, saves it locally, and uploads it to the target service.
     * Used for reference images and reference videos in motion generation.
     *
     * @param remoteUrl Remote URL to download from
     * @param baseUrl   Service base URL to upload to
     * @param type      Asset type (IMAGE or VIDEO)
     * @return future resolving to UploadResult
     */
    public CompletableFuture<UploadResult> downloadThenUpload(
            String remoteUrl, String baseUrl, XeltaAssetCache.AssetType type) {

        String tempId = "temp_" + UUID.randomUUID();
        return storage.downloadAndSave(remoteUrl, tempId, type)
            .thenCompose(pathOpt -> {
                if (pathOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(
                        UploadResult.failure("Could not download reference asset: " + remoteUrl));
                }
                return uploadLocalImage(pathOpt.get(), baseUrl);
            });
    }

    // ── Parse upload response ─────────────────────────────────────────────────

    private UploadResult parseUploadResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data")
                : root;

            // Extract asset ID from various possible field names
            String assetId  = str(data, "asset_id", "id", "file_id", "upload_id");
            String assetUrl = str(data, "url", "file_url", "asset_url", "public_url");

            if (assetId != null && !assetId.isBlank()) {
                return UploadResult.success(assetId, assetUrl != null ? assetUrl : "");
            }
            if (assetUrl != null && !assetUrl.isBlank()) {
                return UploadResult.success(assetUrl, assetUrl); // URL doubles as ID
            }

            XeltaCraftMod.LOGGER.warn("[Upload] Response missing asset ID/URL: {}", body);
            return UploadResult.failure("Upload response missing asset ID.");
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.warn("[Upload] Parse error: {}", e.getMessage());
            return UploadResult.failure("Could not parse upload response.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                String val = obj.get(key).getAsString();
                if (!val.isBlank()) return val;
            }
        }
        return null;
    }

    private static String guessMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".mp4"))  return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    // ── Result Types ──────────────────────────────────────────────────────────

    public record UploadResult(
        boolean success,
        String assetId,
        String assetUrl,
        String errorMessage
    ) {
        public static UploadResult success(String id, String url) {
            return new UploadResult(true, id, url, "");
        }
        public static UploadResult failure(String message) {
            return new UploadResult(false, "", "", message);
        }
    }
}

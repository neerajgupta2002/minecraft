package ai.xelta.craft.workflow;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.XeltaMotionClient;
import ai.xelta.craft.config.XeltaCraftConfig;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.sdk.XeltaSDK;
import ai.xelta.craft.sdk.XeltaUploadClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * VideoGenerationWorkflow — Application Layer
 *
 * End-to-end workflow for Motion Control video generation:
 *
 *   1. Validate inputs (prompt, reference image path)
 *   2. Download reference video from config URL
 *   3. Upload reference image + reference video as multipart
 *   4. Submit motion control job → receive job_id
 *   5. Poll until complete
 *   6. Download result video to local cache
 *   7. Notify caller with local file path
 */
public class VideoGenerationWorkflow {

    private static VideoGenerationWorkflow INSTANCE;

    public static VideoGenerationWorkflow getInstance() {
        if (INSTANCE == null) INSTANCE = new VideoGenerationWorkflow();
        return INSTANCE;
    }

    private VideoGenerationWorkflow() {}

    // ── Input Model ───────────────────────────────────────────────────────────

    public record VideoGenerationRequest(
        String prompt,
        String referenceImagePath,   // local path to reference image
        String referenceVideoUrl,    // URL of reference motion video (from config)
        String token
    ) {
        public String effectiveToken() {
            if (token != null && !token.isBlank()) return token;
            return XeltaSDK.get().token();
        }
        public String effectiveRefVideoUrl() {
            if (referenceVideoUrl != null && !referenceVideoUrl.isBlank()) return referenceVideoUrl;
            return XeltaCraftConfig.REFERENCE_VIDEO_URL.get();
        }
    }

    // ── Result Model ──────────────────────────────────────────────────────────

    public record VideoGenerationResult(
        boolean success,
        String localFilePath,
        String remoteUrl,
        String jobId,
        String errorMessage
    ) {
        public static VideoGenerationResult success(String local, String remote, String jobId) {
            return new VideoGenerationResult(true, local, remote, jobId, "");
        }
        public static VideoGenerationResult failure(String message) {
            return new VideoGenerationResult(false, "", "", "", message);
        }
    }

    // ── Run Workflow ──────────────────────────────────────────────────────────

    public CompletableFuture<VideoGenerationResult> run(
            VideoGenerationRequest request,
            Consumer<String> onProgress) {

        String token = request.effectiveToken();

        if (token.isBlank()) {
            return CompletableFuture.completedFuture(
                VideoGenerationResult.failure("Not authenticated. Please sign in first."));
        }
        if (request.referenceImagePath().isBlank()) {
            return CompletableFuture.completedFuture(
                VideoGenerationResult.failure("A reference image is required for video generation."));
        }

        onProgress.accept("Starting video generation...");
        XeltaCraftMod.LOGGER.info("[VideoWorkflow] Starting for image: {}", request.referenceImagePath());

        XeltaMotionClient motionClient = XeltaMotionClient.getInstance();

        // ── Step 2: Load reference image bytes from disk ──────────────────────
        byte[] charImageBytes;
        try {
            charImageBytes = java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(request.referenceImagePath()));
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.error("[VideoWorkflow] Cannot read reference image: {}", e.getMessage());
            return CompletableFuture.completedFuture(
                VideoGenerationResult.failure("Could not read reference image: " + e.getMessage()));
        }

        // ── Step 3: Download reference video bytes from URL ───────────────────
        onProgress.accept("Downloading reference video...");
        return motionClient.downloadUrl(request.effectiveRefVideoUrl())
            .thenCompose(refVideoBytes -> {
                onProgress.accept("Submitting video generation job...");
                XeltaCraftMod.LOGGER.info("[VideoWorkflow] Starting for image path: {}", request.referenceImagePath());

                // ── Steps 4–6: submit + poll + download ───────────────────────
                return motionClient.generateVideo(charImageBytes, refVideoBytes, token, onProgress)
                    .thenCompose(videoUrl -> {
                        if (videoUrl == null || videoUrl.isBlank()) {
                            throw new RuntimeException("Video generation produced no output URL.");
                        }

                        onProgress.accept("Video ready! Downloading...");
                        XeltaCraftMod.LOGGER.info("[VideoWorkflow] Video URL: {}", videoUrl);

                        return XeltaSDK.get().storage()
                            .downloadAndSave(videoUrl, "video_" + System.currentTimeMillis(),
                                XeltaAssetCache.AssetType.VIDEO)
                            .thenApply(pathOpt -> {
                                String localPath = pathOpt
                                    .map(p -> p.toAbsolutePath().toString())
                                    .orElse("");

                                XeltaSDK.get().cache().put(
                                    localPath, localPath,
                                    XeltaAssetCache.AssetType.VIDEO,
                                    request.prompt());

                                onProgress.accept("✓ Done! Video ready.");
                                return VideoGenerationResult.success(localPath, videoUrl, localPath);
                            });
                    });
            })
            .exceptionally(e -> {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                XeltaCraftMod.LOGGER.error("[VideoWorkflow] Failed: {}", msg);
                return VideoGenerationResult.failure(msg != null ? msg : "Unknown error during video generation.");
            });
    }
}

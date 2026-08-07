package ai.xelta.craft.workflow;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.XeltaApiClient;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.infra.cache.XeltaFileStorage;
import ai.xelta.craft.sdk.XeltaSDK;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ImageGenerationWorkflow — Application Layer
 *
 * End-to-end workflow for Photo Lab image generation:
 *
 *   1. Validate inputs
 *   2. Upload reference image (optional)
 *   3. Submit generation job → receive task_id
 *   4. Poll until complete (via XeltaJobPoller)
 *   5. Fetch result URL
 *   6. Download result to local cache
 *   7. Notify caller with local file path
 *
 * This class decouples the UI from the API clients.
 * The Terminal Screen calls this workflow and receives progress updates
 * and the final result via callbacks.
 */
public class ImageGenerationWorkflow {

    private static ImageGenerationWorkflow INSTANCE;

    public static ImageGenerationWorkflow getInstance() {
        if (INSTANCE == null) INSTANCE = new ImageGenerationWorkflow();
        return INSTANCE;
    }

    private ImageGenerationWorkflow() {}

    // ── Input Model ───────────────────────────────────────────────────────────

    public record ImageGenerationRequest(
        String prompt,
        String negativePrompt,
        String referenceImagePath,  // local disk path, may be blank
        String token                // auth token (falls back to session if blank)
    ) {
        /** True if a reference image should be included in the request. */
        public boolean hasReferenceImage() {
            return referenceImagePath != null && !referenceImagePath.isBlank();
        }

        /** Returns the effective auth token (session → manual fallback). */
        public String effectiveToken() {
            if (token != null && !token.isBlank()) return token;
            return XeltaSDK.get().token();
        }
    }

    // ── Result Model ──────────────────────────────────────────────────────────

    public record ImageGenerationResult(
        boolean success,
        String localFilePath,   // absolute path to downloaded image
        String remoteUrl,       // original CDN URL from Xelta
        String taskId,
        String errorMessage
    ) {
        public static ImageGenerationResult success(String local, String remote, String taskId) {
            return new ImageGenerationResult(true, local, remote, taskId, "");
        }
        public static ImageGenerationResult failure(String message) {
            return new ImageGenerationResult(false, "", "", "", message);
        }
    }

    // ── Run Workflow ──────────────────────────────────────────────────────────

    /**
     * Executes the full image generation workflow asynchronously.
     *
     * @param request       Generation parameters
     * @param onProgress    Progress callback (called from background thread)
     * @return future resolving to the result
     */
    public CompletableFuture<ImageGenerationResult> run(
            ImageGenerationRequest request,
            Consumer<String> onProgress) {

        String token = request.effectiveToken();

        // ── Step 1: Validate ──────────────────────────────────────────────────
        if (request.prompt().isBlank()) {
            return CompletableFuture.completedFuture(
                ImageGenerationResult.failure("Prompt cannot be empty."));
        }
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(
                ImageGenerationResult.failure("Not authenticated. Please sign in first."));
        }

        onProgress.accept("Starting image generation...");
        XeltaCraftMod.LOGGER.info("[ImageWorkflow] Starting: prompt='{}', hasRef={}",
            request.prompt(), request.hasReferenceImage());

        XeltaApiClient api = XeltaApiClient.getInstance();

        // ── Load reference image bytes if provided ────────────────────────────
        byte[] refImageBytes = null;
        if (request.hasReferenceImage()) {
            try {
                refImageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(request.referenceImagePath()));
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.warn("[ImageWorkflow] Could not load reference image: {}", e.getMessage());
            }
        }
        final byte[] finalRefBytes = refImageBytes;

        // ── Steps 2–6 chained as async pipeline ──────────────────────────────
        return api.submitImageGeneration(request.prompt(), token, finalRefBytes, "", "")
            .thenCompose(taskId -> {
                onProgress.accept("Job submitted (ID: " + taskId + "). Polling...");
                XeltaCraftMod.LOGGER.info("[ImageWorkflow] Task ID: {}", taskId);

                // ── Step 4: Poll ──────────────────────────────────────────────
                return XeltaSDK.get().jobPoller()
                    .pollPhotoLabTask(taskId, token, onProgress)
                    .thenCompose(completedId -> {
                        onProgress.accept("Generation complete! Fetching result...");

                        // ── Step 5: Fetch result URL ──────────────────────────
                        return api.fetchTaskResult(completedId, token)
                            .thenCompose(resultUrl -> {
                                if (resultUrl == null || resultUrl.isBlank()) {
                                    throw new RuntimeException("Generation produced no output URL.");
                                }

                                onProgress.accept("Downloading image...");
                                XeltaCraftMod.LOGGER.info("[ImageWorkflow] Result URL: {}", resultUrl);

                                // ── Step 6: Download to cache ─────────────────
                                return XeltaSDK.get().storage()
                                    .downloadAndSave(resultUrl, completedId, XeltaAssetCache.AssetType.IMAGE)
                                    .thenApply(pathOpt -> {
                                        String localPath = pathOpt
                                            .map(p -> p.toAbsolutePath().toString())
                                            .orElse("");

                                        XeltaSDK.get().cache().put(
                                            completedId, localPath,
                                            XeltaAssetCache.AssetType.IMAGE,
                                            request.prompt());

                                        onProgress.accept("✓ Done! Image ready.");
                                        return ImageGenerationResult.success(localPath, resultUrl, completedId);
                                    });
                            });
                    });
            })
            .exceptionally(e -> {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                XeltaCraftMod.LOGGER.error("[ImageWorkflow] Failed: {}", msg);
                return ImageGenerationResult.failure(msg != null ? msg : "Unknown error during generation.");
            });
    }
}

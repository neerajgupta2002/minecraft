package ai.xelta.craft.controller;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.XeltaGenAvatarClient;
import ai.xelta.craft.api.XeltaMixClient;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.sdk.XeltaSDK;
import ai.xelta.craft.workflow.ImageGenerationWorkflow;
import ai.xelta.craft.workflow.VideoGenerationWorkflow;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * GenerationController — Application Layer
 *
 * Routes generation requests from the UI to the appropriate workflow or API client.
 * The Terminal Screen calls this controller; it never calls API clients directly.
 *
 * Supported services:
 *   0 IMAGE  → ImageGenerationWorkflow (Photo Lab)
 *   1 VIDEO  → VideoGenerationWorkflow (Motion Control)
 *   2 AVATAR → XeltaGenAvatarClient
 *   3 MIX    → XeltaMixClient
 *   4 BUILD  → GeminiArchitectClient (handled by BlockEntity/Command)
 *
 * The Super Computer (Nexus Deep Agent backend) is a separate, chat-based generation path
 * with its own skill routing and mid-turn approval flow — it's driven directly from
 * SuperComputerTab via {@link ai.xelta.craft.workflow.SuperComputerWorkflow}, not through
 * this numeric-service dispatcher.
 */
public class GenerationController {

    private static GenerationController INSTANCE;

    public static GenerationController getInstance() {
        if (INSTANCE == null) INSTANCE = new GenerationController();
        return INSTANCE;
    }

    private GenerationController() {}

    // ── Service Index ─────────────────────────────────────────────────────────
    public static final int SERVICE_IMAGE       = 0;
    public static final int SERVICE_VIDEO       = 1;
    public static final int SERVICE_AVATAR      = 2;
    public static final int SERVICE_MIX         = 3;
    public static final int SERVICE_BUILD       = 4;

    // ── Unified Generation Request ────────────────────────────────────────────

    public record GenerationRequest(
        int    serviceIndex,
        String prompt,
        String negativePrompt,
        String referenceImagePath,
        String referenceVideoUrl,
        int    slideSizeIndex,       // for BUILD mode
        String token
    ) {}

    // ── Unified Result ────────────────────────────────────────────────────────

    public record GenerationResult(
        boolean success,
        String localFilePath,
        String remoteUrl,
        String taskId,
        String errorMessage,
        int    serviceIndex
    ) {
        public static GenerationResult success(int svc, String local, String remote, String id) {
            return new GenerationResult(true, local, remote, id, "", svc);
        }
        public static GenerationResult failure(int svc, String message) {
            return new GenerationResult(false, "", "", "", message, svc);
        }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /**
     * Dispatches a generation request to the correct workflow based on serviceIndex.
     *
     * @param request    The generation request from UI
     * @param onProgress Progress callback (called from background thread)
     * @return future resolving to a GenerationResult
     */
    public CompletableFuture<GenerationResult> dispatch(
            GenerationRequest request,
            Consumer<String> onProgress) {

        XeltaCraftMod.LOGGER.info("[Controller] Dispatch service={}, prompt='{}'",
            request.serviceIndex(), request.prompt());

        return switch (request.serviceIndex()) {
            case SERVICE_IMAGE       -> dispatchImage(request, onProgress);
            case SERVICE_VIDEO       -> dispatchVideo(request, onProgress);
            case SERVICE_AVATAR      -> dispatchAvatar(request, onProgress);
            case SERVICE_MIX         -> dispatchMix(request, onProgress);
            default -> {
                yield CompletableFuture.completedFuture(
                    GenerationResult.failure(request.serviceIndex(),
                        "Unknown service index: " + request.serviceIndex()));
            }
        };
    }

    // ── Service Dispatchers ───────────────────────────────────────────────────

    private CompletableFuture<GenerationResult> dispatchImage(
            GenerationRequest req, Consumer<String> onProgress) {

        ImageGenerationWorkflow.ImageGenerationRequest wfReq =
            new ImageGenerationWorkflow.ImageGenerationRequest(
                req.prompt(), req.negativePrompt(),
                req.referenceImagePath(), req.token());

        return ImageGenerationWorkflow.getInstance().run(wfReq, onProgress)
            .thenApply(r -> r.success()
                ? GenerationResult.success(SERVICE_IMAGE, r.localFilePath(), r.remoteUrl(), r.taskId())
                : GenerationResult.failure(SERVICE_IMAGE, r.errorMessage()));
    }

    private CompletableFuture<GenerationResult> dispatchVideo(
            GenerationRequest req, Consumer<String> onProgress) {

        VideoGenerationWorkflow.VideoGenerationRequest wfReq =
            new VideoGenerationWorkflow.VideoGenerationRequest(
                req.prompt(), req.referenceImagePath(),
                req.referenceVideoUrl(), req.token());

        return VideoGenerationWorkflow.getInstance().run(wfReq, onProgress)
            .thenApply(r -> r.success()
                ? GenerationResult.success(SERVICE_VIDEO, r.localFilePath(), r.remoteUrl(), r.jobId())
                : GenerationResult.failure(SERVICE_VIDEO, r.errorMessage()));
    }

    private CompletableFuture<GenerationResult> dispatchAvatar(
            GenerationRequest req, Consumer<String> onProgress) {

        String token = req.token() != null && !req.token().isBlank()
            ? req.token() : XeltaSDK.get().token();

        // Avatar uses the prompt as the spoken message, and loads image as bytes
        String message = req.prompt();

        onProgress.accept("Loading avatar image...");

        // Read reference image bytes from disk
        byte[] imageBytes;
        try {
            if (req.referenceImagePath() != null && !req.referenceImagePath().isBlank()) {
                imageBytes = java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(req.referenceImagePath()));
            } else {
                // Use a 1×1 transparent PNG as placeholder when no image provided
                imageBytes = ai.xelta.craft.api.MultipartBodyBuilder.TRANSPARENT_1X1_PNG;
            }
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                GenerationResult.failure(SERVICE_AVATAR, "Could not read avatar image: " + e.getMessage()));
        }
        final byte[] finalBytes = imageBytes;

        onProgress.accept("Starting Avatar generation...");

        return XeltaGenAvatarClient.getInstance()
            .generateAvatar(message, finalBytes, token, onProgress)
            .thenCompose(videoUrl -> {
                if (videoUrl == null || videoUrl.isBlank()) {
                    return CompletableFuture.completedFuture(
                        GenerationResult.failure(SERVICE_AVATAR, "Avatar generation returned no URL."));
                }
                onProgress.accept("Downloading avatar video...");
                return XeltaSDK.get().storage()
                    .downloadAndSave(videoUrl, "avatar_" + System.currentTimeMillis(),
                        XeltaAssetCache.AssetType.VIDEO)
                    .thenApply(pathOpt -> GenerationResult.success(
                        SERVICE_AVATAR,
                        pathOpt.map(p -> p.toAbsolutePath().toString()).orElse(""),
                        videoUrl,
                        ""));
            })
            .exceptionally(e -> {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return GenerationResult.failure(SERVICE_AVATAR, msg != null ? msg : "Avatar generation failed.");
            });
    }

    private CompletableFuture<GenerationResult> dispatchMix(
            GenerationRequest req, Consumer<String> onProgress) {

        String token = req.token() != null && !req.token().isBlank()
            ? req.token() : XeltaSDK.get().token();

        onProgress.accept("Starting Mix generation...");

        // generateMixboard returns List<String> of image URLs; we use the first one
        return XeltaMixClient.getInstance()
            .generateMixboard(req.prompt(), 4, token, onProgress)
            .thenApply(urls -> {
                if (urls == null || urls.isEmpty()) {
                    return GenerationResult.failure(SERVICE_MIX, "Mix generation returned no URLs.");
                }
                String firstUrl = urls.get(0);
                onProgress.accept("✓ Mix complete! " + urls.size() + " variation(s) generated.");
                return GenerationResult.success(SERVICE_MIX, "", firstUrl, "");
            })
            .exceptionally(e -> {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return GenerationResult.failure(SERVICE_MIX, msg != null ? msg : "Mix generation failed.");
            });
    }

}


package ai.xelta.craft.workflow;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.sdk.XeltaSDK;
import ai.xelta.craft.sdk.superxelta.XeltaSessionClient;
import ai.xelta.craft.sdk.superxelta.XeltaSuperComputerClient;
import ai.xelta.craft.sdk.superxelta.XeltaSuperComputerClient.ChatResult;
import ai.xelta.craft.sdk.superxelta.XeltaSuperComputerClient.ChatStreamListener;
import ai.xelta.craft.sdk.superxelta.XeltaSuperComputerClient.UploadResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * SuperComputerWorkflow — application-layer orchestration for the Nexus Deep Agent backend
 * ("Super Computer"), per API.md.
 *
 * Pipeline for a single generation:
 *   1. Resolve (or create) the chat session — {@link XeltaSessionClient}
 *   2. (Optional) upload a reference image → get its URL
 *   3. POST /sessions/{id}/chat/stream with the chosen `skill` (or none, for orchestrator
 *      auto-routing), relaying live text and any mid-turn approval prompt to the caller
 *   4. Cache a produced image locally and return local path + remote URL to the caller
 *
 * If the cached session id turns out to be stale (the server no longer recognizes it), the
 * session is reset and the call retried once against a freshly created session.
 */
public final class SuperComputerWorkflow {

    private static final XeltaSuperComputerClient CLIENT  = XeltaSuperComputerClient.getInstance();
    private static final XeltaSessionClient        SESSION = XeltaSessionClient.getInstance();

    private SuperComputerWorkflow() {}

    // ── Approval handoff ──────────────────────────────────────────────────────

    public record ApprovalRequest(String approvalId, int credits, int balance, String resourceType) {}

    /** Supplied by the UI: decide approve/reject for a pending generation. */
    @FunctionalInterface
    public interface ApprovalHandler {
        CompletableFuture<Boolean> decide(ApprovalRequest request);
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public record SuperComputerResult(
            boolean success,
            String  type,        // "image" | "website" | "text"
            String  localPath,   // cached file on disk (image results only)
            String  remoteUrl,   // backend-hosted URL (image/website results)
            String  text,        // model's text reply
            String  skill,       // resolved skill id, or null for a plain conversational reply
            String  error) {

        public static SuperComputerResult ok(String type, String localPath, String remoteUrl, String text, String skill) {
            return new SuperComputerResult(true, type, localPath, remoteUrl, text, skill, "");
        }
        public static SuperComputerResult fail(String error) {
            return new SuperComputerResult(false, "", "", "", "", null, error);
        }
        public boolean hasMedia() { return remoteUrl != null && !remoteUrl.isBlank(); }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends one chat turn to the Super Computer.
     *
     * @param prompt          User's message
     * @param skillId         Explicit skill id to pin the turn to (e.g. "home-design-generate"),
     *                        or null/blank to let the orchestrator auto-route
     * @param localRefPath    Optional local file path to upload as the reference image
     * @param approvalHandler Called if the backend asks for spend approval mid-turn
     * @param onTextDelta     Called with each streamed text chunk (may be called many times)
     */
    public static CompletableFuture<SuperComputerResult> chat(
            String prompt, String skillId, String localRefPath,
            ApprovalHandler approvalHandler, Consumer<String> onTextDelta) {

        return SESSION.getOrCreate().thenCompose(sessionId -> {
            if (sessionId.isBlank()) {
                return CompletableFuture.completedFuture(
                    SuperComputerResult.fail("Could not start a Super Computer session."));
            }
            return runTurn(sessionId, prompt, skillId, localRefPath, approvalHandler, onTextDelta, true);
        });
    }

    private static CompletableFuture<SuperComputerResult> runTurn(
            String sessionId, String prompt, String skillId, String localRefPath,
            ApprovalHandler approvalHandler, Consumer<String> onTextDelta, boolean allowRetry) {

        CompletableFuture<String> uploadFuture = localRefPath != null && !localRefPath.isBlank()
            ? uploadFirst(localRefPath, onTextDelta)
            : CompletableFuture.completedFuture("");

        return uploadFuture.thenCompose(imageUrl -> {
            ChatStreamListener listener = new ChatStreamListener() {
                @Override public void onTextDelta(String delta) { onTextDelta.accept(delta); }
                @Override public CompletableFuture<Boolean> onApprovalRequired(
                        String approvalId, int credits, int balance, String resourceType) {
                    onTextDelta.accept("\n[Approval needed] " + resourceType + " — " + credits
                        + " credits (balance " + balance + ")");
                    return approvalHandler.decide(new ApprovalRequest(approvalId, credits, balance, resourceType));
                }
            };

            return CLIENT.chatStream(sessionId, prompt, imageUrl, null, skillId, false, listener)
                .thenCompose(result -> {
                    if (allowRetry && !result.success() && looksLikeStaleSession(result.error())) {
                        SESSION.reset();
                        return SESSION.getOrCreate().thenCompose(freshId ->
                            freshId.isBlank()
                                ? CompletableFuture.completedFuture(SuperComputerResult.fail(result.error()))
                                : runTurn(freshId, prompt, skillId, localRefPath, approvalHandler, onTextDelta, false));
                    }
                    return finalizeResult(result, onTextDelta);
                });
        }).exceptionally(e -> SuperComputerResult.fail(rootMsg(e)));
    }

    private static boolean looksLikeStaleSession(String error) {
        if (error == null) return false;
        return error.contains("404") || error.toLowerCase().contains("not found");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static CompletableFuture<String> uploadFirst(String localPath, Consumer<String> onProgress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path p = Path.of(localPath);
                if (!Files.exists(p)) {
                    onProgress.accept("\n⚠ Reference file not found, proceeding without it.");
                    return "";
                }
                byte[] bytes    = Files.readAllBytes(p);
                String filename = p.getFileName().toString();
                onProgress.accept("\nUploading reference image (" + bytes.length / 1024 + " KB)...");

                UploadResult up = CLIENT.uploadFile(bytes, filename).join();
                if (up.success()) {
                    onProgress.accept("\n✓ Upload complete.");
                    XeltaCraftMod.LOGGER.info("[SuperComputer] Uploaded {} → {}", filename, up.url());
                    return up.url();
                }
                onProgress.accept("\n⚠ Upload failed: " + up.error() + " (continuing without it)");
                return "";
            } catch (Exception e) {
                onProgress.accept("\n⚠ Could not read file: " + e.getMessage());
                return "";
            }
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    private static CompletableFuture<SuperComputerResult> finalizeResult(ChatResult result, Consumer<String> onProgress) {
        if (!result.success()) {
            return CompletableFuture.completedFuture(SuperComputerResult.fail(result.error()));
        }

        if (!result.hasResult()) {
            return CompletableFuture.completedFuture(
                SuperComputerResult.ok(result.type(), "", "", result.content(), result.skill()));
        }

        // Only images can be decoded/rendered client-side; websites are left as a remote link.
        if (!result.isImage()) {
            return CompletableFuture.completedFuture(
                SuperComputerResult.ok(result.type(), "", result.resultUrl(), result.content(), result.skill()));
        }

        String taskId = "sc_" + result.resultUrl().hashCode();
        onProgress.accept("\nDownloading result...");
        return XeltaSDK.get().storage()
            .downloadAndSave(result.resultUrl(), taskId, XeltaAssetCache.AssetType.IMAGE)
            .thenApply(pathOpt -> {
                String localPath = pathOpt.map(p -> p.toAbsolutePath().toString()).orElse("");
                if (!localPath.isBlank()) {
                    XeltaSDK.get().cache().put(taskId, localPath, XeltaAssetCache.AssetType.IMAGE, result.content());
                }
                onProgress.accept("\n✓ Done!");
                return SuperComputerResult.ok(result.type(), localPath, result.resultUrl(), result.content(), result.skill());
            });
    }

    private static String rootMsg(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : "Unknown error";
    }
}

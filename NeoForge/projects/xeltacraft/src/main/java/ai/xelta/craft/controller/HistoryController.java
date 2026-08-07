package ai.xelta.craft.controller;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.sdk.XeltaHistoryClient;
import ai.xelta.craft.sdk.XeltaSDK;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HistoryController — Application Layer
 *
 * Loads, refreshes, and caches generation history for the History tab UI.
 *
 * Responsibilities:
 *  • Fetch history from XeltaHistoryClient
 *  • Cache the last-fetched list so the UI can display it without re-fetching
 *  • Support pagination (load more)
 *  • Re-download a history item's asset via AssetController
 */
public class HistoryController {

    private static HistoryController INSTANCE;

    public static HistoryController getInstance() {
        if (INSTANCE == null) INSTANCE = new HistoryController();
        return INSTANCE;
    }

    private HistoryController() {}

    // ── State ─────────────────────────────────────────────────────────────────

    /** Cached last-fetched history items */
    private final AtomicReference<List<XeltaHistoryClient.HistoryItem>> cachedItems =
        new AtomicReference<>(Collections.emptyList());

    private volatile int totalCount = 0;
    private volatile boolean isLoading = false;
    private static final int PAGE_SIZE = 20;

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Loads the first page of history from the API.
     * Updates the internal cache and returns the result.
     *
     * @return future resolving to a LoadResult
     */
    public CompletableFuture<LoadResult> loadFirstPage() {
        return load(0);
    }

    /**
     * Loads the next page of history (appends to existing cache).
     */
    public CompletableFuture<LoadResult> loadNextPage() {
        return load(cachedItems.get().size());
    }

    private CompletableFuture<LoadResult> load(int offset) {
        if (isLoading) {
            return CompletableFuture.completedFuture(
                LoadResult.failure("History is already loading."));
        }
        if (!XeltaSDK.get().isAuthenticated()) {
            return CompletableFuture.completedFuture(
                LoadResult.failure("Not authenticated. Please sign in first."));
        }

        isLoading = true;

        return XeltaSDK.get().history().fetchHistory(PAGE_SIZE, offset)
            .thenApply(result -> {
                isLoading = false;
                if (!result.success()) {
                    return LoadResult.failure(result.errorMessage());
                }

                if (offset == 0) {
                    // Full refresh
                    cachedItems.set(result.items());
                } else {
                    // Append (pagination)
                    List<XeltaHistoryClient.HistoryItem> merged = new java.util.ArrayList<>(cachedItems.get());
                    merged.addAll(result.items());
                    cachedItems.set(Collections.unmodifiableList(merged));
                }

                totalCount = result.total();
                XeltaCraftMod.LOGGER.info("[History] Loaded {} items (total: {})",
                    result.items().size(), totalCount);

                return LoadResult.success(cachedItems.get(), totalCount);
            })
            .exceptionally(e -> {
                isLoading = false;
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return LoadResult.failure(msg != null ? msg : "Failed to load history.");
            });
    }

    // ── Cache Access ──────────────────────────────────────────────────────────

    /** Returns the current cached history items (may be empty if not yet loaded). */
    public List<XeltaHistoryClient.HistoryItem> getCachedItems() {
        return cachedItems.get();
    }

    /** Total count of history items on the server (for "load more" button). */
    public int getTotalCount() { return totalCount; }

    /** True if there are more items to load from the server. */
    public boolean hasMore() { return cachedItems.get().size() < totalCount; }

    /** True if currently fetching. */
    public boolean isLoading() { return isLoading; }

    /** Clears the local history cache (does NOT delete server data). */
    public void clearCache() {
        cachedItems.set(Collections.emptyList());
        totalCount = 0;
    }

    // ── Re-download ───────────────────────────────────────────────────────────

    /**
     * Re-downloads a history item's asset to local cache.
     * Called from the History tab when user clicks "Re-download".
     *
     * @param item The history item to re-download
     * @return future resolving to the local file path, or empty on failure
     */
    public CompletableFuture<java.util.Optional<java.nio.file.Path>> reDownload(
            XeltaHistoryClient.HistoryItem item) {

        if (!item.hasResult()) {
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }

        ai.xelta.craft.infra.cache.XeltaAssetCache.AssetType type =
            "video".equalsIgnoreCase(item.type()) || "avatar".equalsIgnoreCase(item.type())
                ? ai.xelta.craft.infra.cache.XeltaAssetCache.AssetType.VIDEO
                : ai.xelta.craft.infra.cache.XeltaAssetCache.AssetType.IMAGE;

        return AssetController.getInstance().ensureDownloaded(item.id(), item.resultUrl(), type);
    }

    // ── Result Type ───────────────────────────────────────────────────────────

    public record LoadResult(
        boolean success,
        List<XeltaHistoryClient.HistoryItem> items,
        int total,
        String errorMessage
    ) {
        public static LoadResult success(List<XeltaHistoryClient.HistoryItem> items, int total) {
            return new LoadResult(true, items, total, "");
        }
        public static LoadResult failure(String msg) {
            return new LoadResult(false, Collections.emptyList(), 0, msg);
        }
    }
}

package ai.xelta.craft.controller;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.infra.cache.XeltaFileStorage;
import ai.xelta.craft.sdk.XeltaSDK;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * AssetController — Application Layer
 *
 * Manages the lifecycle of generated assets:
 *  • Download result URLs to local cache
 *  • Retrieve locally cached assets for gallery display
 *  • List all cached assets for the Gallery tab
 *  • Re-download assets that were evicted
 *
 * The Gallery tab in the UI calls this controller to get its data.
 */
public class AssetController {

    private static AssetController INSTANCE;

    public static AssetController getInstance() {
        if (INSTANCE == null) INSTANCE = new AssetController();
        return INSTANCE;
    }

    private AssetController() {}

    // ── Gallery Listing ───────────────────────────────────────────────────────

    /**
     * Returns a list of all locally cached asset files for gallery display.
     * Scans the xeltacraft/cache/images and xeltacraft/cache/videos directories.
     *
     * @return list of GalleryEntry items (may be empty, never null)
     */
    public List<GalleryEntry> listCachedAssets() {
        List<GalleryEntry> entries = new ArrayList<>();
        XeltaFileStorage storage = XeltaSDK.get().storage();
        Path root = storage.getRootPath();

        if (root == null) return entries;

        scanDirectory(root.resolve("cache/images"), XeltaAssetCache.AssetType.IMAGE, entries);
        scanDirectory(root.resolve("cache/videos"), XeltaAssetCache.AssetType.VIDEO, entries);

        // Sort newest first (by last-modified time)
        entries.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return entries;
    }

    private void scanDirectory(Path dir, XeltaAssetCache.AssetType type, List<GalleryEntry> out) {
        if (!Files.exists(dir)) return;
        try {
            Files.list(dir).forEach(file -> {
                if (Files.isRegularFile(file)) {
                    try {
                        long modified = Files.getLastModifiedTime(file).toMillis();
                        String name = file.getFileName().toString();
                        String id = stripExtension(name);
                        out.add(new GalleryEntry(id, file.toAbsolutePath().toString(), type, modified));
                    } catch (Exception e) {
                        XeltaCraftMod.LOGGER.debug("[Assets] Could not stat {}: {}", file, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.warn("[Assets] Failed to scan {}: {}", dir, e.getMessage());
        }
    }

    // ── Get Cached Asset ──────────────────────────────────────────────────────

    /**
     * Retrieves the local path for a cached asset by task ID.
     *
     * @param taskId The task/job ID
     * @return Optional with the local path string, or empty if not cached
     */
    public Optional<String> getLocalPath(String taskId) {
        return XeltaSDK.get().cache().get(taskId)
            .map(XeltaAssetCache.CacheEntry::localPath);
    }

    // ── Ensure Downloaded ─────────────────────────────────────────────────────

    /**
     * Ensures an asset is available locally, downloading it if necessary.
     * Used when re-accessing history items that may not be cached locally.
     *
     * @param taskId    The task/job ID
     * @param remoteUrl CDN URL of the asset
     * @param type      IMAGE or VIDEO
     * @return future resolving to the local path, or empty on failure
     */
    public CompletableFuture<Optional<Path>> ensureDownloaded(
            String taskId, String remoteUrl, XeltaAssetCache.AssetType type) {

        // Check L2 cache first
        Optional<String> existing = XeltaSDK.get().storage().findCachedAsset(taskId);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(Path.of(existing.get())));
        }

        // Download from remote
        return XeltaSDK.get().storage().downloadAndSave(remoteUrl, taskId, type)
            .thenApply(pathOpt -> {
                pathOpt.ifPresent(p ->
                    XeltaSDK.get().cache().put(taskId, p.toAbsolutePath().toString(), type, ""));
                return pathOpt;
            });
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Deletes a cached asset from both L1 and disk.
     *
     * @param taskId The task/job ID
     */
    public void deleteAsset(String taskId) {
        XeltaSDK.get().cache().evict(taskId);
        Optional<String> path = XeltaSDK.get().storage().findCachedAsset(taskId);
        path.ifPresent(p -> {
            try { Files.deleteIfExists(Path.of(p)); }
            catch (Exception e) { XeltaCraftMod.LOGGER.warn("[Assets] Delete failed: {}", e.getMessage()); }
        });
    }

    // ── Cache Stats ───────────────────────────────────────────────────────────

    /**
     * Returns total number of gallery entries (cached files on disk).
     */
    public int getCachedAssetCount() {
        return listCachedAssets().size();
    }

    // ── Data Types ────────────────────────────────────────────────────────────

    public record GalleryEntry(
        String id,
        String localPath,
        XeltaAssetCache.AssetType type,
        long lastModified
    ) {
        public boolean isImage() { return type == XeltaAssetCache.AssetType.IMAGE; }
        public boolean isVideo() { return type == XeltaAssetCache.AssetType.VIDEO; }
        public String filename() {
            return Path.of(localPath).getFileName().toString();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}

package ai.xelta.craft.infra.cache;

import ai.xelta.craft.XeltaCraftMod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * XeltaAssetCache — Infrastructure Layer
 *
 * Two-level cache for generated assets:
 *  L1: In-memory LRU (fast access, limited size)
 *  L2: Disk-backed via XeltaFileStorage (persistent across sessions)
 *
 * Keys are job/task IDs.
 * Values are the locally cached file paths (absolute path on disk).
 *
 * Thread-safe for concurrent read/write from async generation threads.
 */
public class XeltaAssetCache {

    // ── Singleton ────────────────────────────────────────────────────────────
    private static XeltaAssetCache INSTANCE;

    public static XeltaAssetCache getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaAssetCache();
        return INSTANCE;
    }

    // ── Cache Config ──────────────────────────────────────────────────────────
    private static final int L1_MAX_ENTRIES = 64; // max in-memory entries

    // ── L1 In-Memory LRU ─────────────────────────────────────────────────────

    /** LRU map: taskId → absolute local file path */
    private final Map<String, CacheEntry> l1 = new LinkedHashMap<>(L1_MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > L1_MAX_ENTRIES;
        }
    };

    // ── Asset Types ───────────────────────────────────────────────────────────

    public enum AssetType {
        IMAGE, VIDEO, THUMBNAIL
    }

    // ── Cache Entry ───────────────────────────────────────────────────────────

    public record CacheEntry(
        String taskId,
        String localPath,
        AssetType type,
        String prompt,
        long createdAt
    ) {}

    // ── Constructor ───────────────────────────────────────────────────────────

    private XeltaAssetCache() {}

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Stores a generated asset in L1 (and optionally triggers L2 disk write).
     *
     * @param taskId    Unique task/job ID from Xelta API
     * @param localPath Absolute path to the cached file on disk
     * @param type      Image, video, or thumbnail
     * @param prompt    The prompt used to generate this asset
     */
    public synchronized void put(String taskId, String localPath, AssetType type, String prompt) {
        CacheEntry entry = new CacheEntry(taskId, localPath, type, prompt, System.currentTimeMillis());
        l1.put(taskId, entry);
        XeltaCraftMod.LOGGER.debug("[Cache] Stored {} → {} ({})", taskId, localPath, type);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Retrieves a cached entry by task ID.
     * Checks L1 first, then falls back to L2 (disk index).
     *
     * @param taskId Task/job ID
     * @return Optional containing the cache entry, or empty if not found
     */
    public synchronized Optional<CacheEntry> get(String taskId) {
        // L1 lookup
        CacheEntry entry = l1.get(taskId);
        if (entry != null) {
            XeltaCraftMod.LOGGER.debug("[Cache] L1 hit for {}", taskId);
            return Optional.of(entry);
        }

        // L2 disk lookup via XeltaFileStorage
        Optional<String> diskPath = XeltaFileStorage.getInstance().findCachedAsset(taskId);
        if (diskPath.isPresent()) {
            XeltaCraftMod.LOGGER.debug("[Cache] L2 hit for {} at {}", taskId, diskPath.get());
            // Promote to L1
            CacheEntry restored = new CacheEntry(taskId, diskPath.get(), AssetType.IMAGE, "", 0L);
            l1.put(taskId, restored);
            return Optional.of(restored);
        }

        return Optional.empty();
    }

    /**
     * Check if an asset is cached without reading its contents.
     */
    public synchronized boolean contains(String taskId) {
        return l1.containsKey(taskId) || XeltaFileStorage.getInstance().findCachedAsset(taskId).isPresent();
    }

    // ── Evict ─────────────────────────────────────────────────────────────────

    /**
     * Evict a single entry from L1 (does NOT delete the file).
     */
    public synchronized void evict(String taskId) {
        l1.remove(taskId);
    }

    /**
     * Clear all L1 entries (cache is rebuilt lazily from disk on next access).
     */
    public synchronized void clearL1() {
        int size = l1.size();
        l1.clear();
        XeltaCraftMod.LOGGER.info("[Cache] Cleared {} L1 entries", size);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /** Returns the current number of L1 in-memory entries. */
    public synchronized int l1Size() { return l1.size(); }
}

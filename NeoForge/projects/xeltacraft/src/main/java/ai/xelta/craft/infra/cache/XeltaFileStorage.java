package ai.xelta.craft.infra.cache;

import ai.xelta.craft.XeltaCraftMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * XeltaFileStorage — Infrastructure Layer
 *
 * Manages organized local file storage under .minecraft/xeltacraft/:
 *
 *   xeltacraft/
 *     cache/images/        ← generated images
 *     cache/videos/        ← generated videos
 *     cache/thumbnails/    ← thumbnail previews
 *     history/             ← history index JSON files
 *     session/             ← session data (refresh token)
 *
 * All file I/O is async via virtual threads.
 */
public class XeltaFileStorage {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static XeltaFileStorage INSTANCE;

    public static XeltaFileStorage getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaFileStorage();
        return INSTANCE;
    }

    // ── Directory Constants ───────────────────────────────────────────────────
    private static final String ROOT_DIR        = "xeltacraft";
    private static final String CACHE_IMAGES    = "cache/images";
    private static final String CACHE_VIDEOS    = "cache/videos";
    private static final String CACHE_THUMBS    = "cache/thumbnails";
    private static final String HISTORY_DIR     = "history";
    private static final String SESSION_DIR     = "session";

    // ── State ─────────────────────────────────────────────────────────────────
    private Path rootPath;
    private boolean initialized = false;

    private XeltaFileStorage() {}

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Initializes all subdirectories. Must be called after Minecraft loads.
     * Safe to call multiple times (idempotent).
     */
    public synchronized void init() {
        if (initialized) return;
        try {
            File gameDir = Minecraft.getInstance().gameDirectory;
            rootPath = gameDir.toPath().resolve(ROOT_DIR);
            createDir(rootPath.resolve(CACHE_IMAGES));
            createDir(rootPath.resolve(CACHE_VIDEOS));
            createDir(rootPath.resolve(CACHE_THUMBS));
            createDir(rootPath.resolve(HISTORY_DIR));
            createDir(rootPath.resolve(SESSION_DIR));
            initialized = true;
            XeltaCraftMod.LOGGER.info("[Storage] Initialized at: {}", rootPath);
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.error("[Storage] Failed to initialize: {}", e.getMessage(), e);
        }
    }

    // ── Download & Save ───────────────────────────────────────────────────────

    /**
     * Downloads an asset from a URL and saves it to the appropriate cache directory.
     *
     * @param url      Remote URL of the asset
     * @param taskId   Task/job ID used as the filename base
     * @param type     IMAGE or VIDEO (determines subdirectory)
     * @return future resolving to the local absolute path, or empty on failure
     */
    public CompletableFuture<Optional<Path>> downloadAndSave(String url, String taskId, XeltaAssetCache.AssetType type) {
        return CompletableFuture.supplyAsync(() -> {
            if (!initialized) init();
            try {
                String ext = guessExtension(url, type);
                String filename = sanitize(taskId) + ext;
                Path subDir = type == XeltaAssetCache.AssetType.VIDEO
                    ? rootPath.resolve(CACHE_VIDEOS)
                    : rootPath.resolve(CACHE_IMAGES);
                Path dest = subDir.resolve(filename);

                if (Files.exists(dest)) {
                    XeltaCraftMod.LOGGER.debug("[Storage] Already cached: {}", dest);
                    return Optional.of(dest);
                }

                XeltaCraftMod.LOGGER.info("[Storage] Downloading {} → {}", url, dest);

                HttpClient client = HttpClient.newBuilder()
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();

                HttpResponse<InputStream> response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    XeltaCraftMod.LOGGER.warn("[Storage] Download HTTP {}: {}", response.statusCode(), url);
                    return Optional.empty();
                }

                Files.copy(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
                XeltaCraftMod.LOGGER.info("[Storage] Saved {} ({} bytes)", dest.getFileName(), Files.size(dest));
                return Optional.of(dest);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[Storage] Download failed for {}: {}", taskId, e.getMessage());
                return Optional.empty();
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Searches the images and videos cache directories for a file matching the taskId.
     *
     * @param taskId The task/job ID
     * @return Optional with the absolute path string if found
     */
    public Optional<String> findCachedAsset(String taskId) {
        if (!initialized) return Optional.empty();
        String base = sanitize(taskId);

        // Check images
        for (String ext : new String[]{".png", ".jpg", ".webp"}) {
            Path p = rootPath.resolve(CACHE_IMAGES).resolve(base + ext);
            if (Files.exists(p)) return Optional.of(p.toAbsolutePath().toString());
        }

        // Check videos
        for (String ext : new String[]{".mp4", ".webm"}) {
            Path p = rootPath.resolve(CACHE_VIDEOS).resolve(base + ext);
            if (Files.exists(p)) return Optional.of(p.toAbsolutePath().toString());
        }

        return Optional.empty();
    }

    // ── Session File (for refresh tokens) ────────────────────────────────────

    /**
     * Reads the session file (stores the refresh token as obfuscated text).
     */
    public Optional<String> readSessionFile(String filename) {
        if (!initialized) init();
        Path file = rootPath.resolve(SESSION_DIR).resolve(filename);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file).trim());
        } catch (IOException e) {
            XeltaCraftMod.LOGGER.warn("[Storage] Could not read session file {}: {}", filename, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Writes a session file (stores refresh token as text).
     */
    public void writeSessionFile(String filename, String content) {
        if (!initialized) init();
        Path file = rootPath.resolve(SESSION_DIR).resolve(filename);
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            XeltaCraftMod.LOGGER.error("[Storage] Could not write session file {}: {}", filename, e.getMessage());
        }
    }

    /**
     * Deletes a session file (logout).
     */
    public void deleteSessionFile(String filename) {
        if (!initialized) init();
        Path file = rootPath.resolve(SESSION_DIR).resolve(filename);
        try { Files.deleteIfExists(file); }
        catch (IOException e) { XeltaCraftMod.LOGGER.warn("[Storage] Could not delete session file: {}", e.getMessage()); }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    public Path getRootPath() { return rootPath; }

    private static void createDir(Path path) throws IOException {
        Files.createDirectories(path);
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return UUID.randomUUID().toString();
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static String guessExtension(String url, XeltaAssetCache.AssetType type) {
        if (url != null) {
            String lower = url.toLowerCase();
            if (lower.contains(".mp4"))  return ".mp4";
            if (lower.contains(".webm")) return ".webm";
            if (lower.contains(".jpg") || lower.contains(".jpeg")) return ".jpg";
            if (lower.contains(".webp")) return ".webp";
            if (lower.contains(".png"))  return ".png";
        }
        return type == XeltaAssetCache.AssetType.VIDEO ? ".mp4" : ".png";
    }
}

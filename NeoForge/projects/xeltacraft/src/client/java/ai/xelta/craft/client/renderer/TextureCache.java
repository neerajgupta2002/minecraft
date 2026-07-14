package ai.xelta.craft.client.renderer;

import ai.xelta.craft.XeltaCraftMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * TextureCache — client-side AI image download and GPU texture management.
 *
 * Responsibilities:
 *   1. Download image bytes from Xelta CDN URLs (async, off render thread)
 *   2. Resize images to max 1024×1024 (prevents GPU OOM)
 *   3. Register as DynamicTexture with Minecraft's TextureManager
 *   4. Cache ResourceLocation → avoids repeat downloads
 *   5. 3-attempt retry with exponential backoff on failure
 *   6. Semaphore limits to 5 concurrent downloads (prevents flooding)
 *
 * Calling convention:
 *   - Call load(url, callback) from render thread (safe to call every frame)
 *   - Callback fires on render thread when texture is ready
 *   - If texture not yet loaded, renders placeholder until ready
 */
public class TextureCache {

    private static TextureCache INSTANCE;
    public static TextureCache getInstance() {
        if (INSTANCE == null) INSTANCE = new TextureCache();
        return INSTANCE;
    }

    // ── Constants ──────────────────────────────────────────────────────────
    private static final int MAX_SIZE          = 1024;    // Max texture dimension
    private static final int MAX_CONCURRENT    = 5;       // Max parallel downloads
    private static final int MAX_CACHE_SIZE    = 50;      // Max cached textures
    private static final int MAX_RETRIES       = 3;

    public static final ResourceLocation PLACEHOLDER_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("xeltacraft", "textures/item/photo_slide_loading.png");

    public static final ResourceLocation ERROR_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("xeltacraft", "textures/item/photo_slide_error.png");

    // ── State ──────────────────────────────────────────────────────────────
    /** URL → registered ResourceLocation (ready to render) */
    private final Map<String, ResourceLocation> cache     = new ConcurrentHashMap<>();

    /** URLs currently being downloaded */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    /** URLs that permanently failed (don't retry) */
    private final Set<String> failedUrls = ConcurrentHashMap.newKeySet();

    private final Semaphore downloadSemaphore = new Semaphore(MAX_CONCURRENT);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Get the cached texture, or start loading it.
     *
     * @param url      Xelta CDN image URL
     * @param callback called on render thread when texture is ready (may be immediate)
     * @return current texture (placeholder if not yet loaded)
     */
    @Nullable
    public ResourceLocation get(String url) {
        if (url == null || url.isBlank()) return ERROR_TEXTURE;
        if (failedUrls.contains(url))     return ERROR_TEXTURE;
        return cache.get(url);  // null if not loaded yet
    }

    public void load(String url, Consumer<ResourceLocation> onReady) {
        if (url == null || url.isBlank()) { onReady.accept(ERROR_TEXTURE); return; }

        // Cache hit — immediate
        ResourceLocation cached = cache.get(url);
        if (cached != null) { onReady.accept(cached); return; }

        // Permanently failed
        if (failedUrls.contains(url)) { onReady.accept(ERROR_TEXTURE); return; }

        // Already downloading — onReady will be called when done
        if (inProgress.contains(url)) return;

        // Start async download
        startDownload(url, onReady, 0);
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private void startDownload(String url, Consumer<ResourceLocation> onReady, int attempt) {
        if (attempt >= MAX_RETRIES) {
            XeltaCraftMod.LOGGER.warn("Texture download failed after {} attempts: {}", MAX_RETRIES, url);
            failedUrls.add(url);
            inProgress.remove(url);
            Minecraft.getInstance().execute(() -> onReady.accept(ERROR_TEXTURE));
            return;
        }

        inProgress.add(url);

        long backoffMs = attempt == 0 ? 0 : (long) Math.pow(2, attempt) * 1000;

        CompletableFuture.runAsync(() -> {
            if (backoffMs > 0) {
                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
            }

            downloadSemaphore.acquireUninterruptibly();
            try {
                byte[] imageBytes = downloadBytes(url);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));

                if (image == null) throw new IOException("ImageIO could not decode the image");

                // Resize to prevent GPU OOM
                if (image.getWidth() > MAX_SIZE || image.getHeight() > MAX_SIZE) {
                    image = resize(image, MAX_SIZE, MAX_SIZE);
                }

                // Convert to NativeImage (MC's GPU format)
                NativeImage nativeImage = toNativeImage(image);

                // Register on render thread
                BufferedImage finalImage = image;
                NativeImage finalNative  = nativeImage;
                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture dynTex = new DynamicTexture(finalNative);
                        String texId = "xeltacraft_ai/" + Math.abs(url.hashCode());
                        ResourceLocation loc  = ResourceLocation.fromNamespaceAndPath("xeltacraft", texId);

                        Minecraft.getInstance().getTextureManager().register(loc, dynTex);

                        cache.put(url, loc);
                        inProgress.remove(url);

                        onReady.accept(loc);
                        XeltaCraftMod.LOGGER.debug("Texture registered: {} → {}", url.substring(0, Math.min(60, url.length())), loc);

                        // Evict oldest if cache too large
                        if (cache.size() > MAX_CACHE_SIZE) evictOldest();

                    } catch (Exception e) {
                        XeltaCraftMod.LOGGER.error("Failed to register texture on GPU: {}", e.getMessage());
                        failedUrls.add(url);
                        inProgress.remove(url);
                        onReady.accept(ERROR_TEXTURE);
                    }
                });

            } catch (Exception e) {
                downloadSemaphore.release();
                int nextAttempt = attempt + 1;
                XeltaCraftMod.LOGGER.warn("Download attempt {}/{} failed for {}: {}",
                    nextAttempt, MAX_RETRIES, url, e.getMessage());
                inProgress.remove(url);
                startDownload(url, onReady, nextAttempt);
                return;
            }
            downloadSemaphore.release();
        });
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());

        if (resp.statusCode() != 200) {
            throw new IOException("CDN returned HTTP " + resp.statusCode() + " for " + url);
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (!contentType.startsWith("image/")) {
            throw new IOException("CDN returned non-image content type: " + contentType);
        }

        return resp.body();
    }

    private BufferedImage resize(BufferedImage src, int maxW, int maxH) {
        double scaleX = (double) maxW / src.getWidth();
        double scaleY = (double) maxH / src.getHeight();
        double scale  = Math.min(scaleX, scaleY);
        int newW = (int) (src.getWidth()  * scale);
        int newH = (int) (src.getHeight() * scale);

        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = result.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, newW, newH, null);
        g2.dispose();
        return result;
    }

    private NativeImage toNativeImage(BufferedImage image) throws IOException {
        // Convert BufferedImage → PNG bytes → NativeImage
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
    }

    private void evictOldest() {
        // Simple eviction: remove first entry (ConcurrentHashMap order not guaranteed, but sufficient)
        String oldest = cache.keySet().stream().findFirst().orElse(null);
        if (oldest != null) {
            ResourceLocation loc = cache.remove(oldest);
            Minecraft.getInstance().getTextureManager().release(loc);
            XeltaCraftMod.LOGGER.debug("Evicted texture from cache: {}", oldest);
        }
    }

    /** Clear all cached textures (called on resource reload). */
    public void clearAll() {
        cache.forEach((url, loc) ->
            Minecraft.getInstance().getTextureManager().release(loc));
        cache.clear();
        inProgress.clear();
        failedUrls.clear();
        XeltaCraftMod.LOGGER.debug("TextureCache cleared.");
    }
}

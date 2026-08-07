package ai.xelta.craft.sdk;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class AssetDownloader {
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public static CompletableFuture<File> downloadAsset(String url, AssetCacheManager.AssetType type) {
        if (AssetCacheManager.isCached(url, type)) {
            return CompletableFuture.completedFuture(AssetCacheManager.getCachedFile(url, type));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Failed to download asset: HTTP " + response.statusCode());
                    }
                    try {
                        File dest = AssetCacheManager.getCachedFile(url, type);
                        try (InputStream is = response.body()) {
                            Files.copy(is, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                        return dest;
                    } catch (Exception e) {
                        throw new RuntimeException("Error saving asset: " + e.getMessage(), e);
                    }
                });
    }
}

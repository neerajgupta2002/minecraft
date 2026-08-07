package ai.xelta.craft.sdk;

import net.neoforged.fml.loading.FMLPaths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class AssetCacheManager {
    private static final String CACHE_DIR = "xeltacraft_cache";

    public enum AssetType {
        IMAGE("images"),
        VIDEO("videos");

        public final String folder;
        AssetType(String folder) { this.folder = folder; }
    }

    private static File getCacheFolder(AssetType type) {
        File gameDir = FMLPaths.GAMEDIR.get().toFile();
        File cacheFolder = new File(gameDir, CACHE_DIR + File.separator + type.folder);
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs();
        }
        return cacheFolder;
    }

    public static File getCachedFile(String url, AssetType type) {
        String hash = hashUrl(url);
        String extension = url.contains(".mp4") ? ".mp4" : ".png";
        return new File(getCacheFolder(type), hash + extension);
    }

    public static boolean isCached(String url, AssetType type) {
        File file = getCachedFile(url, type);
        return file.exists() && file.length() > 0;
    }

    private static String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(url.hashCode());
        }
    }
}

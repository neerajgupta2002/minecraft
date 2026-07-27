package ai.xelta.craft.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class GeminiApiKeyResolver {

    private GeminiApiKeyResolver() {
    }

    public static String resolve(String configuredValue) {
        return resolve(configuredValue, null);
    }

    public static String resolve(String configuredValue, Path envFile) {
        if (configuredValue != null && !configuredValue.isBlank()) {
            return configuredValue.trim();
        }

        String envValue = System.getenv("GEMINI_API_KEY");
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        Path targetFile = envFile != null ? envFile : findEnvFile();
        if (targetFile != null) {
            try {
                for (String rawLine : Files.readAllLines(targetFile)) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2 && "GEMINI_API_KEY".equals(parts[0].trim())) {
                        String value = parts[1].trim();
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        return value;
                    }
                }
            } catch (IOException ignored) {
                // Ignore and continue.
            }
        }

        return "";
    }

    private static Path findEnvFile() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path directory = current; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(".env");
            if (Files.exists(candidate)) {
                return candidate;
            }
            if (directory.getParent() == null) {
                break;
            }
        }
        return null;
    }
}

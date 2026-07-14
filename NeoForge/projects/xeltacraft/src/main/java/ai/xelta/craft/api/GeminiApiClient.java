package ai.xelta.craft.api;

import ai.xelta.craft.XeltaCraftMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.List;

public class GeminiApiClient {

    private static GeminiApiClient INSTANCE;
    public static GeminiApiClient getInstance() {
        if (INSTANCE == null) INSTANCE = new GeminiApiClient();
        return INSTANCE;
    }

    private final HttpClient httpClient;

    private GeminiApiClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private String getEnvProperty(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value;
        try {
            java.nio.file.Path currentPath = java.nio.file.Paths.get("").toAbsolutePath();
            java.nio.file.Path envPath = null;
            
            // Traverse up to 3 levels to find .env (handles run/ folder execution)
            for (int i = 0; i < 3; i++) {
                java.nio.file.Path checkPath = currentPath.resolve(".env");
                if (java.nio.file.Files.exists(checkPath)) {
                    envPath = checkPath;
                    break;
                }
                currentPath = currentPath.getParent();
                if (currentPath == null) break;
            }

            if (envPath != null) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(envPath);
                for (String line : lines) {
                    if (line.trim().startsWith(key + "=")) {
                        return line.substring(line.indexOf('=') + 1).trim();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public CompletableFuture<String> parseIntentToJson(String prompt) {
        String systemPrompt = "You are a Minecraft planning engine.\n" +
            "Convert the user's request into structured JSON.\n" +
            "Rules:\n" +
            "- Return only JSON.\n" +
            "- Recognize: object type, material, block type, color, quantity, biome, dimensions, orientation, location, style, decorations, redstone, furniture, mobs, lighting.\n" +
            "- Infer missing information reasonably.\n" +
            "User:\n" + prompt;
        return generateContent(systemPrompt, true, 3);
    }

    public CompletableFuture<String> generateBlueprintBlocks(JsonObject objectsPlan) {
        String systemPrompt = "You are a Minecraft 3D Blueprint architect.\n" +
            "Given the following object plan, you must design a 3D block-by-block structure.\n" +
            "Return ONLY a JSON array of block definitions.\n" +
            "Each block must have exactly this format: {\"x\": 0, \"y\": 0, \"z\": 0, \"state\": \"minecraft:stone\"}\n" +
            "Rules:\n" +
            "- The coordinate system starts at (0,0,0).\n" +
            "- Y is up. Positive X is Right. Positive Z is Forward.\n" +
            "- Use valid minecraft block IDs for 'state' (e.g. minecraft:oak_planks, minecraft:yellow_wool).\n" +
            "- Do not return an object, return ONLY the JSON array.\n" +
            "Object Plan:\n" + objectsPlan.toString();
        return generateContent(systemPrompt, true, 3);
    }

    public CompletableFuture<String> generateContent(
            String prompt,
            boolean jsonMode,
            int retries) {
            
        String apiKey = getEnvProperty("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = ai.xelta.craft.config.XeltaCraftConfig.API_KEY.get();
        }
        
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(new RuntimeException("API Key missing! Set it with /xeltacraft setkey <key> or in .env"));
        }

        String baseUrl = getEnvProperty("GEMINI_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
        }

        String url = baseUrl + (baseUrl.contains("?") ? "&key=" : "?key=") + apiKey;

        JsonObject root = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject partObj = new JsonObject();
        
        partObj.addProperty("text", prompt);
        parts.add(partObj);
        
        contentObj.addProperty("role", "user");
        contentObj.add("parts", parts);
        contents.add(contentObj);
        
        root.add("contents", contents);

        if (jsonMode) {
            JsonObject config = new JsonObject();
            config.addProperty("responseMimeType", "application/json");
            config.addProperty("temperature", 0.3);
            root.add("generationConfig", config);
        }

        String bodyJson = root.toString();

        return executeWithRetries(url, bodyJson, retries, 0);
    }

    private CompletableFuture<String> executeWithRetries(String url, String bodyJson, int maxRetries, int attempt) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(300))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                int status = response.statusCode();
                
                if (status == 429) {
                    if (attempt < maxRetries - 1) {
                        int waitTimeSeconds = (int) Math.pow(2, attempt) * 2;
                        XeltaCraftMod.LOGGER.warn("Quota exhausted (429). Retrying in {}s...", waitTimeSeconds);
                        
                        return CompletableFuture.supplyAsync(() -> null, 
                            CompletableFuture.delayedExecutor(waitTimeSeconds, java.util.concurrent.TimeUnit.SECONDS))
                        .thenCompose(v -> executeWithRetries(url, bodyJson, maxRetries, attempt + 1));
                    } else {
                        return CompletableFuture.failedFuture(new RuntimeException("Gemini API Rate Limit Exceeded (HTTP 429)"));
                    }
                }
                
                if (status != 200) {
                    XeltaCraftMod.LOGGER.error("Gemini API Error ({}): {}", status, response.body());
                    return CompletableFuture.failedFuture(new RuntimeException("Gemini API Error (HTTP " + status + ")"));
                }

                try {
                    JsonObject resRoot = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonArray candidates = resRoot.getAsJsonArray("candidates");
                    if (candidates != null && candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        JsonObject content = candidate.getAsJsonObject("content");
                        JsonArray resParts = content.getAsJsonArray("parts");
                        if (resParts != null && resParts.size() > 0) {
                            return CompletableFuture.completedFuture(resParts.get(0).getAsJsonObject().get("text").getAsString());
                        }
                    }
                    return CompletableFuture.failedFuture(new RuntimeException("No content returned from Gemini API"));
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(new RuntimeException("Failed to parse Gemini API response", e));
                }
            });
    }

    // -----------------------------------------------------
    // BATCH TRANSLATION
    // -----------------------------------------------------
    public CompletableFuture<String> translateSegments(List<String> texts, String targetLang) {
        if (texts == null || texts.isEmpty()) {
            return CompletableFuture.completedFuture("[]");
        }

        JsonArray textsArray = new JsonArray();
        for (String text : texts) {
            textsArray.add(text);
        }

        String prompt = "Terjemahkan senarai kapsyen berikut ke " + targetLang + ".\n\n" +
            "PENTING:\n" +
            "- Gunakan Bahasa Melayu Malaysia yang natural dan santai.\n" +
            "- Sesuai untuk TikTok/Reels/Shorts.\n" +
            "- Jangan translate perkataan English yang biasa digunakan.\n" +
            "- Kekalkan urutan asal.\n" +
            "- Return ONLY JSON array.\n\n" +
            "Kapsyen:\n" +
            textsArray.toString();

        return generateContent(prompt, true, 3)
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.warn("Translation failed. Returning original.");
                return textsArray.toString();
            });
    }

    // -----------------------------------------------------
    // SINGLE TEXT TRANSLATION
    // -----------------------------------------------------
    public CompletableFuture<String> translateAndNormalize(String text, String targetLang) {
        String prompt = "Terjemahkan ke " + targetLang + ".\n\n" +
            "Gunakan gaya Bahasa Melayu Malaysia yang natural.\n" +
            "Normalkan singkatan dan slang jika perlu.\n\n" +
            "Text:\n" +
            text;

        return generateContent(prompt, false, 3)
            .exceptionally(e -> {
                return text;
            });
    }
}

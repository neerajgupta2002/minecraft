package ai.xelta.craft.api;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.config.XeltaCraftConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * GeminiArchitectClient — Calls the Google Gemini API to generate Minecraft
 * block blueprints from a natural language prompt.
 *
 * The dynamic system prompt is modelled after the reference Python integration:
 *   - {block_size}  → derived from the Slide Size selector (e.g. 5 from "5x5")
 *   - {max_colors}  → derived from the backend image-size default (512→8, 1024→16, etc.)
 *
 * API endpoint:
 *   POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *   ?key={GEMINI_API_KEY}
 *
 * Returns: CompletableFuture<String> with a raw JSON array of block placements:
 *   [{"x":0,"y":0,"z":0,"block":"minecraft:stone"}, ...]
 */
public class GeminiArchitectClient {

    private static GeminiArchitectClient INSTANCE;

    public static GeminiArchitectClient getInstance() {
        if (INSTANCE == null) INSTANCE = new GeminiArchitectClient();
        return INSTANCE;
    }

    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Dynamic system prompt template — identical in structure to the Python reference
    private static final String SYSTEM_PROMPT_TEMPLATE =
        "You are a Minecraft block-placement architect agent.\n" +
        "Your task is to generate a precise JSON block placement blueprint for a Minecraft structure.\n\n" +
        "The structure is described as: \"{prompt}\"\n\n" +
        "You must apply these exact user-defined constraints:\n" +
        "- The structure footprint must fit within {block_size} x {block_size} blocks (x and z axes).\n" +
        "- Use a maximum of {max_colors} distinct block types across the entire structure.\n" +
        "- Style: voxel-accurate, architecturally sensible, with walls, roof, and floor.\n" +
        "- All blocks at y=0 form the floor. y=1 is the first layer above ground. Negative y is underground.\n" +
        "- Use only valid Minecraft Java Edition 1.21.1 block IDs (e.g. minecraft:stone, minecraft:oak_planks).\n\n" +
        "Output ONLY a valid JSON array. Do NOT include markdown code blocks, explanations, or any other text.\n" +
        "Format: [{\"x\":0,\"y\":0,\"z\":0,\"block\":\"minecraft:stone\"}, ...]";

    private final HttpClient httpClient;

    private GeminiArchitectClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /**
     * Generates a Minecraft block blueprint using Gemini.
     *
     * @param prompt        Natural language description of the structure.
     * @param blockSize     Slide size (e.g. 5 from "5x5"). Controls footprint.
     * @param maxColors     Max block types (derived from resolution slider: 512→8, 1024→16...).
     * @param refImageBytes Optional reference image bytes (base64-encoded in payload). Can be null.
     * @param onProgress    Callback for progress strings (called from a virtual thread).
     * @return CompletableFuture resolving to a raw JSON array string of block entries.
     */
    public CompletableFuture<String> generateBlueprint(
            String prompt, int blockSize, int maxColors,
            byte[] refImageBytes,
            java.util.function.Consumer<String> onProgress) {

        String apiKey = XeltaCraftConfig.resolveGeminiApiKey();
        if (apiKey.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Gemini API key not set. Run: /xeltacraft setgeminikey <key>"));
        }

        String model = XeltaCraftConfig.GEMINI_MODEL.get();
        onProgress.accept("Sending to Gemini (" + model + ")...");

        // 1. Build the dynamic system prompt — inject user variables
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace("{prompt}", prompt)
            .replace("{block_size}", String.valueOf(blockSize))
            .replace("{max_colors}", String.valueOf(maxColors));

        // 2. Build the JSON request body for Gemini REST API
        String requestBody = buildRequestBody(systemPrompt, refImageBytes);

        String url = GEMINI_BASE + model + ":generateContent?key=" + apiKey;

        int timeout = XeltaCraftConfig.TIMEOUT_SECONDS.get() + 30;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(timeout))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new GeminiApiException("Gemini returned HTTP " + response.statusCode()
                        + ": " + extractGeminiError(response.body()));
                }

                onProgress.accept("Blueprint received. Parsing...");
                String jsonBlueprint = extractGeneratedText(response.body());

                XeltaCraftMod.LOGGER.info("[Gemini] Blueprint received ({} chars)", jsonBlueprint.length());
                if (XeltaCraftConfig.DEBUG_MODE.get()) {
                    XeltaCraftMod.LOGGER.debug("[Gemini] Raw response: {}",
                        jsonBlueprint.length() > 500 ? jsonBlueprint.substring(0, 500) + "..." : jsonBlueprint);
                }

                return jsonBlueprint;
            });
    }

    // =========================================================
    // Build Gemini REST API request body
    // =========================================================
    private String buildRequestBody(String systemPrompt, byte[] refImageBytes) {
        // Gemini REST API format:
        // {
        //   "system_instruction": { "parts": [{ "text": "..." }] },
        //   "generationConfig": { "temperature": 0.1 },
        //   "contents": [{ "parts": [{ "text": "Generate the blueprint." }] }]
        // }
        JsonObject root = new JsonObject();

        // System instruction (the dynamic template)
        JsonObject sysInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysText = new JsonObject();
        sysText.addProperty("text", systemPrompt);
        sysParts.add(sysText);
        sysInstruction.add("parts", sysParts);
        root.add("system_instruction", sysInstruction);

        // Generation config: low temperature for strict JSON adherence
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature", 0.1);
        genConfig.addProperty("maxOutputTokens", 65536); // Gemini 2.5-flash supports up to 64K output tokens
        root.add("generationConfig", genConfig);

        // Content parts
        JsonArray contentParts = new JsonArray();

        // Text trigger
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", "Generate the Minecraft block placement blueprint now. Output ONLY the JSON array.");
        contentParts.add(textPart);

        // Optional reference image
        if (refImageBytes != null && refImageBytes.length > 0) {
            JsonObject imagePart = new JsonObject();
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mime_type", "image/png");
            inlineData.addProperty("data", Base64.getEncoder().encodeToString(refImageBytes));
            imagePart.add("inline_data", inlineData);
            contentParts.add(imagePart);
        }

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        userContent.add("parts", contentParts);

        JsonArray contents = new JsonArray();
        contents.add(userContent);
        root.add("contents", contents);

        return root.toString();
    }

    // =========================================================
    // Extract generated text from Gemini response
    // =========================================================
    private String extractGeneratedText(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new GeminiApiException("No candidates in Gemini response: " + responseBody.substring(0, Math.min(300, responseBody.length())));
            }

            JsonObject candidate = candidates.get(0).getAsJsonObject();
            JsonObject content = candidate.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            String text = parts.get(0).getAsJsonObject().get("text").getAsString().trim();

            // Strip markdown fences if Gemini wraps them anyway (low-temp should prevent this)
            if (text.startsWith("```")) {
                String[] lines = text.split("\n");
                StringBuilder stripped = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    if (lines[i].startsWith("```")) break;
                    stripped.append(lines[i]).append("\n");
                }
                text = stripped.toString().trim();
            }

            return text;
        } catch (Exception e) {
            throw new GeminiApiException("Failed to parse Gemini response: " + e.getMessage());
        }
    }

    private String extractGeminiError(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("error")) {
                JsonObject err = root.getAsJsonObject("error");
                return err.has("message") ? err.get("message").getAsString() : responseBody;
            }
        } catch (Exception ignored) {}
        return responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody;
    }

    // =========================================================
    // Utility: map resolution selector index → max_colors
    // =========================================================
    public static int resolutionToMaxColors(int resolutionPx) {
        return switch (resolutionPx) {
            case 512  -> 8;
            case 1024 -> 16;
            case 1536 -> 24;
            case 2048 -> 32;
            default   -> 8;
        };
    }

    // =========================================================
    // Utility: parse slide size string (e.g. "5x5") → integer
    // =========================================================
    public static int parseSlideSizeToInt(String slideSizeStr) {
        if (slideSizeStr == null || slideSizeStr.isBlank()) return 3;
        String part = slideSizeStr.split("x")[0].trim();
        try { return Integer.parseInt(part); } catch (Exception e) { return 3; }
    }

    // =========================================================
    // Exception types
    // =========================================================
    public static class GeminiApiException extends RuntimeException {
        public GeminiApiException(String msg) { super(msg); }
    }
}

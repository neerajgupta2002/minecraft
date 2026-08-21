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
        "You are a Master Architect for the X-DAVE AI-Native Voxel Generation Engine. You design highly aesthetic, structurally sound, and beautiful buildings.\n" +
        "Your task is to analyze a natural language prompt and return a Semantic Object Graph.\n\n" +
        "The structure is described as: \"{prompt}\"\n\n" +
        "You must apply these constraints:\n" +
        "- The structure footprint must fit within {block_size} x {block_size} blocks.\n" +
        "- The overall scene should suggest a style. If the user's inventory contains blocks, you MUST set the 'style' and 'material' fields to exact block IDs from their inventory (e.g., 'minecraft:cobblestone') to ensure you use their available resources.\n" +
        "- The 'scale' of the scene should be determined (e.g. small, medium, large).\n" +
        "- IMPORTANT: Use pre-designed architectural macros whenever possible by setting 'type' to exactly 'gothic_arch' or 'watchtower_roof'.\n\n" +
        "Output ONLY a valid JSON object matching this schema:\n" +
        "{\n" +
        "  \"scene\": {\n" +
        "    \"objects\": [\n" +
        "      {\n" +
        "        \"type\": \"object_type_name_or_prefab\",\n" +
        "        \"position\": \"center/left/right/north/south/east/west\",\n" +
        "        \"scale\": 1.0,\n" +
        "        \"material\": \"optional_material_hint_or_prefab_id\"\n" +
        "      }\n" +
        "    ],\n" +
        "    \"style\": \"style_name\",\n" +
        "    \"scale\": \"overall_scale\"\n" +
        "  },\n" +
        "  \"house\": null_or_object_below,\n" +
        "  \"tower\": null_or_object_below,\n" +
        "  \"castle\": null_or_object_below\n" +
        "}\n\n" +
        "Populate AT MOST ONE of 'house', 'tower', 'castle' — whichever matches the request — and leave " +
        "the other two null. For every other structure type (walls, roofs, furniture, decorations, " +
        "anything else) leave all three null.\n\n" +
        "If the request describes a house/building/cottage, populate 'house' with real per-wall detail:\n" +
        "{\n" +
        "  \"width\": blocks_along_x_within_footprint,\n" +
        "  \"depth\": blocks_along_z_within_footprint,\n" +
        "  \"wallHeight\": 3_to_5,\n" +
        "  \"pitchHeight\": 2_to_4,\n" +
        "  \"roofType\": \"GABLE|HIP|FLAT\",\n" +
        "  \"walls\": [\n" +
        "    { \"facing\": \"NORTH|SOUTH|EAST|WEST\", \"openings\": [ { \"type\": \"DOOR|WINDOW\", \"offset\": blocks_from_wall_start } ] }\n" +
        "  ],\n" +
        "  \"annex\": null_or_object_below\n" +
        "}\n" +
        "Include exactly one wall entry per side (NORTH, SOUTH, EAST, WEST) that has an opening — omit a " +
        "side entirely if it has no door/window. Exactly one wall in the whole house should have a DOOR " +
        "opening (the entrance); any wall may have WINDOW openings. 'offset' must be strictly between 0 " +
        "and that wall's length (width for NORTH/SOUTH, depth for EAST/WEST) minus 1.\n\n" +
        "Choose 'roofType' to match the request: 'GABLE' for a classic two-sided pitched roof " +
        "(cottages, cabins, farmhouses — this is the default when the request doesn't imply anything else), " +
        "'HIP' for a roof that slopes down on all four sides instead of having flat gable ends " +
        "(bungalows, symmetric or villa-style homes), or 'FLAT' for a flat modern roof, optionally " +
        "with a low parapet ledge (modern, urban, or minimalist builds).\n\n" +
        "Only populate 'annex' when the request implies an attached porch, wing, sunroom, garage, or " +
        "otherwise asymmetric/L-shaped footprint (e.g. \"a cottage with a small porch\", \"an L-shaped " +
        "farmhouse\") — leave it null for a plain single-rectangle house. When populated:\n" +
        "{\n" +
        "  \"width\": blocks_along_x, \"depth\": blocks_along_z, \"wallHeight\": 2_to_4,\n" +
        "  \"offsetX\": blocks_from_main_house_origin, \"offsetZ\": blocks_from_main_house_origin,\n" +
        "  \"walls\": [ same_shape_as_the_main_houses_walls_array ]\n" +
        "}\n" +
        "The annex is a second room positioned relative to the main house's (0,0) corner — pick " +
        "offsetX/offsetZ so it sits FLUSH against one side of the main house rather than floating " +
        "away from it (e.g. offsetX = the main house's width to attach an annex to its east side, " +
        "offsetZ = 0 to align its north edge with the main house's north edge). Keep the annex smaller " +
        "than the main house.\n\n" +
        "If the request describes a tower/watchtower/spire, populate 'tower':\n" +
        "{\n" +
        "  \"radius\": 2_to_8,\n" +
        "  \"height\": 5_to_25,\n" +
        "  \"crenellated\": true_or_false,\n" +
        "  \"roofHeight\": 2_to_8\n" +
        "}\n\n" +
        "If the request describes a castle/keep/fortress, populate 'castle':\n" +
        "{\n" +
        "  \"width\": blocks_along_x_within_footprint,\n" +
        "  \"depth\": blocks_along_z_within_footprint,\n" +
        "  \"wallHeight\": 3_to_6,\n" +
        "  \"towerRadius\": 2_to_5,\n" +
        "  \"towerHeight\": 8_to_20,\n" +
        "  \"gateWall\": \"NORTH|SOUTH|EAST|WEST\",\n" +
        "  \"gateOffset\": blocks_from_that_walls_start\n" +
        "}\n" +
        "A castle always gets a real cylindrical tower at each of its 4 corners automatically — do not " +
        "describe the towers as separate objects.\n\n" +
        "Do NOT output blocks or coordinates. Output ONLY valid JSON. Do NOT include markdown code blocks, explanations, or any other text.";

    private final HttpClient httpClient;

    private GeminiArchitectClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    /** Total attempts (1 initial + retries) if Gemini's response doesn't parse as the expected schema shape. */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Deliberately NOT tied to {@link XeltaCraftConfig#TIMEOUT_SECONDS} — that config is shared
     * by every lightweight REST call in the mod (credits, sessions, ...) and is meant to be tuned
     * short for those. Gemini calls are a different class of request: multimodal (image + text),
     * structured-output schema, up to 64K output tokens. A reference-image request that legitimately
     * needs ~45-90s was being cut off at exactly TIMEOUT_SECONDS+30 (60s with the default config),
     * failing every image-attached build with "request timed out" while text-only prompts (which
     * finish in 15-40s) happened to sneak under the same limit.
     */
    private static final int GEMINI_TIMEOUT_SECONDS = 180;

    /**
     * Generates a Minecraft block blueprint using Gemini.
     *
     * @param prompt          Natural language description of the structure.
     * @param blockSize       Slide size (e.g. 5 from "5x5"). Controls footprint.
     * @param attachmentBytes Optional attachment: an image (sent as inline image data) or a text
     *                        file (its contents are appended to the prompt as reference context).
     *                        Can be null.
     * @param onProgress      Callback for progress strings (called from a virtual thread).
     * @return CompletableFuture resolving to a raw JSON array string of block entries.
     */
    public CompletableFuture<String> generateBlueprint(
            String prompt, int blockSize,
            byte[] attachmentBytes,
            java.util.function.Consumer<String> onProgress) {

        String apiKey = XeltaCraftConfig.resolveGeminiApiKey();
        if (apiKey.isBlank()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Gemini API key not set. Run: /xeltacraft setgeminikey <key>"));
        }

        String model = XeltaCraftConfig.GEMINI_MODEL.get();
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace("{prompt}", prompt)
            .replace("{block_size}", String.valueOf(blockSize));

        return attemptGenerate(model, apiKey, systemPrompt, attachmentBytes, onProgress, 1, null);
    }

    /**
     * One Gemini call, with schema-shape validation. On a malformed/incomplete response
     * (missing 'scene.objects', or not valid JSON at all) it retries up to
     * {@link #MAX_ATTEMPTS}, telling Gemini exactly what was wrong with its last answer
     * rather than silently failing the whole generation on the first bad response —
     * previously there was no retry at all here.
     */
    private CompletableFuture<String> attemptGenerate(
            String model, String apiKey, String systemPrompt, byte[] attachmentBytes,
            java.util.function.Consumer<String> onProgress, int attempt, String previousError) {

        onProgress.accept(attempt == 1
            ? "Sending to Gemini (" + model + ")..."
            : "Retrying Gemini (attempt " + attempt + "/" + MAX_ATTEMPTS + ")...");

        String effectivePrompt = previousError == null ? systemPrompt
            : systemPrompt + "\n\nYour previous response could not be used: " + previousError
                + "\nReturn ONLY the JSON object described above — no other text, no markdown fences.";

        String requestBody = buildRequestBody(effectivePrompt, attachmentBytes);
        String url = GEMINI_BASE + model + ":generateContent?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(GEMINI_TIMEOUT_SECONDS))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenCompose(response -> {
                if (response.statusCode() != 200) {
                    return CompletableFuture.<String>failedFuture(new GeminiApiException(
                        "Gemini returned HTTP " + response.statusCode() + ": " + extractGeminiError(response.body())));
                }

                onProgress.accept("Blueprint received. Validating...");
                String jsonBlueprint = extractGeneratedText(response.body());

                if (XeltaCraftConfig.DEBUG_MODE.get()) {
                    XeltaCraftMod.LOGGER.debug("[Gemini] Raw response: {}",
                        jsonBlueprint.length() > 500 ? jsonBlueprint.substring(0, 500) + "..." : jsonBlueprint);
                }

                String shapeError = validateSceneShape(jsonBlueprint);
                if (shapeError == null) {
                    XeltaCraftMod.LOGGER.info("[Gemini] Blueprint received ({} chars, attempt {})", jsonBlueprint.length(), attempt);
                    return CompletableFuture.completedFuture(jsonBlueprint);
                }

                XeltaCraftMod.LOGGER.warn("[Gemini] Attempt {}/{} produced an invalid response: {}", attempt, MAX_ATTEMPTS, shapeError);
                if (attempt >= MAX_ATTEMPTS) {
                    return CompletableFuture.<String>failedFuture(new GeminiApiException(
                        "Gemini did not return a valid blueprint after " + MAX_ATTEMPTS + " attempts (" + shapeError + ")"));
                }
                return attemptGenerate(model, apiKey, systemPrompt, attachmentBytes, onProgress, attempt + 1, shapeError);
            });
    }

    /**
     * Checks the response is valid JSON and has the minimum shape the rest of the pipeline
     * requires ({@code scene.objects} as an array) — without this, a malformed response used
     * to surface only as an opaque downstream "Blueprint parse failed" error.
     *
     * @return {@code null} if valid, otherwise a short description of what's wrong.
     */
    private String validateSceneShape(String jsonText) {
        try {
            JsonObject root = JsonParser.parseString(jsonText.trim()).getAsJsonObject();
            if (!root.has("scene") || !root.get("scene").isJsonObject()) {
                return "missing a 'scene' object";
            }
            JsonObject scene = root.getAsJsonObject("scene");
            if (!scene.has("objects") || !scene.get("objects").isJsonArray()) {
                return "missing a 'scene.objects' array";
            }
            if (scene.getAsJsonArray("objects").isEmpty()) {
                return "'scene.objects' was empty";
            }
            return null;
        } catch (Exception e) {
            return "not valid JSON (" + e.getMessage() + ")";
        }
    }

    // =========================================================
    // Build Gemini REST API request body
    // =========================================================
    private String buildRequestBody(String systemPrompt, byte[] attachmentBytes) {
        // Gemini REST API format:
        // {
        //   "system_instruction": { "parts": [{ "text": "..." }] },
        //   "generationConfig": { "temperature": 0.7, "responseMimeType": "application/json", "responseSchema": {...} },
        //   "contents": [{ "parts": [{ "text": "..." }, { "inline_data": {...} }] }]
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

        // Generation config: higher temperature for varied and creative designs.
        // responseMimeType + responseSchema force Gemini's native structured-output mode
        // instead of relying on prompt-only instructions ("output ONLY JSON") — previously
        // nothing constrained the response shape beyond hoping the model followed the prompt.
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature", 0.7);
        genConfig.addProperty("maxOutputTokens", 65536); // Gemini 2.5-flash supports up to 64K output tokens
        genConfig.addProperty("responseMimeType", "application/json");
        genConfig.add("responseSchema", buildResponseSchema());
        root.add("generationConfig", genConfig);

        // Content parts
        JsonArray contentParts = new JsonArray();

        // Text trigger
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", "Generate the Semantic Object Graph now. Output ONLY the JSON object.");
        contentParts.add(textPart);

        // Optional attachment — the client only ever sends us raw bytes (no filename/extension
        // travels over the network, see C2SGeneratePacket), so the actual format is sniffed from
        // the file's magic bytes rather than hardcoded. An image goes in as inline image data;
        // a text file (txt/json/md, or anything else that decodes as valid UTF-8) is folded into
        // the prompt as reference context instead, since Gemini's inline_data path here is only
        // wired up for images. Anything that's neither is dropped rather than sent as mislabeled
        // image bytes (which used to silently default to "image/png" and just fail server-side).
        String imageMimeType = detectImageMimeType(attachmentBytes);
        if (imageMimeType != null) {
            JsonObject imagePart = new JsonObject();
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mime_type", imageMimeType);
            inlineData.addProperty("data", Base64.getEncoder().encodeToString(attachmentBytes));
            imagePart.add("inline_data", inlineData);
            contentParts.add(imagePart);
        } else if (attachmentBytes != null && attachmentBytes.length > 0 && looksLikeText(attachmentBytes)) {
            String text = new String(attachmentBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.length() > MAX_ATTACHMENT_TEXT_CHARS) {
                text = text.substring(0, MAX_ATTACHMENT_TEXT_CHARS) + "\n...[truncated]";
            }
            JsonObject filePart = new JsonObject();
            filePart.addProperty("text", "Reference file contents:\n```\n" + text + "\n```");
            contentParts.add(filePart);
        } else if (attachmentBytes != null && attachmentBytes.length > 0) {
            XeltaCraftMod.LOGGER.warn("[Gemini] Attachment is neither a recognized image nor valid text — ignoring it ({} bytes)", attachmentBytes.length);
        }

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        userContent.add("parts", contentParts);

        JsonArray contents = new JsonArray();
        contents.add(userContent);
        root.add("contents", contents);

        return root.toString();
    }

    /**
     * Gemini structured-output schema matching the object shape {@link #SYSTEM_PROMPT_TEMPLATE}
     * describes in prose. Field names/types use Gemini's OpenAPI-subset schema format.
     */
    private JsonObject buildResponseSchema() {
        JsonObject sceneObjectSchema = new JsonObject();
        sceneObjectSchema.addProperty("type", "OBJECT");
        JsonObject sceneObjectProps = new JsonObject();
        sceneObjectProps.add("type", typeSchema("STRING"));
        sceneObjectProps.add("position", typeSchema("STRING"));
        sceneObjectProps.add("scale", typeSchema("NUMBER"));
        sceneObjectProps.add("material", typeSchema("STRING"));
        sceneObjectSchema.add("properties", sceneObjectProps);
        JsonArray sceneObjectRequired = new JsonArray();
        sceneObjectRequired.add("type"); sceneObjectRequired.add("position");
        sceneObjectRequired.add("scale"); sceneObjectRequired.add("material");
        sceneObjectSchema.add("required", sceneObjectRequired);

        JsonObject objectsArraySchema = new JsonObject();
        objectsArraySchema.addProperty("type", "ARRAY");
        objectsArraySchema.add("items", sceneObjectSchema);

        JsonObject sceneSchema = new JsonObject();
        sceneSchema.addProperty("type", "OBJECT");
        JsonObject sceneProps = new JsonObject();
        sceneProps.add("objects", objectsArraySchema);
        sceneProps.add("style", typeSchema("STRING"));
        sceneProps.add("scale", typeSchema("STRING"));
        sceneSchema.add("properties", sceneProps);
        JsonArray sceneRequired = new JsonArray();
        sceneRequired.add("objects"); sceneRequired.add("style"); sceneRequired.add("scale");
        sceneSchema.add("required", sceneRequired);

        // Optional 'house' field — real per-wall detail, populated only for house/building/cottage
        // requests. Nullable + omitted from `required` so every other structure type is unaffected.
        JsonObject openingSchema = new JsonObject();
        openingSchema.addProperty("type", "OBJECT");
        JsonObject openingProps = new JsonObject();
        openingProps.add("type", typeSchema("STRING"));
        openingProps.add("offset", typeSchema("INTEGER"));
        openingSchema.add("properties", openingProps);
        JsonArray openingRequired = new JsonArray();
        openingRequired.add("type"); openingRequired.add("offset");
        openingSchema.add("required", openingRequired);

        JsonObject openingsArraySchema = new JsonObject();
        openingsArraySchema.addProperty("type", "ARRAY");
        openingsArraySchema.add("items", openingSchema);

        JsonObject wallSchema = new JsonObject();
        wallSchema.addProperty("type", "OBJECT");
        JsonObject wallProps = new JsonObject();
        wallProps.add("facing", typeSchema("STRING"));
        wallProps.add("openings", openingsArraySchema);
        wallSchema.add("properties", wallProps);
        JsonArray wallRequired = new JsonArray();
        wallRequired.add("facing"); wallRequired.add("openings");
        wallSchema.add("required", wallRequired);

        JsonObject wallsArraySchema = new JsonObject();
        wallsArraySchema.addProperty("type", "ARRAY");
        wallsArraySchema.add("items", wallSchema);

        // 'annex' — a second, smaller room offset relative to the main house, giving houses a
        // real L/T-shaped footprint instead of always being a single rectangle.
        JsonObject annexSchema = new JsonObject();
        annexSchema.addProperty("type", "OBJECT");
        annexSchema.addProperty("nullable", true);
        JsonObject annexProps = new JsonObject();
        annexProps.add("width", typeSchema("INTEGER"));
        annexProps.add("depth", typeSchema("INTEGER"));
        annexProps.add("wallHeight", typeSchema("INTEGER"));
        annexProps.add("offsetX", typeSchema("INTEGER"));
        annexProps.add("offsetZ", typeSchema("INTEGER"));
        annexProps.add("walls", wallsArraySchema);
        annexSchema.add("properties", annexProps);

        JsonObject houseSchema = new JsonObject();
        houseSchema.addProperty("type", "OBJECT");
        houseSchema.addProperty("nullable", true);
        JsonObject houseProps = new JsonObject();
        houseProps.add("width", typeSchema("INTEGER"));
        houseProps.add("depth", typeSchema("INTEGER"));
        houseProps.add("wallHeight", typeSchema("INTEGER"));
        houseProps.add("pitchHeight", typeSchema("INTEGER"));
        houseProps.add("roofType", typeSchema("STRING"));
        houseProps.add("walls", wallsArraySchema);
        houseProps.add("annex", annexSchema);
        houseSchema.add("properties", houseProps);

        // Optional 'tower' field — real cylindrical geometry, populated only for tower/watchtower/spire.
        JsonObject towerSchema = new JsonObject();
        towerSchema.addProperty("type", "OBJECT");
        towerSchema.addProperty("nullable", true);
        JsonObject towerProps = new JsonObject();
        towerProps.add("radius", typeSchema("INTEGER"));
        towerProps.add("height", typeSchema("INTEGER"));
        towerProps.add("crenellated", typeSchema("BOOLEAN"));
        towerProps.add("roofHeight", typeSchema("INTEGER"));
        towerSchema.add("properties", towerProps);

        // Optional 'castle' field — curtain wall + gate; corner towers are automatic, not described here.
        JsonObject castleSchema = new JsonObject();
        castleSchema.addProperty("type", "OBJECT");
        castleSchema.addProperty("nullable", true);
        JsonObject castleProps = new JsonObject();
        castleProps.add("width", typeSchema("INTEGER"));
        castleProps.add("depth", typeSchema("INTEGER"));
        castleProps.add("wallHeight", typeSchema("INTEGER"));
        castleProps.add("towerRadius", typeSchema("INTEGER"));
        castleProps.add("towerHeight", typeSchema("INTEGER"));
        castleProps.add("gateWall", typeSchema("STRING"));
        castleProps.add("gateOffset", typeSchema("INTEGER"));
        castleSchema.add("properties", castleProps);

        JsonObject rootSchema = new JsonObject();
        rootSchema.addProperty("type", "OBJECT");
        JsonObject rootProps = new JsonObject();
        rootProps.add("scene", sceneSchema);
        rootProps.add("house", houseSchema);
        rootProps.add("tower", towerSchema);
        rootProps.add("castle", castleSchema);
        rootSchema.add("properties", rootProps);
        JsonArray rootRequired = new JsonArray();
        rootRequired.add("scene");
        rootSchema.add("required", rootRequired);

        return rootSchema;
    }

    private JsonObject typeSchema(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    /** Sniffs the image format from its magic bytes. Returns null if it isn't a recognized image. */
    private static String detectImageMimeType(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** Max characters of an attached text file's contents folded into the prompt. */
    private static final int MAX_ATTACHMENT_TEXT_CHARS = 20_000;

    /** True if the bytes decode cleanly as UTF-8 text (used to tell a text attachment from binary junk). */
    private static boolean looksLikeText(byte[] bytes) {
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
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

            // Strip markdown fences if Gemini wraps them anyway (responseMimeType=application/json
            // should prevent this, but it's a cheap safety net rather than a hard guarantee)
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

package ai.xelta.craft.sdk.superxelta;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.MultipartBodyBuilder;
import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.config.XeltaCraftConfig;
import ai.xelta.craft.sdk.superxelta.XeltaSSEParser.XeltaSSEFrame;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * XeltaSuperComputerClient — SDK for the Nexus Deep Agent backend (the "Super Computer").
 *
 * Implements the REST surface documented in API.md:
 *   POST   /upload                                       — asset upload
 *   POST   /sessions                                      — create a session
 *   DELETE /sessions/{id}                                 — delete a session
 *   POST   /sessions/{id}/chat                             — blocking chat turn
 *   POST   /sessions/{id}/chat/stream                      — SSE streaming chat turn
 *   POST   /sessions/{id}/approvals/{approval_id}/respond  — approve/reject a pending generation
 *   GET    /api/credits                                    — credit balance
 *
 * Not implemented here: GET/PATCH /sessions, GET /api/history, GET /api/features, and the
 * /community/* endpoints — nothing in the mod currently drives them; add on demand following
 * the same pattern.
 *
 * Auth: `Authorization: Bearer <jwt>` — the same xelta.ai platform token already held by
 * {@link XeltaSessionManager}, which is exactly what API.md's auth dependency accepts first.
 */
public final class XeltaSuperComputerClient {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static XeltaSuperComputerClient INSTANCE;
    public static XeltaSuperComputerClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaSuperComputerClient();
        return INSTANCE;
    }

    private static final String UA = "XeltaCraft-Mod/1.0.0 (NeoForge)";
    private final HttpClient http;

    private XeltaSuperComputerClient() {
        this.http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Sessions
    // ─────────────────────────────────────────────────────────────────────────

    public record SessionResult(boolean success, String sessionId, String title, String createdAt, String error) {
        public static SessionResult ok(String id, String title, String createdAt) {
            return new SessionResult(true, id, title, createdAt, "");
        }
        public static SessionResult fail(String error) {
            return new SessionResult(false, "", "", "", error);
        }
    }

    /** POST /sessions — creates a new, empty chat session. */
    public CompletableFuture<SessionResult> createSession() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base() + "/sessions"))
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", UA)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(XeltaCraftConfig.TIMEOUT_SECONDS.get()))
                    .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    return SessionResult.ok(str(json, "session_id", ""), str(json, "title", ""),
                        str(json, "created_at", ""));
                }
                return SessionResult.fail("Create session failed (HTTP " + resp.statusCode() + "): "
                    + extractError(resp.body()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SessionResult.fail("Interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[SuperComputer] Create session error: {}", e.getMessage(), e);
                return SessionResult.fail(e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** DELETE /sessions/{id} — used when a cached session id turns out to be stale/invalid. */
    public CompletableFuture<Boolean> deleteSession(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base() + "/sessions/" + sessionId))
                    .header("Authorization", "Bearer " + token())
                    .header("User-Agent", UA)
                    .DELETE()
                    .timeout(Duration.ofSeconds(XeltaCraftConfig.TIMEOUT_SECONDS.get()))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Upload — POST /upload
    //  Response: { "filename": "...", "path": "...", "s3_url": "..." }
    // ─────────────────────────────────────────────────────────────────────────

    public record UploadResult(boolean success, String filename, String url, String error) {
        public static UploadResult ok(String filename, String url) {
            return new UploadResult(true, filename, url, "");
        }
        public static UploadResult fail(String error) {
            return new UploadResult(false, "", "", error);
        }
    }

    public CompletableFuture<UploadResult> uploadFile(byte[] fileBytes, String filename) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MultipartBodyBuilder mp = new MultipartBodyBuilder()
                    .addFile("file", filename, fileBytes, guessMime(filename));
                byte[] body = mp.build();

                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base() + "/upload"))
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", mp.getContentType())
                    .header("User-Agent", UA)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    String url = str(json, "s3_url", str(json, "path", ""));
                    return UploadResult.ok(str(json, "filename", filename), url);
                }
                return UploadResult.fail("Upload failed (HTTP " + resp.statusCode() + "): " + extractError(resp.body()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return UploadResult.fail("Interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[SuperComputer] Upload error: {}", e.getMessage(), e);
                return UploadResult.fail(e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Chat — blocking
    //  POST /sessions/{id}/chat
    //  Body: { content, image, image2, skill, skip_history }
    //  Response: { type: "image"|"website"|"text", content, image_url, skill, credits_used, remaining_credits }
    // ─────────────────────────────────────────────────────────────────────────

    public record ChatResult(boolean success, String type, String content, String resultUrl,
                              String skill, int creditsUsed, int remainingCredits, String error) {
        public static ChatResult ok(String type, String content, String resultUrl, String skill,
                                     int creditsUsed, int remainingCredits) {
            return new ChatResult(true, type, content, resultUrl, skill, creditsUsed, remainingCredits, "");
        }
        public static ChatResult fail(String error) {
            return new ChatResult(false, "", "", "", "", 0, -1, error);
        }
        public boolean hasResult() { return resultUrl != null && !resultUrl.isBlank(); }
        public boolean isImage()   { return "image".equals(type); }
        public boolean isWebsite() { return "website".equals(type); }
    }

    public CompletableFuture<ChatResult> chat(String sessionId, String content, String image,
                                               String image2, String skill, boolean skipHistory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = jsonPost(base() + "/sessions/" + sessionId + "/chat",
                    chatRequestBody(content, image, image2, skill, skipHistory).toString(), token());
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                    return parseChatResponse(resp.body());
                }
                if (resp.statusCode() == 402) {
                    return ChatResult.fail(extractError(resp.body()));
                }
                return ChatResult.fail("Chat failed (HTTP " + resp.statusCode() + "): " + extractError(resp.body()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ChatResult.fail("Interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[SuperComputer] Chat error: {}", e.getMessage(), e);
                return ChatResult.fail(e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private ChatResult parseChatResponse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return ChatResult.ok(
                str(json, "type", "text"),
                str(json, "content", ""),
                str(json, "image_url", ""),
                json.has("skill") && !json.get("skill").isJsonNull() ? json.get("skill").getAsString() : null,
                intOf(json, "credits_used", 0),
                intOf(json, "remaining_credits", -1));
        } catch (Exception e) {
            return ChatResult.ok("text", body, "", null, 0, -1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Chat — SSE streaming
    //  POST /sessions/{id}/chat/stream
    // ─────────────────────────────────────────────────────────────────────────

    /** Callbacks fired as the SSE stream is parsed. All are called off the render thread. */
    public interface ChatStreamListener {
        /** A chunk of the model's live-generated reply. */
        default void onTextDelta(String delta) {}

        /**
         * `generate_image` is about to run — must resolve before {@link #onApprovalRequired}'s
         * caller proceeds, since this method blocks the reading thread until it completes.
         * The server itself times out (and treats it as rejected) after ~240s with no response.
         */
        CompletableFuture<Boolean> onApprovalRequired(String approvalId, int credits, int balance, String resourceType);

        /** Fired once the resolved skill is known (may be null for a plain conversational reply). */
        default void onSkillResolved(String skill) {}
    }

    public CompletableFuture<ChatResult> chatStream(String sessionId, String content, String image,
                                                     String image2, String skill, boolean skipHistory,
                                                     ChatStreamListener listener) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = jsonPost(base() + "/sessions/" + sessionId + "/chat/stream",
                    chatRequestBody(content, image, image2, skill, skipHistory).toString(), token());

                HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                    return ChatResult.fail("Stream failed (HTTP " + resp.statusCode() + ")");
                }

                StringBuilder textBuf = new StringBuilder();
                String[] resultUrl   = {""};
                String[] resolvedSkill = {null};
                boolean[] hadError    = {false};
                String[] errorMsg     = {""};

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        XeltaSSEFrame frame = XeltaSSEParser.parseLine(line);
                        if (frame == null) continue;

                        switch (frame.kind()) {
                            case KEEP_ALIVE -> {}
                            case TEXT_DELTA -> {
                                if (!frame.text().isEmpty()) {
                                    textBuf.append(frame.text());
                                    listener.onTextDelta(frame.text());
                                }
                            }
                            case RETRACT -> {
                                int newLen = Math.max(0, textBuf.length() - frame.retractChars());
                                textBuf.setLength(newLen);
                            }
                            case APPROVAL_REQUIRED -> {
                                boolean approved;
                                try {
                                    approved = Boolean.TRUE.equals(listener.onApprovalRequired(
                                        frame.approvalId(), frame.approvalCredits(),
                                        frame.approvalBalance(), frame.approvalResourceType()).join());
                                } catch (Exception e) {
                                    approved = false;
                                }
                                respondApproval(sessionId, frame.approvalId(), approved).join();
                            }
                            case SKILL_TAG -> {
                                resolvedSkill[0] = frame.skill();
                                listener.onSkillResolved(frame.skill());
                                if (frame.skillTagIsError()) {
                                    hadError[0] = true;
                                    errorMsg[0] = frame.text();
                                }
                            }
                            case IMAGE_FRAME -> resultUrl[0] = frame.resultUrl();
                            case BILLING_ERROR -> {
                                hadError[0] = true;
                                errorMsg[0] = frame.billingError();
                            }
                            case DONE -> { return finalizeStreamResult(hadError[0], errorMsg[0],
                                textBuf.toString(), resultUrl[0], resolvedSkill[0]); }
                        }
                    }
                }

                // Stream ended without an explicit [DONE] — treat what we have as final.
                return finalizeStreamResult(hadError[0], errorMsg[0], textBuf.toString(), resultUrl[0], resolvedSkill[0]);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ChatResult.fail("Interrupted");
            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[SuperComputer] Stream error: {}", e.getMessage(), e);
                return ChatResult.fail(e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static ChatResult finalizeStreamResult(boolean hadError, String errorMsg, String text,
                                                     String resultUrl, String skill) {
        if (hadError) return ChatResult.fail(errorMsg.isBlank() ? "Generation failed." : errorMsg);

        String type = "text";
        if (!resultUrl.isBlank()) {
            String lower = resultUrl.toLowerCase();
            type = lower.endsWith(".html") ? "website" : "image";
        }
        // credits_used/remaining_credits aren't carried over SSE — callers should refresh
        // balance via fetchCredits() once hasResult() is true, per API.md's documented signal.
        return ChatResult.ok(type, text, resultUrl, skill, 0, -1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Approvals — POST /sessions/{id}/approvals/{approval_id}/respond
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<Boolean> respondApproval(String sessionId, String approvalId, boolean approved) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("decision", approved ? "approved" : "rejected");

                HttpRequest req = jsonPost(base() + "/sessions/" + sessionId + "/approvals/" + approvalId + "/respond",
                    body.toString(), token());
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200;

            } catch (Exception e) {
                XeltaCraftMod.LOGGER.error("[SuperComputer] Approval respond error: {}", e.getMessage(), e);
                return false;
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Credits — GET /api/credits
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the balance, or -1 if unavailable/not authenticated. */
    public CompletableFuture<Integer> fetchCredits() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base() + "/api/credits"))
                    .header("Authorization", "Bearer " + token())
                    .header("User-Agent", UA)
                    .GET()
                    .timeout(Duration.ofSeconds(XeltaCraftConfig.TIMEOUT_SECONDS.get()))
                    .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return -1;
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                return intOf(json, "balance", -1);
            } catch (Exception e) {
                return -1;
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static JsonObject chatRequestBody(String content, String image, String image2,
                                               String skill, boolean skipHistory) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if (image  != null && !image.isBlank())  body.addProperty("image",  image);
        if (image2 != null && !image2.isBlank()) body.addProperty("image2", image2);
        if (skill  != null && !skill.isBlank())  body.addProperty("skill",  skill);
        if (skipHistory) body.addProperty("skip_history", true);
        return body;
    }

    private HttpRequest jsonPost(String url, String jsonBody, String token) {
        int timeout = XeltaCraftConfig.TIMEOUT_SECONDS.get();
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type",  "application/json")
            .header("Accept",        "application/json, text/event-stream")
            .header("User-Agent",    UA)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(timeout))
            .build();
    }

    private String extractError(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("detail")) return json.get("detail").getAsString();
        } catch (Exception ignored) {}
        return body == null ? "" : (body.length() > 160 ? body.substring(0, 160) : body);
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()
            ? obj.get(key).getAsString() : def;
    }

    private static int intOf(JsonObject obj, String key, int def) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()
                ? obj.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String token() {
        return XeltaSessionManager.getInstance().getAccessToken();
    }

    private static String base() {
        return XeltaCraftConfig.SUPER_XELTA_URL.get();
    }

    private static String guessMime(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".png"))  return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}

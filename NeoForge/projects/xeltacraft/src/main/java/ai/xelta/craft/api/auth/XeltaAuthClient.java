package ai.xelta.craft.api.auth;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.infra.http.XeltaHttpClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * XeltaAuthClient — Authentication Layer
 *
 * Handles all interactions with the Xelta Authentication API:
 *
 *   POST /auth/login       → email + password → access_token + refresh_token
 *   POST /auth/refresh     → refresh_token    → new access_token
 *   POST /auth/logout      → access_token     → invalidate session
 *
 * This client does NOT hold any tokens — that is XeltaSessionManager's job.
 * This client is purely an API boundary: it makes the HTTP call and returns the result.
 *
 * Auth base URL: https://devpmt.xelta.ai/api  (same as credit API)
 */
public class XeltaAuthClient {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static XeltaAuthClient INSTANCE;

    public static XeltaAuthClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaAuthClient();
        return INSTANCE;
    }

    // ── Auth base URL ─────────────────────────────────────────────────────────
    // Uses the same base as credit API since auth lives under devpmt.xelta.ai
    private static final String AUTH_BASE_URL = "https://devpmt.xelta.ai/api";

    private XeltaAuthClient() {}

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticates with email + password.
     *
     * @param email    User's Xelta account email
     * @param password User's Xelta account password
     * @return future resolving to a LoginResult
     */
    public CompletableFuture<LoginResult> login(String email, String password) {
        String json = String.format("{\"email\":\"%s\",\"password\":\"%s\"}",
            escapeJson(email), escapeJson(password));

        return XeltaHttpClient.getInstance()
            .postJsonPublic(AUTH_BASE_URL + "/auth/login", json)
            .thenApply(this::parseLoginResponse)
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.error("[Auth] Login network error: {}", e.getMessage());
                return LoginResult.failure("Network error: " + e.getMessage());
            });
    }

    // ── Token Refresh ─────────────────────────────────────────────────────────

    /**
     * Exchanges a refresh token for a new access token.
     *
     * @param refreshToken The stored refresh token
     * @return future resolving to a new access token, or empty string on failure
     */
    public CompletableFuture<String> refresh(String refreshToken) {
        String json = String.format("{\"refresh_token\":\"%s\"}", escapeJson(refreshToken));

        return XeltaHttpClient.getInstance()
            .postJsonPublic(AUTH_BASE_URL + "/auth/refresh", json)
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    XeltaCraftMod.LOGGER.warn("[Auth] Token refresh failed: HTTP {}", response.statusCode());
                    return "";
                }
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    return extractString(root, "access_token", "token", "data");
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[Auth] Token refresh parse error: {}", e.getMessage());
                    return "";
                }
            })
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.warn("[Auth] Token refresh network error: {}", e.getMessage());
                return "";
            });
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Invalidates the current session on the server.
     * Client-side tokens should be cleared regardless of whether this succeeds.
     *
     * @param accessToken Current access token
     */
    public CompletableFuture<Void> logout(String accessToken) {
        return XeltaHttpClient.getInstance()
            .postJson(AUTH_BASE_URL + "/auth/logout", accessToken, "{}")
            .thenRun(() -> XeltaCraftMod.LOGGER.info("[Auth] Logout successful"))
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.warn("[Auth] Logout request failed (tokens cleared locally): {}", e.getMessage());
                return null;
            });
    }

    // ── Response Parsing ──────────────────────────────────────────────────────

    private LoginResult parseLoginResponse(HttpResponse<String> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return LoginResult.failure("Invalid email or password.");
        }
        if (response.statusCode() == 429) {
            return LoginResult.failure("Too many login attempts. Please wait.");
        }
        if (response.statusCode() != 200) {
            return LoginResult.failure("Login failed (HTTP " + response.statusCode() + ")");
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();

            // Handle { "data": { "access_token": "...", "refresh_token": "..." } }
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data")
                : root;

            String accessToken  = extractString(data, "access_token", "token", "jwt");
            String refreshToken = extractString(data, "refresh_token", "refresh");

            if (accessToken == null || accessToken.isBlank()) {
                XeltaCraftMod.LOGGER.warn("[Auth] Login response missing access_token. Body: {}", response.body());
                return LoginResult.failure("Server did not return an access token.");
            }

            XeltaCraftMod.LOGGER.info("[Auth] Login successful — token received.");
            return LoginResult.success(accessToken, refreshToken != null ? refreshToken : "");

        } catch (Exception e) {
            XeltaCraftMod.LOGGER.error("[Auth] Login parse error: {} | body: {}", e.getMessage(), response.body());
            return LoginResult.failure("Unexpected login response. Please try again.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String extractString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Result Types ──────────────────────────────────────────────────────────

    /**
     * Result of a login attempt.
     */
    public record LoginResult(
        boolean success,
        String accessToken,
        String refreshToken,
        String errorMessage
    ) {
        public static LoginResult success(String access, String refresh) {
            return new LoginResult(true, access, refresh, "");
        }
        public static LoginResult failure(String message) {
            return new LoginResult(false, "", "", message);
        }
    }
}

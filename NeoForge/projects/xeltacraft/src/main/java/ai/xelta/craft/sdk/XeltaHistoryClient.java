package ai.xelta.craft.sdk;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.infra.http.XeltaHttpClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * XeltaHistoryClient — SDK Layer
 *
 * Fetches generation history from the Xelta History API.
 *
 * Endpoint: GET https://devpmt.xelta.ai/api/history?limit={n}&offset={o}
 *
 * Response shape (expected):
 * {
 *   "success": true,
 *   "data": [
 *     {
 *       "id": "task_abc123",
 *       "type": "image" | "video" | "avatar" | "mix",
 *       "prompt": "A cozy house...",
 *       "status": "completed" | "failed",
 *       "result_url": "https://...",
 *       "credits_used": 10,
 *       "created_at": "2026-07-28T10:00:00Z"
 *     }
 *   ],
 *   "total": 42
 * }
 */
public class XeltaHistoryClient {

    private static final String HISTORY_BASE_URL = "https://devpmt.xelta.ai/api";

    private final XeltaHttpClient    http;
    private final XeltaSessionManager session;

    public XeltaHistoryClient(XeltaHttpClient http, XeltaSessionManager session) {
        this.http    = http;
        this.session = session;
    }

    // ── Fetch History ─────────────────────────────────────────────────────────

    /**
     * Fetches the most recent generation history items for the current user.
     *
     * @param limit   Number of items to fetch (max 50)
     * @param offset  Pagination offset
     * @return future resolving to a HistoryResult
     */
    public CompletableFuture<HistoryResult> fetchHistory(int limit, int offset) {
        String url = HISTORY_BASE_URL + "/history?limit=" + limit + "&offset=" + offset;
        String token = session.getAccessToken();

        if (token.isBlank()) {
            return CompletableFuture.completedFuture(
                HistoryResult.failure("Not authenticated. Please sign in first."));
        }

        return http.get(url, token)
            .thenApply(response -> {
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    return HistoryResult.failure("Session expired. Please sign in again.");
                }
                if (response.statusCode() != 200) {
                    return HistoryResult.failure("History service returned HTTP " + response.statusCode());
                }
                return parseHistory(response.body());
            })
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.warn("[History] Fetch error: {}", e.getMessage());
                return HistoryResult.failure("Could not reach history service: " + e.getMessage());
            });
    }

    /**
     * Convenience method — fetches the 20 most recent items.
     */
    public CompletableFuture<HistoryResult> fetchRecent(int count) {
        return fetchHistory(Math.min(count, 50), 0);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private HistoryResult parseHistory(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            // Data can be at root level or nested under "data"
            JsonArray items;
            if (root.has("data") && root.get("data").isJsonArray()) {
                items = root.getAsJsonArray("data");
            } else if (root.isJsonObject()) {
                // Try root-level array fields
                items = findArray(root, "items", "results", "history");
                if (items == null) items = new JsonArray();
            } else {
                items = new JsonArray();
            }

            int total = root.has("total") ? root.get("total").getAsInt() : items.size();

            List<HistoryItem> list = new ArrayList<>();
            for (JsonElement el : items) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                list.add(new HistoryItem(
                    str(obj, "id", "task_id", "job_id"),
                    str(obj, "type", "service"),
                    str(obj, "prompt", "description"),
                    str(obj, "status"),
                    str(obj, "result_url", "output_url", "image_url", "video_url"),
                    str(obj, "thumbnail_url", "thumbnail"),
                    intVal(obj, "credits_used", "credits"),
                    str(obj, "created_at", "timestamp", "date")
                ));
            }

            return HistoryResult.success(list, total);

        } catch (Exception e) {
            XeltaCraftMod.LOGGER.warn("[History] Parse error: {} | body: {}", e.getMessage(),
                body.length() > 200 ? body.substring(0, 200) + "..." : body);
            return HistoryResult.failure("Could not parse history response.");
        }
    }

    // ── Data Types ────────────────────────────────────────────────────────────

    /**
     * A single history entry.
     */
    public record HistoryItem(
        String id,
        String type,
        String prompt,
        String status,
        String resultUrl,
        String thumbnailUrl,
        int    creditsUsed,
        String createdAt
    ) {
        public boolean isCompleted() { return "completed".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status); }
        public boolean isFailed()    { return "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status); }
        public boolean hasResult()   { return resultUrl != null && !resultUrl.isBlank(); }
    }

    /**
     * Result container for a history fetch operation.
     */
    public record HistoryResult(
        boolean success,
        List<HistoryItem> items,
        int total,
        String errorMessage
    ) {
        public static HistoryResult success(List<HistoryItem> items, int total) {
            return new HistoryResult(true, Collections.unmodifiableList(items), total, "");
        }
        public static HistoryResult failure(String message) {
            return new HistoryResult(false, Collections.emptyList(), 0, message);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsString();
            }
        }
        return "";
    }

    private static int intVal(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                try { return obj.get(key).getAsInt(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static JsonArray findArray(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonArray()) return obj.getAsJsonArray(key);
        }
        return null;
    }
}

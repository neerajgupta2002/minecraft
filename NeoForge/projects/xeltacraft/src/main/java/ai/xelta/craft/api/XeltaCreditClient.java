package ai.xelta.craft.api;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.config.XeltaCraftConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Credit balance client.
 *
 * The balance endpoint is optional for gameplay. Generation can still work even
 * when this service rejects a token or is temporarily unavailable.
 */
public class XeltaCreditClient {

    private static XeltaCreditClient INSTANCE;

    public static XeltaCreditClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaCreditClient();
        return INSTANCE;
    }

    public enum BalanceError {
        NONE,
        AUTH,
        HTTP,
        PARSE,
        NETWORK
    }

    public record BalanceResult(int balance, BalanceError error, String message) {
        public boolean success() {
            return error == BalanceError.NONE;
        }
    }

    private final HttpClient httpClient;

    private XeltaCreditClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    /**
     * Backward-compatible helper used by optional GUI refreshes.
     *
     * @return balance, or -1 if unavailable.
     */
    public CompletableFuture<Integer> fetchBalance(String token) {
        return fetchBalanceResult(token).thenApply(result -> result.success() ? result.balance() : -1);
    }

    public CompletableFuture<BalanceResult> fetchBalanceResult(String token) {
        String baseUrl = XeltaCraftConfig.CREDIT_API_URL.get();
        String cleanToken = normalizeToken(token);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/users/credits"))
            .header("Authorization", "Bearer " + cleanToken)
            .header("Content-Type", "application/json")
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .GET()
            .timeout(Duration.ofSeconds(8))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    String error = extractError(response.body());
                    XeltaCraftMod.LOGGER.warn("[Credits] HTTP {} from /users/credits: {}",
                        response.statusCode(), error);

                    if (response.statusCode() == 401 || response.statusCode() == 403) {
                        return new BalanceResult(-1, BalanceError.AUTH,
                            "Credit service rejected this token. Re-run /xeltacraft settoken with a fresh xelta.ai JWT.");
                    }

                    return new BalanceResult(-1, BalanceError.HTTP,
                        "Credit service returned HTTP " + response.statusCode() + ".");
                }

                return parseBalance(response.body());
            })
            .exceptionally(e -> {
                XeltaCraftMod.LOGGER.warn("[Credits] Balance fetch failed: {}", e.getMessage());
                return new BalanceResult(-1, BalanceError.NETWORK, "Could not reach the credit service.");
            });
    }

    public CompletableFuture<String> fetchBalanceDisplay(String token) {
        return fetchBalance(token).thenApply(balance -> {
            if (balance < 0) return "";
            if (balance >= 1_000_000) return (balance / 1_000_000) + "M credits";
            if (balance >= 1_000) return (balance / 1_000) + "K credits";
            return balance + " credits";
        });
    }

    private BalanceResult parseBalance(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            if (root.has("data")) {
                if (root.get("data").isJsonPrimitive()) {
                    return new BalanceResult(root.get("data").getAsInt(), BalanceError.NONE, "");
                }
                if (root.get("data").isJsonObject()) {
                    JsonObject data = root.getAsJsonObject("data");
                    Integer balance = getInt(data, "balance", "credits", "remaining_credits");
                    if (balance != null) return new BalanceResult(balance, BalanceError.NONE, "");
                }
            }

            Integer balance = getInt(root, "balance", "credits", "remaining_credits");
            if (balance != null) return new BalanceResult(balance, BalanceError.NONE, "");

            XeltaCraftMod.LOGGER.warn("[Credits] Unexpected response format: {}", body);
            return new BalanceResult(-1, BalanceError.PARSE,
                "Credit service returned an unexpected response.");
        } catch (Exception e) {
            XeltaCraftMod.LOGGER.warn("[Credits] Parse error: {}", e.getMessage());
            return new BalanceResult(-1, BalanceError.PARSE, "Could not parse the credit response.");
        }
    }

    private static Integer getInt(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsInt();
            }
        }
        return null;
    }

    private static String normalizeToken(String token) {
        String cleaned = token == null ? "" : token.trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.regionMatches(true, 0, "Bearer ", 0, 7)) {
            cleaned = cleaned.substring(7).trim();
        }
        return cleaned;
    }

    private static String extractError(String body) {
        if (body == null || body.isBlank()) return "empty response";
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (String key : new String[]{"message", "detail", "msg", "error"}) {
                if (root.has(key)) return root.get(key).getAsString();
            }
        } catch (Exception ignored) {
        }
        return body.length() > 180 ? body.substring(0, 180) + "..." : body;
    }
}

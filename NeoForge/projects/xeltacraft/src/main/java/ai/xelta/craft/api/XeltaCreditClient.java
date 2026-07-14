package ai.xelta.craft.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.config.XeltaCraftConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * XeltaCreditClient — Credit Balance
 *
 * Real endpoint: https://devpmt.xelta.ai/api
 *
 * ╔══════════════════════════════════════════════════════════╗
 * ║  GET /users/credits                                      ║
 * ║  Headers: Authorization: Bearer <token>                  ║
 * ║                                                          ║
 * ║  Response:                                               ║
 * ║  { "success": true, "data": { "balance": 120017 } }      ║
 * ║                                                          ║
 * ║  Credit History (for future use):                        ║
 * ║  GET /users/credit-history                               ║
 * ║  Params: limit, cursor, startDate, endDate               ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * Called:
 *  - On Terminal GUI open
 *  - After each successful generation
 *  - On /xeltacraft balance command
 */
public class XeltaCreditClient {

    private static XeltaCreditClient INSTANCE;
    public static XeltaCreditClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaCreditClient();
        return INSTANCE;
    }

    private final HttpClient httpClient;

    private XeltaCreditClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    // =========================================================
    // GET /users/credits
    // Returns balance as int, or -1 if unavailable (non-fatal)
    // =========================================================

    /**
     * Fetches the user's current credit balance.
     *
     * @param token  JWT auth token
     * @return CompletableFuture<Integer> — balance, or -1 on any error
     */
    public CompletableFuture<Integer> fetchBalance(String token) {
        String baseUrl = XeltaCraftConfig.CREDIT_API_URL.get(); // https://devpmt.xelta.ai/api

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/users/credits"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("User-Agent", "XeltaCraft-Mod/1.0.0")
            .GET()
            .timeout(Duration.ofSeconds(8))
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    XeltaCraftMod.LOGGER.debug("[Credits] HTTP {} from /users/credits", response.statusCode());
                    return -1;
                }

                try {
                    // Response: { "success": true, "data": { "balance": 120017 } }
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (root.has("data") && root.get("data").isJsonObject()) {
                        JsonObject data = root.getAsJsonObject("data");
                        if (data.has("balance")) return data.get("balance").getAsInt();
                        // Fallback — some versions use "credits"
                        if (data.has("credits")) return data.get("credits").getAsInt();
                    }
                    // Root-level fallback
                    if (root.has("balance")) return root.get("balance").getAsInt();
                    if (root.has("credits")) return root.get("credits").getAsInt();

                    XeltaCraftMod.LOGGER.debug("[Credits] Unexpected response format: {}", response.body());
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.debug("[Credits] Parse error: {}", e.getMessage());
                }
                return -1;
            })
            .exceptionally(e -> {
                // Credit balance is non-critical — log and continue
                XeltaCraftMod.LOGGER.debug("[Credits] Balance fetch failed: {}", e.getMessage());
                return -1;
            });
    }

    // =========================================================
    // Formatted balance string for GUI display
    // =========================================================

    /**
     * Fetches and formats balance as a display string.
     * Returns "💳 N credits" or "" if unavailable.
     */
    public CompletableFuture<String> fetchBalanceDisplay(String token) {
        return fetchBalance(token).thenApply(balance -> {
            if (balance < 0) return "";
            if (balance >= 1_000_000) return "💳 " + (balance / 1_000_000) + "M credits";
            if (balance >= 1_000)    return "💳 " + (balance / 1_000) + "K credits";
            return "💳 " + balance + " credits";
        });
    }
}

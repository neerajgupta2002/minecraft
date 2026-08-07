package ai.xelta.craft.infra.http;

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
 * XeltaHttpClient — Infrastructure Layer
 *
 * Single shared HTTP client with:
 *  • Virtual thread executor (Java 21)
 *  • Exponential backoff retry for transient errors
 *  • Unified debug logging (controlled by config)
 *  • Bearer token injection via helper methods
 *
 * All SDK clients should use this instead of creating their own HttpClient.
 */
public class XeltaHttpClient {

    // ── Singleton ────────────────────────────────────────────────────────────
    private static XeltaHttpClient INSTANCE;

    public static XeltaHttpClient getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new XeltaHttpClient();
        }
        return INSTANCE;
    }

    // ── Fields ───────────────────────────────────────────────────────────────
    private final HttpClient httpClient;

    private static final String USER_AGENT = "XeltaCraft-Mod/1.0.0 (NeoForge)";

    private XeltaHttpClient() {
        this.httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Returns the raw underlying {@link HttpClient} for callers that build
     * their own {@link HttpRequest} (e.g. multipart uploads).
     */
    public HttpClient raw() {
        return httpClient;
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    /**
     * Authenticated GET request with automatic retries.
     *
     * @param url   Full URL
     * @param token Bearer token (may be null/blank for unauthenticated endpoints)
     * @return future resolving to the raw response body string
     */
    public CompletableFuture<HttpResponse<String>> get(String url, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(XeltaCraftConfig.TIMEOUT_SECONDS.get()));

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = builder.build();
        return sendWithRetry(request, "GET", url);
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    /**
     * Authenticated POST request with a JSON body.
     *
     * @param url   Full URL
     * @param token Bearer token
     * @param json  JSON body string
     * @return future resolving to the raw response
     */
    public CompletableFuture<HttpResponse<String>> postJson(String url, String token, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(XeltaCraftConfig.TIMEOUT_SECONDS.get()));

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = builder.build();
        return sendWithRetry(request, "POST", url);
    }

    /**
     * Unauthenticated POST (for login — no token available yet).
     */
    public CompletableFuture<HttpResponse<String>> postJsonPublic(String url, String json) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .build();

        return sendWithRetry(request, "POST", url);
    }

    // ── Core Send + Retry ─────────────────────────────────────────────────────

    /**
     * Sends the request with exponential backoff retry for:
     *  429 (rate limit), 503 (unavailable), and network errors.
     *
     * @param request    The pre-built request
     * @param method     For logging
     * @param url        For logging
     * @return future resolving to the response (may still carry an error status code)
     */
    private CompletableFuture<HttpResponse<String>> sendWithRetry(
            HttpRequest request, String method, String url) {

        int maxRetries = XeltaCraftConfig.MAX_RETRIES.get();
        return CompletableFuture.supplyAsync(() -> {
            HttpResponse<String> response = null;
            int attempt = 0;

            while (attempt <= maxRetries) {
                try {
                    if (XeltaCraftConfig.DEBUG_MODE.get()) {
                        XeltaCraftMod.LOGGER.debug("[HTTP] {} {} (attempt {})", method, url, attempt + 1);
                    }

                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (XeltaCraftConfig.DEBUG_MODE.get()) {
                        XeltaCraftMod.LOGGER.debug("[HTTP] {} {} → {} | body={}",
                            method, url, response.statusCode(),
                            truncate(response.body(), 300));
                    }

                    int status = response.statusCode();

                    // Retry only on rate-limit or temporary server issues
                    if ((status == 429 || status == 503) && attempt < maxRetries) {
                        long backoff = (long) Math.pow(2, attempt) * 1_000L; // 1s, 2s, 4s
                        XeltaCraftMod.LOGGER.warn("[HTTP] {} {} → {} — retrying in {}ms (attempt {}/{})",
                            method, url, status, backoff, attempt + 1, maxRetries);
                        sleep(backoff);
                        attempt++;
                        continue;
                    }

                    return response;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new XeltaHttpException("Request interrupted: " + url);
                } catch (Exception e) {
                    if (attempt < maxRetries) {
                        long backoff = (long) Math.pow(2, attempt) * 500L;
                        XeltaCraftMod.LOGGER.warn("[HTTP] {} {} network error — retrying in {}ms: {}",
                            method, url, backoff, e.getMessage());
                        sleep(backoff);
                        attempt++;
                    } else {
                        throw new XeltaHttpException("Network error after " + maxRetries + " retries: " + e.getMessage(), e);
                    }
                }
            }

            return response;
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ── Inner Exception ───────────────────────────────────────────────────────

    public static class XeltaHttpException extends RuntimeException {
        public XeltaHttpException(String message) { super(message); }
        public XeltaHttpException(String message, Throwable cause) { super(message, cause); }
    }
}

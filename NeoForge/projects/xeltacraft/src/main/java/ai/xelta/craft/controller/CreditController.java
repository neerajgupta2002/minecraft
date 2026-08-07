package ai.xelta.craft.controller;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.api.XeltaCreditClient;
import ai.xelta.craft.sdk.XeltaSDK;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CreditController — Application Layer
 *
 * Fetches and caches the user's credit balance for the Credits tab and
 * the header credit display in the Dashboard.
 *
 * Features:
 *  • Caches balance to avoid redundant API calls
 *  • Respects a minimum refresh interval (30 seconds)
 *  • Returns a formatted display string (e.g. "1.2K credits")
 *  • Auto-refreshes after generation if credits were consumed
 */
public class CreditController {

    private static CreditController INSTANCE;

    public static CreditController getInstance() {
        if (INSTANCE == null) INSTANCE = new CreditController();
        return INSTANCE;
    }

    private CreditController() {}

    // ── Cache ─────────────────────────────────────────────────────────────────
    private final AtomicInteger cachedBalance  = new AtomicInteger(-1);
    private final AtomicLong    lastFetchedAt  = new AtomicLong(0L);

    /** Minimum time between auto-refreshes in milliseconds (30 seconds). */
    private static final long REFRESH_INTERVAL_MS = 30_000L;

    // ── Fetch ─────────────────────────────────────────────────────────────────

    /**
     * Returns the credit balance, using cache if the last fetch was recent.
     *
     * @param forceRefresh If true, always fetches from API even if cache is fresh
     * @return future resolving to the credit balance (-1 on error)
     */
    public CompletableFuture<Integer> getBalance(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        int cached = cachedBalance.get();

        if (!forceRefresh && cached >= 0 && (now - lastFetchedAt.get()) < REFRESH_INTERVAL_MS) {
            return CompletableFuture.completedFuture(cached);
        }

        String token = XeltaSDK.get().token();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(-1);
        }

        return XeltaSDK.get().credits().fetchBalance(token)
            .thenApply(balance -> {
                cachedBalance.set(balance);
                lastFetchedAt.set(System.currentTimeMillis());
                XeltaCraftMod.LOGGER.debug("[Credits] Balance updated: {}", balance);
                return balance;
            });
    }

    /**
     * Returns cached balance immediately (non-blocking).
     * Returns -1 if not yet fetched.
     */
    public int getCachedBalance() { return cachedBalance.get(); }

    /**
     * Returns a formatted display string for the balance.
     * Examples: "1,250 credits", "1.2K credits", "2.5M credits"
     */
    public String getFormattedBalance() {
        int balance = cachedBalance.get();
        if (balance < 0) return "--- credits";
        if (balance >= 1_000_000) return String.format("%.1fM credits", balance / 1_000_000.0);
        if (balance >= 10_000)    return String.format("%dK credits", balance / 1_000);
        if (balance >= 1_000)     return String.format("%.1fK credits", balance / 1_000.0);
        return balance + " credits";
    }

    /**
     * Refreshes balance from API regardless of cache age.
     * Called after generation completes to show updated balance.
     *
     * @return future resolving to the new balance
     */
    public CompletableFuture<Integer> refreshAfterGeneration() {
        return getBalance(true);
    }

    /**
     * Fetches the full BalanceResult with error details.
     * Used by the Credits tab to show error messages.
     */
    public CompletableFuture<XeltaCreditClient.BalanceResult> getFullResult() {
        String token = XeltaSDK.get().token();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(
                new XeltaCreditClient.BalanceResult(-1,
                    XeltaCreditClient.BalanceError.AUTH,
                    "Not authenticated."));
        }
        return XeltaSDK.get().credits().fetchBalanceResult(token);
    }

    /** Clears the cached balance (next call will fetch from API). */
    public void invalidate() {
        cachedBalance.set(-1);
        lastFetchedAt.set(0L);
    }
}

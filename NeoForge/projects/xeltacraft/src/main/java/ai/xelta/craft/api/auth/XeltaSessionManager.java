package ai.xelta.craft.api.auth;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.infra.cache.XeltaFileStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * XeltaSessionManager — Authentication Layer
 *
 * Single source of truth for the current user's auth session.
 *
 * Responsibilities:
 *  • Holds access token in memory (never written to disk)
 *  • Persists refresh token to disk via XeltaFileStorage
 *  • Automatically refreshes the access token before it expires
 *  • Notifies listeners on session state changes (login/logout/refresh)
 *
 * Architecture notes:
 *  • Access token: in-memory only, short-lived (~15–60 min depending on Xelta config)
 *  • Refresh token: persisted to .minecraft/xeltacraft/session/refresh.tok
 *  • Token format: raw JWT (Bearer prefix is added by XeltaHttpClient)
 *
 * Usage:
 *   XeltaSessionManager.getInstance().getAccessToken()  → current JWT or ""
 *   XeltaSessionManager.getInstance().isAuthenticated() → boolean
 */
public class XeltaSessionManager {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static XeltaSessionManager INSTANCE;

    public static XeltaSessionManager getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaSessionManager();
        return INSTANCE;
    }

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final String REFRESH_TOKEN_FILE = "refresh.tok";

    /**
     * Proactively refresh the access token this many seconds before it expires.
     * Xelta tokens last 30–60 minutes; we refresh 5 minutes early.
     */
    private static final long TOKEN_REFRESH_LEAD_SEC = 300L;

    /**
     * Default assumed token lifetime if the server doesn't tell us (45 minutes).
     */
    private static final long DEFAULT_TOKEN_LIFETIME_SEC = 45L * 60L;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile String accessToken  = "";
    private volatile String refreshToken = "";
    private volatile long   tokenIssuedAt = 0L;  // System.currentTimeMillis()
    private volatile long   tokenLifetimeSec = DEFAULT_TOKEN_LIFETIME_SEC;

    // ── Refresh Scheduler ─────────────────────────────────────────────────────
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "xeltacraft-token-refresh");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> refreshTask;

    // ── Listeners ─────────────────────────────────────────────────────────────
    private final List<Consumer<SessionEvent>> listeners = new ArrayList<>();

    public enum SessionEvent { LOGGED_IN, LOGGED_OUT, TOKEN_REFRESHED, REFRESH_FAILED }

    private XeltaSessionManager() {
        // Attempt to restore session from disk on startup
        tryRestoreFromDisk();
    }

    // ── Token Access ──────────────────────────────────────────────────────────

    /**
     * Returns the current access token, or an empty string if not authenticated.
     * Callers should check {@link #isAuthenticated()} first.
     */
    public String getAccessToken() { return accessToken; }

    /** Returns true when a non-blank access token is held. */
    public boolean isAuthenticated() { return !accessToken.isBlank(); }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticates with the given credentials.
     * On success, stores tokens and schedules auto-refresh.
     *
     * @param email    Xelta account email
     * @param password Xelta account password
     * @return future resolving to the login result
     */
    public CompletableFuture<XeltaAuthClient.LoginResult> login(String email, String password) {
        return XeltaAuthClient.getInstance().login(email, password)
            .thenApply(result -> {
                if (result.success()) {
                    applyNewTokens(result.accessToken(), result.refreshToken());
                    notify(SessionEvent.LOGGED_IN);
                    XeltaCraftMod.LOGGER.info("[Session] Logged in successfully.");
                } else {
                    XeltaCraftMod.LOGGER.warn("[Session] Login failed: {}", result.errorMessage());
                }
                return result;
            });
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Clears session state and removes the persisted refresh token.
     * Server-side session invalidation is attempted in the background.
     */
    public void logout() {
        String currentToken = accessToken;
        clearSession();
        notify(SessionEvent.LOGGED_OUT);
        XeltaCraftMod.LOGGER.info("[Session] Logged out.");

        // Best-effort server logout (fire and forget)
        if (!currentToken.isBlank()) {
            XeltaAuthClient.getInstance().logout(currentToken);
        }
    }

    // ── Manual Token Injection (for settoken command) ─────────────────────────

    /**
     * Accepts a manually-pasted token (from /xeltacraft settoken).
     * Used as a fallback when the login screen is bypassed.
     * No refresh token available in this mode — no auto-refresh.
     *
     * @param token Raw JWT string
     */
    public void setManualToken(String token) {
        if (token == null || token.isBlank()) return;
        String cleaned = stripBearer(token);
        this.accessToken      = cleaned;
        this.refreshToken     = "";
        this.tokenIssuedAt    = System.currentTimeMillis();
        this.tokenLifetimeSec = DEFAULT_TOKEN_LIFETIME_SEC;
        cancelRefreshSchedule(); // no refresh token = can't refresh
        notify(SessionEvent.LOGGED_IN);
        XeltaCraftMod.LOGGER.info("[Session] Manual token accepted (no auto-refresh).");
    }

    // ── Auto-Refresh ──────────────────────────────────────────────────────────

    /**
     * Manually triggers a token refresh.
     * Normally this is handled automatically by the scheduler.
     *
     * @return future resolving to true if refresh succeeded
     */
    public CompletableFuture<Boolean> refreshNow() {
        if (refreshToken.isBlank()) {
            XeltaCraftMod.LOGGER.warn("[Session] No refresh token available — cannot refresh.");
            return CompletableFuture.completedFuture(false);
        }

        return XeltaAuthClient.getInstance().refresh(refreshToken)
            .thenApply(newToken -> {
                if (newToken.isBlank()) {
                    XeltaCraftMod.LOGGER.warn("[Session] Token refresh returned empty — session may be expired.");
                    notify(SessionEvent.REFRESH_FAILED);
                    return false;
                }
                this.accessToken   = newToken;
                this.tokenIssuedAt = System.currentTimeMillis();
                scheduleNextRefresh();
                notify(SessionEvent.TOKEN_REFRESHED);
                XeltaCraftMod.LOGGER.info("[Session] Access token refreshed successfully.");
                return true;
            });
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    /** Register a listener to be called on session state changes. */
    public synchronized void addListener(Consumer<SessionEvent> listener) {
        listeners.add(listener);
    }

    /** Remove a previously registered listener. */
    public synchronized void removeListener(Consumer<SessionEvent> listener) {
        listeners.remove(listener);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void applyNewTokens(String access, String refresh) {
        this.accessToken      = stripBearer(access);
        this.refreshToken     = refresh;
        this.tokenIssuedAt    = System.currentTimeMillis();
        this.tokenLifetimeSec = DEFAULT_TOKEN_LIFETIME_SEC;

        // Persist refresh token to disk
        if (!refresh.isBlank()) {
            XeltaFileStorage.getInstance().writeSessionFile(REFRESH_TOKEN_FILE, refresh);
        }

        scheduleNextRefresh();
    }

    private void clearSession() {
        this.accessToken  = "";
        this.refreshToken = "";
        cancelRefreshSchedule();
        XeltaFileStorage.getInstance().deleteSessionFile(REFRESH_TOKEN_FILE);
    }

    private void tryRestoreFromDisk() {
        XeltaFileStorage.getInstance().readSessionFile(REFRESH_TOKEN_FILE).ifPresent(token -> {
            if (!token.isBlank()) {
                this.refreshToken = token;
                XeltaCraftMod.LOGGER.info("[Session] Refresh token restored from disk — will attempt auto-login.");
                // Attempt silent re-login in background
                refreshNow().thenAccept(ok -> {
                    if (!ok) {
                        XeltaCraftMod.LOGGER.info("[Session] Auto-login failed — user must log in manually.");
                        clearSession();
                    }
                });
            }
        });
    }

    private void scheduleNextRefresh() {
        cancelRefreshSchedule();
        if (refreshToken.isBlank()) return;

        long elapsed = (System.currentTimeMillis() - tokenIssuedAt) / 1000L;
        long remaining = tokenLifetimeSec - elapsed;
        long delay = Math.max(60L, remaining - TOKEN_REFRESH_LEAD_SEC);

        refreshTask = scheduler.schedule(() -> {
            XeltaCraftMod.LOGGER.info("[Session] Scheduled token refresh triggered.");
            refreshNow();
        }, delay, TimeUnit.SECONDS);

        XeltaCraftMod.LOGGER.debug("[Session] Next token refresh in {}s", delay);
    }

    private void cancelRefreshSchedule() {
        if (refreshTask != null && !refreshTask.isDone()) {
            refreshTask.cancel(false);
        }
    }

    private synchronized void notify(SessionEvent event) {
        for (Consumer<SessionEvent> listener : listeners) {
            try { listener.accept(event); }
            catch (Exception e) { XeltaCraftMod.LOGGER.warn("[Session] Listener error: {}", e.getMessage()); }
        }
    }

    private static String stripBearer(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) return t.substring(7).trim();
        return t;
    }
}

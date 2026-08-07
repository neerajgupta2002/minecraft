package ai.xelta.craft.sdk;

import ai.xelta.craft.api.XeltaCreditClient;
import ai.xelta.craft.api.XeltaJobPoller;
import ai.xelta.craft.api.auth.XeltaAuthClient;
import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.infra.cache.XeltaAssetCache;
import ai.xelta.craft.infra.cache.XeltaFileStorage;
import ai.xelta.craft.infra.http.XeltaHttpClient;
import ai.xelta.craft.sdk.superxelta.XeltaSessionClient;
import ai.xelta.craft.sdk.superxelta.XeltaSuperComputerClient;

/**
 * XeltaSDK — SDK Layer Entry Point
 *
 * Single access point for all Xelta client functionality.
 * Replaces scattered getInstance() calls across the codebase with a structured SDK facade.
 *
 * Usage:
 *   XeltaSDK sdk = XeltaSDK.get();
 *   sdk.session().isAuthenticated()
 *   sdk.history().fetchRecent(10)
 *   sdk.credits().fetchBalance()
 *   sdk.upload().uploadImage(...)
 *
 * Architecture:
 *   Controllers → XeltaSDK → SDK clients → XeltaHttpClient → Xelta APIs
 */
public final class XeltaSDK {

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static XeltaSDK INSTANCE;

    public static XeltaSDK get() {
        if (INSTANCE == null) INSTANCE = new XeltaSDK();
        return INSTANCE;
    }

    // ── SDK Clients ───────────────────────────────────────────────────────────
    private final XeltaSessionManager       sessionManager;
    private final XeltaHttpClient            httpClient;
    private final XeltaAssetCache            assetCache;
    private final XeltaFileStorage           fileStorage;
    private final XeltaHistoryClient         historyClient;
    private final XeltaUploadClient          uploadClient;
    private final XeltaJobPoller             jobPoller;
    private final XeltaCreditClient          creditClient;
    private final XeltaSuperComputerClient   superComputer;
    private final XeltaSessionClient         superSession;

    private XeltaSDK() {
        this.sessionManager = XeltaSessionManager.getInstance();
        this.httpClient     = XeltaHttpClient.getInstance();
        this.assetCache     = XeltaAssetCache.getInstance();
        this.fileStorage    = XeltaFileStorage.getInstance();
        this.jobPoller      = new XeltaJobPoller(httpClient.raw());
        this.creditClient   = XeltaCreditClient.getInstance();
        this.historyClient  = new XeltaHistoryClient(httpClient, sessionManager);
        this.uploadClient   = new XeltaUploadClient(httpClient, sessionManager, fileStorage, assetCache);
        this.superComputer  = XeltaSuperComputerClient.getInstance();
        this.superSession   = XeltaSessionClient.getInstance();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Session manager — check auth state, login, logout, token access. */
    public XeltaSessionManager session() { return sessionManager; }

    /** HTTP client — lower-level HTTP for SDK clients that need raw access. */
    public XeltaHttpClient http() { return httpClient; }

    /** Asset cache — L1/L2 cache for generated files. */
    public XeltaAssetCache cache() { return assetCache; }

    /** File storage — local disk management. */
    public XeltaFileStorage storage() { return fileStorage; }

    /** Job poller — async job lifecycle polling. */
    public XeltaJobPoller jobPoller() { return jobPoller; }

    /** Credit client — fetch and display user credit balance. */
    public XeltaCreditClient credits() { return creditClient; }

    /** History client — fetch, display, and re-download generation history. */
    public XeltaHistoryClient history() { return historyClient; }

    /** Upload client — unified asset upload abstraction. */
    public XeltaUploadClient upload() { return uploadClient; }

    /** SuperXelta (SuperComputer) FastAPI client — session chat + tool endpoints. */
    public XeltaSuperComputerClient superComputer() { return superComputer; }

    /** SuperXelta session manager — create/restore session IDs. */
    public XeltaSessionClient superSession() { return superSession; }

    // ── Convenience ───────────────────────────────────────────────────────────

    /**
     * Returns the current auth token from the session manager.
     * Shorthand for {@code sdk.session().getAccessToken()}.
     */
    public String token() { return sessionManager.getAccessToken(); }

    /**
     * Returns true if the user is currently authenticated.
     * Shorthand for {@code sdk.session().isAuthenticated()}.
     */
    public boolean isAuthenticated() { return sessionManager.isAuthenticated(); }
}

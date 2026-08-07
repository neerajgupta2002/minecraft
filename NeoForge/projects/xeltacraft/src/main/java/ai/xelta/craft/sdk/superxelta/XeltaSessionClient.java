package ai.xelta.craft.sdk.superxelta;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.infra.cache.XeltaFileStorage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * XeltaSessionClient — manages the Super Computer's chat session id.
 *
 * The Nexus Deep Agent backend is session-based (see API.md § Sessions): a session is a real
 * server-side resource created via {@code POST /sessions}, not a client-generated id — so
 * obtaining one is an async call, unlike the old SuperXelta backend which accepted any
 * client-picked UUID.
 *
 * The resolved session id is persisted per-install so the same conversation is restored on
 * next login: xeltacraft/session/supercomputer_session.id
 */
public final class XeltaSessionClient {

    private static final String SESSION_FILE = "supercomputer_session.id";

    private static XeltaSessionClient INSTANCE;
    public static XeltaSessionClient getInstance() {
        if (INSTANCE == null) INSTANCE = new XeltaSessionClient();
        return INSTANCE;
    }

    private volatile String currentSessionId;
    private volatile CompletableFuture<String> pendingCreate;

    private XeltaSessionClient() {}

    /**
     * Returns the active session id, restoring from disk or creating a new one via the API.
     * Concurrent callers share the same in-flight creation request.
     */
    public synchronized CompletableFuture<String> getOrCreate() {
        if (currentSessionId != null && !currentSessionId.isBlank()) {
            return CompletableFuture.completedFuture(currentSessionId);
        }
        if (pendingCreate != null) {
            return pendingCreate;
        }

        Optional<String> saved = XeltaFileStorage.getInstance().readSessionFile(SESSION_FILE);
        if (saved.isPresent() && !saved.get().isBlank()) {
            currentSessionId = saved.get();
            XeltaCraftMod.LOGGER.info("[SuperComputer] Restored session: {}", currentSessionId);
            return CompletableFuture.completedFuture(currentSessionId);
        }

        pendingCreate = XeltaSuperComputerClient.getInstance().createSession()
            .thenApply(result -> {
                synchronized (XeltaSessionClient.this) {
                    pendingCreate = null;
                    if (result.success()) {
                        currentSessionId = result.sessionId();
                        XeltaFileStorage.getInstance().writeSessionFile(SESSION_FILE, currentSessionId);
                        XeltaCraftMod.LOGGER.info("[SuperComputer] Created session: {}", currentSessionId);
                        return currentSessionId;
                    }
                    XeltaCraftMod.LOGGER.warn("[SuperComputer] Session creation failed: {}", result.error());
                    return "";
                }
            });
        return pendingCreate;
    }

    /**
     * Discards the cached session id (e.g. after the server reports it as gone/invalid).
     * The next {@link #getOrCreate()} call creates a fresh one.
     */
    public synchronized void reset() {
        currentSessionId = null;
        XeltaFileStorage.getInstance().deleteSessionFile(SESSION_FILE);
        XeltaCraftMod.LOGGER.info("[SuperComputer] Session reset.");
    }

    public String current() { return currentSessionId; }

    public boolean hasSession() {
        return currentSessionId != null && !currentSessionId.isBlank();
    }
}

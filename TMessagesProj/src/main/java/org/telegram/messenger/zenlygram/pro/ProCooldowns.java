package org.telegram.messenger.zenlygram.pro;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side respect for server 429 answers, scoped like the backend limiter: every route per trust
 * domain (API base URL) and route, shared by every Telegram account of this installation (one device
 * id). The unauthenticated linking routes are no exception: the backend counts bind/start and
 * bind/poll separately (own IP counters and windows), so each waits only for its own Retry-After.
 * A throttled scope never blocks another. The client only waits; it never retries by itself and
 * never shortens a server cooldown. Times are elapsed-realtime milliseconds, kept in process memory
 * only. Immutable.
 */
final class ProCooldowns {
    static final ProCooldowns NONE = new ProCooldowns(Collections.<String, Long>emptyMap());
    private static final long MIN_WAIT_MS = 1000;

    private final Map<String, Long> until;

    private ProCooldowns(Map<String, Long> until) { this.until = until; }

    /** One scope per trust domain and route, for every route (customer routes use their route names). */
    static String scope(String apiUrl, String route) {
        return apiUrl + "\n" + route;
    }

    long remainingMs(String scope, long elapsedNow) {
        Long end = until.get(scope);
        return end == null ? 0 : Math.max(0, end - elapsedNow);
    }

    /** A 429 on scope: wait Retry-After (at least one second); a longer existing cooldown is kept. */
    ProCooldowns throttled(String scope, long elapsedNow, int retryAfterSeconds) {
        long wait = Math.max(MIN_WAIT_MS, Math.max(0, retryAfterSeconds) * 1000L);
        long end = ProPolicy.saturatingAdd(elapsedNow, wait);
        Map<String, Long> next = new HashMap<>();
        for (Map.Entry<String, Long> entry : until.entrySet()) {
            if (entry.getValue() > elapsedNow) next.put(entry.getKey(), entry.getValue()); // expired ones are dropped
        }
        Long current = next.get(scope);
        next.put(scope, current == null ? end : Math.max(current, end));
        return new ProCooldowns(Collections.unmodifiableMap(next));
    }

    int size() { return until.size(); }
}

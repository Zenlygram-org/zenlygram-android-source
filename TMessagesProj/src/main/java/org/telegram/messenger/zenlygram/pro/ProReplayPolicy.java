package org.telegram.messenger.zenlygram.pro;

/**
 * Pure ordering rules that keep replayed or delayed responses from overriding newer authority.
 *
 * The backend IdempotencyKey middleware replays a stored 2xx verbatim for 24 h (header
 * Idempotent-Replay: true). A durable attach key makes such replays intentional, so a replayed body
 * is only proof that the operation committed: its entitlement may predate a later revocation and
 * its server_time may be a day old. Neither is ever trusted; the client fetches a fresh signed
 * entitlement through the existing authenticated GET instead.
 */
public final class ProReplayPolicy {
    private ProReplayPolicy() { }

    /** Replayed server_time is historical and must never move the trusted clock or request offset. */
    public static boolean trustsServerTime(boolean replayed) { return !replayed; }

    /** A replayed body only proves the operation committed; its grant may predate a revocation and is never adopted. */
    public static boolean adoptsBody(boolean replayed) { return !replayed; }

    /**
     * A freshly issued (non-replayed) signed grant replaces the current one only if it is not older.
     * Equal iat is accepted (same issuance second); currentIssuedAt < 0 means no current grant.
     */
    public static boolean acceptsGrant(long candidateIssuedAt, long currentIssuedAt) {
        return currentIssuedAt < 0 || candidateIssuedAt >= currentIssuedAt;
    }
}

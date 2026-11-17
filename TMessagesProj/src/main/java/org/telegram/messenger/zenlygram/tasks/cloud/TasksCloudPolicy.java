package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * R3 x-native-offline-undo.error_states as pure decisions, plus the R4 rules of design 30 §3.12 / §7.2–§7.4. Retries
 * always reuse the same sealed id and bytes. {@link #classify} is the single-entry table shared by the single route and
 * every batch item; the engine applies {@link #decide}, which adds the account-level consent fence on top of it.
 */
public final class TasksCloudPolicy {
    public enum Action {
        /** Outcome unknown/transient: same id after bounded exponential backoff. */
        BACKOFF,
        /** 429: same id not before Retry-After. */
        RETRY_AFTER,
        /** Fence/credential race before anything was applied: same id, retry on next step. */
        REQUEUE,
        /** 403 tasks_account_not_linked: account-level pause; session, cache, outbox and cursors intact. */
        PAUSE_UNLINKED,
        /** Real 401: account-level stop until the user re-links; nothing wiped. */
        PAUSE_SESSION,
        /** No binding / not configured / signed out locally: account-level, nothing wiped. */
        PAUSE_NOT_LINKED,
        BLOCK_CREATION,
        INVALID,
        QUARANTINE,
        CONFLICT,
        NOT_FOUND,
        RESYNC,
        /**
         * R4 403 tasks_consent_required (x-consent-fence, any call or batch item): never a failure of an entry — the
         * request's entries go back to the queue without an attempt, the account backs off, nothing is discarded.
         */
        CONSENT_FENCED
    }

    /** Occasions of the automatic requeue (§7.4), reported by the sync owner (TR4-A3) through TasksCloudRepository. */
    public enum Occasion { LAUNCH, FOREGROUND, NETWORK, LINK, SESSION, ENTITLEMENT }

    public static final int MAX_AUTO_ATTEMPTS = 6;
    public static final long BASE_BACKOFF_MS = 2000;
    public static final long MAX_BACKOFF_MS = 5 * 60 * 1000;

    /** §3.12: the consent fence holds the account 30 s, doubling per consecutive fence, at most 15 min. */
    public static final long CONSENT_FENCE_INITIAL_MS = 30_000, CONSENT_FENCE_MAX_MS = 900_000;

    /** §7.3 / §7.4: automatic resolutions per intent, automatic requeues per entry. */
    public static final int MAX_AUTO_RESOLUTIONS = 2, MAX_AUTO_REQUEUES = 5;

    /** §7.2 batch sizing: request bytes start at 64 KiB, halve to one-entry batches, double after 3 successes. */
    public static final int BATCH_INITIAL_BYTES = 65536, BATCH_MIN_BYTES = 1024, BATCH_MAX_BYTES = TasksWire.MAX_BATCH_BYTES;
    /** Room kept for a sponsor creation_proof when a create is in the batch (parity with iOS), else for the envelope. */
    public static final int BATCH_PROOF_RESERVE = 34000, BATCH_ENVELOPE_RESERVE = 1024;
    public static final int BATCH_SUCCESSES_TO_GROW = 3, BATCH_503S_TO_FALL_BACK = 3, MAX_BATCHES_PER_CYCLE = 10;
    /** 404 / batch_disabled / 3 consecutive 503: user entries take the single route this long. */
    public static final long BATCH_UNAVAILABLE_MS = 900_000;
    /** migration_paused without a retry_after (the server sends 3600). */
    public static final long DEFAULT_MIGRATION_PAUSE_MS = 3_600_000;

    private TasksCloudPolicy() { }

    public static Action classify(TasksCloudRemote.Failure failure) {
        switch (failure.kind) {
            case UNLINKED: return Action.PAUSE_UNLINKED;
            case SESSION: return Action.PAUSE_SESSION;
            case STALE:
            case BUSY: return Action.REQUEUE;
            case RATE_LIMITED: return Action.RETRY_AFTER;
            case OFFLINE:
            case UNAVAILABLE: return Action.BACKOFF;
            case LOCAL:
                switch (failure.code) {
                    case "link_required":
                    case "not_configured":
                    case "login_required": return Action.PAUSE_NOT_LINKED;
                    case "invalid_request": return Action.QUARANTINE; // client-side argument bug: never auto-new-id
                    default: return Action.BACKOFF;                  // storage_unavailable, clock_skew
                }
            case PROTOCOL:
                // A malformed success may hide a committed mutation: retry the same identity. 413/415/4xx cannot succeed.
                return "request_rejected".equals(failure.code) ? Action.INVALID : Action.BACKOFF;
            case TASK:
                switch (failure.code) {
                    case "tasks_creation_not_permitted": return Action.BLOCK_CREATION;
                    case "tasks_not_found": return Action.NOT_FOUND;
                    case "tasks_conflict": return Action.CONFLICT;
                    case "tasks_resync_required": return Action.RESYNC;
                    case "tasks_mutation_in_flight": return Action.BACKOFF;
                    case "tasks_invalid_request": return Action.INVALID;
                    case "tasks_mutation_mismatch": return Action.QUARANTINE;
                    default: return Action.BACKOFF;
                }
            default:
                return Action.BACKOFF;
        }
    }

    /** The engine's decision: the consent fence first (it is never an entry failure), else {@link #classify}. */
    public static Action decide(TasksCloudRemote.Failure failure) {
        return isConsentFence(failure) ? Action.CONSENT_FENCED : classify(failure);
    }

    /** 403 tasks_consent_required (TasksApi.CODE_CONSENT_REQUIRED). */
    public static boolean isConsentFence(TasksCloudRemote.Failure failure) {
        return failure.kind == TasksCloudRemote.Failure.Kind.TASK && "tasks_consent_required".equals(failure.code);
    }

    /** 503 migration_paused (TasksApi.CODE_MIGRATION_PAUSED): a migration item that never ran. */
    public static boolean isMigrationPaused(TasksCloudRemote.Failure failure) {
        return failure.kind == TasksCloudRemote.Failure.Kind.UNAVAILABLE && "migration_paused".equals(failure.code);
    }

    /** 503 batch_disabled (TasksApi.CODE_BATCH_DISABLED): the batch route is off; nothing ran. */
    public static boolean isBatchDisabled(TasksCloudRemote.Failure failure) {
        return failure.kind == TasksCloudRemote.Failure.Kind.UNAVAILABLE && "batch_disabled".equals(failure.code);
    }

    /** Delay before attempt number {@code attempts + 1}; attempts counts sends already made. */
    public static long backoffMs(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 16));
        return Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << exponent);
    }

    /** The next consent-fence hold after one of {@code previousMs} (0 = none yet): 30 s, doubling, at most 900 s. */
    public static long consentFenceMs(long previousMs) {
        return previousMs <= 0 ? CONSENT_FENCE_INITIAL_MS : Math.min(CONSENT_FENCE_MAX_MS, previousMs * 2);
    }

    /** A timeout, an outcome-unknown answer or a 413: half, never below one-entry batches. */
    public static int shrinkBatch(int budget) {
        return Math.max(BATCH_MIN_BYTES, budget / 2);
    }

    /** Three consecutive fully processed batches: double, at most 512 KiB. */
    public static int growBatch(int budget) {
        return (int) Math.min((long) BATCH_MAX_BYTES, budget * 2L);
    }
}

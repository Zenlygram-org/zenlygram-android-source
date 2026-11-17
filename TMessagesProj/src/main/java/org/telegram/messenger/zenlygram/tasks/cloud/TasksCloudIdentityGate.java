package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * Main-thread identity fence of one repository's visible state. EVERY state that reaches an observer or a
 * currentState() reader passes {@link #admit} with a FRESH Pro identity lookup, so a delivery queued before a Pro
 * rebind (Pro saves P2 and only then posts its status event) cannot show P1 content first.
 * <p>
 * Remembers the last observed LINKED principal and Pro generation; changes are detected BEFORE the baseline is
 * updated. A principal different from it (the first linked one after an unlinked baseline, or a relink while nobody
 * was subscribed) replaces any view of another principal by a content-free placeholder and asks for an engine rebind.
 * Presentation-only Pro changes (same principal and generation) do nothing. Unlinked (null principal) keeps the fence,
 * so the legitimate cache persisted under that principal stays visible. A failed lookup shows no content at all.
 */
final class TasksCloudIdentityGate {
    /** Fresh identity of the repository's own Telegram user; null = lookup failed or the slot holds someone else. */
    static final class Identity {
        final String principal;
        final long generation;

        Identity(String principal, long generation) {
            this.principal = principal;
            this.generation = generation;
        }
    }

    static final class Verdict {
        /** What observers may see now: the candidate, or a content-free placeholder. */
        final TasksCloudState state;
        /** The engine must re-evaluate its binding/pauses. */
        final boolean rebind;

        Verdict(TasksCloudState state, boolean rebind) {
            this.state = state;
            this.rebind = rebind;
        }
    }

    private String fence;
    private long generation = Long.MIN_VALUE;

    /**
     * @param identity fresh lookup (null = failed: never shows content, never moves the baseline)
     * @param candidate the state the caller wants to show (an engine publication, or the currently held state)
     */
    Verdict admit(Identity identity, TasksCloudState candidate) {
        boolean rebind = false;
        if (identity != null) {
            boolean principalChanged = identity.principal != null && !identity.principal.equals(fence);
            rebind = principalChanged || identity.generation != generation;
            if (identity.principal != null) fence = identity.principal;
            generation = identity.generation;
        }
        if (candidate.contentFree() || identity != null && (fence == null || fence.equals(candidate.principal))) {
            return new Verdict(candidate, rebind);
        }
        // Rebind only on an observed identity change: a rejected publication never schedules work by itself, so a
        // stale engine plus a failed/unlinked lookup cannot loop (rebind -> publish -> reject -> rebind).
        return new Verdict(placeholder(candidate), rebind);
    }

    /** Status kept for failures/closure (so an error is never swallowed), otherwise LOADING until the rebind lands. */
    private static TasksCloudState placeholder(TasksCloudState rejected) {
        TasksCloudState.Status status = rejected.status == TasksCloudState.Status.STORAGE_FAILED
                || rejected.status == TasksCloudState.Status.CLOSED ? rejected.status : TasksCloudState.Status.LOADING;
        return TasksCloudState.empty(status, rejected.ownTelegramId);
    }
}

package org.telegram.messenger.zenlygram.pro;

/** UI-owned linking lifecycle. Uses elapsed milliseconds, never the user-adjustable wall clock. */
public final class ProLinkLifecycle {
    private final long telegramId;
    private long attempt, nextPollAt;
    private int failures;
    private boolean linking, visible, destroyed, polling;

    public ProLinkLifecycle(long telegramId) { this.telegramId = telegramId; }

    public long begin(long currentTelegramId) {
        if (destroyed || telegramId <= 0 || telegramId != currentTelegramId) return -1;
        attempt++;
        linking = true;
        polling = false;
        failures = 0;
        nextPollAt = 0;
        return attempt;
    }

    public boolean accepts(long operation, long currentTelegramId) {
        return !destroyed && linking && operation == attempt && telegramId == currentTelegramId;
    }

    public long attempt() { return attempt; }
    public boolean ownsLink() { return linking; }
    public void resume() { if (!destroyed) visible = true; }
    public void pause() { visible = false; }

    public boolean tryPoll(long elapsedNow, long currentTelegramId) {
        if (!visible || polling || !accepts(attempt, currentTelegramId) || elapsedNow < nextPollAt) return false;
        polling = true;
        return true;
    }

    public long nextPollDelay(long elapsedNow) { return Math.max(0, nextPollAt - elapsedNow); }

    public void pending(long operation, long elapsedNow) {
        if (!linking || operation != attempt || destroyed) return;
        polling = false;
        failures = 0;
        nextPollAt = ProPolicy.saturatingAdd(elapsedNow, 3000);
    }

    /** The server confirmed this link (Telegram attestation): the next poll may start at once. */
    public void expedite(long operation) {
        if (!linking || operation != attempt || destroyed) return;
        nextPollAt = 0;
    }

    public void retry(long operation, long elapsedNow, int retryAfterSeconds) {
        if (!linking || operation != attempt || destroyed) return;
        polling = false;
        long backoff = Math.min(60000L, 5000L << Math.min(failures++, 4));
        long delay = Math.max(backoff, Math.max(0L, retryAfterSeconds) * 1000L);
        nextPollAt = ProPolicy.saturatingAdd(elapsedNow, delay);
    }

    /** Returns whether this screen owned a request, including a begin call without its code yet. */
    public boolean cancel() {
        boolean owned = linking;
        attempt++;
        linking = false;
        polling = false;
        return owned;
    }

    public boolean destroy() {
        boolean owned = cancel();
        visible = false;
        destroyed = true;
        return owned;
    }
}

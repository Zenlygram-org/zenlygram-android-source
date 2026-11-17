/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.ProLinkRequests;

import java.util.Locale;

/**
 * What the "Link Telegram Account" screen is doing, as a state machine without any Android or client type (iOS
 * SGLinkAccountModel): which phase it shows, which attempt is the current one, when it polls and what each answer
 * does to it. {@link ZgLinkAccountActivity} is its {@link Host}: it performs the requests through the client and
 * draws the phase; a test is another host (ZgLinkFlowTest).
 *
 * <p>One attempt: bind/start; then the silent Telegram confirmation when the bind offers it and the account's consent
 * allows it (ProLinkRequests.useAttest), with {@link #ATTEST_TIMEOUT_MS} for Telegram's part; when it is confirmed
 * the session is polled at once under "Linking…", and whenever it is not possible the code is shown and polled
 * ({@link ZgLinkPollSchedule}) until it is confirmed, declined or expired.
 *
 * <p>Rules that hold in every state:
 * <ul>
 *   <li>Every attempt, and every end of one, takes a new number. An answer carries the number and the bind it was
 *       asked for, and one that is no longer current changes nothing. Neither does any answer once the host is not
 *       {@link Host#alive()} (the screen is gone, or its account is no longer the selected one).</li>
 *   <li>While the screen is hidden no poll is sent and no timer runs; the code and the schedule stay, and the poll
 *       that was due goes out when the screen is shown again. An answer already on its way is still taken, so a
 *       confirmation is never thrown away.</li>
 *   <li>The code's expiry ends new polls only.</li>
 *   <li>The client's bind is cancelled exactly when this screen holds one and gives it up.</li>
 *   <li>One {@link Host#attemptEnded} per attempt that reached an outcome; none for one the user left, and none for
 *       one that was refused before anything was sent.</li>
 * </ul>
 *
 * <p>Proof mode (iOS requiresTelegramProof, for a caller to whom a stored session is not enough: Cloud Tasks after the
 * server said the account is not linked): the screen starts a fresh attempt even when a session is stored, and that
 * session is neither its outcome nor touched until the attempt's own confirmation replaces it.
 *
 * @param <B> the client's handle of one bind (ZenlyPro.LinkRequest); only compared, never read.
 */
final class ZgLinkFlow<B> {

    /** How long Telegram may take to answer the silent confirmation before the code is shown (iOS: 15 s). */
    static final long ATTEST_TIMEOUT_MS = 15_000;

    enum Phase {
        /** Not shown yet: the first appearance decides between linked and a first attempt. Drawn like STARTING. */
        IDLE,
        /** bind/start, then the silent confirmation and its poll: "Linking…". */
        STARTING,
        /** The code is on screen and polled. */
        WAITING,
        FAILED,
        LINKED,
        /** The user unlinked here, or the session ended in the background: nothing starts until the user asks. */
        UNLINKED
    }

    enum Timer {
        POLL,
        /** The countdown line, once a second while the code is visible. */
        TICK,
        ATTEST_TIMEOUT
    }

    /** The client, the clock and the screen. Everything on the main thread. */
    interface Host<B> {
        /** Monotonic milliseconds. */
        long now();

        /** Uniform in [0, 1): the poll jitter. */
        double random();

        /** The screen still exists and its account (and that slot's user) is still the selected one. */
        boolean alive();

        /** The client holds a session for the account: a local read. */
        boolean linked();

        ZgLinkRules.Permission permission();

        /** The account's data consent allows the silent confirmation right now. */
        boolean mayAttest();

        /** bind/start. Answer: {@link #started} or {@link #startFailed} with this number. */
        void start(int attempt);

        /**
         * Ask Telegram for the data of the bind's offer. Answer: {@link #attestationObtained}; when that returns true
         * the host sends the data to the server and answers {@link #attested} or {@link #attestFailed}. The data never
         * passes through this class.
         */
        void obtainAttestation(int attempt, B bind);

        /** bind/poll. Answer: {@link #polled} or {@link #pollFailed}. */
        void poll(int attempt, B bind);

        /** The client forgets this screen's bind (ZenlyPro.cancelLink). */
        void cancel();

        /** {@link #timer} is called once after {@code delayMs}; replaces a pending timer of the same kind. */
        void setTimer(Timer timer, long delayMs);

        void clearTimer(Timer timer);

        /** link.attempt{trigger: manual}: the method is attest only when the server confirmed the attestation. */
        void attemptEnded(boolean attested, boolean success, String errorCode, long durationSeconds);

        /** What is shown changed. */
        void changed();

        /** Only the countdown line changed. */
        void countdown();

        /** The screen closes itself. */
        void close();
    }

    private final Host<B> host;
    private Phase phase;
    private ZgLinkRules.Message failure;
    private boolean mergeConflict;

    private int attempt;
    /** The client holds a bind of this screen (bind/start is on its way, or its code is alive). */
    private boolean owning;
    /** The bind of the current attempt once bind/start answered. */
    private B bind;
    /** Telegram or bind/attest is being asked: no poll yet, no code shown. */
    private boolean attesting;
    /** The server confirmed this bind from Telegram's data: the session is polled under "Linking…". */
    private boolean attested;
    /** The silent confirmation may run, or runs, for this attempt: the screen says what it asks Telegram for. */
    private boolean discloses;
    private boolean pollInFlight;
    private ZgLinkPollSchedule schedule;
    /** When the code is finished, on the host's clock. */
    private long expiresAtMs;
    private boolean offline;
    private boolean copied;
    /** The attempt's start on the host's clock until its outcome is reported; -1 = nothing to report. */
    private long startedAtMs = -1;

    /** Proof mode until this screen linked once: a stored session does not count as the outcome. */
    private boolean proofWanted;

    private boolean visible;
    private boolean closed;
    private boolean leaving;

    /** {@code linked}: the client's session when the screen is created (a local read; opening never waits). */
    ZgLinkFlow(Host<B> host, boolean linked) {
        this(host, linked, false);
    }

    /** {@code freshProof}: proof mode, see the class comment. */
    ZgLinkFlow(Host<B> host, boolean linked, boolean freshProof) {
        this.host = host;
        this.proofWanted = freshProof;
        this.phase = linked && !freshProof ? Phase.LINKED : Phase.IDLE;
    }

    // ---------------------------------------------------------------------------------------------------------
    // What the screen draws
    // ---------------------------------------------------------------------------------------------------------

    Phase phase() {
        return phase;
    }

    /** Why the attempt failed; only in {@link Phase#FAILED}. */
    ZgLinkRules.Message failure() {
        return failure;
    }

    /** The link succeeded and the server found a separate subscription on this device: a notice, never a block. */
    boolean mergeConflict() {
        return mergeConflict;
    }

    /** The current bind (its code is what {@link Phase#WAITING} shows), or null. */
    B bind() {
        return bind;
    }

    /** "No connection." under the waiting line: the code stays, the reason for the wait is added. */
    boolean offline() {
        return offline;
    }

    boolean copied() {
        return copied;
    }

    /** The screen says what the silent confirmation asks Telegram for. */
    boolean discloses() {
        return discloses;
    }

    /** Whole seconds until the code is finished; 0 once it is. */
    long remainingSeconds() {
        return Math.max(0, (expiresAtMs - host.now()) / 1000L);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The screen's life
    // ---------------------------------------------------------------------------------------------------------

    /** The screen is on top and the app is in the foreground. */
    void shown() {
        visible = true;
        if (!alive()) {
            leave();
            return;
        }
        if (phase == Phase.IDLE) {
            // Opening the screen for an account that is not linked starts linking at once (iOS: the idle phase begins).
            if (host.linked() && !proofWanted) {
                phase = Phase.LINKED;
                host.changed();
            } else {
                begin();
            }
            return;
        }
        follow();
        host.changed();
        host.countdown();
        // The same code goes on: the poll that was due while the screen was hidden goes out now.
        schedulePoll();
        scheduleTick();
    }

    /** Another screen is on top, or the app is in the background. */
    void hidden() {
        visible = false;
        host.clearTimer(Timer.POLL);
        host.clearTimer(Timer.TICK);
    }

    /** The screen is gone: its attempt ends without an outcome; the server's code expires by itself. */
    void destroyed() {
        closed = true;
        visible = false;
        dropAttempt();
    }

    /**
     * Anything the client shows about the account changed. The screen follows the stored session when the change is
     * not its own doing: an automatic link that finished just before the screen took over shows "linked", a session
     * the server ended in the background shows "not linked".
     */
    void statusChanged() {
        if (alive()) {
            follow();
        }
    }

    /** Another account was selected: a screen that outlives the switch closes. */
    void accountChanged() {
        if (!closed && !host.alive()) {
            leave();
        }
    }

    private boolean alive() {
        return !closed && host.alive();
    }

    private void leave() {
        if (leaving) {
            return;
        }
        leaving = true;
        dropAttempt();
        host.close();
    }

    private void follow() {
        final boolean linked = host.linked();
        if (linked && phase != Phase.LINKED) {
            if (pollInFlight) {
                // This screen's own poll: the client saves the session before the answer is delivered, and the answer
                // (which follows at once) also says whether to show the merge notice and ends the attempt.
                return;
            }
            if (proofWanted) {
                // The stored session is what this screen was opened to replace, not a link made meanwhile.
                return;
            }
            dropAttempt();
            bind = null;
            startedAtMs = -1;
            mergeConflict = false;
            phase = Phase.LINKED;
            host.changed();
        } else if (!linked && phase == Phase.LINKED) {
            mergeConflict = false;
            phase = Phase.UNLINKED;
            host.changed();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Taps
    // ---------------------------------------------------------------------------------------------------------

    /** "Get a New Code" and "Link Telegram Account": only from the states that show them (a second tap does nothing). */
    void retry() {
        if (phase == Phase.FAILED || phase == Phase.UNLINKED) {
            begin();
        }
    }

    /** The code was copied. */
    void codeCopied() {
        if (phase == Phase.WAITING && bind != null && !copied) {
            copied = true;
            host.changed();
        }
    }

    /** Whether Unlink may run now (asked again when the confirmation dialog is answered). */
    boolean mayUnlink() {
        return alive() && phase == Phase.LINKED;
    }

    /** The host dropped the session: not linked, and nothing starts by itself, not even here. */
    void unlinked() {
        if (phase == Phase.LINKED) {
            mergeConflict = false;
            phase = Phase.UNLINKED;
            host.changed();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // One attempt
    // ---------------------------------------------------------------------------------------------------------

    /** Whether an answer of attempt {@code number} still counts. */
    private boolean owns(int number) {
        return number == attempt && alive();
    }

    /**
     * Ends whatever attempt is running without an outcome: its timers stop, its answers are dropped, and the client
     * forgets its bind (only when this screen has one, so that it never cancels anybody else's).
     */
    private void dropAttempt() {
        host.clearTimer(Timer.POLL);
        host.clearTimer(Timer.TICK);
        host.clearTimer(Timer.ATTEST_TIMEOUT);
        if (owning) {
            owning = false;
            host.cancel();
        }
        attempt++;
        attesting = false;
        pollInFlight = false;
    }

    /**
     * A new attempt. Whether anything may be sent is decided first (the iOS bind permission): an account without a
     * usable data consent in a required-consent build, or one whose deletion is pending, gets the failed state and no
     * request at all.
     */
    private void begin() {
        if (!alive()) {
            return;
        }
        if (host.linked() && !proofWanted) {
            // Linked a moment ago (the notice is on its way): there is nothing to start.
            follow();
            return;
        }
        dropAttempt();
        final int number = ++attempt;
        bind = null;
        failure = null;
        mergeConflict = false;
        offline = false;
        copied = false;
        attested = false;
        final ZgLinkRules.Permission permission = host.permission();
        discloses = permission == ZgLinkRules.Permission.ALLOWED;
        if (permission == ZgLinkRules.Permission.REFUSED) {
            // Nothing is sent, so there is no attempt to report either.
            startedAtMs = -1;
            failure = ZgLinkRules.Message.TEMPORARILY_UNAVAILABLE;
            phase = Phase.FAILED;
            host.changed();
            return;
        }
        startedAtMs = host.now();
        phase = Phase.STARTING;
        host.changed();
        owning = true;
        host.start(number);
    }

    /**
     * bind/start answered: the silent confirmation when the bind offers it and the consent allows it (read again now:
     * it may have changed while bind/start was on its way), else the code. {@code lifetimeMs}: how long the code lives.
     */
    void started(int number, B handle, boolean offersAttest, long lifetimeMs) {
        if (!owns(number)) {
            return;
        }
        if (handle == null) {
            // Never from the client; a bind that cannot be named cannot be polled either.
            fail(ZgLinkRules.Message.TEMPORARILY_UNAVAILABLE, "invalid_response");
            return;
        }
        bind = handle;
        final long now = host.now();
        schedule = new ZgLinkPollSchedule(now);
        expiresAtMs = now + lifetimeMs;
        if (ProLinkRequests.useAttest(offersAttest, host.mayAttest())) {
            attesting = true;
            // It runs now, so it is disclosed now.
            discloses = true;
            host.changed();
            host.setTimer(Timer.ATTEST_TIMEOUT, ATTEST_TIMEOUT_MS);
            host.obtainAttestation(number, handle);
        } else {
            showCode();
        }
    }

    void startFailed(int number, String code, int httpStatus) {
        if (owns(number)) {
            fail(ZgLinkRules.failure(code, httpStatus), code);
        }
    }

    /**
     * Telegram answered the silent confirmation. True: the host sends the data to the server now. False: nothing is
     * sent (the answer is late, there is no data, or the account's consent ended meanwhile) and, when the answer is
     * the current attempt's, the code is shown instead.
     */
    boolean attestationObtained(int number, B handle, boolean hasData) {
        if (!owns(number) || !attesting || bind != handle) {
            return false;
        }
        host.clearTimer(Timer.ATTEST_TIMEOUT);
        if (!hasData || !host.mayAttest()) {
            showCode();
            return false;
        }
        return true;
    }

    /** The server confirmed this bind from Telegram's data: the first poll goes out at once, still under "Linking…". */
    void attested(int number, B handle) {
        if (!owns(number) || !attesting || bind != handle) {
            return;
        }
        attesting = false;
        attested = true;
        schedulePoll();
    }

    /** Any failure of the silent confirmation shows the code instead, silently. */
    void attestFailed(int number, B handle) {
        if (owns(number) && attesting && bind == handle) {
            showCode();
        }
    }

    /** The code flow: the code is shown and polled until it is confirmed, declined or expired. */
    private void showCode() {
        host.clearTimer(Timer.ATTEST_TIMEOUT);
        attesting = false;
        phase = Phase.WAITING;
        // Nobody can have confirmed a code that has just appeared: the first poll comes one interval later.
        schedule.next(host.now(), host.random());
        host.changed();
        schedulePoll();
        scheduleTick();
    }

    /** A timer the host was asked for ran out. */
    void timer(Timer timer) {
        if (timer == Timer.POLL) {
            pollNow();
        } else if (timer == Timer.TICK) {
            tick();
        } else if (alive() && attesting) {
            // Telegram did not answer in time; its late answer is dropped.
            showCode();
        }
    }

    private boolean polling() {
        return bind != null && !attesting && (phase == Phase.WAITING || (phase == Phase.STARTING && attested));
    }

    private void schedulePoll() {
        host.clearTimer(Timer.POLL);
        if (!alive() || !visible || pollInFlight || !polling()) {
            return;
        }
        host.setTimer(Timer.POLL, schedule.delayMs(host.now()));
    }

    private void pollNow() {
        if (!alive() || !visible || pollInFlight || !polling()) {
            return;
        }
        final long now = host.now();
        if (now >= expiresAtMs) {
            // The expiry ends new requests only; an answer already on its way was still taken.
            fail(ZgLinkRules.Message.CODE_EXPIRED, "expired");
            return;
        }
        final long wait = schedule.delayMs(now);
        if (wait > 0) {
            host.setTimer(Timer.POLL, wait);
            return;
        }
        pollInFlight = true;
        host.poll(attempt, bind);
    }

    /** bind/poll answered with {@code state} (ZenlyPro.LinkState, any case). */
    void polled(int number, B handle, String state, boolean conflict) {
        if (!owns(number) || bind != handle || !pollInFlight) {
            return;
        }
        pollInFlight = false;
        final ZgLinkRules.PollStep step = ZgLinkRules.afterPoll(state);
        if (step.next == ZgLinkRules.PollStep.Next.LINKED) {
            linked(conflict);
        } else if (step.next == ZgLinkRules.PollStep.Next.FAILED) {
            fail(step.message, state == null ? null : state.toLowerCase(Locale.ROOT));
        } else {
            pollAgain(step.hint, 0);
        }
    }

    void pollFailed(int number, B handle, String code, int httpStatus, int retryAfterSeconds) {
        if (!owns(number) || bind != handle || !pollInFlight) {
            return;
        }
        pollInFlight = false;
        final ZgLinkRules.PollStep step = ZgLinkRules.afterPollError(code, httpStatus);
        if (step.next == ZgLinkRules.PollStep.Next.FAILED) {
            fail(step.message, code);
        } else {
            pollAgain(step.hint, retryAfterSeconds);
        }
    }

    /** The code is still good: the next poll is one interval away, and never before a Retry-After the server asked for. */
    private void pollAgain(ZgLinkRules.PollStep.Hint hint, int retryAfterSeconds) {
        final long now = host.now();
        if (retryAfterSeconds > 0) {
            schedule.retryAfter(now, retryAfterSeconds);
        }
        schedule.next(now, host.random());
        final boolean before = offline;
        if (hint == ZgLinkRules.PollStep.Hint.CLEAR) {
            offline = false;
        } else if (hint == ZgLinkRules.PollStep.Hint.NO_CONNECTION) {
            offline = true;
        }
        if (offline != before) {
            host.changed();
        }
        schedulePoll();
    }

    /** The client adopted the session of this attempt. */
    private void linked(boolean conflict) {
        report(true, null);
        host.clearTimer(Timer.POLL);
        host.clearTimer(Timer.TICK);
        // The client dropped the bind when it adopted the session: there is nothing left to cancel.
        owning = false;
        attempt++;
        bind = null;
        // The fresh proof is there; from here on the screen follows the stored session like any other.
        proofWanted = false;
        mergeConflict = conflict;
        phase = Phase.LINKED;
        host.changed();
    }

    /** The attempt's end without a link: the message and "Get a New Code". {@code code} is its error code. */
    private void fail(ZgLinkRules.Message message, String code) {
        report(false, code);
        dropAttempt();
        bind = null;
        failure = message;
        phase = Phase.FAILED;
        host.changed();
    }

    private void report(boolean success, String errorCode) {
        if (startedAtMs < 0) {
            return;
        }
        final long durationSeconds = Math.max(0, host.now() - startedAtMs) / 1000L;
        startedAtMs = -1;
        host.attemptEnded(attested, success, success ? null : errorCode, durationSeconds);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The countdown
    // ---------------------------------------------------------------------------------------------------------

    private void scheduleTick() {
        host.clearTimer(Timer.TICK);
        if (!alive() || !visible || phase != Phase.WAITING) {
            return;
        }
        final long left = expiresAtMs - host.now();
        // The line changes when a whole second is gone.
        host.setTimer(Timer.TICK, left > 0 ? left % 1000 + 20 : 1000);
    }

    private void tick() {
        if (!alive()) {
            if (!closed) {
                leave();
            }
            return;
        }
        if (!visible || phase != Phase.WAITING) {
            return;
        }
        if (host.now() >= expiresAtMs && !pollInFlight) {
            fail(ZgLinkRules.Message.CODE_EXPIRED, "expired");
            return;
        }
        host.countdown();
        scheduleTick();
    }
}

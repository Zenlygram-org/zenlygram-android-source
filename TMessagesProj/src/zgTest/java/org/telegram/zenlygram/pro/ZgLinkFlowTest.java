package org.telegram.zenlygram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.zenlygram.pro.ZgLinkFlow.Phase;
import org.telegram.zenlygram.pro.ZgLinkFlow.Timer;
import org.telegram.zenlygram.pro.ZgLinkRules.Message;
import org.telegram.zenlygram.pro.ZgLinkRules.Permission;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * JVM tests of the link screen's state machine against a scripted host: a virtual clock, the timers the flow asks
 * for, and a record of every request, cancel and report (iOS run-link-lifecycle-tests).
 */
public class ZgLinkFlowTest {

    private static final long LIFETIME = 300_000;
    /** With random = 0.5 an interval is 3.2 s in the first minute and 5.2 s after it. */
    private static final long FAST = 3_200;
    private static final long SLOW = 5_200;

    /** The world the flow lives in, scripted by the test. */
    private static final class World implements ZgLinkFlow.Host<String> {
        ZgLinkFlow<String> flow;
        long now = 1_000_000;
        boolean alive = true;
        boolean linked;
        Permission permission = Permission.ALLOWED;
        boolean mayAttest = true;

        final Map<Timer, Long> timers = new EnumMap<>(Timer.class);
        final List<Integer> starts = new ArrayList<>();
        final List<String> obtains = new ArrayList<>();
        final List<String> polls = new ArrayList<>();
        final List<String> reports = new ArrayList<>();
        int lastAttempt;
        int cancels;
        int changes;
        int countdowns;
        int closes;

        @Override
        public long now() {
            return now;
        }

        @Override
        public double random() {
            return 0.5;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public boolean linked() {
            return linked;
        }

        @Override
        public Permission permission() {
            return permission;
        }

        @Override
        public boolean mayAttest() {
            return mayAttest;
        }

        @Override
        public void start(int attempt) {
            lastAttempt = attempt;
            starts.add(attempt);
        }

        @Override
        public void obtainAttestation(int attempt, String bind) {
            obtains.add(bind);
        }

        @Override
        public void poll(int attempt, String bind) {
            polls.add(bind);
        }

        @Override
        public void cancel() {
            cancels++;
        }

        @Override
        public void setTimer(Timer timer, long delayMs) {
            assertTrue("a timer never runs backwards", delayMs >= 0);
            timers.put(timer, now + delayMs);
        }

        @Override
        public void clearTimer(Timer timer) {
            timers.remove(timer);
        }

        @Override
        public void attemptEnded(boolean attested, boolean success, String errorCode, long durationSeconds) {
            reports.add((attested ? "attest" : "code") + "/" + (success ? "success" : "failed") + "/" + errorCode + "/" + durationSeconds);
        }

        @Override
        public void changed() {
            changes++;
        }

        @Override
        public void countdown() {
            countdowns++;
        }

        @Override
        public void close() {
            closes++;
        }

        /** Lets {@code ms} pass, running every timer that comes due, in order. */
        void advance(long ms) {
            final long until = now + ms;
            while (true) {
                Timer next = null;
                for (Map.Entry<Timer, Long> entry : timers.entrySet()) {
                    if (entry.getValue() <= until && (next == null || entry.getValue() < timers.get(next))) {
                        next = entry.getKey();
                    }
                }
                if (next == null) {
                    break;
                }
                now = Math.max(now, timers.remove(next));
                flow.timer(next);
            }
            now = until;
        }

        boolean has(Timer timer) {
            return timers.containsKey(timer);
        }

        long in(Timer timer) {
            return timers.get(timer) - now;
        }
    }

    private static World open(boolean linked) {
        final World world = new World();
        world.linked = linked;
        world.flow = new ZgLinkFlow<>(world, linked);
        return world;
    }

    /** A screen that was opened, shown and whose bind/start answered with a code (no silent confirmation). */
    private static World waiting() {
        final World world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        assertEquals(Phase.WAITING, world.flow.phase());
        return world;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Opening
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void aLinkedAccountOpensAsLinkedAndSendsNothing() {
        final World world = open(true);
        assertEquals(Phase.LINKED, world.flow.phase());
        world.flow.shown();
        world.advance(600_000);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertTrue(world.starts.isEmpty());
        assertTrue(world.polls.isEmpty());
        assertEquals(0, world.cancels);
        assertTrue(world.reports.isEmpty());
    }

    @Test
    public void anUnlinkedAccountStartsLinkingWhenTheScreenAppears() {
        final World world = open(false);
        assertEquals(Phase.IDLE, world.flow.phase());
        assertTrue("nothing before the screen is shown", world.starts.isEmpty());
        world.flow.shown();
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(1, world.starts.size());
        // Coming back to the screen does not start again.
        world.flow.hidden();
        world.flow.shown();
        assertEquals(1, world.starts.size());
    }

    @Test
    public void linkedBetweenCreationAndTheFirstAppearance() {
        final World world = open(false);
        world.linked = true;
        world.flow.shown();
        assertEquals(Phase.LINKED, world.flow.phase());
        assertTrue(world.starts.isEmpty());
    }

    @Test
    public void aRefusedAccountSendsNothingAndReportsNothing() {
        final World world = open(false);
        world.permission = Permission.REFUSED;
        world.flow.shown();
        assertEquals(Phase.FAILED, world.flow.phase());
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, world.flow.failure());
        assertTrue(world.starts.isEmpty());
        assertEquals(0, world.cancels);
        assertTrue(world.reports.isEmpty());
        // Asking again once the consent is there starts a real attempt.
        world.permission = Permission.ALLOWED;
        world.flow.retry();
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(1, world.starts.size());
    }

    @Test
    public void aScreenForAnotherAccountClosesInsteadOfStarting() {
        final World world = open(false);
        world.alive = false;
        world.flow.shown();
        assertEquals(1, world.closes);
        assertTrue(world.starts.isEmpty());
        world.flow.shown();
        assertEquals("closed once", 1, world.closes);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The silent confirmation
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void silentConfirmationLinksWithoutACode() {
        final World world = open(false);
        world.flow.shown();
        assertTrue("the consent allows it: disclosed from the start", world.flow.discloses());
        world.advance(1_000);
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(1, world.obtains.size());
        assertEquals(ZgLinkFlow.ATTEST_TIMEOUT_MS, world.in(Timer.ATTEST_TIMEOUT));
        assertFalse("no poll before the confirmation", world.has(Timer.POLL));

        world.advance(2_000);
        assertTrue("the data is sent", world.flow.attestationObtained(world.lastAttempt, "bind1", true));
        assertFalse(world.has(Timer.ATTEST_TIMEOUT));
        world.flow.attested(world.lastAttempt, "bind1");
        // The first poll goes out at once, still under "Linking…".
        assertEquals(0, world.in(Timer.POLL));
        world.advance(0);
        assertEquals(1, world.polls.size());
        assertEquals(Phase.STARTING, world.flow.phase());

        world.advance(1_000);
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertFalse(world.flow.mergeConflict());
        assertNull(world.flow.bind());
        assertEquals(1, world.reports.size());
        assertEquals("attest/success/null/4", world.reports.get(0));
        assertEquals("the client dropped the bind itself", 0, world.cancels);
        assertTrue(world.timers.isEmpty());
    }

    @Test
    public void aConfirmedAttestationKeepsPollingAtTheCadenceUntilTheSessionIsThere() {
        final World world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        world.flow.attestationObtained(world.lastAttempt, "bind1", true);
        world.flow.attested(world.lastAttempt, "bind1");
        world.advance(0);
        world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(FAST, world.in(Timer.POLL));
        world.advance(FAST);
        assertEquals(2, world.polls.size());
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", true);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertTrue(world.flow.mergeConflict());
    }

    @Test
    public void withoutAnOfferOrWithoutConsentTheCodeIsShown() {
        // The bind offers nothing.
        World world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        assertEquals(Phase.WAITING, world.flow.phase());
        assertTrue(world.obtains.isEmpty());
        assertSame("bind1", world.flow.bind());

        // An optional-consent build without a grant: the code only, and nothing is disclosed because nothing is asked.
        world = open(false);
        world.permission = Permission.CODE_ONLY;
        world.mayAttest = false;
        world.flow.shown();
        assertFalse(world.flow.discloses());
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        assertEquals(Phase.WAITING, world.flow.phase());
        assertTrue(world.obtains.isEmpty());
    }

    @Test
    public void consentGivenWhileStartingIsDisclosedBeforeTelegramIsAsked() {
        final World world = open(false);
        world.permission = Permission.CODE_ONLY;
        world.mayAttest = false;
        world.flow.shown();
        assertFalse(world.flow.discloses());
        world.mayAttest = true;
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        assertEquals(1, world.obtains.size());
        assertTrue(world.flow.discloses());
    }

    @Test
    public void everyFailureOfTheSilentConfirmationShowsTheCode() {
        // Telegram has no data.
        World world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        assertFalse(world.flow.attestationObtained(world.lastAttempt, "bind1", false));
        assertEquals(Phase.WAITING, world.flow.phase());
        assertFalse(world.has(Timer.ATTEST_TIMEOUT));

        // The server does not confirm.
        world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        assertTrue(world.flow.attestationObtained(world.lastAttempt, "bind1", true));
        world.flow.attestFailed(world.lastAttempt, "bind1");
        assertEquals(Phase.WAITING, world.flow.phase());

        // The consent ended while Telegram was asked: nothing Telegram signed is sent.
        world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        world.mayAttest = false;
        assertFalse(world.flow.attestationObtained(world.lastAttempt, "bind1", true));
        assertEquals(Phase.WAITING, world.flow.phase());
    }

    @Test
    public void telegramGetsFifteenSecondsThenTheCodeAndItsLateAnswerIsDropped() {
        final World world = open(false);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        world.advance(ZgLinkFlow.ATTEST_TIMEOUT_MS - 1);
        assertEquals(Phase.STARTING, world.flow.phase());
        world.advance(1);
        assertEquals(Phase.WAITING, world.flow.phase());
        // The first poll of the code comes one interval after it appeared.
        assertEquals(FAST, world.in(Timer.POLL));
        assertFalse("too late: not sent", world.flow.attestationObtained(world.lastAttempt, "bind1", true));
        world.flow.attested(world.lastAttempt, "bind1");
        assertEquals(Phase.WAITING, world.flow.phase());
        // The link then ends as a code link.
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals("code/success/null/18", world.reports.get(0));
    }

    // ---------------------------------------------------------------------------------------------------------
    // The code
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void theCodeIsPolledEveryThreeSecondsThenEveryFive() {
        final World world = waiting();
        final long start = world.now;
        // The first poll comes one interval after the code appeared; every answer below comes at once.
        long lastAnswer = start;
        int polls = 0;
        int fast = 0;
        int slow = 0;
        while (world.now - start < 80_000) {
            world.advance(world.in(Timer.POLL));
            assertEquals(++polls, world.polls.size());
            final long interval = world.now - lastAnswer;
            if (lastAnswer - start < ZgLinkPollSchedule.FAST_WINDOW_MS) {
                assertEquals(FAST, interval);
                fast++;
            } else {
                assertEquals(SLOW, interval);
                slow++;
            }
            lastAnswer = world.now;
            world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
        }
        assertEquals("3.2 s apart for the first minute", 19, fast);
        assertTrue("then 5.2 s apart", slow >= 3);
        assertEquals(Phase.WAITING, world.flow.phase());
    }

    @Test
    public void onePollAtATime() {
        final World world = waiting();
        world.advance(FAST);
        assertEquals(1, world.polls.size());
        // No answer yet: nothing else goes out, however long it takes.
        world.advance(60_000);
        assertEquals(1, world.polls.size());
        world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
        // The code is past its first minute by now: the next poll is five seconds after the answer.
        world.advance(SLOW - 1);
        assertEquals(1, world.polls.size());
        world.advance(1);
        assertEquals(2, world.polls.size());
    }

    @Test
    public void theBotsAnswersEndTheCodeWithTheirMessage() {
        World world = waiting();
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "DENIED", false);
        assertEquals(Phase.FAILED, world.flow.phase());
        assertEquals(Message.DECLINED, world.flow.failure());
        assertEquals("code/failed/denied/3", world.reports.get(0));
        assertEquals(1, world.cancels);
        assertTrue(world.timers.isEmpty());

        world = waiting();
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "EXPIRED", false);
        assertEquals(Message.CODE_EXPIRED, world.flow.failure());
        assertEquals("code/failed/expired/3", world.reports.get(0));

        world = waiting();
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "CONSUMED", false);
        assertEquals(Message.DID_NOT_GO_THROUGH, world.flow.failure());
        assertEquals("code/failed/consumed/3", world.reports.get(0));
    }

    @Test
    public void theCodeExpiresWhenItsTimeIsUp() {
        final World world = waiting();
        final long start = world.now;
        assertEquals(300, world.flow.remainingSeconds());
        world.advance(1_500);
        assertEquals(298, world.flow.remainingSeconds());
        // Every poll is answered "pending" at once until the screen gives the code up.
        int answered = 0;
        while (world.flow.phase() == Phase.WAITING && world.now < start + LIFETIME + 60_000) {
            if (world.polls.size() > answered) {
                answered++;
                world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
            } else {
                world.advance(100);
            }
        }
        assertEquals(Phase.FAILED, world.flow.phase());
        assertEquals(Message.CODE_EXPIRED, world.flow.failure());
        assertTrue("not before its time", world.now >= start + LIFETIME);
        assertTrue("and within a tick of it", world.now <= start + LIFETIME + 1_100);
        assertEquals(0, world.flow.remainingSeconds());
        // About 19 polls in the first minute and 46 in the other four.
        assertTrue("polled " + answered + " times", answered >= 60 && answered <= 68);
        assertEquals(1, world.reports.size());
        assertEquals("code/failed/expired/300", world.reports.get(0));
        assertEquals(1, world.cancels);
        assertTrue(world.timers.isEmpty());
    }

    @Test
    public void expiryNeverThrowsAwayAnAnswerThatIsOnItsWay() {
        final World world = waiting();
        // A poll goes out just before the end and is answered after it.
        world.advance(LIFETIME - 4_000);
        if (world.polls.isEmpty()) {
            world.advance(world.in(Timer.POLL));
        }
        final int sent = world.polls.size();
        assertTrue(sent >= 1);
        world.advance(10_000);
        assertEquals("the screen waits for the answer", Phase.WAITING, world.flow.phase());
        assertEquals(sent, world.polls.size());
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.LINKED, world.flow.phase());
    }

    @Test
    public void afterTheExpiryNoNewPollGoesOut() {
        final World world = waiting();
        world.advance(FAST);
        world.flow.hidden();
        world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
        world.advance(LIFETIME);
        world.flow.shown();
        world.advance(0);
        assertEquals(Phase.FAILED, world.flow.phase());
        assertEquals(Message.CODE_EXPIRED, world.flow.failure());
        assertEquals("only the poll before the expiry", 1, world.polls.size());
    }

    @Test
    public void aRetryAfterWinsOverTheCadence() {
        final World world = waiting();
        world.advance(FAST);
        world.flow.pollFailed(world.lastAttempt, "bind1", "rate_limited", 429, 20);
        assertEquals(Phase.WAITING, world.flow.phase());
        assertFalse("throttling is not an outage", world.flow.offline());
        assertEquals(20_000, world.in(Timer.POLL));
        world.advance(19_999);
        assertEquals(1, world.polls.size());
        world.advance(1);
        assertEquals(2, world.polls.size());
        assertTrue(world.reports.isEmpty());
    }

    @Test
    public void aLostConnectionKeepsTheCodeAndSaysSo() {
        final World world = waiting();
        world.advance(FAST);
        final int before = world.changes;
        world.flow.pollFailed(world.lastAttempt, "bind1", "connection_failed", 0, 0);
        assertEquals(Phase.WAITING, world.flow.phase());
        assertTrue(world.flow.offline());
        assertEquals(before + 1, world.changes);
        assertEquals(FAST, world.in(Timer.POLL));
        // A throttled answer in between keeps the hint.
        world.advance(FAST);
        world.flow.pollFailed(world.lastAttempt, "bind1", "rate_limited", 429, 1);
        assertTrue(world.flow.offline());
        // The next answer of the server clears it.
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
        assertFalse(world.flow.offline());
        assertEquals(0, world.cancels);
        assertTrue(world.reports.isEmpty());
    }

    @Test
    public void anErrorThatEndsTheCodeShowsItsMessage() {
        World world = waiting();
        world.advance(FAST);
        world.flow.pollFailed(world.lastAttempt, "bind1", "bind_not_found", 404, 0);
        assertEquals(Phase.FAILED, world.flow.phase());
        assertEquals(Message.DID_NOT_GO_THROUGH, world.flow.failure());
        assertEquals("code/failed/bind_not_found/3", world.reports.get(0));
        assertEquals(1, world.cancels);

        world = waiting();
        world.advance(FAST);
        world.flow.pollFailed(world.lastAttempt, "bind1", "account_mismatch", 0, 0);
        assertEquals(Message.WRONG_ACCOUNT, world.flow.failure());

        world = waiting();
        world.advance(FAST);
        world.flow.pollFailed(world.lastAttempt, "bind1", "bind_paused", 503, 0);
        assertEquals(Message.LINKING_UNAVAILABLE, world.flow.failure());
    }

    @Test
    public void copyingIsRememberedForThisCodeOnly() {
        final World world = waiting();
        assertFalse(world.flow.copied());
        final int before = world.changes;
        world.flow.codeCopied();
        assertTrue(world.flow.copied());
        assertEquals(before + 1, world.changes);
        world.flow.codeCopied();
        assertEquals("a second tap changes nothing", before + 1, world.changes);
        // A new code starts clean.
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "DENIED", false);
        world.flow.retry();
        world.flow.started(world.lastAttempt, "bind2", false, LIFETIME);
        assertFalse(world.flow.copied());
        assertSame("bind2", world.flow.bind());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hidden and shown
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void nothingIsPolledWhileHiddenAndTheSameCodeGoesOn() {
        final World world = waiting();
        world.flow.hidden();
        assertTrue("no timer runs while hidden", world.timers.isEmpty());
        world.advance(60_000);
        assertTrue(world.polls.isEmpty());
        assertEquals(Phase.WAITING, world.flow.phase());
        assertSame("bind1", world.flow.bind());
        // Shown again: the poll that was due goes out at once.
        world.flow.shown();
        assertEquals(0, world.in(Timer.POLL));
        assertTrue(world.has(Timer.TICK));
        world.advance(0);
        assertEquals(1, world.polls.size());
        assertEquals(0, world.cancels);
    }

    @Test
    public void shownAgainBeforeThePollIsDueWaitsTheRest() {
        final World world = waiting();
        world.advance(1_000);
        world.flow.hidden();
        world.advance(1_000);
        world.flow.shown();
        assertEquals(FAST - 2_000, world.in(Timer.POLL));
    }

    @Test
    public void anAnswerThatArrivesWhileHiddenIsTaken() {
        final World world = waiting();
        world.advance(FAST);
        assertEquals(1, world.polls.size());
        world.flow.hidden();
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertEquals(1, world.reports.size());
    }

    @Test
    public void aPendingAnswerWhileHiddenSchedulesNothingUntilShown() {
        final World world = waiting();
        world.advance(FAST);
        world.flow.hidden();
        world.flow.polled(world.lastAttempt, "bind1", "PENDING", false);
        assertTrue(world.timers.isEmpty());
        world.advance(1_000);
        world.flow.shown();
        assertEquals(FAST - 1_000, world.in(Timer.POLL));
    }

    @Test
    public void theSilentConfirmationFinishesWhileHiddenButItsPollWaits() {
        final World world = open(false);
        world.flow.shown();
        world.flow.hidden();
        world.flow.started(world.lastAttempt, "bind1", true, LIFETIME);
        assertTrue(world.flow.attestationObtained(world.lastAttempt, "bind1", true));
        world.flow.attested(world.lastAttempt, "bind1");
        world.advance(30_000);
        assertTrue(world.polls.isEmpty());
        world.flow.shown();
        world.advance(0);
        assertEquals(1, world.polls.size());
    }

    @Test
    public void theCountdownTicksOncePerSecondWhileTheCodeIsVisible() {
        final World world = waiting();
        final int before = world.countdowns;
        assertEquals(300, world.flow.remainingSeconds());
        // The line is rewritten right after a whole second is gone (20 ms after it, to be safely past).
        world.advance(19);
        assertEquals(before, world.countdowns);
        world.advance(1);
        assertEquals(before + 1, world.countdowns);
        assertEquals(299, world.flow.remainingSeconds());
        world.advance(999);
        assertEquals(before + 1, world.countdowns);
        world.advance(1);
        assertEquals(before + 2, world.countdowns);
        assertEquals(298, world.flow.remainingSeconds());
        world.flow.hidden();
        final int hidden = world.countdowns;
        world.advance(5_000);
        assertEquals(hidden, world.countdowns);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Attempts
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void aFailedStartShowsWhyAndIsReported() {
        final World world = open(false);
        world.flow.shown();
        world.advance(2_500);
        world.flow.startFailed(world.lastAttempt, "connection_failed", 0);
        assertEquals(Phase.FAILED, world.flow.phase());
        assertEquals(Message.NO_CONNECTION, world.flow.failure());
        assertEquals("code/failed/connection_failed/2", world.reports.get(0));
        assertEquals("the client forgets the attempt", 1, world.cancels);

        final World paused = open(false);
        paused.flow.shown();
        paused.flow.startFailed(paused.lastAttempt, "bind_paused", 503);
        assertEquals(Message.LINKING_UNAVAILABLE, paused.flow.failure());

        final World throttled = open(false);
        throttled.flow.shown();
        throttled.flow.startFailed(throttled.lastAttempt, "rate_limited", 429);
        assertEquals(Message.TOO_MANY_ATTEMPTS, throttled.flow.failure());
    }

    @Test
    public void aNewCodeIsANewAttemptAndOldAnswersAreDropped() {
        final World world = waiting();
        final int first = world.lastAttempt;
        world.advance(FAST);
        world.flow.polled(first, "bind1", "DENIED", false);
        world.flow.retry();
        final int second = world.lastAttempt;
        assertTrue(second != first);
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(2, world.starts.size());

        // Late answers of the first attempt change nothing.
        world.flow.started(first, "stale", false, LIFETIME);
        world.flow.polled(first, "bind1", "CONFIRMED", false);
        world.flow.pollFailed(first, "bind1", "bind_not_found", 404, 0);
        world.flow.startFailed(first, "connection_failed", 0);
        assertFalse(world.flow.attestationObtained(first, "bind1", true));
        assertEquals(Phase.STARTING, world.flow.phase());
        assertNull(world.flow.bind());
        assertEquals("one report: the first attempt's", 1, world.reports.size());

        world.flow.started(second, "bind2", false, LIFETIME);
        assertEquals(Phase.WAITING, world.flow.phase());
        // An answer for another bind of the same attempt number is not this bind's either.
        world.advance(FAST);
        world.flow.polled(second, "bind1", "CONFIRMED", false);
        assertEquals(Phase.WAITING, world.flow.phase());
        world.flow.polled(second, "bind2", "CONFIRMED", false);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertEquals(2, world.reports.size());
    }

    @Test
    public void theButtonsActOnlyInTheStatesThatShowThem() {
        final World world = open(false);
        world.flow.shown();
        world.flow.retry();
        assertEquals("already starting", 1, world.starts.size());
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        world.flow.retry();
        assertEquals("a code is on screen", 1, world.starts.size());
        assertEquals(0, world.cancels);

        final World linked = open(true);
        linked.flow.shown();
        linked.flow.retry();
        assertTrue(linked.starts.isEmpty());
    }

    @Test
    public void leavingTheScreenCancelsItsOwnBindAndNothingElse() {
        // With a code alive.
        World world = waiting();
        world.flow.destroyed();
        assertEquals(1, world.cancels);
        assertTrue(world.timers.isEmpty());
        assertTrue("an attempt the user left is not reported", world.reports.isEmpty());
        // Answers after that change nothing.
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertTrue(world.reports.isEmpty());

        // While bind/start is on its way.
        world = open(false);
        world.flow.shown();
        world.flow.destroyed();
        assertEquals(1, world.cancels);
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        assertEquals(Phase.STARTING, world.flow.phase());
        assertTrue(world.timers.isEmpty());

        // Linked, failed or never started: the screen holds no bind, so it cancels none (it could be somebody else's).
        world = open(true);
        world.flow.shown();
        world.flow.destroyed();
        assertEquals(0, world.cancels);

        world = open(false);
        world.flow.destroyed();
        assertEquals(0, world.cancels);

        world = waiting();
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "DENIED", false);
        assertEquals(1, world.cancels);
        world.flow.destroyed();
        assertEquals("not twice", 1, world.cancels);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The session changes outside the screen
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void aLinkMadeElsewhereShowsLinked() {
        // From the failed state.
        World world = open(false);
        world.flow.shown();
        world.flow.startFailed(world.lastAttempt, "connection_failed", 0);
        world.linked = true;
        world.flow.statusChanged();
        assertEquals(Phase.LINKED, world.flow.phase());
        assertEquals("only the failed attempt was reported", 1, world.reports.size());

        // With a code on screen and no poll on its way: the code is given up, and no outcome is invented for it.
        world = waiting();
        world.linked = true;
        world.flow.statusChanged();
        assertEquals(Phase.LINKED, world.flow.phase());
        assertEquals(1, world.cancels);
        assertTrue(world.reports.isEmpty());
        assertTrue(world.timers.isEmpty());
    }

    @Test
    public void theScreensOwnPollIsNotMistakenForALinkMadeElsewhere() {
        final World world = waiting();
        world.advance(FAST);
        // The client saves the session and posts its notice before the poll's answer is delivered.
        world.linked = true;
        world.flow.statusChanged();
        assertEquals("the answer follows and decides", Phase.WAITING, world.flow.phase());
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", true);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertTrue(world.flow.mergeConflict());
        assertEquals("code/success/null/3", world.reports.get(0));
        assertEquals(0, world.cancels);
    }

    @Test
    public void aSessionEndedInTheBackgroundShowsNotLinkedAndStartsNothing() {
        final World world = open(true);
        world.flow.shown();
        world.linked = false;
        world.flow.statusChanged();
        assertEquals(Phase.UNLINKED, world.flow.phase());
        world.advance(600_000);
        assertTrue(world.starts.isEmpty());
        // Also noticed when the screen comes back.
        final World away = open(true);
        away.flow.shown();
        away.flow.hidden();
        away.linked = false;
        away.flow.shown();
        assertEquals(Phase.UNLINKED, away.flow.phase());
        assertTrue(away.starts.isEmpty());
    }

    @Test
    public void tappingLinkWhenAlreadyLinkedShowsLinked() {
        final World world = open(false);
        world.flow.shown();
        world.flow.startFailed(world.lastAttempt, "connection_failed", 0);
        world.linked = true;
        world.flow.retry();
        assertEquals(Phase.LINKED, world.flow.phase());
        assertEquals(1, world.starts.size());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Unlink
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void unlinkOnlyFromLinkedAndNothingStartsByItselfAfterwards() {
        final World world = open(true);
        world.flow.shown();
        assertTrue(world.flow.mayUnlink());
        world.linked = false;
        world.flow.unlinked();
        assertEquals(Phase.UNLINKED, world.flow.phase());
        assertFalse(world.flow.mayUnlink());
        // The client's notice about the dropped session changes nothing.
        world.flow.statusChanged();
        world.flow.hidden();
        world.flow.shown();
        world.advance(600_000);
        assertEquals(Phase.UNLINKED, world.flow.phase());
        assertTrue("not even on this screen", world.starts.isEmpty());
        // Linking again is the user's own tap.
        world.flow.retry();
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(1, world.starts.size());
    }

    @Test
    public void unlinkIsRefusedOutsideLinked() {
        final World waiting = waiting();
        assertFalse(waiting.flow.mayUnlink());
        waiting.flow.unlinked();
        assertEquals(Phase.WAITING, waiting.flow.phase());

        final World other = open(true);
        other.flow.shown();
        other.alive = false;
        assertFalse("another account is selected", other.flow.mayUnlink());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Proof mode
    // ---------------------------------------------------------------------------------------------------------

    private static World openForProof(boolean linked) {
        final World world = new World();
        world.linked = linked;
        world.flow = new ZgLinkFlow<>(world, linked, true);
        return world;
    }

    @Test
    public void proofModeStartsAFreshLinkAlthoughASessionIsStored() {
        final World world = openForProof(true);
        assertEquals(Phase.IDLE, world.flow.phase());
        world.flow.shown();
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(1, world.starts.size());
        // Without a stored session it is an ordinary first attempt.
        final World unlinked = openForProof(false);
        unlinked.flow.shown();
        assertEquals(1, unlinked.starts.size());
    }

    @Test
    public void proofModeDoesNotTakeTheStoredSessionForItsOutcome() {
        final World world = openForProof(true);
        world.flow.shown();
        world.flow.statusChanged();
        assertEquals(Phase.STARTING, world.flow.phase());
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        world.flow.statusChanged();
        world.flow.hidden();
        world.flow.shown();
        assertEquals(Phase.WAITING, world.flow.phase());
        assertEquals("the code is not given up", 0, world.cancels);
        world.advance(FAST);
        assertEquals(1, world.polls.size());
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.LINKED, world.flow.phase());
        assertEquals("code/success/null/3", world.reports.get(0));
    }

    @Test
    public void aFailedProofShowsTheFailureAndAnotherTapTriesAgain() {
        final World world = openForProof(true);
        world.flow.shown();
        world.flow.startFailed(world.lastAttempt, "connection_failed", 0);
        assertEquals("the stored session does not hide the failure", Phase.FAILED, world.flow.phase());
        world.flow.statusChanged();
        assertEquals(Phase.FAILED, world.flow.phase());
        world.flow.retry();
        assertEquals(Phase.STARTING, world.flow.phase());
        assertEquals(2, world.starts.size());
    }

    @Test
    public void afterItsProofTheScreenFollowsTheSessionLikeAnyOther() {
        final World world = openForProof(true);
        world.flow.shown();
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.LINKED, world.flow.phase());
        // Nothing starts again on the next appearance, and an ended session shows.
        world.flow.hidden();
        world.flow.shown();
        assertEquals(1, world.starts.size());
        world.linked = false;
        world.flow.statusChanged();
        assertEquals(Phase.UNLINKED, world.flow.phase());
        world.linked = true;
        world.flow.statusChanged();
        assertEquals("a link made elsewhere counts again", Phase.LINKED, world.flow.phase());
    }

    // ---------------------------------------------------------------------------------------------------------
    // The account
    // ---------------------------------------------------------------------------------------------------------

    @Test
    public void anotherAccountClosesTheScreenAndGivesUpItsBind() {
        final World world = waiting();
        world.flow.accountChanged();
        assertEquals("still this account", 0, world.closes);
        world.alive = false;
        world.flow.accountChanged();
        assertEquals(1, world.closes);
        assertEquals(1, world.cancels);
        assertTrue(world.timers.isEmpty());
        world.flow.accountChanged();
        world.flow.shown();
        assertEquals(1, world.closes);
        // Its answers no longer count, even if the first account is selected again before the screen is gone.
        world.alive = true;
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.WAITING, world.flow.phase());
        assertTrue(world.reports.isEmpty());
    }

    @Test
    public void answersForAnAccountThatIsNoLongerSelectedAreDropped() {
        final World world = waiting();
        world.advance(FAST);
        world.alive = false;
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals(Phase.WAITING, world.flow.phase());
        assertTrue(world.reports.isEmpty());
        // The countdown notices it and the screen closes.
        world.advance(1_100);
        assertEquals(1, world.closes);
    }

    @Test
    public void theReportedDurationIsTheAttemptsOwn() {
        final World world = open(false);
        world.advance(50_000);
        world.flow.shown();
        world.advance(4_900);
        world.flow.started(world.lastAttempt, "bind1", false, LIFETIME);
        world.advance(FAST);
        world.flow.polled(world.lastAttempt, "bind1", "CONFIRMED", false);
        assertEquals("code/success/null/8", world.reports.get(0));
    }
}

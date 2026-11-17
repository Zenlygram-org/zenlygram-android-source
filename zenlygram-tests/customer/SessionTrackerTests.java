package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain-JVM checks of the account session (contract §3.3, §3.6; design 50 §11.2): {@code app.session_start} with
 * {@code seq} 0 and the visible screen as the first view; {@code seq} strictly increasing; the end with its aggregates
 * and the foreground seconds (clamped); same-screen suppression for 30 s; {@code feature.used} once per session;
 * screen views only in a session started in this process while other events continue the most recent (also an
 * earlier process's) session; nothing without a session; aggregates per (domain, code) and per kind, capped at 1000;
 * the {@code seq} ceiling; and the stored row. Run with run-tests.sh.
 */
public final class SessionTrackerTests {
    private static int checks;
    private static int ids;
    private static final long T0 = 1793523600L; // 2026-11-01T09:00:00Z

    public static void main(String[] args) {
        startAndEnd();
        screenSuppression();
        featureUsedOncePerSession();
        betweenSessions();
        withoutSession();
        restartDropsTheOpenSession();
        aggregates();
        seqCeiling();
        resumeRows();
        System.out.println("Session tracker: " + checks + " checks passed");
    }

    private static void startAndEnd() {
        SessionTracker tracker = SessionTracker.fresh(SessionTrackerTests::uuid);
        check(tracker.sessionId() == null && !tracker.isOpen() && tracker.nextSeq() == 0, "no session yet");
        List<SessionTracker.Stamped> start = tracker.start(CustomerEvents.SessionTrigger.LAUNCH, CustomerEvents.Screen.TG_CHAT_LIST, T0, 10_000);
        check(start.size() == 2, "start + the visible screen");
        String session = tracker.sessionId();
        check(CustomerJson.isUuid4(session) && tracker.isOpen() && tracker.startedAt() == T0 && tracker.endedAt() == 0, "an open session");
        check(start.get(0).event.name.equals(CustomerEvents.SESSION_START) && start.get(0).seq == 0, "app.session_start has seq 0");
        check(props(start.get(0)).get("trigger").equals("launch"), "its trigger");
        check(start.get(1).event.name.equals(CustomerEvents.SCREEN_VIEW) && start.get(1).seq == 1
                && props(start.get(1)).get("screen").equals("tg.chat_list"), "then the visible screen");
        for (SessionTracker.Stamped stamped : start) {
            Map<String, Object> tree = MiniJson.object(MiniJson.parse(stamped.json));
            check(tree.get("id").equals(stamped.eventId) && CustomerJson.isUuid4(stamped.eventId), "a uuid4 event id");
            check(tree.get("session_id").equals(session) && tree.get("ts").equals(CustomerJson.instant(T0)), "session id and ts in the JSON");
            check(stamped.bytes == CustomerEvents.utf8Length(stamped.json) && stamped.bytes <= CustomerEvents.MAX_EVENT_BYTES, "bytes = UTF-8 size");
        }
        int previous = 1;
        long elapsed = 10_000;
        for (CustomerEvents.Event event : new CustomerEvents.Event[]{CustomerEvents.vaultChatAdded(), CustomerEvents.featureToggle(
                CustomerEvents.Feature.VAULT, true), CustomerEvents.taskDeleted(CustomerEvents.TasksScope.LOCAL)}) {
            List<SessionTracker.Stamped> out = tracker.record(event, T0 + 5, elapsed += 1_000);
            check(out.size() == 1 && out.get(0).seq == previous + 1 && out.get(0).sessionId.equals(session), "seq strictly increases");
            previous = out.get(0).seq;
        }
        check(tracker.nextSeq() == previous + 1, "next_seq is one past the last");
        List<SessionTracker.Stamped> end = tracker.end(CustomerEvents.SessionEndReason.BACKGROUND, T0 + 840, 10_000 + 840_500);
        check(end.size() == 1 && end.get(0).event.name.equals(CustomerEvents.SESSION_END) && end.get(0).seq == previous + 1, "app.session_end last");
        check(((Integer) props(end.get(0)).get("duration_s")) == 840 && props(end.get(0)).get("reason").equals("background"),
                "duration_s = elapsed-realtime foreground seconds");
        check(!tracker.isOpen() && tracker.endedAt() == T0 + 840 && tracker.sessionId().equals(session), "ended, the row keeps the session");
        check(tracker.end(CustomerEvents.SessionEndReason.BACKGROUND, T0 + 900, 999_999).isEmpty(), "no second end");

        SessionTracker longOne = SessionTracker.fresh(SessionTrackerTests::uuid);
        longOne.start(CustomerEvents.SessionTrigger.FOREGROUND, null, T0, 0);
        List<SessionTracker.Stamped> day = longOne.end(CustomerEvents.SessionEndReason.ACCOUNT_SWITCH, T0 + 200_000, 200_000_000L);
        check(((Integer) props(day.get(0)).get("duration_s")) == 86400, "duration clamped to 86400");
        SessionTracker backwards = SessionTracker.fresh(SessionTrackerTests::uuid);
        check(backwards.start(CustomerEvents.SessionTrigger.ACCOUNT_SWITCH, null, T0, 50_000).size() == 1, "no visible screen: start only");
        check(((Integer) props(backwards.end(CustomerEvents.SessionEndReason.LOGOUT, T0, 10_000).get(0)).get("duration_s")) == 0, "never negative");
    }

    private static void screenSuppression() {
        SessionTracker tracker = started();
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 1_000).size() == 1, "first view");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 30_999).isEmpty(), "same screen < 30 s: suppressed");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 31_000).size() == 1, "same screen after 30 s");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_PRO), T0, 32_000).size() == 1, "another screen at once");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 33_000).size() == 1,
                "back to the first: the last view was another screen");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 34_000).isEmpty(), "suppressed again");
        SessionTracker fresh = SessionTracker.fresh(SessionTrackerTests::uuid);
        fresh.start(CustomerEvents.SessionTrigger.LAUNCH, CustomerEvents.Screen.ZG_HUB, T0, 0);
        check(fresh.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 1_000).isEmpty(),
                "the start's visible screen counts as the last view");
        fresh.end(CustomerEvents.SessionEndReason.BACKGROUND, T0, 2_000);
        check(fresh.start(CustomerEvents.SessionTrigger.FOREGROUND, CustomerEvents.Screen.ZG_HUB, T0, 3_000).size() == 2,
                "a new session reports the screen again");
    }

    private static void featureUsedOncePerSession() {
        SessionTracker tracker = started();
        check(tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.OCR), T0, 1).size() == 1, "first use");
        check(tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.OCR), T0, 2).isEmpty(), "once per session");
        check(tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.DICTATION), T0, 3).size() == 1, "per feature");
        check(tracker.record(CustomerEvents.featureToggle(CustomerEvents.Feature.OCR, true), T0, 4).size() == 1, "toggles are not deduplicated");
        check(tracker.record(CustomerEvents.featureToggle(CustomerEvents.Feature.OCR, true), T0, 5).size() == 1, "every toggle counts");
        tracker.end(CustomerEvents.SessionEndReason.BACKGROUND, T0, 6);
        check(tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.OCR), T0, 7).isEmpty(), "still the same (ended) session");
        tracker.start(CustomerEvents.SessionTrigger.FOREGROUND, null, T0, 8);
        check(tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.OCR), T0, 9).size() == 1, "a new session: again");
    }

    private static void betweenSessions() {
        SessionTracker tracker = started();
        tracker.record(CustomerEvents.vaultChatAdded(), T0, 1);
        tracker.end(CustomerEvents.SessionEndReason.BACKGROUND, T0 + 10, 2);
        String session = tracker.sessionId();
        int next = tracker.nextSeq();
        List<SessionTracker.Stamped> background = tracker.record(CustomerEvents.tasksMigration(CustomerEvents.MigrationPhase.COMPLETED, 1, 2, 0, 3L,
                null), T0 + 100, 100_000);
        check(background.size() == 1 && background.get(0).sessionId.equals(session) && background.get(0).seq == next,
                "an event between sessions continues the most recent session and its seq");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.TASKS_BOARD), T0 + 101, 101_000).size() == 1,
                "a screen of this process's (ended) session is still attributed to it");
        // An earlier process's session: events continue it, screens are never attributed to it.
        SessionTracker resumed = SessionTracker.resume(SessionTrackerTests::uuid, session, 42, T0, T0 + 10);
        check(!resumed.isOpen() && resumed.sessionId().equals(session) && resumed.nextSeq() == 42 && resumed.endedAt() == T0 + 10, "resumed row");
        check(resumed.record(CustomerEvents.screenView(CustomerEvents.Screen.TASKS_BOARD), T0, 0).isEmpty(),
                "no screen.view for a session of an earlier process");
        List<SessionTracker.Stamped> continued = resumed.record(CustomerEvents.listCreated(CustomerEvents.TasksScope.CLOUD), T0 + 200, 0);
        check(continued.size() == 1 && continued.get(0).seq == 42 && continued.get(0).sessionId.equals(session), "continues seq 42");
        check(resumed.end(CustomerEvents.SessionEndReason.BACKGROUND, T0, 0).isEmpty(), "an earlier process's session is never ended here");
    }

    private static void withoutSession() {
        SessionTracker tracker = SessionTracker.fresh(SessionTrackerTests::uuid);
        check(tracker.record(CustomerEvents.vaultChatAdded(), T0, 0).isEmpty(), "no session: dropped");
        check(tracker.record(CustomerEvents.screenView(CustomerEvents.Screen.ZG_HUB), T0, 0).isEmpty(), "no session: no screen");
        check(tracker.record(null, T0, 0).isEmpty(), "null event");
        tracker.countError(CustomerEvents.ErrorDomain.NETWORK, "timeout");
        check(tracker.hasAggregates() && tracker.drainAggregates(T0).isEmpty() && !tracker.hasAggregates(), "aggregates without a session are discarded");
        check(tracker.nextSeq() == 0 && tracker.sessionId() == null, "nothing spent");
    }

    private static void restartDropsTheOpenSession() {
        SessionTracker tracker = started();
        String first = tracker.sessionId();
        tracker.countError(CustomerEvents.ErrorDomain.NETWORK, "timeout");
        tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.OCR), T0, 1);
        List<SessionTracker.Stamped> again = tracker.start(CustomerEvents.SessionTrigger.FOREGROUND, null, T0 + 5, 2);
        check(again.size() == 1 && again.get(0).event.name.equals(CustomerEvents.SESSION_START) && again.get(0).seq == 0,
                "a start while open drops the open session silently (no end, no aggregates)");
        check(!tracker.sessionId().equals(first) && !tracker.hasAggregates(), "a new session id; the counters are gone");
        check(tracker.record(CustomerEvents.featureUsed(CustomerEvents.Feature.OCR), T0, 3).size() == 1, "the used set is per session");
    }

    private static void aggregates() {
        SessionTracker tracker = started();
        for (int i = 0; i < 3; i++) tracker.countError(CustomerEvents.ErrorDomain.NETWORK, "timeout");
        for (int i = 0; i < 1500; i++) tracker.countError(CustomerEvents.ErrorDomain.STORAGE, ErrorCodes.sqlite(13));
        tracker.countError(CustomerEvents.ErrorDomain.NETWORK, "Read timed out after 30000 ms");
        tracker.countError(CustomerEvents.ErrorDomain.NETWORK, "unknown");
        tracker.countError(null, "timeout");
        tracker.countSyncIssue(CustomerEvents.SyncIssueKind.STALLED, 2);
        tracker.countSyncIssue(CustomerEvents.SyncIssueKind.CONFLICT_AUTO_RESOLVED, 0);
        tracker.countSyncIssue(CustomerEvents.SyncIssueKind.STALLED, 999);
        tracker.countSyncIssue(null, 5);
        List<SessionTracker.Stamped> drained = tracker.drainAggregates(T0 + 1);
        List<String> seen = new ArrayList<>();
        for (SessionTracker.Stamped event : drained) seen.add(event.event.toString());
        check(seen.size() == 5, "three error codes, two sync kinds: " + seen);
        check(seen.get(0).equals("error.occurred{\"domain\":\"network\",\"code\":\"timeout\",\"count\":3}"), "first-seen order, counted");
        check(seen.get(1).equals("error.occurred{\"domain\":\"storage\",\"code\":\"sqlite_13\",\"count\":1000}"), "capped at 1000");
        check(seen.get(2).equals("error.occurred{\"domain\":\"network\",\"code\":\"unknown\",\"count\":2}"),
                "free text and unknown share the unknown code");
        check(seen.get(3).equals("tasks.sync_issue{\"kind\":\"conflict_auto_resolved\",\"count\":1}")
                && seen.get(4).equals("tasks.sync_issue{\"kind\":\"stalled\",\"count\":1000}"), "sync issues by kind, at least 1, capped");
        check(!tracker.hasAggregates() && tracker.drainAggregates(T0 + 2).isEmpty(), "drained once");
        tracker.countError(CustomerEvents.ErrorDomain.LINK, "attest_unavailable");
        List<SessionTracker.Stamped> end = tracker.end(CustomerEvents.SessionEndReason.BACKGROUND, T0 + 3, 10);
        check(end.size() == 2 && end.get(0).event.name.equals(CustomerEvents.ERROR_OCCURRED) && end.get(1).event.name.equals(CustomerEvents.SESSION_END),
                "aggregates are materialized before app.session_end");
        check(end.get(0).seq < end.get(1).seq, "in seq order");
    }

    private static void seqCeiling() {
        SessionTracker tracker = SessionTracker.resume(SessionTrackerTests::uuid, uuid(), CustomerEvents.MAX_SEQ, T0, 0);
        List<SessionTracker.Stamped> last = tracker.record(CustomerEvents.vaultChatAdded(), T0, 0);
        check(last.size() == 1 && last.get(0).seq == CustomerEvents.MAX_SEQ, "seq 1000000 is the last one given out");
        check(tracker.nextSeq() == SessionTracker.MAX_NEXT_SEQ, "next_seq stops at 1000001");
        check(tracker.record(CustomerEvents.vaultChatAdded(), T0, 0).isEmpty(), "nothing past 1000000");
        check(tracker.nextSeq() == SessionTracker.MAX_NEXT_SEQ, "and nothing spent");
    }

    private static void resumeRows() {
        check(SessionTracker.resume(SessionTrackerTests::uuid, "not-a-uuid", 3, T0, 0).sessionId() == null, "a bad session id is no session");
        check(SessionTracker.resume(SessionTrackerTests::uuid, uuid().toUpperCase(Locale.ROOT), 3, T0, 0).sessionId() == null, "upper case is not a uuid4");
        check(SessionTracker.resume(SessionTrackerTests::uuid, uuid(), -1, T0, 0).sessionId() == null, "a negative next_seq is no session");
        check(SessionTracker.resume(SessionTrackerTests::uuid, uuid(), SessionTracker.MAX_NEXT_SEQ + 1, T0, 0).sessionId() == null,
                "next_seq above 1000001 is no session");
        check(SessionTracker.resume(SessionTrackerTests::uuid, null, 0, T0, 0).sessionId() == null, "no row");
        SessionTracker ok = SessionTracker.resume(SessionTrackerTests::uuid, uuid(), 0, -5, -5);
        check(ok.sessionId() != null && ok.startedAt() == 0 && ok.endedAt() == 0, "times are never negative");
        try {
            SessionTracker.fresh(null);
            check(false, "ids are required");
        } catch (IllegalArgumentException expected) {
            check(true, "ids are required");
        }
    }

    // ---- helpers ----

    private static SessionTracker started() {
        SessionTracker tracker = SessionTracker.fresh(SessionTrackerTests::uuid);
        tracker.start(CustomerEvents.SessionTrigger.LAUNCH, null, T0, 0);
        return tracker;
    }

    private static Map<String, Object> props(SessionTracker.Stamped stamped) {
        return MiniJson.object(MiniJson.object(MiniJson.parse(stamped.json)).get("props"));
    }

    private static String uuid() { return String.format(Locale.ROOT, "00000000-0000-4000-a000-%012d", ++ids); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

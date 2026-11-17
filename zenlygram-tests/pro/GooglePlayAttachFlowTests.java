package org.telegram.messenger.zenlygram.pro;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain-JVM failure-injection checks of the production Google attach orchestration (AC-P3):
 * GooglePlayAttachFlow.attach runs ZenlyPro's real commit order against a Boundary. Here the
 * boundary is an in-memory session ("disk") whose save, network send, grant adoption and fresh
 * refresh can each throw, so every interruption point is exercised with the production ordering.
 * No server, Play purchase, Telegram identity or entitlement is involved.
 */
public final class GooglePlayAttachFlowTests {
    private static int checks;
    private static final String TOKEN = "fixture-purchase-token.not-a-real-play-token";
    private static final String HASH = GooglePlayAttachKeys.tokenHash(TOKEN);
    private static final GooglePlayAttachKeys.Entropy ENTROPY = bytes -> { for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (7 * i + 1); };

    public static void main(String[] args) throws Exception {
        nothingLeavesTheDeviceWithoutDurableRecovery();
        lostResponse();
        freshAnswer();
        freshAnswerAdoptionFails();
        freshAnswerCleanupWriteFails();
        hintArrivingDuringAdoptionStaysOwed();
        replayedAnswer();
        rejectedAndTransientAnswers();
        identityChangedMidFlight();
        localCooldownRefusesBeforeJournal();
        System.out.println("Google attach flow: " + checks + " checks passed");
    }

    /**
     * The binding's encrypted session as ZenlyPro persists it: record fields plus the adopted grant.
     * Like the production adapter, every step after the send re-checks the account Stamp and throws
     * session_changed once the Telegram identity or binding changed.
     */
    private static final class Session implements GooglePlayAttachFlow.Boundary<String> {
        String keys;
        long received, consumed;
        String entitlement = "old-grant";
        final List<String> events = new ArrayList<>();
        GooglePlayAttachFlow.Reply reply = new GooglePlayAttachFlow.Reply(200, null, false);
        int failSave = -1, saves;
        boolean failSend, failAdoptFresh, failAdoptCurrent, identityChangedAfterSend, hintDuringAdoption, sent, coolingDown;

        private void requireCurrent() {
            if (identityChangedAfterSend && sent) { events.add("stamp-rejected"); throw new IllegalStateException("session_changed"); }
        }

        /** The route's local Retry-After cooldown, checked before anything is written. */
        @Override public void preflight() throws Exception {
            events.add("preflight");
            if (coolingDown) throw new IllegalStateException("rate_limited");
        }

        /** One locked read-modify-write of the session, as ZenlyPro does it (a hint can never be overwritten). */
        @Override public GooglePlayAttachRecord update(GooglePlayAttachFlow.Change change) throws Exception {
            requireCurrent();
            events.add("load");
            GooglePlayAttachRecord next = change.apply(GooglePlayAttachRecord.of(keys, received, consumed));
            if (saves++ == failSave) { events.add("save-failed"); throw new IOException("disk full"); }
            events.add("save");
            keys = next.encodedKeys();
            received = next.received();
            consumed = next.consumed();
            return next;
        }

        @Override public GooglePlayAttachFlow.Reply send(String idempotencyKey) throws Exception {
            events.add("send:" + idempotencyKey);
            sent = true;
            if (failSend) throw new IOException("connection reset after the request was written");
            return reply;
        }

        @Override public String adoptFresh() throws Exception {
            requireCurrent();
            if (failAdoptFresh) { events.add("adopt-failed"); throw new IllegalStateException("invalid_response"); }
            events.add("adopt");
            entitlement = "fresh-grant";
            if (hintDuringAdoption) received++; // a push hint persisted by another worker step meanwhile
            return "active";
        }

        @Override public void adoptCurrent() throws Exception {
            requireCurrent();
            if (failAdoptCurrent) { events.add("refresh-failed"); throw new IOException("offline"); }
            events.add("refresh");
            entitlement = "current-grant";
        }

        @Override public void reject() throws Exception {
            requireCurrent();
            events.add("reject");
            throw new IllegalStateException("server:" + reply.status);
        }

        GooglePlayAttachRecord disk() { return GooglePlayAttachRecord.of(keys, received, consumed); }
        String key() { return disk().keys.keyFor(HASH); }
    }

    /** True only when the attach failed with exactly the fault injected at that boundary. */
    private static boolean failsWith(Session session, Class<? extends Exception> type, String message) {
        try {
            GooglePlayAttachFlow.attach(session, HASH, false, ENTROPY);
            return false;
        } catch (Exception failure) {
            return failure.getClass() == type && message.equals(failure.getMessage());
        }
    }

    private static void nothingLeavesTheDeviceWithoutDurableRecovery() throws Exception {
        Session session = new Session();
        session.failSave = 0;
        check(failsWith(session, IOException.class, "disk full"), "a failed pre-send write aborts the attach");
        check(!session.sent, "the purchase token is never sent unless its key and owed refresh are on disk");
        check(session.key() == null && !session.disk().refreshOwed(), "nothing half-written remains");
    }

    private static void lostResponse() throws Exception {
        Session session = new Session();
        session.failSend = true;
        check(failsWith(session, IOException.class, "connection reset after the request was written"), "transport loss surfaces as an error");
        String key = session.key();
        check(key != null && session.disk().refreshOwed(), "after the lost answer the operation key and owed refresh stay on disk");
        session.failSend = false;
        session.events.clear();
        GooglePlayAttachFlow.attach(session, HASH, false, ENTROPY);
        check(session.events.contains("send:" + key), "the automatic resend reuses the same server operation key");
    }

    private static void freshAnswer() throws Exception {
        Session session = new Session();
        String state = GooglePlayAttachFlow.attach(session, HASH, false, ENTROPY);
        check("active".equals(state), "the fresh backend state is reported");
        check(session.events.indexOf("adopt") > session.events.indexOf("send:" + firstKey(session))
                && session.events.lastIndexOf("save") > session.events.indexOf("adopt"),
                "cleanup is written only after the signed grant was adopted");
        check(session.key() == null && !session.disk().refreshOwed(), "a completed attach leaves nothing pending");
    }

    private static void freshAnswerAdoptionFails() {
        Session session = new Session();
        session.failAdoptFresh = true;
        check(failsWith(session, IllegalStateException.class, "invalid_response"),
                "an unverifiable grant (bad token, unknown signing key, disk) fails the attach");
        check(session.key() != null && session.disk().refreshOwed(), "the operation and the owed refresh both survive for recovery");
    }

    private static void freshAnswerCleanupWriteFails() {
        Session session = new Session();
        session.failSave = 1; // 0: pre-send, 1: cleanup after adoption
        check(failsWith(session, IOException.class, "disk full") && session.events.contains("adopt"),
                "the cleanup write after adoption is the one that failed");
        check(session.disk().refreshOwed(), "a failed cleanup leaves the refresh owed (one extra signed GET, never lost access)");
    }

    private static void hintArrivingDuringAdoptionStaysOwed() throws Exception {
        Session session = new Session();
        session.hintDuringAdoption = true;
        GooglePlayAttachFlow.attach(session, HASH, false, ENTROPY);
        check(session.key() == null, "the operation completed");
        check(session.disk().refreshOwed(), "cleanup is computed from the session after adoption, so a newer hint is not erased");
    }

    private static void replayedAnswer() throws Exception {
        Session session = new Session();
        session.reply = new GooglePlayAttachFlow.Reply(200, null, true);
        session.failAdoptCurrent = true;
        check(failsWith(session, IOException.class, "offline"), "a replay whose fresh refresh fails is reported");
        check(session.key() == null, "the replay proves the operation committed: its key is completed");
        check(session.disk().refreshOwed() && "old-grant".equals(session.entitlement),
                "the replayed grant is never adopted and the refresh stays owed");
        session.failAdoptCurrent = false;
        session.events.clear();
        check(GooglePlayAttachFlow.attach(session, HASH, false, ENTROPY) == null, "a replay reports no state of its own");
        check(session.events.indexOf("refresh") >= 0 && !session.disk().refreshOwed() && "current-grant".equals(session.entitlement),
                "after the fresh signed GET is adopted nothing is owed");
    }

    private static void rejectedAndTransientAnswers() {
        Session rejected = new Session();
        rejected.reply = new GooglePlayAttachFlow.Reply(422, "invalid_purchase", false);
        check(failsWith(rejected, IllegalStateException.class, "server:422"), "a rejection is reported");
        check(rejected.key() == null && rejected.disk().refreshOwed() && !rejected.events.contains("adopt"),
                "a definitive rejection ends the operation, grants nothing, and leaves the refresh owed");
        Session mismatch = new Session();
        mismatch.reply = new GooglePlayAttachFlow.Reply(409, "purchase_account_mismatch", false);
        check(failsWith(mismatch, IllegalStateException.class, "server:409") && mismatch.key() == null,
                "another account's purchase ends the operation; nothing moves here");
        Session busy = new Session();
        busy.reply = new GooglePlayAttachFlow.Reply(409, "idempotency_in_flight", false);
        check(failsWith(busy, IllegalStateException.class, "server:409") && busy.key() != null && busy.disk().refreshOwed(),
                "a still-running operation keeps its key");
        Session outage = new Session();
        outage.reply = new GooglePlayAttachFlow.Reply(503, "service_unavailable", false);
        check(failsWith(outage, IllegalStateException.class, "server:503") && outage.key() != null && outage.disk().refreshOwed(),
                "an outage keeps key and owed refresh");
        check(!outage.events.contains("save-failed") && outage.saves == 1, "a transient answer writes nothing after the send");
    }

    /** The per-account Stamp check (requireCurrentLocked) throws once the identity or binding changed. */
    private static void identityChangedMidFlight() {
        Session session = new Session();
        session.identityChangedAfterSend = true;
        check(failsWith(session, IllegalStateException.class, "session_changed"), "a stale completion is dropped");
        check(session.saves == 1 && !session.events.contains("adopt"), "nothing is adopted or written after the identity changed");
    }

    /** Security review L1: a send refused locally (route cooldown) must not journal, owe or replace a key. */
    private static void localCooldownRefusesBeforeJournal() throws Exception {
        Session session = new Session();
        session.failSend = true;
        check(failsWith(session, IOException.class, "connection reset after the request was written"), "an earlier attach was lost");
        String pending = session.key();
        long owed = session.received;
        session.failSend = false;
        session.coolingDown = true;
        session.events.clear();
        int savesBefore = session.saves;
        try {
            GooglePlayAttachFlow.attach(session, HASH, true, ENTROPY); // even an explicit restore
            check(false, "a cooling-down route refuses the attach");
        } catch (IllegalStateException refused) {
            check("rate_limited".equals(refused.getMessage()), "the local cooldown is the reported reason");
        }
        check(session.events.equals(Collections.singletonList("preflight")), "the cooldown check is the only step: no read, write or send");
        check(session.saves == savesBefore, "nothing is written while the route cools down");
        check(pending.equals(session.key()) && session.received == owed, "the earlier pending key and owed sequence are untouched");
    }

    private static String firstKey(Session session) {
        for (String event : session.events) if (event.startsWith("send:")) return event.substring(5);
        return "";
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

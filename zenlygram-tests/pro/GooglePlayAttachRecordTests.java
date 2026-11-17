package org.telegram.messenger.zenlygram.pro;

import java.util.Collections;
import java.util.List;

/**
 * Plain-JVM checks of the durable Google attach record (AC-P3): the per-binding pending
 * Idempotency-Key and the "fresh signed refresh owed" sequence that ZenlyPro persists in the
 * encrypted session. Every step goes through the production transitions and the persisted field
 * values (as after a process restart); no purchase, server or entitlement is fabricated. These are
 * record transitions only, not the real encrypted store or JWS verification.
 */
public final class GooglePlayAttachRecordTests {
    private static int checks;
    private static final String TOKEN = "fixture-purchase-token.not-a-real-play-token";
    private static final String HASH = GooglePlayAttachKeys.tokenHash(TOKEN);
    private static final String ACCOUNT = repeat("a", 64), OTHER = repeat("b", 64);
    private static final long HOUR = 3600, MINUTE_MS = 60000;
    private static final int PURCHASED = 1;

    public static void main(String[] args) {
        oweBeforeSend();
        lostResponseAfterServerCommit();
        pendingKeyOutlivesASettlingRefresh();
        freshAnswerAdopted();
        replayedAndRejected();
        foregroundRefresh();
        corruptPersistedValues();
        System.out.println("Google attach record: " + checks + " checks passed");
    }

    /** What ZenlyPro writes into the session and reads back after a restart. */
    private static GooglePlayAttachRecord restart(GooglePlayAttachRecord record) {
        return GooglePlayAttachRecord.of(record.encodedKeys(), record.received(), record.consumed());
    }

    private static GooglePlayAttachKeys.Entropy entropy(int seed) {
        return bytes -> { for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed + i); };
    }

    private static GooglePlayModel.Catalog catalog(String account) {
        return new GooglePlayModel.Catalog(account, Collections.singletonList(new GooglePlayModel.Plan("fixture.pro", "monthly")));
    }

    private static GooglePlayModel.Purchase purchase(String account, boolean acknowledged) {
        return new GooglePlayModel.Purchase(TOKEN, Collections.singletonList("fixture.pro"), PURCHASED, account, acknowledged);
    }

    private static void oweBeforeSend() {
        GooglePlayAttachRecord empty = GooglePlayAttachRecord.of(null, 0, 0);
        check(!empty.refreshOwed(), "a fresh binding owes nothing");
        GooglePlayAttachRecord journal = empty.beforeSend(HASH, false, entropy(1));
        String key = journal.keys.keyFor(HASH);
        check(key != null && key.startsWith("google-attach-"), "the operation key is recorded before the request");
        check(journal.refreshOwed() && journal.received() == 1, "a signed refresh is owed before the purchase token leaves the device");
        check(!empty.refreshOwed() && empty.keys.size() == 0, "transitions return new values");
    }

    /** The answer was lost after the backend committed; nothing settled yet. */
    private static void lostResponseAfterServerCommit() {
        GooglePlayAttachRecord journal = GooglePlayAttachRecord.of(null, 0, 0).beforeSend(HASH, false, entropy(2));
        GooglePlayAttachRecord persisted = restart(journal);
        check(persisted.refreshOwed(), "after the lost answer and a restart the refresh is still owed");
        check(ProPushHint.foregroundRefreshDue(true, persisted.refreshOwed(), 10 * MINUTE_MS, true, HOUR),
                "a grant under a day old does not suppress the owed refresh on the next foreground");
        GooglePlayAttachRecord again = persisted.beforeSend(HASH, false, entropy(3));
        check(again.keys.keyFor(HASH).equals(journal.keys.keyFor(HASH)), "an automatic resend stays the same server operation");
        GooglePlayAttachRecord restore = persisted.beforeSend(HASH, true, entropy(4));
        check(!restore.keys.keyFor(HASH).equals(journal.keys.keyFor(HASH)), "an explicit restore is a new operation");
    }

    /**
     * Security review M1: journal -> the send times out -> an unrelated refresh GET (which may predate
     * the server commit) settles the owed sequence -> Play then reports the purchase acknowledged.
     * The unresolved operation key must still select the purchase for an automatic same-key resend.
     */
    private static void pendingKeyOutlivesASettlingRefresh() {
        GooglePlayAttachRecord journal = GooglePlayAttachRecord.of(null, 0, 0).beforeSend(HASH, false, entropy(5));
        String key = journal.keys.keyFor(HASH);
        GooglePlayAttachRecord afterRefresh = restart(journal.settled(journal.received()));
        check(!afterRefresh.refreshOwed(), "the refresh settled everything owed when it started");
        check(afterRefresh.keys.pendingTokenHashes().contains(HASH), "the attach outcome is still unknown: its key stays pending");

        List<GooglePlayModel.Purchase> play = Collections.singletonList(purchase(ACCOUNT, true));
        check(GooglePlayModel.toAttach(play, catalog(ACCOUNT), false, Collections.<String>emptySet()).isEmpty(),
                "an acknowledged purchase without a pending operation is not re-sent");
        check(GooglePlayModel.toAttach(play, catalog(ACCOUNT), false, afterRefresh.keys.pendingTokenHashes()).size() == 1,
                "an acknowledged purchase whose operation is unresolved is re-sent automatically");
        check(GooglePlayModel.awaitsBackend(play.get(0), afterRefresh.keys.pendingTokenHashes()),
                "foreground reconcile selects it even though Play shows it acknowledged");
        check(!GooglePlayModel.awaitsBackend(play.get(0), Collections.<String>emptySet()), "otherwise reconcile leaves it alone");
        check(afterRefresh.beforeSend(HASH, false, entropy(6)).keys.keyFor(HASH).equals(key),
                "the automatic resend reuses the same Idempotency-Key, so the backend replays the committed attach");
        check(GooglePlayModel.toAttach(Collections.singletonList(purchase(OTHER, true)), catalog(ACCOUNT), false,
                afterRefresh.keys.pendingTokenHashes()).isEmpty(), "a pending key never moves another account's purchase here");
        GooglePlayAttachRecord resolved = afterRefresh.completed(HASH, key);
        check(!resolved.keys.pendingTokenHashes().contains(HASH)
                        && GooglePlayModel.toAttach(play, catalog(ACCOUNT), false, resolved.keys.pendingTokenHashes()).isEmpty(),
                "once the same-key attach resolves, the acknowledged purchase is left alone again");
    }

    private static void freshAnswerAdopted() {
        GooglePlayAttachRecord journal = GooglePlayAttachRecord.of(null, 0, 0).beforeSend(HASH, false, entropy(7));
        String key = journal.keys.keyFor(HASH);
        GooglePlayAttachRecord adopted = journal.adopted(HASH, key, journal.received());
        check(adopted.keys.keyFor(HASH) == null, "the operation ends once its signed grant is adopted");
        check(!adopted.refreshOwed(), "the adopted fresh grant satisfies the owed refresh");

        GooglePlayAttachRecord hintDuringAttach = GooglePlayAttachRecord.of(journal.encodedKeys(),
                journal.owed.received().received, journal.consumed());
        check(hintDuringAttach.adopted(HASH, key, journal.received()).refreshOwed(),
                "a push hint that arrived while the attach was in flight stays owed");
    }

    private static void replayedAndRejected() {
        GooglePlayAttachRecord journal = GooglePlayAttachRecord.of(null, 0, 0).beforeSend(HASH, false, entropy(8));
        String key = journal.keys.keyFor(HASH);
        GooglePlayAttachRecord replayed = journal.completed(HASH, key);
        check(replayed.keys.keyFor(HASH) == null && replayed.refreshOwed(),
                "a replayed answer ends the operation but its stored grant is never adopted: refresh stays owed");
        GooglePlayAttachRecord newer = journal.beforeSend(HASH, true, entropy(9));
        check(newer.completed(HASH, key).keys.keyFor(HASH).equals(newer.keys.keyFor(HASH)),
                "a stale completion never clears a newer restore operation");
    }

    private static void foregroundRefresh() {
        check(!ProPushHint.foregroundRefreshDue(true, false, 10 * MINUTE_MS, true, HOUR), "nothing owed: a recent grant keeps the daily skip");
        check(ProPushHint.foregroundRefreshDue(true, false, 10 * MINUTE_MS, true, ProPolicy.DAY), "a day-old grant refreshes");
        check(ProPushHint.foregroundRefreshDue(true, false, 10 * MINUTE_MS, false, 0), "no verified grant refreshes");
        check(!ProPushHint.foregroundRefreshDue(true, true, 4 * MINUTE_MS, true, HOUR), "owed refreshes are still spaced five minutes apart");
        check(!ProPushHint.foregroundRefreshDue(false, true, 10 * MINUTE_MS, true, HOUR), "an unlinked account never refreshes");
    }

    private static void corruptPersistedValues() {
        GooglePlayAttachRecord corrupt = GooglePlayAttachRecord.of("not-a-ledger", -5, 99);
        check(corrupt.keys.size() == 0 && !corrupt.refreshOwed() && corrupt.received() == 0 && corrupt.consumed() == 0,
                "tampered session fields start clean instead of failing the attach");
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

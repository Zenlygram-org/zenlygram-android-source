package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.File;
import java.util.Arrays;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.*;

/**
 * Host regression tests (final review, TasksCloudEngine.java:445): a persisted account-level pause must not outlive the
 * re-link it waits for. The pause binding is process-local (the Pro generation), so before the fix a 401 pause saved
 * by one process kept every later process paused (SESSION_INVALID, no request, migration creates never sent) until the
 * user tapped Refresh, even after the account was linked again. The scripted remote models TasksApi's 401 contract: the
 * failure is delivered after the binding's refresh credential was dropped, so Pro reports no principal until a re-link;
 * the re-link here keeps the principal (and, on the server, the binding id: a re-bind of the same install and account).
 * Same harness as TasksCloudStateTests (real engine/ledger over a host SQLite file, sqlite_bridge.py, NOT Android
 * SQLite); the batch case uses TasksCloudR4Tests' R4 helpers.
 */
public final class TasksCloudPauseBindingTests {
    /** What Pro reports right after a real 401: the same Telegram user, no principal (the refresh credential is gone). */
    static TasksCloudRemote.Identity dropped(long generation) {
        return new TasksCloudRemote.Identity(TG, null, generation);
    }

    static TasksCloudRemote.Identity linked(String principal, long generation) {
        return new TasksCloudRemote.Identity(TG, principal, generation);
    }

    static boolean paused(Harness h, String kind) {
        return kind.equals(h.ledger.readMeta(TasksCloudLedger.META_PAUSE));
    }

    /** One user create answered 401 on the single route (an R3 server: the batch 404 opens the fallback first). */
    static Request sessionPausedSend(Harness h) {
        h.engine.createTask(content(null, "Sent into a 401"));
        h.drain();
        Request sent = h.remote.take("mutate");
        h.remote.identity = dropped(1);
        fail(sent, TasksCloudRemote.Failure.Kind.SESSION, "device_revoked", null, 0);
        h.drain();
        check(h.state().status == TasksCloudState.Status.SESSION_INVALID && paused(h, TasksCloudLedger.PAUSE_SESSION),
                "a real 401 records the durable session pause");
        check(h.pending(sent.mutation.mutationId).state == TasksCloudState.PendingState.QUEUED, "its entry waits with the same id");
        return sent;
    }

    // ---- scenarios ----

    /** The reported scenario: 401, process death, re-consent and auto-link of the same account in the next process. */
    static void sessionPauseEndsWithTheRelinkAfterARestart() {
        Harness h = new Harness();
        String kept = TasksWire.newId();
        h.bootstrap(task(kept, "1", "Kept"));
        Request first = sessionPausedSend(h);

        Harness next = h.restart();
        next.remote.identity = dropped(4); // needs_reconsent: no binding and no auto-link until the user acts on the wall
        next.engine.start();
        next.drain();
        next.remote.none("no binding yet: nothing is sent");
        check(next.state().status == TasksCloudState.Status.NOT_LINKED && paused(next, TasksCloudLedger.PAUSE_SESSION),
                "the pause is kept while no binding exists");

        next.remote.identity = linked(A, 5); // re-consent, auto-link re-links the same core account
        next.engine.identityChanged();       // the repository's identity gate saw the Pro generation change
        next.drain();
        Request resumed = next.remote.take("mutate");
        check(resumed.mutation.mutationId.equals(first.mutation.mutationId) && Arrays.equals(resumed.mutation.body(), first.mutation.body()),
                "the re-link resumes the same sealed mutation without an explicit refresh");
        check(next.ledger.readMeta(TasksCloudLedger.META_PAUSE) == null, "the pause is lifted durably");
        receipt(resumed, "1");
        next.drain();
        changes(next.remote.take("changes"), "c2", false);
        next.drain();
        next.remote.none("one cycle, then idle");
        check(next.state().status == TasksCloudState.Status.READY && next.state().task(kept) != null,
                "automatic sync runs again; the cache was kept");

        Harness later = next.restart();
        later.remote.identity = linked(A, 1);
        later.engine.start();
        later.drain();
        changes(later.remote.take("changes"), "c3", false);
        later.drain();
        check(later.state().status == TasksCloudState.Status.READY, "a lifted pause stays lifted across the next restart");
        later.sql.close();
    }

    /** Auto-link already ran when the sync owner starts the engine: the first step resumes. */
    static void sessionPauseEndsWhenTheEngineStartsAlreadyRelinked() {
        Harness h = new Harness();
        h.bootstrap();
        Request first = sessionPausedSend(h);

        Harness next = h.restart();
        next.remote.identity = linked(A, 2);
        next.engine.start();
        next.drain();
        Request resumed = next.remote.take("mutate");
        check(resumed.mutation.mutationId.equals(first.mutation.mutationId), "the first step after the re-link resumes the outbox");
        check(next.ledger.readMeta(TasksCloudLedger.META_PAUSE) == null, "no pause left");
        next.sql.close();
    }

    /** A migration batch whose item got the 401: the receipts count, the rest is resent after the re-link. */
    static void batchItemSessionPauseEndsWithTheRelinkAfterARestart() {
        Harness h = TasksCloudR4Tests.r4();
        h.engine.enqueueImport(A, TasksCloudR4Tests.migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch());
        h.drain();
        Request batch = TasksCloudR4Tests.takeBatch(h);
        TasksWire.Mutation committed = batch.batch.items.get(0).mutation, refused = batch.batch.items.get(1).mutation;
        h.remote.identity = dropped(1); // the item 401 applied the session handling once (TasksApi.sessionItem)
        TasksCloudR4Tests.answer(batch, null, TasksCloudR4Tests.ok(committed, "1"), TasksCloudR4Tests.err(refused, 401, "device_revoked", ""));
        h.drain();
        check(h.state().status == TasksCloudState.Status.SESSION_INVALID && paused(h, TasksCloudLedger.PAUSE_SESSION),
                "a batch item 401 is the same session pause");
        check(TasksCloudR4Tests.entry(h, committed.mutationId).ackedRevision != null, "the other item's receipt counted");
        check(TasksCloudSchema.QUEUED.equals(TasksCloudR4Tests.entry(h, refused.mutationId).state), "the refused migration create waits");

        Harness next = h.restart();
        next.remote.batchRoute = true;
        next.remote.identity = linked(A, 3);
        next.engine.start();
        next.drain();
        Request resent = TasksCloudR4Tests.takeBatch(next);
        check(resent.batch.items.size() == 1 && resent.batch.items.get(0).mutation.mutationId.equals(refused.mutationId)
                        && resent.batch.items.get(0).origin == TasksWire.Origin.MIGRATION,
                "the migration create is resent after the re-link, same id, still a migration batch");
        TasksCloudR4Tests.answerAll(resent, "1");
        next.drain();
        check(TasksCloudR4Tests.entry(next, refused.mutationId).ackedRevision != null, "the migration completes without a refresh");
        TasksCloudR4Tests.quiet(next);
        check(next.state().status == TasksCloudState.Status.READY, "automatic sync runs again");
        next.sql.close();
    }

    /** A realtime metadata/auth 401 records the same pause; it ends the same way. */
    static void realtimeSessionPauseEndsWithTheRelinkAfterARestart() {
        Harness h = new Harness();
        h.bootstrap();
        TasksCloudRemote.Identity called = h.remote.identity;
        h.remote.identity = dropped(1);
        h.engine.realtimeRefused(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.SESSION, "invalid_token", null, 401, 0), called);
        h.drain();
        check(h.engine.accountPaused() && paused(h, TasksCloudLedger.PAUSE_SESSION), "a realtime 401 records the durable pause");

        Harness next = h.restart();
        next.remote.identity = linked(A, 6);
        next.engine.start();
        next.drain();
        changes(next.remote.take("changes"), "c2", false);
        next.drain();
        check(!next.engine.accountPaused() && next.state().status == TasksCloudState.Status.READY, "realtime may run again after the re-link");
        next.sql.close();
    }

    /** Without this account's own binding nothing lifts the pause, whatever drives the engine. */
    static void sessionPauseHoldsUntilThisAccountIsLinkedAgain() {
        Harness h = new Harness();
        h.bootstrap();
        sessionPausedSend(h);

        Harness next = h.restart();
        next.remote.identity = dropped(7);
        next.engine.start();
        next.engine.permissionChanged();
        next.engine.requeue(TasksCloudPolicy.Occasion.LINK);
        next.engine.requestSync();
        next.engine.kick();
        next.drain();
        next.advance(TasksCloudEngine.POLL_MS * 3);
        next.remote.none("no binding: nothing is sent");
        check(paused(next, TasksCloudLedger.PAUSE_SESSION), "the pause is kept");

        next.remote.identity = new TasksCloudRemote.Identity(TG + 1, A, 8); // another Telegram user in the slot
        next.engine.identityChanged();
        next.drain();
        next.remote.none("another Telegram user's binding is not this account's re-link");
        check(paused(next, TasksCloudLedger.PAUSE_SESSION) && next.state().status == TasksCloudState.Status.NOT_LINKED,
                "the pause is still kept");
        next.sql.close();
    }

    /** In the process that answered the 401 the generation fence is unchanged: the re-link resumes, nothing before. */
    static void sessionPauseInTheSameProcessEndsOnTheRelink() {
        Harness h = new Harness();
        h.bootstrap();
        Request first = sessionPausedSend(h);
        h.engine.kick();
        h.engine.requestSync();
        h.drain();
        h.advance(TasksCloudEngine.POLL_MS * 3);
        h.remote.none("no automatic retry while the binding is gone");
        h.remote.identity = linked(A, 2);
        h.engine.identityChanged();
        h.drain();
        check(h.remote.take("mutate").mutation.mutationId.equals(first.mutation.mutationId), "the observed re-link resumes");
        h.sql.close();
    }

    /**
     * A 403 tasks_account_not_linked pause kept its session: after a restart the same principal is the same binding as
     * far as this client can tell, so it stays paused. A Telegram re-link lands on the account that holds this Telegram
     * id (zenlypay BindService::claim), i.e. another principal, and the rebind lifts it.
     */
    static void unlinkedPauseAfterARestartWaitsForAnotherBinding() {
        Harness h = new Harness();
        h.bootstrap();
        h.engine.createTask(content(null, "Unlinked"));
        h.drain();
        Request first = h.remote.take("mutate");
        fail(first, TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 0);
        h.drain();
        check(paused(h, TasksCloudLedger.PAUSE_UNLINKED), "a 403 records the durable unlinked pause");

        Harness next = h.restart();
        next.remote.identity = linked(A, 2);
        next.engine.start();
        next.engine.identityChanged();
        next.drain();
        next.remote.none("the session the 403 was answered for is still linked: nothing is sent");
        check(next.state().status == TasksCloudState.Status.UNLINKED_PAUSED, "still paused after the restart");

        next.remote.identity = linked(B, 3);
        next.engine.identityChanged();
        next.drain();
        Request snapshot = next.remote.take("snapshot");
        check(snapshot.cursor == null && next.ledger.readMeta(TasksCloudLedger.META_PAUSE) == null,
                "a re-bind to another principal lifts the pause and pulls that principal's snapshot");
        check(next.pending(first.mutation.mutationId).state == TasksCloudState.PendingState.WRONG_PRINCIPAL,
                "the old principal's entry is never sent under the new one");
        next.sql.close();
    }

    public static void main(String[] args) throws Exception {
        bridge = args[0];
        dir = new File(args[1]);
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        sessionPauseEndsWithTheRelinkAfterARestart();
        sessionPauseEndsWhenTheEngineStartsAlreadyRelinked();
        batchItemSessionPauseEndsWithTheRelinkAfterARestart();
        realtimeSessionPauseEndsWithTheRelinkAfterARestart();
        sessionPauseHoldsUntilThisAccountIsLinkedAgain();
        sessionPauseInTheSameProcessEndsOnTheRelink();
        unlinkedPauseAfterARestartWaitsForAnotherBinding();
        System.out.println("Tasks cloud pause binding (host SQLite bridge, scripted remote): " + checks + " checks passed");
    }
}

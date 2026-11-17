package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.Collections;
import java.util.List;

/**
 * Host tests of the real TasksCloudIdentityGate.admit, the single boundary TasksCloudRepository passes every engine
 * delivery, subscribe callback, Pro status event and currentState() read through (main thread). The Android wiring
 * (NotificationCenter observer, TasksApi.identity() lookup, main-thread posting order) is NOT executed here.
 */
public final class TasksCloudIdentityGateTests {
    static final String P1 = "01jaaaaaaaaaaaaaaaaaaaaaaa", P2 = "01jbbbbbbbbbbbbbbbbbbbbbbb";
    static final long TG = 777;
    static int checks;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    /** A state carrying one cloud list of {@code principal} (real TasksCloudState, no wire codec needed). */
    static TasksCloudState withContent(String principal, TasksCloudState.Status status) {
        List<TasksCloudState.ListView> lists = Collections.singletonList(new TasksCloudState.ListView("l", null, "List", 0, null));
        return new TasksCloudState(status, false, true, TG, principal, null, lists, Collections.<TasksCloudState.TaskView>emptyList(),
                Collections.<TasksWire.InvitationItem>emptyList(), Collections.<TasksWire.BlockItem>emptyList(),
                Collections.<TasksCloudState.Pending>emptyList(), Collections.<TasksCloudState.Notice>emptyList());
    }

    static TasksCloudIdentityGate.Identity id(String principal, long generation) {
        return new TasksCloudIdentityGate.Identity(principal, generation);
    }

    public static void main(String[] args) {
        TasksCloudState p1 = withContent(P1, TasksCloudState.Status.READY), p2 = withContent(P2, TasksCloudState.Status.READY);

        // Race: P1 delivery D queued; Pro saves P2+gen, then posts its status event; D is handled FIRST.
        TasksCloudIdentityGate gate = new TasksCloudIdentityGate();
        TasksCloudIdentityGate.Verdict first = gate.admit(id(P1, 1), p1);
        check(first.state == p1 && first.rebind, "first admission shows P1 and lets the engine evaluate");
        TasksCloudIdentityGate.Verdict raced = gate.admit(id(P2, 2), p1);
        check(raced.state != p1 && raced.state.contentFree() && raced.state.status == TasksCloudState.Status.LOADING,
                "queued P1 delivery handled before Pro's event is replaced by a content-free placeholder");
        check(raced.rebind, "the fresh identity change requests the engine rebind");
        TasksCloudIdentityGate.Verdict event = gate.admit(id(P2, 2), raced.state);
        check(event.state == raced.state && !event.rebind, "Pro's later status event: no flicker, no second rebind");
        check(gate.admit(id(P2, 2), p2).state == p2, "P2 publications are shown");
        check(gate.admit(id(P2, 2), p1).state != p1, "late P1 publications stay rejected");

        // Second subscriber / currentState() read: the held state is re-admitted with a fresh identity.
        TasksCloudIdentityGate held = new TasksCloudIdentityGate();
        held.admit(id(P1, 1), p1);
        TasksCloudIdentityGate.Verdict reread = held.admit(id(P2, 1), p1);
        check(reread.state.contentFree() && reread.rebind, "relink while subscribed elsewhere: held P1 view replaced before the new callback");

        // Lookup failure shows no content, never moves the baseline, never loops.
        TasksCloudIdentityGate failing = new TasksCloudIdentityGate();
        failing.admit(id(P1, 1), p1);
        TasksCloudIdentityGate.Verdict failed = failing.admit(null, p1);
        check(failed.state.contentFree() && !failed.rebind, "failed lookup: placeholder, no rebind (distinct from 'no change')");
        check(failing.admit(null, failed.state).state == failed.state, "placeholder is stable; nothing recurses");
        check(failing.admit(id(P1, 1), p1).state == p1 && !failing.admit(id(P1, 1), p1).rebind, "baseline kept: same identity shows P1 again");

        // Unlinked keeps the legitimate cache persisted under the fenced principal.
        TasksCloudIdentityGate unlinked = new TasksCloudIdentityGate();
        unlinked.admit(id(P1, 1), p1);
        TasksCloudState cache = withContent(P1, TasksCloudState.Status.NOT_LINKED);
        TasksCloudIdentityGate.Verdict kept = unlinked.admit(id(null, 2), cache);
        check(kept.state == cache, "unlinked: P1 cache stays visible");
        check(unlinked.admit(id(null, 2), withContent(null, TasksCloudState.Status.NOT_LINKED)).state.contentFree(),
                "principal-less content is not shown under a fence");
        TasksCloudIdentityGate cold = new TasksCloudIdentityGate();
        check(cold.admit(id(null, 1), cache).state == cache, "cold unlinked (no linked principal ever seen): own cache shown");
        TasksCloudIdentityGate later = new TasksCloudIdentityGate();
        later.admit(id(null, 1), cache);
        check(later.admit(id(P2, 1), cache).state.contentFree(), "first linked principal after an unlinked baseline is detected");

        // Presentation-only and generation changes.
        TasksCloudIdentityGate presentation = new TasksCloudIdentityGate();
        presentation.admit(id(P2, 5), p2);
        check(!presentation.admit(id(P2, 5), p2).rebind, "expiry/policy-only events neither clear nor refetch");
        check(presentation.admit(id(P2, 6), p2).rebind && !presentation.admit(id(P2, 6), p2).rebind, "generation change re-evaluates once");

        // Errors are never swallowed, stale content never escapes through them.
        TasksCloudState staleError = withContent(P1, TasksCloudState.Status.STORAGE_FAILED);
        TasksCloudIdentityGate.Verdict error = presentation.admit(id(P2, 6), staleError);
        check(error.state.contentFree() && error.state.status == TasksCloudState.Status.STORAGE_FAILED,
                "STORAGE_FAILED over a stale P1 view is shown as a content-free error");
        TasksCloudState emptyError = TasksCloudState.empty(TasksCloudState.Status.STORAGE_FAILED, TG);
        check(presentation.admit(id(P2, 6), emptyError).state == emptyError, "content-free error after a rebind is shown as is");
        System.out.println("Tasks cloud identity gate: " + checks + " checks passed");
    }
}

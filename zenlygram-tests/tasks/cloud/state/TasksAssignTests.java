package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudR4Tests.*;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.*;

/**
 * Host tests of "the assignee pick is the invitation" (iOS wf15 §4, §5.3, §12) on the real engine, ledger and state:
 * the pure pacer, contact rule, manual-answer record and display table; a create with an assignee as one chain; the
 * pick-time refusals; Send again and the exact-epoch removal; a long rate limit deferring the assignment out of the
 * outbox and its automatic re-send; the legacy annotation (never written, cleared after a send); the automatic accept
 * of a contact's invitation with its provisional row, its reseals and its quiet drops. Same harness as
 * TasksCloudR4Tests (host SQLite bridge, scripted R4 remote; nothing here is backend or device evidence).
 */
public final class TasksAssignTests {
    static final String BOB = "555", CAROL = "556";
    /** Later than the harness clock (2027-01-15): a pending epoch that has not expired. */
    static final String LATER = "2027-06-01T00:00:00Z", EARLIER = "2026-10-08T00:00:00Z";

    // ---- wire JSON ----

    static String ownerTask(String id, String revision, String title, String annotation, String assignment, boolean completed) {
        return "{\"kind\":\"task\",\"id\":\"" + id + "\",\"revision\":\"" + revision + "\",\"owner_tg_id\":\"777\",\"viewer_role\":\"owner\","
                + "\"content\":{\"list_id\":null,\"title\":\"" + title + "\",\"description\":null,\"due_at\":null,\"priority\":0,\"sort_order\":0,"
                + "\"annotation\":" + (annotation == null ? "null" : annotation) + "},\"is_completed\":" + completed + ",\"completed_at\":"
                + (completed ? q(T0) : "null") + ",\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\",\"assignment\":"
                + (assignment == null ? "null" : assignment) + "}";
    }

    static String note(String telegramId, String name) {
        return "{\"telegram_user_id\":\"" + telegramId + "\",\"display_name\":\"" + name + "\"}";
    }

    static String assignment(String invitationId, String recipient, String state, String expiresAt) {
        return "{\"invitation_id\":\"" + invitationId + "\",\"recipient_tg_id\":\"" + recipient + "\",\"state\":\"" + state
                + "\",\"expires_at\":\"" + expiresAt + "\"}";
    }

    static String invitationFrom(String id, String taskId, String taskRevision, String owner, String expiresAt) {
        return "{\"kind\":\"invitation\",\"id\":\"" + id + "\",\"task_id\":\"" + taskId + "\",\"task_revision\":\"" + taskRevision
                + "\",\"owner_tg_id\":\"" + owner + "\",\"title\":\"Join\",\"due_at\":null,\"expires_at\":\"" + expiresAt + "\"}";
    }

    static String assigneeTask(String id, String revision, String owner, String invitationId) {
        return taskJson(id, revision, "assignee", owner, null, "Join", false, assignment(invitationId, "777", "accepted", LATER));
    }

    static TasksAssigneeDisplay.State shown(Harness h, String taskId) {
        return h.state().task(taskId).assigneeState(h.now / 1000);
    }

    static String meta(Harness h, String key) {
        return h.ledger.readMeta(key);
    }

    static Set<Long> ids(Long... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    static TasksAutoAcceptPolicy.Inputs inputs(Set<Long> contacts, Set<Long> blocked, boolean loaded, Set<Long> checked) {
        return new TasksAutoAcceptPolicy.Inputs(contacts, blocked, loaded, checked);
    }

    /** Contacts {555}, nothing blocked, the list read, 555's flag read. */
    static TasksAutoAcceptPolicy.Inputs bobIsAContact() {
        return inputs(ids(555L), Collections.<Long>emptySet(), true, ids(555L));
    }

    /** Answers the open batch: every item committed at {@code revision}; then the pull the receipt asks for stays open. */
    static Request batchOf(Harness h, int size, String message) {
        Request request = takeBatch(h);
        check(request.batch.items.size() == size, message + " (items: " + request.batch.items.size() + ")");
        return request;
    }

    /**
     * A rate-limited item as the server answers it (TasksBatchService): the items before it committed at
     * {@code revision}, the item itself 429 with its Retry-After, every later item skipped, and the batch repeating
     * that Retry-After.
     */
    static void throttle(Request request, int index, int seconds, String revision) {
        String[] results = new String[request.batch.items.size()];
        for (int i = 0; i < results.length; i++) {
            TasksWire.Mutation mutation = request.batch.items.get(i).mutation;
            results[i] = i < index ? ok(mutation, revision)
                    : i == index ? err(mutation, 429, "tasks_rate_limited", ",\"retry_after\":" + seconds) : skip(mutation);
        }
        answer(request, seconds, results);
    }

    // ---- pure: the pacer ----

    static void pacerTable() {
        check(TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(4, 9, 9, 94)) == null, "under every limit");
        check(TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(5, 0, 0, 0)) == TasksInvitationPacer.Limit.PER_PERSON, "the 6th to one person today");
        check(TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(0, 0, 10, 0)) == TasksInvitationPacer.Limit.PER_MINUTE, "the 11th this minute");
        check(TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(0, 95, 0, 0)) == TasksInvitationPacer.Limit.TOTAL, "the 96th today");
        check(TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(0, 0, 0, 95)) == TasksInvitationPacer.Limit.PENDING, "95 pending");
        check(TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(5, 95, 10, 95)) == TasksInvitationPacer.Limit.PER_PERSON, "the order: person, minute, day, pending");
        check("19700101".equals(TasksInvitationPacer.dayKey(0)) && "20270115".equals(TasksInvitationPacer.dayKey(1_800_000_000_000L)), "UTC day keys");
        check("20240229".equals(TasksInvitationPacer.dayKey(1_709_164_800_000L)) && "20231231".equals(TasksInvitationPacer.dayKey(1_704_067_199_999L)), "leap day and year end");
        check(TasksInvitationPacer.minuteKey(119_999L) == 1 && TasksInvitationPacer.minuteKey(120_000L) == 2, "epoch minutes");
        long now = 1_800_000_000_000L;
        check(!TasksInvitationPacer.isExpired("invite_day.20270115", now) && !TasksInvitationPacer.isExpired("invite_day.20270114.555", now)
                && TasksInvitationPacer.isExpired("invite_day.20270113", now) && TasksInvitationPacer.isExpired("invite_day.20270113.555", now),
                "today's and yesterday's day counters stay, older ones go");
        check(!TasksInvitationPacer.isExpired("invite_minute." + (TasksInvitationPacer.minuteKey(now) - 2880), now)
                && TasksInvitationPacer.isExpired("invite_minute." + (TasksInvitationPacer.minuteKey(now) - 2881), now), "minute counters go after two days");
        check(!TasksInvitationPacer.isExpired("invite_day.x", now) && !TasksInvitationPacer.isExpired("server_wire", now)
                && !TasksInvitationPacer.isExpired("invite_minute.x", now), "a key of neither shape is never expired");
    }

    // ---- pure: the contact rule ----

    static TasksAutoAcceptPolicy.Context context(boolean allowed, TasksAutoAcceptPolicy.Inputs inputs, Set<String> tasksBlocked, long nowSeconds,
                                                 Set<String> unresolved, TasksInviteAnswers answers, HashMap<String, Integer> attempts) {
        return new TasksAutoAcceptPolicy.Context(allowed, inputs, 777L, tasksBlocked, nowSeconds, unresolved, answers, attempts);
    }

    static void contactRuleTable() {
        String id = TasksWire.newId(), task = TasksWire.newId();
        TasksWire.InvitationItem invitation = (TasksWire.InvitationItem) TasksWire.decodeProjection(invitationFrom(id, task, "4", BOB, LATER));
        long now = 1_800_000_000L;
        Set<String> none = Collections.emptySet();
        HashMap<String, Integer> noAttempts = new HashMap<>();
        TasksInviteAnswers clean = new TasksInviteAnswers();
        check(TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, now, none, clean, noAttempts)), "a contact's pending invitation is accepted");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(false, bobIsAContact(), none, now, none, clean, noAttempts)), "not while the account is not allowed");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, null, none, now, none, clean, noAttempts)), "not before the inputs arrive");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, inputs(ids(556L), Collections.<Long>emptySet(), true, ids(555L)), none, now, none, clean, noAttempts)),
                "not from a stranger");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), new HashSet<>(Arrays.asList(BOB)), now, none, clean, noAttempts)),
                "not from a sender blocked in Tasks");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, inputs(ids(555L), Collections.<Long>emptySet(), false, ids(555L)), none, now, none, clean, noAttempts)),
                "not before the Telegram blocked list has been read");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, inputs(ids(555L), ids(555L), true, ids(555L)), none, now, none, clean, noAttempts)),
                "not from a sender blocked in Telegram");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, inputs(ids(555L), Collections.<Long>emptySet(), true, Collections.<Long>emptySet()), none, now, none, clean, noAttempts)),
                "not before the sender's own block flag has been read");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, TasksWire.epochSeconds(LATER), none, clean, noAttempts)),
                "not at or past its expiry");
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, now, new HashSet<>(Arrays.asList(id)), clean, noAttempts)),
                "not while an answer to it is unresolved");
        TasksInviteAnswers answered = new TasksInviteAnswers();
        answered.recordInvitation(id, now);
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, now, none, answered, noAttempts)), "not once answered by hand");
        TasksInviteAnswers refused = new TasksInviteAnswers();
        refused.recordRefusedTask(task, now);
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, now, none, refused, noAttempts)),
                "not a later epoch of a task declined or left by hand");
        HashMap<String, Integer> capped = new HashMap<>();
        capped.put(id, 2);
        check(TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, now, none, clean, capped)), "a third attempt is allowed");
        capped.put(id, 3);
        check(!TasksAutoAcceptPolicy.accepts(invitation, context(true, bobIsAContact(), none, now, none, clean, capped)), "never a fourth");
        TasksWire.InvitationItem own = (TasksWire.InvitationItem) TasksWire.decodeProjection(invitationFrom(TasksWire.newId(), TasksWire.newId(), "1", "777", LATER));
        check(!TasksAutoAcceptPolicy.accepts(own, context(true, inputs(ids(777L), Collections.<Long>emptySet(), true, ids(777L)), none, now, none, clean, noAttempts)),
                "never from the account itself");
    }

    // ---- pure: the manual answers ----

    static void manualAnswersRecord() {
        final String[] stored = {null};
        TasksInviteAnswers.Store store = new TasksInviteAnswers.Store() {
            @Override public String load() { return stored[0]; }
            @Override public void save(String json) { stored[0] = json; }
        };
        long now = 1_800_000_000L;
        String invitation = TasksWire.newId(), task = TasksWire.newId(), left = TasksWire.newId();
        check(TasksInviteAnswers.load(store, now).isEmpty() && stored[0] == null, "nothing recorded yet");
        TasksInviteAnswers.recordInvitation(store, invitation, null, now);
        check(TasksInviteAnswers.load(store, now).answered(invitation) && !TasksInviteAnswers.load(store, now).refused(task), "a manual accept records the invitation only");
        TasksInviteAnswers.recordInvitation(store, invitation, task, now);
        check(TasksInviteAnswers.load(store, now).refused(task), "a manual decline records its task too");
        TasksInviteAnswers.recordRefusedTask(store, left, null, now);
        check(TasksInviteAnswers.load(store, now).refused(left), "a manual leave records the task");
        check(stored[0].startsWith("{\"i\":{\"") && stored[0].contains("\"t\":{\""), "the JSON shape {i:{…},t:{…}}");
        TasksInviteAnswers later = TasksInviteAnswers.load(store, now + TasksInviteAnswers.INVITATION_RETENTION_SECONDS);
        check(!later.answered(invitation) && later.refused(task) && later.refused(left), "invitation ids go after 8 days; refused tasks stay");
        TasksInviteAnswers many = new TasksInviteAnswers();
        for (int i = 0; i < TasksInviteAnswers.MAXIMUM_REFUSED_TASKS + 20; i++) many.recordRefusedTask(TasksWire.newId(), now + i);
        String newest = TasksWire.newId();
        many.recordRefusedTask(newest, now + 10_000);
        many.prune(now + 10_000);
        check(many.refusedTaskCount() == TasksInviteAnswers.MAXIMUM_REFUSED_TASKS && many.refused(newest), "refused tasks are capped at the newest 500");
        check(TasksInviteAnswers.decode("not json").isEmpty() && TasksInviteAnswers.decode("{\"i\":{\"x\":1},\"t\":[]}").isEmpty()
                && TasksInviteAnswers.decode(null).isEmpty(), "unreadable or foreign input decodes as empty");
        TasksInviteAnswers round = TasksInviteAnswers.decode(TasksInviteAnswers.load(store, now).encode());
        check(round.answered(invitation) && round.refused(task) && round.refused(left) && round.invitationCount() == 1, "encode and decode agree");
        stored[0] = null;
        TasksInviteAnswers.recordInvitation(store, invitation, null, now - TasksInviteAnswers.INVITATION_RETENTION_SECONDS);
        check(stored[0] != null, "written");
        TasksInviteAnswers.recordRefusedTask(TasksInviteAnswers.NO_STORE, task, invitation, now);
        check(TasksInviteAnswers.load(TasksInviteAnswers.NO_STORE, now).isEmpty(), "the no-op store keeps nothing");
    }

    // ---- pure: the owner's state word ----

    static TasksWire.Assignment epoch(String state, String expiresAt) {
        return new TasksWire.Assignment(TasksWire.newId(), BOB, TasksWire.AssignmentState.fromWire(state), expiresAt);
    }

    static TasksAssigneeDisplay.State word(TasksWire.Assignment assignment, boolean queued, boolean acked, boolean deferred, boolean completed, boolean legacy) {
        return TasksAssigneeDisplay.resolve(new TasksAssigneeDisplay.Inputs(assignment, queued, acked, deferred, completed, legacy, 1_800_000_000L));
    }

    static void displayTable() {
        check(word(null, false, false, false, false, false) == null, "no assignee");
        check(word(epoch("accepted", LATER), true, false, true, false, true) == TasksAssigneeDisplay.State.SENDING, "a request on its way wins");
        check(word(epoch("declined", LATER), false, true, false, false, false) == TasksAssigneeDisplay.State.SENT, "an acknowledged request reads Sent before the pull");
        check(word(epoch("pending", LATER), false, false, true, false, false) == TasksAssigneeDisplay.State.QUEUED, "a deferral reads Queued whatever the shadow says");
        check(word(epoch("pending", LATER), false, false, false, false, false) == TasksAssigneeDisplay.State.SENT, "pending");
        check(word(epoch("pending", EARLIER), false, false, false, false, false) == TasksAssigneeDisplay.State.EXPIRED, "pending past its expiry");
        check(word(epoch("expired", EARLIER), false, false, false, false, false) == TasksAssigneeDisplay.State.EXPIRED, "expired on the server");
        check(word(epoch("accepted", EARLIER), false, false, false, false, false) == TasksAssigneeDisplay.State.ACCEPTED, "accepted never expires");
        check(word(epoch("accepted", LATER), false, false, false, true, false) == TasksAssigneeDisplay.State.COMPLETED, "accepted and completed");
        check(word(epoch("declined", LATER), false, false, false, false, false) == TasksAssigneeDisplay.State.DECLINED
                && word(epoch("left", LATER), false, false, false, false, false) == TasksAssigneeDisplay.State.LEFT
                && word(epoch("revoked", LATER), false, false, false, false, false) == TasksAssigneeDisplay.State.REMOVED, "declined, left, removed");
        check(word(null, false, false, false, false, true) == TasksAssigneeDisplay.State.NOT_SENT, "a legacy note that was never sent");
        check(word(epoch("declined", LATER), false, false, false, false, true) == TasksAssigneeDisplay.State.DECLINED, "an assignment outranks the legacy note");
        for (TasksAssigneeDisplay.State state : TasksAssigneeDisplay.State.values()) {
            boolean live = state == TasksAssigneeDisplay.State.SENT || state == TasksAssigneeDisplay.State.ACCEPTED || state == TasksAssigneeDisplay.State.COMPLETED;
            check(state.someoneLosesAccess() == live, "only a live epoch asks before it is replaced: " + state);
            check(state.rowPicks() == (state != TasksAssigneeDisplay.State.SENDING), "the row picks except while sending: " + state);
        }
        check(TasksAssigneeDisplay.State.DECLINED.offersSendAction() && TasksAssigneeDisplay.State.NOT_SENT.offersSendAction()
                && !TasksAssigneeDisplay.State.SENT.offersSendAction() && !TasksAssigneeDisplay.State.REMOVED.offersSendAction(), "Send again / Send");
        check(TasksAssigneeDisplay.State.QUEUED.footer() == TasksAssigneeDisplay.Footer.QUEUED && TasksAssigneeDisplay.State.SENT.footer() == TasksAssigneeDisplay.Footer.PENDING
                && TasksAssigneeDisplay.State.EXPIRED.footer() == TasksAssigneeDisplay.Footer.ENDED && TasksAssigneeDisplay.State.SENDING.footer() == TasksAssigneeDisplay.Footer.NONE,
                "footers");
        TasksWire.Annotation ali = new TasksWire.Annotation(BOB, "Ali");
        check("Bob".equals(TasksAssigneeDisplay.name(BOB, " Bob ", ali, "User 555")) && "Ali".equals(TasksAssigneeDisplay.name(BOB, " ", ali, "User 555"))
                && "User 556".equals(TasksAssigneeDisplay.name(CAROL, null, ali, "User 556")), "contact name, then the note of the same id, then the number");
    }

    // ---- a create with an assignee is one chain ----

    static void createWithAssigneeIsOneChain() {
        TasksCloudR4Tests.Sink sink = new TasksCloudR4Tests.Sink();
        TasksAnalyticsSink.Holder.set(sink);
        Harness h = r4();
        String task = h.engine.createTask(new TasksWire.Content(null, "Report", null, null, 0, 0, new TasksWire.Annotation(BOB, "Bob")), BOB);
        h.drain();
        check(shown(h, task) == TasksAssigneeDisplay.State.SENDING && BOB.equals(h.state().task(task).assigneeTgId()), "the row reads Sending… for Bob at once");
        check(h.state().task(task).content.annotation == null, "the create never carries an annotation, whatever the draft held");
        check(h.ledger.entries().size() == 2 && h.ledger.entries().get(0).sealed && !h.ledger.entries().get(1).sealed,
                "task.create sealed, invitation.request unsealed behind it");
        Request create = batchOf(h, 1, "only the create can go");
        check(batchBody(create).contains("\"kind\":\"task.create\"") && batchBody(create).contains("\"annotation\":null")
                && !batchBody(create).contains("invitation.request"), "the create goes alone, annotation null");
        answerAll(create, "1");
        h.drain();
        Request invite = batchOf(h, 1, "then the request");
        check(batchBody(invite).contains("\"kind\":\"invitation.request\"") && batchBody(invite).contains("\"base_revision\":\"1\"")
                && batchBody(invite).contains("\"recipient_tg_id\":\"555\""), "sealed on the create's revision, to Bob");
        String invitationId = ((TasksWire.Operation.InvitationRequest) invite.batch.items.get(0).mutation.operation).invitationId;
        answerAll(invite, "2");
        h.drain();
        check(shown(h, task) == TasksAssigneeDisplay.State.SENT && h.state().task(task).requestAcknowledged, "acknowledged: Sent before the pull");
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "2", "Report", null, assignment(invitationId, BOB, "pending", LATER), false)));
        h.drain();
        quiet(h);
        check(shown(h, task) == TasksAssigneeDisplay.State.SENT && h.state().task(task).requestRecipientTgId == null
                && h.ledger.entries().isEmpty(), "the pull shows the pending epoch; the outbox is empty");
        String day = TasksInvitationPacer.dayKey(h.now);
        check("1".equals(meta(h, "invite_day." + day)) && "1".equals(meta(h, "invite_day." + day + "." + BOB))
                && "1".equals(meta(h, "invite_minute." + TasksInvitationPacer.minuteKey(h.now))), "the pick charged the pacer once");
        check(sink.count("shared:invite_sent") == 1 && sink.count("taskCreated:true:false") == 1, "analytics: one create, one invite_sent");
        check(h.state().pendingAssignments(h.now / 1000) == 1, "one pending assignment");
        // The accepted assignee completes it: the owner reads Accepted, then Completed.
        changes4pull(h, "c3", upsert(6, ownerTask(task, "3", "Report", null, assignment(invitationId, BOB, "accepted", LATER), false)));
        check(shown(h, task) == TasksAssigneeDisplay.State.ACCEPTED, "accepted");
        changes4pull(h, "c4", upsert(7, ownerTask(task, "4", "Report", null, assignment(invitationId, BOB, "accepted", LATER), true)));
        check(shown(h, task) == TasksAssigneeDisplay.State.COMPLETED && h.state().pendingAssignments(h.now / 1000) == 0, "completed");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    /** An explicit refresh's pull, answered with these changes. */
    static void changes4pull(Harness h, String next, String... changes) {
        quiet(h);
        h.engine.refresh();
        h.drain();
        changes4(h.remote.take("changes"), next, changes);
        h.drain();
        quiet(h);
    }

    // ---- pick-time refusals queue nothing ----

    static void refusalsQueueNothing() {
        Harness h = r4();
        TasksCloudError self = expect(TasksCloudError.class, () -> h.engine.createTask(content(null, "Mine"), "777"), "self");
        check(self.code == TasksCloudError.Code.SELF_ASSIGNMENT && h.ledger.entries().isEmpty(), "self: refused, the create included");
        expect(TasksWire.Invalid.class, () -> h.engine.checkAssignment("0"), "not a Telegram user id");
        check(expect(TasksCloudError.class, () -> h.engine.checkAssignment("777"), "self at pick time").code == TasksCloudError.Code.SELF_ASSIGNMENT, "self at pick time");
        h.engine.checkAssignment(BOB);
        check(h.ledger.entries().isEmpty() && meta(h, "invite_day." + TasksInvitationPacer.dayKey(h.now)) == null, "a check queues and charges nothing");
        h.engine.stop();
        for (int i = 0; i < TasksInvitationPacer.RECIPIENT_PER_DAY; i++) h.engine.createTask(content(null, "T" + i), BOB);
        check(h.ledger.entries().size() == 10, "five creates with their requests");
        TasksCloudError limit = expect(TasksCloudError.class, () -> h.engine.createTask(content(null, "Sixth"), BOB), "the 6th to Bob today");
        check(limit.code == TasksCloudError.Code.ASSIGNMENT_LIMIT && "PER_PERSON".equals(limit.field) && h.ledger.entries().size() == 10,
                "the 6th to one person: refused at pick time, nothing queued");
        check(expect(TasksCloudError.class, () -> h.engine.checkAssignment(BOB), "check").code == TasksCloudError.Code.ASSIGNMENT_LIMIT, "the check says the same");
        for (int i = 0; i < 5; i++) h.engine.createTask(content(null, "C" + i), CAROL);
        TasksCloudError minute = expect(TasksCloudError.class, () -> h.engine.createTask(content(null, "Eleventh"), "557"), "the 11th this minute");
        check("PER_MINUTE".equals(minute.field) && h.ledger.entries().size() == 20, "the 11th pick of the minute");
        h.now += 60_000;
        String next = h.engine.createTask(content(null, "Next minute"), "557");
        check(next != null && h.ledger.entries().size() == 22, "a minute later the pick goes");
        // Tomorrow the per-person counter is fresh; the old counters go two UTC days later, at a pick.
        h.now += 86_400_000L;
        h.engine.createTask(content(null, "Tomorrow"), BOB);
        h.now += 86_400_000L;
        String oldDay = TasksInvitationPacer.dayKey(h.now - 2 * 86_400_000L);
        check(meta(h, "invite_day." + oldDay) != null, "the old counter is still there before the next pick");
        h.engine.createTask(content(null, "Two days later"), BOB);
        check(meta(h, "invite_day." + oldDay) == null && meta(h, "invite_day." + oldDay + "." + BOB) == null, "counters older than two UTC days are deleted at a pick");
        // A completed task is refused.
        String done = TasksWire.newId();
        Harness c = r4(ownerTask(done, "2", "Done", null, null, true));
        TasksCloudError completed = expect(TasksCloudError.class, () -> c.engine.assign(done, BOB), "a completed task");
        check(completed.code == TasksCloudError.Code.INVALID && "is_completed".equals(completed.field) && c.ledger.entries().isEmpty(), "a completed task is not assigned");
        c.sql.close();
        h.sql.close();
    }

    // ---- Send again, and removing exactly the epoch shown ----

    static void resendAndExactRemoval() {
        String task = TasksWire.newId(), old = TasksWire.newId();
        Harness h = r4(ownerTask(task, "3", "Review", null, assignment(old, BOB, "declined", LATER), false));
        check(shown(h, task) == TasksAssigneeDisplay.State.DECLINED, "the owner reads Declined");
        check(expect(TasksCloudError.class, () -> h.engine.revoke(task, old), "nothing live to remove").code == TasksCloudError.Code.NOT_FOUND, "a declined epoch is not removed");
        String epoch = h.engine.resendAssignment(task);
        h.drain();
        check(!epoch.equals(old) && shown(h, task) == TasksAssigneeDisplay.State.SENDING, "Send again: a new epoch on its way");
        Request request = batchOf(h, 1, "the request");
        check(batchBody(request).contains("\"recipient_tg_id\":\"555\"") && batchBody(request).contains("\"base_revision\":\"3\""), "to the same recipient, on the shown revision");
        answerAll(request, "4");
        h.drain();
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "4", "Review", null, assignment(epoch, BOB, "pending", LATER), false)));
        h.drain();
        quiet(h);
        check(expect(TasksCloudError.class, () -> h.engine.resendAssignment(task), "a live epoch").code == TasksCloudError.Code.NOT_ALLOWED, "a live epoch is not re-sent");
        check(expect(TasksCloudError.class, () -> h.engine.revoke(task, old), "another epoch").code == TasksCloudError.Code.CHANGED_ELSEWHERE,
                "removing an epoch the screen did not show is refused");
        h.engine.revoke(task, epoch);
        h.drain();
        Request revoke = batchOf(h, 1, "the revoke");
        check(batchBody(revoke).contains("\"kind\":\"invitation.revoke\"") && batchBody(revoke).contains(epoch), "exactly the epoch shown");
        answerAll(revoke, "5");
        h.drain();
        changes4(h.remote.take("changes"), "c3", upsert(6, ownerTask(task, "5", "Review", null, assignment(epoch, BOB, "revoked", LATER), false)));
        h.drain();
        quiet(h);
        check(shown(h, task) == TasksAssigneeDisplay.State.REMOVED, "Removed until the server detaches it");
        // A pending epoch past its expiry reads as ended and may be sent again.
        String stale = TasksWire.newId(), expired = TasksWire.newId();
        Harness e = r4(ownerTask(stale, "2", "Stale", null, assignment(expired, BOB, "pending", EARLIER), false));
        check(shown(e, stale) == TasksAssigneeDisplay.State.EXPIRED && e.state().pendingAssignments(e.now / 1000) == 0, "pending past its expiry: not accepted in time");
        check(e.engine.resendAssignment(stale) != null, "and it may be sent again");
        e.sql.close();
        h.sql.close();
    }

    // ---- a revision_changed on the request reseals it ----

    static void requestConflictReseals() {
        String task = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Plan", null, null, false));
        String first = h.engine.assign(task, BOB);
        h.drain();
        Request request = batchOf(h, 1, "the request");
        answer(request, null, err(request.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
        h.drain();
        check(h.state().pending.size() == 1 && h.state().pending.get(0).state == TasksCloudState.PendingState.WAITING
                && shown(h, task) == TasksAssigneeDisplay.State.SENDING, "waiting for the pull, still Sending…");
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "7", "Plan (edited elsewhere)", null, null, false)));
        h.drain();
        Request resealed = batchOf(h, 1, "the resealed request");
        String second = ((TasksWire.Operation.InvitationRequest) resealed.batch.items.get(0).mutation.operation).invitationId;
        check(batchBody(resealed).contains("\"base_revision\":\"7\"") && !second.equals(first)
                && !resealed.batch.items.get(0).mutation.mutationId.equals(request.batch.items.get(0).mutation.mutationId),
                "resealed on the fresh revision as a new epoch under a new identity");
        answerAll(resealed, "8");
        h.drain();
        quiet(h);
        // The task was completed elsewhere meanwhile: the user's pick stays explicit.
        String closed = TasksWire.newId();
        Harness c = r4(ownerTask(closed, "2", "Closed", null, null, false));
        c.engine.assign(closed, BOB);
        c.drain();
        Request refused = batchOf(c, 1, "the request");
        answer(refused, null, err(refused.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
        c.drain();
        changes4(c.remote.take("changes"), "c2", upsert(5, ownerTask(closed, "3", "Closed", null, null, true)));
        c.drain();
        quiet(c);
        check(c.state().pending.size() == 1 && c.state().pending.get(0).state == TasksCloudState.PendingState.CONFLICT, "a completed task: the pick is the user's to decide");
        c.sql.close();
        h.sql.close();
    }

    // ---- a long rate limit defers the assignment out of the outbox ----

    static void longRateLimitDefers() {
        TasksCloudR4Tests.Sink sink = new TasksCloudR4Tests.Sink();
        TasksAnalyticsSink.Holder.set(sink);
        String task = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Budget", null, null, false));
        h.engine.stop();
        h.engine.assign(task, BOB);
        String unrelated = h.engine.createTask(content(null, "Unrelated"));
        h.engine.start();
        h.drain();
        Request request = batchOf(h, 2, "the request and an unrelated create");
        String sentId = request.batch.items.get(0).mutation.mutationId;
        // The server stops at the throttled request, skips the create and repeats the Retry-After for the batch.
        throttle(request, 0, 1800, null);
        h.drain();
        Request skipped = h.remote.take("batch");
        check(skipped.batch.items.size() == 1 && batchBody(skipped).contains("\"kind\":\"task.create\"") && batchBody(skipped).contains(unrelated),
                "the deferral took the wait with it: the skipped create goes at once, nothing is held");
        answerAll(skipped, "1");
        h.drain();
        quiet(h);
        check(h.ledger.entries().size() == 1 && h.state().pending.isEmpty(), "the request left the outbox: no Unsent Changes row (only the create's receipt remains)");
        String deferral = meta(h, "assign_deferred." + task);
        check(("{\"after\":" + (h.now / 1000 + 1800) + ",\"to\":\"555\"}").equals(deferral), "assign_deferred.<taskId> = {after, to}: " + deferral);
        check(shown(h, task) == TasksAssigneeDisplay.State.QUEUED && BOB.equals(h.state().task(task).deferredRecipientTgId)
                && BOB.equals(h.state().task(task).assigneeTgId()), "the row reads Queued for Bob");
        check(h.state().unsentUserChanges == 1 && h.engine.unsentCounts()[0] == 1, "a deferral is an unsent change in both counts");
        check(h.state().status == TasksCloudState.Status.READY, "no account hold");
        // The task's later edits are not blocked behind it.
        h.engine.updateTask(task, content(null, "Budget 2027"));
        h.drain();
        Request edit = batchOf(h, 1, "the edit goes at once");
        check(batchBody(edit).contains("\"kind\":\"task.patch\""), "a field patch");
        answerAll(edit, "3");
        h.drain();
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "3", "Budget 2027", null, null, false)));
        h.drain();
        quiet(h);
        check(shown(h, task) == TasksAssigneeDisplay.State.QUEUED, "still queued");
        String day = TasksInvitationPacer.dayKey(h.now);
        check("1".equals(meta(h, "invite_day." + day)), "charged once, at the pick");
        // Its time comes: the same pick is retried by itself, not charged again.
        h.advance(1_800_000L);
        Request resend = batchOf(h, 1, "the automatic re-send");
        TasksWire.Mutation again = resend.batch.items.get(0).mutation;
        check(batchBody(resend).contains("\"kind\":\"invitation.request\"") && batchBody(resend).contains("\"recipient_tg_id\":\"555\"")
                && batchBody(resend).contains("\"base_revision\":\"3\"") && !again.mutationId.equals(sentId), "a fresh request to Bob on the current revision");
        check(meta(h, "assign_deferred." + task) == null && again.mutationId.equals(meta(h, "assign_resend." + task)), "the deferral became the re-send marker");
        check(shown(h, task) == TasksAssigneeDisplay.State.SENDING, "Sending… again");
        // A second long 429 re-defers it.
        throttle(resend, 0, 1800, null);
        h.drain();
        quiet(h);
        check(h.state().pending.isEmpty() && meta(h, "assign_resend." + task) == null && meta(h, "assign_deferred." + task) != null
                && shown(h, task) == TasksAssigneeDisplay.State.QUEUED, "re-deferred, the marker gone");
        h.advance(1_800_000L);
        Request third = batchOf(h, 1, "the next automatic re-send");
        String epoch = ((TasksWire.Operation.InvitationRequest) third.batch.items.get(0).mutation.operation).invitationId;
        answerAll(third, "4");
        h.drain();
        check(meta(h, "assign_resend." + task) == null && shown(h, task) == TasksAssigneeDisplay.State.SENT, "acknowledged: the marker goes, Sent");
        changes4(h.remote.take("changes"), "c3", upsert(6, ownerTask(task, "4", "Budget 2027", null, assignment(epoch, BOB, "pending", LATER), false)));
        h.drain();
        quiet(h);
        String today = TasksInvitationPacer.dayKey(h.now);
        check(meta(h, "invite_day." + today) == null || "1".equals(meta(h, "invite_day." + today)), "the automatic re-sends were never charged");
        check(sink.count("shared:invite_sent") == 3, "invite_sent for the pick and for each re-send");
        // A Retry-After at the transport's ceiling is a floor: the assignment waits for the next UTC day.
        long morning = 1_800_000_000_000L; // 2027-01-15T08:00:00Z
        long nextDay = (morning / 86_400_000L + 1) * 86_400_000L;
        check(TasksCloudEngine.deferralDue(morning, 3599) == morning + 3_599_000L, "below the ceiling: exactly the Retry-After");
        check(TasksCloudEngine.deferralDue(morning, 3600) == nextDay + TasksCloudEngine.DEFERRAL_DAY_MARGIN_MS, "at the ceiling: the next UTC day");
        check(TasksCloudEngine.deferralDue(nextDay - 60_000L, 3600) == nextDay - 60_000L + 3_600_000L, "never sooner than the Retry-After itself");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    static void deferralEdges() {
        // A short 429 keeps the entry in the outbox (the account's ordinary backoff).
        String task = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Short", null, null, false));
        h.engine.assign(task, BOB);
        h.drain();
        Request request = batchOf(h, 1, "the request");
        throttle(request, 0, 60, null);
        h.drain();
        quiet(h);
        check(h.ledger.entries().size() == 1 && meta(h, "assign_deferred." + task) == null
                && h.state().pending.get(0).state == TasksCloudState.PendingState.BACKOFF && shown(h, task) == TasksAssigneeDisplay.State.SENDING,
                "a minute's rate limit is not a deferral");
        h.advance(60_000L);
        answerAll(batchOf(h, 1, "the same entry again"), "3");
        h.drain();
        quiet(h);
        h.sql.close();

        // A newer pick cancels the deferral; a deferral whose task was assigned elsewhere meanwhile ends quietly.
        String a = TasksWire.newId(), b = TasksWire.newId(), c = TasksWire.newId(), d = TasksWire.newId();
        Harness n = r4(ownerTask(a, "2", "A", null, null, false), ownerTask(b, "2", "B", null, null, false),
                ownerTask(c, "2", "C", null, null, false), ownerTask(d, "2", "D", null, null, false));
        n.engine.stop();
        n.engine.assign(a, BOB);
        n.engine.assign(b, BOB);
        n.engine.assign(c, BOB);
        n.engine.assign(d, BOB);
        n.engine.start();
        n.drain();
        // The server stops at each throttled request and skips the rest: one deferral a batch, and no hold in between.
        for (int left = 4; left >= 1; left--) {
            Request batch = n.remote.take("batch");
            check(batch.batch.items.size() == left, "the requests still queued go at once: " + left);
            throttle(batch, 0, 1800, null);
            n.drain();
        }
        quiet(n);
        check(n.ledger.entries().isEmpty() && n.state().unsentUserChanges == 4, "four deferrals");
        n.engine.assign(a, CAROL);
        n.drain();
        check(meta(n, "assign_deferred." + a) == null && shown(n, a) == TasksAssigneeDisplay.State.SENDING && CAROL.equals(n.state().task(a).assigneeTgId()),
                "a newer pick cancels the deferral: the task goes to Carol");
        answerAll(batchOf(n, 1, "Carol's request"), "3");
        n.drain();
        String other = TasksWire.newId();
        changes4(n.remote.take("changes"), "c2",
                upsert(5, ownerTask(a, "3", "A", null, assignment(TasksWire.newId(), CAROL, "pending", LATER), false)),
                upsert(6, ownerTask(b, "3", "B", null, assignment(other, CAROL, "accepted", LATER), false)),
                upsert(7, ownerTask(c, "3", "C", null, null, true)),
                remove(8, "task", d));
        n.drain();
        quiet(n);
        n.advance(1_800_000L);
        none(n, "nothing is re-sent: B was assigned elsewhere, C completed, D deleted");
        check(meta(n, "assign_deferred." + b) == null && meta(n, "assign_deferred." + c) == null && meta(n, "assign_deferred." + d) == null
                && n.state().unsentUserChanges == 0 && n.state().notices.isEmpty(), "the due deferrals ended quietly");
        check(shown(n, b) == TasksAssigneeDisplay.State.ACCEPTED, "the row shows whatever the projection says");
        n.sql.close();

        // A definite refusal of the automatic re-send ends it quietly; an undecodable row still counts as unsent.
        String gone = TasksWire.newId();
        Harness g = r4(ownerTask(gone, "2", "Gone", null, null, false));
        g.engine.assign(gone, BOB);
        g.drain();
        Request first = batchOf(g, 1, "the request");
        throttle(first, 0, 600, null);
        g.drain();
        quiet(g);
        g.advance(600_000L);
        Request resend = batchOf(g, 1, "the automatic re-send");
        answer(resend, null, err(resend.batch.items.get(0).mutation, 404, "tasks_not_found", ""));
        g.drain();
        quiet(g);
        check(g.ledger.entries().isEmpty() && g.state().pending.isEmpty() && g.state().notices.isEmpty()
                && meta(g, "assign_resend." + gone) == null && meta(g, "assign_deferred." + gone) == null,
                "a 404 on the automatic re-send: no row, no notice, no marker");
        g.ledger.writeMeta("assign_deferred." + gone, "garbage");
        check(g.state().unsentUserChanges == 1 && g.ledger.deferrals().isEmpty() && g.state().task(gone).deferredRecipientTgId == null,
                "an unreadable deferral row is counted, never pretended away");
        g.sql.close();
    }

    // ---- the task's later entries survive a deferral ----

    static void deferralDoesNotOrphanSuccessors() {
        String task = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Chain", null, null, false));
        h.engine.stop();
        h.engine.assign(task, BOB);
        String complete = h.engine.setCompleted(task, true);
        h.engine.start();
        h.drain();
        Request request = batchOf(h, 1, "only the request is sendable (the completion waits for its receipt)");
        throttle(request, 0, 1800, null);
        h.drain();
        Request completion = batchOf(h, 1, "the completion goes on the chain's known revision");
        check(completion.batch.items.get(0).mutation.mutationId.equals(complete) && batchBody(completion).contains("\"base_revision\":\"2\""),
                "sealed on the shadow revision instead of the lost receipt");
        answerAll(completion, "3");
        h.drain();
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "3", "Chain", null, null, true)));
        h.drain();
        quiet(h);
        check(h.state().notices.isEmpty() && h.ledger.entries().isEmpty(), "no dependency notice, nothing stuck");
        h.advance(1_800_000L);
        none(h, "a completed task's deferral ends quietly");
        check(meta(h, "assign_deferred." + task) == null, "gone");
        h.sql.close();
    }

    // ---- process death: the markers are durable, the inputs are not ----

    static void restartKeepsDeferralsAndForgetsInputs() {
        String task = TasksWire.newId(), inv = TasksWire.newId(), invTask = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Survives", null, null, false), invitationFrom(inv, invTask, "4", BOB, LATER));
        h.engine.assign(task, CAROL);
        h.drain();
        Request request = batchOf(h, 1, "the request");
        throttle(request, 0, 900, null);
        h.drain();
        quiet(h);
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        Request accept = batchOf(h, 1, "the automatic accept, in flight when the process dies");
        String acceptId = accept.batch.items.get(0).mutation.mutationId;
        long due = h.now + 900_000L;

        Harness r = h.restart();
        r.remote.batchRoute = true;
        r.now = h.now;
        r.engine.start();
        r.drain();
        check(shown(r, task) == TasksAssigneeDisplay.State.QUEUED && r.state().unsentUserChanges == 2, "after the restart: still queued, still counted");
        check(r.state().provisional(inv) != null, "the accept in flight keeps its provisional row");
        // The interrupted accept is sent again with the same identity (its outcome was unknown); no inputs are needed for that.
        Request again = r.remote.take("batch");
        check(again.batch.items.size() == 1, "the same accept again, before anything else");
        check(again.batch.items.get(0).mutation.mutationId.equals(acceptId) && (acceptId + ":1").equals(meta(r, "auto_accept." + inv)),
                "same mutation id, still the first attempt");
        answerAll(again, "5");
        r.drain();
        changes4(r.remote.take("changes"), "r2", remove(5, "invitation", inv), upsert(6, assigneeTask(invTask, "5", BOB, inv)));
        r.drain();
        quiet(r);
        // A new invitation after the restart waits: the inputs lived in memory only.
        String later = TasksWire.newId();
        r.engine.refresh();
        r.drain();
        changes4(r.remote.take("changes"), "r3", upsert(7, invitationFrom(later, TasksWire.newId(), "2", BOB, LATER)));
        r.drain();
        none(r, "no inputs after a restart: nothing is accepted until the app hands them in again");
        check(r.state().invitation(later) != null, "it waits in Invitations");
        // The deferral comes due in the new process.
        r.advance(due - r.now);
        Request resend = batchOf(r, 1, "the deferred assignment is re-sent by the new process");
        check(batchBody(resend).contains("\"recipient_tg_id\":\"556\"") && meta(r, "assign_deferred." + task) == null, "to Carol; the deferral is gone");
        answerAll(resend, "3");
        r.drain();
        quiet(r);
        r.sql.close();
    }

    // ---- an assignment moves its list's members: the list's own operations wait for it ----

    static void assignmentHoldsItsListsOperations() {
        String l = TasksWire.newId(), task = TasksWire.newId();
        Harness h = r4(list4(l, "1", "4", "Work"), task4(task, "2", l, "In Work", null, 0));
        h.engine.stop();
        String epoch = h.engine.assign(task, BOB);
        String delete = h.engine.deleteList(l);
        h.engine.start();
        h.drain();
        check(l.equals(h.ledger.entries().get(0).listKey == null ? null : h.ledger.entries().get(0).listKey.substring(5)),
                "the request names its task's list as a dependency");
        check(entry(h, delete).waitsMembers && !entry(h, delete).sealed, "the list's delete is stored unsealed behind the member task's assignment");
        Request request = batchOf(h, 1, "only the request goes");
        check(batchBody(request).contains("\"kind\":\"invitation.request\""), "the assignment first");
        answerAll(request, "3");
        h.drain();
        Request settle = h.remote.take("changes");
        check(h.pending(delete).state == TasksCloudState.PendingState.WAITING, "acknowledged but not pulled: the delete still waits");
        // The server moved the list's members revision with the assignment.
        changes4(settle, "c2", upsert(5, "{\"kind\":\"task\",\"id\":\"" + task + "\",\"revision\":\"3\",\"owner_tg_id\":\"777\",\"viewer_role\":\"owner\","
                + "\"content\":{\"list_id\":\"" + l + "\",\"title\":\"In Work\",\"description\":null,\"due_at\":null,\"priority\":0,\"sort_order\":0,\"annotation\":null},"
                + "\"is_completed\":false,\"completed_at\":null,\"created_at\":\"" + T0 + "\",\"updated_at\":\"" + T0 + "\",\"assignment\":"
                + assignment(epoch, BOB, "pending", LATER) + "}"), upsert(6, list4(l, "1", "5", "Work")));
        h.drain();
        Request deleteBatch = batchOf(h, 1, "then the delete");
        TasksWire.Operation.ListDelete sealed = (TasksWire.Operation.ListDelete) deleteBatch.batch.items.get(0).mutation.operation;
        check("1".equals(sealed.baseRevision) && "5".equals(sealed.baseMembersRevision), "sealed on the members revision the assignment produced: no 409");
        answerAll(deleteBatch, "2");
        h.drain();
        quiet(h);
        h.sql.close();
    }

    // ---- the legacy annotation: never written, cleared after a send ----

    static void legacyAnnotation() {
        String task = TasksWire.newId(), plain = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Old note", note(BOB, "Bob"), null, false), ownerTask(plain, "2", "Plain", null, null, false));
        check(shown(h, task) == TasksAssigneeDisplay.State.NOT_SENT && BOB.equals(h.state().task(task).assigneeTgId()), "a note-only task: Not sent yet");
        check(shown(h, plain) == null && h.state().task(plain).assigneeTgId() == null, "no assignee");
        // An edit keeps the note whatever the draft carries.
        h.engine.updateTask(task, content(null, "Old note, edited"));
        h.drain();
        Request edit = batchOf(h, 1, "the edit");
        check(batchBody(edit).contains("\"title\"") && !batchBody(edit).contains("annotation"), "a Save never touches the annotation");
        answerAll(edit, "3");
        h.drain();
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "3", "Old note, edited", note(BOB, "Bob"), null, false)));
        h.drain();
        quiet(h);
        check(h.engine.updateTask(plain, new TasksWire.Content(null, "Plain", null, null, 0, 0, new TasksWire.Annotation(BOB, "Bob"))) == null,
                "a draft that only adds an annotation changes nothing");
        // Send: the request, then the chained clear expecting the note shown.
        h.engine.assign(task, BOB);
        h.drain();
        Request request = batchOf(h, 1, "the request");
        String epoch = ((TasksWire.Operation.InvitationRequest) request.batch.items.get(0).mutation.operation).invitationId;
        answerAll(request, "4");
        h.drain();
        Request clear = batchOf(h, 1, "the chained clear");
        check(batchBody(clear).contains("\"kind\":\"task.patch\"") && batchBody(clear).contains("\"set\":{\"annotation\":null}")
                && batchBody(clear).contains("\"expect\":{\"annotation\":{"), "task.patch set.annotation=null expecting the note");
        check(TasksCloudLedger.isAnnotationClear(clear.batch.items.get(0).mutation.operation), "recognised as the clear");
        answerAll(clear, "5");
        h.drain();
        changes4(h.remote.take("changes"), "c3", upsert(6, ownerTask(task, "5", "Old note, edited", null, assignment(epoch, BOB, "pending", LATER), false)));
        h.drain();
        quiet(h);
        check(shown(h, task) == TasksAssigneeDisplay.State.SENT && h.state().task(task).content.annotation == null && h.ledger.entries().isEmpty(),
                "sent, and no longer a candidate on any device");
        h.sql.close();

        // The note changed elsewhere meanwhile: the clear is dropped quietly, the task stays a candidate on that note.
        String raced = TasksWire.newId();
        Harness r = r4(ownerTask(raced, "2", "Raced", note(BOB, "Bob"), null, false));
        r.engine.assign(raced, BOB);
        r.drain();
        answerAll(batchOf(r, 1, "the request"), "3");
        r.drain();
        Request refusedClear = batchOf(r, 1, "the clear");
        answer(refusedClear, null, err(refusedClear.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"annotation\"]"));
        r.drain();
        quiet(r);
        check(r.ledger.entries().size() == 1 && TasksCloudSchema.ACKED.equals(r.ledger.entries().get(0).state) && r.state().pending.isEmpty()
                && r.state().notices.isEmpty(), "field_changed on the clear: dropped quietly (only the acknowledged request remains)");
        r.sql.close();

        // Remove assignee on a note-only task: the one explicit annotation write.
        String removed = TasksWire.newId();
        Harness m = r4(ownerTask(removed, "2", "Remove", note(BOB, "Bob"), null, false));
        check(m.engine.removeLegacyAnnotation(removed) != null, "queued");
        m.drain();
        Request removal = batchOf(m, 1, "the clear");
        check(batchBody(removal).contains("\"set\":{\"annotation\":null}"), "the owner-field clear");
        answerAll(removal, "3");
        m.drain();
        changes4(m.remote.take("changes"), "c2", upsert(5, ownerTask(removed, "3", "Remove", null, null, false)));
        m.drain();
        quiet(m);
        check(shown(m, removed) == null && m.engine.removeLegacyAnnotation(removed) == null, "no assignee; a task without a note is left alone");
        m.sql.close();

        // A delete queued behind the request: no clear is chained (it would meet a 404 for an edit nobody made).
        String deleted = TasksWire.newId();
        Harness x = r4(ownerTask(deleted, "2", "Delete", note(BOB, "Bob"), null, false));
        x.engine.stop();
        x.engine.assign(deleted, BOB);
        String held = x.engine.holdDelete(deleted);
        x.engine.promote(held);
        x.engine.start();
        x.drain();
        answerAll(batchOf(x, 1, "the request"), "3");
        x.drain();
        Request deletion = batchOf(x, 1, "the delete, and no clear before it");
        check(batchBody(deletion).contains("\"kind\":\"task.delete\""), "the delete follows the request directly");
        answerAll(deletion, "4");
        x.drain();
        quiet(x);
        x.sql.close();
    }

    // ---- the contact rule: a contact's invitation is accepted by the device ----

    static void automaticAccept() {
        TasksCloudR4Tests.Sink sink = new TasksCloudR4Tests.Sink();
        TasksAnalyticsSink.Holder.set(sink);
        String inv = TasksWire.newId(), task = TasksWire.newId(), stranger = TasksWire.newId(), strangerTask = TasksWire.newId();
        Harness h = r4(invitationFrom(inv, task, "4", BOB, LATER), invitationFrom(stranger, strangerTask, "9", CAROL, LATER));
        check(h.state().invitations.size() == 2 && h.state().provisional.isEmpty(), "two invitations wait; no inputs yet, nothing accepted");
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        check(h.state().invitations.size() == 1 && h.state().invitation(stranger) != null, "the stranger's invitation waits in Invitations");
        check(h.state().provisional.size() == 1 && h.state().provisional(inv) != null && h.state().provisional.get(0).taskId.equals(task),
                "Bob's is a provisional row built from the preview");
        Request accept = batchOf(h, 1, "the automatic accept");
        TasksWire.Mutation sent = accept.batch.items.get(0).mutation;
        check(batchBody(accept).contains("\"kind\":\"invitation.respond\"") && batchBody(accept).contains("\"decision\":\"accept\"")
                && batchBody(accept).contains("\"base_revision\":\"4\""), "the ordinary accept on the previewed revision");
        check((sent.mutationId + ":1").equals(meta(h, "auto_accept." + inv)), "auto_accept.<invitationId> = <mutationId>:1");
        answerAll(accept, "5");
        h.drain();
        check(h.state().provisional.size() == 1, "acknowledged, not pulled yet: still provisional, never accepted twice");
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        check(h.ledger.entries().size() == 1, "a rerun in that gap queues nothing");
        changes4(h.remote.take("changes"), "c2", remove(5, "invitation", inv), upsert(6, assigneeTask(task, "5", BOB, inv)));
        h.drain();
        quiet(h);
        check(h.state().provisional.isEmpty() && h.state().task(task) != null && !h.state().task(task).owner, "the task arrived as an assignee row");
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        check(meta(h, "auto_accept." + inv) == null && h.ledger.entries().isEmpty(), "the bookkeeping is swept once the invitation left the snapshot");
        check(sink.count("shared:invite_accepted") == 1, "the same invite_accepted action as a manual accept, once");

        // Carol becomes a contact later: her still-pending invitation is accepted by the run after the change.
        h.engine.runAutoAccept(inputs(ids(555L, 556L), Collections.<Long>emptySet(), true, ids(555L, 556L)));
        h.drain();
        check(h.state().invitations.isEmpty() && h.state().provisional(stranger) != null, "accepted once the sender is a contact");
        answerAll(batchOf(h, 1, "Carol's accept"), "10");
        h.drain();
        changes4(h.remote.take("changes"), "c3", remove(7, "invitation", stranger), upsert(8, assigneeTask(strangerTask, "10", CAROL, stranger)));
        h.drain();
        quiet(h);

        // An invitation that arrives with a pull is accepted in that cycle, without another trigger.
        String next = TasksWire.newId(), nextTask = TasksWire.newId();
        h.engine.refresh();
        h.drain();
        changes4(h.remote.take("changes"), "c4", upsert(9, invitationFrom(next, nextTask, "2", BOB, LATER)));
        h.drain();
        Request arrived = batchOf(h, 1, "accepted by the cycle that delivered it");
        check(batchBody(arrived).contains(next) && h.state().provisional(next) != null, "queued before the drain of the same cycle");
        answerAll(arrived, "3");
        h.drain();
        changes4(h.remote.take("changes"), "c5", remove(10, "invitation", next), upsert(11, assigneeTask(nextTask, "3", BOB, next)));
        h.drain();
        quiet(h);

        // The app tore its sources down: nothing is accepted until fresh inputs arrive.
        h.engine.clearAutoAcceptInputs();
        String after = TasksWire.newId();
        h.engine.refresh();
        h.drain();
        changes4(h.remote.take("changes"), "c6", upsert(12, invitationFrom(after, TasksWire.newId(), "2", BOB, LATER)));
        h.drain();
        none(h, "no inputs: the invitation waits like a stranger's");
        check(h.state().invitation(after) != null && h.state().provisional.isEmpty(), "in Invitations");
        TasksAnalyticsSink.Holder.set(null);
        h.sql.close();
    }

    static void automaticAcceptRespectsManualAnswers() {
        final String[] stored = {null};
        TasksInviteAnswers.Store store = new TasksInviteAnswers.Store() {
            @Override public String load() { return stored[0]; }
            @Override public void save(String json) { stored[0] = json; }
        };
        String first = TasksWire.newId(), task = TasksWire.newId(), blockedInv = TasksWire.newId(), sibling = TasksWire.newId();
        Harness h = r4(invitationFrom(first, task, "4", BOB, LATER), invitationFrom(blockedInv, TasksWire.newId(), "2", CAROL, LATER),
                invitationFrom(sibling, TasksWire.newId(), "2", CAROL, LATER));
        h.engine.setInviteAnswers(store);
        h.engine.respond(first, TasksWire.Decision.DECLINE);
        h.drain();
        check(TasksInviteAnswers.decode(stored[0]).answered(first) && TasksInviteAnswers.decode(stored[0]).refused(task), "a manual decline is recorded: the invitation and its task");
        answerAll(batchOf(h, 1, "the decline"), "5");
        h.drain();
        // The owner sends the same task again: a new epoch from a contact waits in Invitations.
        String again = TasksWire.newId();
        changes4(h.remote.take("changes"), "c2", remove(5, "invitation", first), upsert(6, invitationFrom(again, task, "6", BOB, LATER)));
        h.drain();
        quiet(h);
        h.engine.runAutoAccept(inputs(ids(555L, 556L), Collections.<Long>emptySet(), true, ids(555L, 556L)));
        h.drain();
        check(h.state().invitation(again) != null && h.state().provisional(again) == null, "a later epoch of a task declined by hand is never accepted automatically");
        check(h.state().provisional.size() == 2, "Carol's two invitations are accepted meanwhile");
        Request carol = batchOf(h, 2, "both accepts");
        answerAll(carol, "3");
        h.drain();
        quiet(h);
        h.sql.close();

        // A queued Tasks block of a sender holds for every invitation of that sender.
        String one = TasksWire.newId(), two = TasksWire.newId();
        Harness b = r4(invitationFrom(one, TasksWire.newId(), "2", BOB, LATER), invitationFrom(two, TasksWire.newId(), "2", BOB, LATER));
        b.engine.setInviteAnswers(store);
        b.engine.stop();
        b.engine.blockSender(one);
        check(TasksInviteAnswers.decode(stored[0]).answered(one), "a block is a manual answer");
        b.engine.start();
        b.drain();
        b.engine.runAutoAccept(bobIsAContact());
        b.drain();
        check(b.state().provisional.isEmpty() && b.ledger.entries().size() == 1, "nothing of a sender being blocked is accepted");
        b.sql.close();

        // An expired invitation, and the fail-closed inputs.
        String old = TasksWire.newId(), fresh = TasksWire.newId();
        Harness e = r4(invitationFrom(old, TasksWire.newId(), "2", BOB, EARLIER), invitationFrom(fresh, TasksWire.newId(), "2", BOB, LATER));
        e.engine.runAutoAccept(inputs(ids(555L), Collections.<Long>emptySet(), false, ids(555L)));
        e.drain();
        check(e.ledger.entries().isEmpty(), "the blocked list has not been read: nothing");
        e.engine.runAutoAccept(inputs(ids(555L), Collections.<Long>emptySet(), true, Collections.<Long>emptySet()));
        e.drain();
        check(e.ledger.entries().isEmpty(), "the sender's flag has not been read: nothing");
        e.engine.runAutoAccept(inputs(ids(555L), ids(555L), true, ids(555L)));
        e.drain();
        check(e.ledger.entries().isEmpty(), "blocked in Telegram: nothing");
        e.engine.runAutoAccept(bobIsAContact());
        e.drain();
        check(e.ledger.entries().size() == 1 && e.state().provisional(fresh) != null && e.state().invitation(old) != null, "only the unexpired one is accepted");
        e.sql.close();
    }

    static void automaticAcceptResealsAndGivesUp() {
        String inv = TasksWire.newId(), task = TasksWire.newId();
        Harness h = r4(invitationFrom(inv, task, "4", BOB, LATER));
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        int revision = 4;
        String lastId = null;
        for (int attempt = 1; attempt <= TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS; attempt++) {
            Request accept = batchOf(h, 1, "attempt " + attempt);
            TasksWire.Mutation sent = accept.batch.items.get(0).mutation;
            check(batchBody(accept).contains("\"base_revision\":\"" + revision + "\"") && !sent.mutationId.equals(lastId), "sealed on the invitation's current task revision");
            check((sent.mutationId + ":" + attempt).equals(meta(h, "auto_accept." + inv)), "attempt " + attempt + " recorded");
            lastId = sent.mutationId;
            answer(accept, null, err(sent, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
            h.drain();
            check(h.state().provisional.size() == (attempt < TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS ? 1 : 0), "provisional while it will be resealed");
            revision++;
            // The server re-upserted the pending invitation with the task's new revision.
            changes4(h.remote.take("changes"), "c" + (attempt + 1), upsert(4 + attempt, invitationFrom(inv, task, String.valueOf(revision), BOB, LATER)));
            h.drain();
        }
        quiet(h);
        check(h.ledger.entries().isEmpty() && h.state().pending.isEmpty() && h.state().notices.isEmpty(), "past the cap: dropped quietly — no row, no notice");
        check(h.state().invitation(inv) != null && h.state().provisional.isEmpty(), "the invitation is back in Invitations");
        check(meta(h, "auto_accept." + inv).endsWith(":" + TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS), "given up: attempts at the cap");
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        none(h, "never tried again");
        // The user may still accept it by hand.
        h.engine.respond(inv, TasksWire.Decision.ACCEPT);
        h.drain();
        answerAll(batchOf(h, 1, "the manual accept"), "9");
        h.drain();
        quiet(h);
        h.sql.close();

        // A definite refusal (the task is gone): dropped quietly, no access-lost notice, never retried.
        String gone = TasksWire.newId();
        Harness g = r4(invitationFrom(gone, TasksWire.newId(), "4", BOB, LATER));
        g.engine.runAutoAccept(bobIsAContact());
        g.drain();
        Request accept = batchOf(g, 1, "the automatic accept");
        answer(accept, null, err(accept.batch.items.get(0).mutation, 404, "tasks_not_found", ""));
        g.drain();
        quiet(g);
        check(g.ledger.entries().isEmpty() && g.state().notices.isEmpty() && g.state().pending.isEmpty(), "404: no row, no notice");
        check(g.state().invitation(gone) != null && meta(g, "auto_accept." + gone).endsWith(":3"), "back in Invitations until the pull removes it; given up");
        g.engine.runAutoAccept(bobIsAContact());
        g.drain();
        none(g, "not retried");
        g.sql.close();
    }


    // ---- a reseal or a re-send never ends an assignment the owner did not confirm ending ----

    static void nobodyLosesTheTaskWithoutTheOwnersWord() {
        // A pick on an unassigned task meets a task that another device assigned meanwhile: the owner decides.
        String task = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Race", null, null, false));
        h.engine.assign(task, BOB);
        h.drain();
        Request request = batchOf(h, 1, "the request");
        answer(request, null, err(request.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
        h.drain();
        String carols = TasksWire.newId();
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "3", "Race", null, assignment(carols, CAROL, "accepted", LATER), false)));
        h.drain();
        quiet(h);
        check(h.state().pending.size() == 1 && h.state().pending.get(0).state == TasksCloudState.PendingState.CONFLICT,
                "Carol got the task elsewhere: the pick for Bob is not resealed over her, it waits for the owner");
        check(shown(h, task) == TasksAssigneeDisplay.State.ACCEPTED && CAROL.equals(h.state().task(task).assigneeTgId()), "the row shows Carol");
        h.sql.close();

        // Replacing Bob was confirmed: when Bob accepts in between, the pick for Carol is still resealed.
        String swap = TasksWire.newId(), bobs = TasksWire.newId();
        Harness s = r4(ownerTask(swap, "2", "Swap", null, assignment(bobs, BOB, "pending", LATER), false));
        s.engine.assign(swap, CAROL);
        s.drain();
        check(bobs.equals(s.ledger.entries().get(0).baseSnapshot), "the request names the epoch it replaces");
        Request first = batchOf(s, 1, "Carol's request");
        answer(first, null, err(first.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
        s.drain();
        changes4(s.remote.take("changes"), "c2", upsert(5, ownerTask(swap, "3", "Swap", null, assignment(bobs, BOB, "accepted", LATER), false)));
        s.drain();
        Request resealed = batchOf(s, 1, "resealed over the epoch the owner confirmed replacing");
        check(batchBody(resealed).contains("\"base_revision\":\"3\"") && batchBody(resealed).contains("\"recipient_tg_id\":\"556\"")
                && bobs.equals(s.ledger.entries().get(0).baseSnapshot), "to Carol on the fresh revision, still naming Bob's epoch");
        answerAll(resealed, "4");
        s.drain();
        quiet(s);
        s.sql.close();

        // A deferred replacement is re-sent while the replaced assignee is still the live one, and ends quietly otherwise.
        String kept = TasksWire.newId(), moved = TasksWire.newId(), keptEpoch = TasksWire.newId(), movedEpoch = TasksWire.newId();
        Harness d = r4(ownerTask(kept, "2", "Kept", null, assignment(keptEpoch, BOB, "accepted", LATER), false),
                ownerTask(moved, "2", "Moved", null, assignment(movedEpoch, BOB, "accepted", LATER), false));
        d.engine.stop();
        d.engine.assign(kept, CAROL);
        d.engine.assign(moved, CAROL);
        d.engine.start();
        d.drain();
        throttle(d.remote.take("batch"), 0, 1800, null);
        d.drain();
        throttle(d.remote.take("batch"), 0, 1800, null);
        d.drain();
        quiet(d);
        check(("{\"after\":" + (d.now / 1000 + 1800) + ",\"to\":\"556\",\"replaces\":\"" + keptEpoch + "\"}").equals(meta(d, "assign_deferred." + kept)),
                "the deferral remembers the epoch the pick replaces");
        check(shown(d, kept) == TasksAssigneeDisplay.State.QUEUED && CAROL.equals(d.state().task(kept).assigneeTgId()), "Queued for Carol");
        // Meanwhile another device gave the second task to someone else.
        String davids = TasksWire.newId();
        d.engine.refresh();
        d.drain();
        changes4(d.remote.take("changes"), "c2", upsert(5, ownerTask(moved, "3", "Moved", null, assignment(davids, "557", "pending", LATER), false)));
        d.drain();
        quiet(d);
        d.advance(1_800_000L);
        Request resend = batchOf(d, 1, "only the replacement whose replaced assignee is still the live one is re-sent");
        check(batchBody(resend).contains(kept) && batchBody(resend).contains("\"recipient_tg_id\":\"556\"") && keptEpoch.equals(d.ledger.entries().get(0).baseSnapshot),
                "Kept goes to Carol, still naming Bob's epoch");
        check(meta(d, "assign_deferred." + moved) == null && d.state().notices.isEmpty(), "Moved has a new assignee nobody confirmed replacing: its deferral ended quietly");
        // The automatic re-send meets a newer assignee after all (assigned elsewhere while it was on its way): dropped quietly.
        answer(resend, null, err(resend.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
        d.drain();
        changes4(d.remote.take("changes"), "c3", upsert(6, ownerTask(kept, "3", "Kept", null, assignment(TasksWire.newId(), "557", "accepted", LATER), false)));
        d.drain();
        quiet(d);
        check(d.ledger.entries().isEmpty() && d.state().pending.isEmpty() && d.state().notices.isEmpty() && meta(d, "assign_resend." + kept) == null,
                "no reseal over the newer assignee, no row, no notice");
        d.sql.close();
    }

    // ---- Remove reads Removed at once; a pick behind a dropped Remove still goes ----

    static void removeIsShownAtOnceAndNeverStrandsThePickBehindIt() {
        String task = TasksWire.newId(), epoch = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "Remove", null, assignment(epoch, BOB, "accepted", LATER), false));
        check(shown(h, task) == TasksAssigneeDisplay.State.ACCEPTED && !h.state().task(task).removalQueued(), "Bob has it");
        h.engine.stop();
        h.engine.revoke(task, epoch);
        check(shown(h, task) == TasksAssigneeDisplay.State.REMOVED && h.state().task(task).removalQueued() && BOB.equals(h.state().task(task).assigneeTgId()),
                "a queued Remove reads Removed at once, not the stale live state");
        check(expect(TasksCloudError.class, () -> h.engine.revoke(task, epoch), "a second Remove").code == TasksCloudError.Code.CHANGED_ELSEWHERE,
                "a second Remove of the same epoch is refused");
        // The owner picks Carol right away: her request waits unsealed behind the Remove.
        h.engine.assign(task, CAROL);
        check(shown(h, task) == TasksAssigneeDisplay.State.SENDING && CAROL.equals(h.state().task(task).assigneeTgId()), "Sending… for Carol");
        h.engine.start();
        h.drain();
        Request revoke = batchOf(h, 1, "the Remove first");
        check(batchBody(revoke).contains("\"kind\":\"invitation.revoke\""), "the revoke");
        // Bob left meanwhile: the Remove is already true on the server.
        answer(revoke, null, err(revoke.batch.items.get(0).mutation, 409, "tasks_conflict", ",\"reason\":\"revision_changed\""));
        h.drain();
        changes4(h.remote.take("changes"), "c2", upsert(5, ownerTask(task, "3", "Remove", null, assignment(epoch, BOB, "left", LATER), false)));
        h.drain();
        Request request = batchOf(h, 1, "the pick behind the dropped Remove goes on the fresh revision");
        check(batchBody(request).contains("\"kind\":\"invitation.request\"") && batchBody(request).contains("\"base_revision\":\"3\"")
                && batchBody(request).contains("\"recipient_tg_id\":\"556\""), "Carol's request, sealed on revision 3");
        answerAll(request, "4");
        h.drain();
        quiet(h);
        check(h.state().notices.isEmpty() && h.state().pending.isEmpty(), "no dependency notice, nothing stuck");
        h.sql.close();
    }

    // ---- an automatic accept that has not left the device is not final; one that has keeps its record ----

    static void automaticAcceptIsWithdrawnOrKeptByWhatHappened() {
        String inv = TasksWire.newId();
        Harness h = r4(invitationFrom(inv, TasksWire.newId(), "4", BOB, LATER));
        // A request is in flight, so the accept is queued and waits.
        String busy = h.engine.createTask(content(null, "Busy"));
        h.drain();
        Request create = h.remote.take("batch");
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        check(h.ledger.entries().size() == 2 && h.state().provisional(inv) != null && meta(h, "auto_accept." + inv) != null, "the accept is queued behind the request in flight");
        // The user blocks Bob in Telegram before it is sent: the rule no longer holds, the accept is withdrawn.
        h.engine.runAutoAccept(inputs(ids(555L), ids(555L), true, ids(555L)));
        h.drain();
        check(h.ledger.entries().size() == 1 && meta(h, "auto_accept." + inv) == null && h.state().provisional.isEmpty()
                && h.state().invitation(inv) != null, "withdrawn: the invitation waits in Invitations, nothing will be sent for it");
        answerAll(create, "1");
        h.drain();
        changes4(h.remote.take("changes"), "c1b", upsert(4, ownerTask(busy, "1", "Busy", null, null, false)));
        h.drain();
        quiet(h);
        check(h.state().pending.isEmpty() && h.ledger.entries().isEmpty(), "only the create went out");
        // Unblocked again: accepted, as for any contact (the withdrawal left no trace).
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        Request accept = batchOf(h, 1, "the accept");
        check((accept.batch.items.get(0).mutation.mutationId + ":1").equals(meta(h, "auto_accept." + inv)), "a first attempt again");
        // Its answer is lost (offline), and the owner revokes the task meanwhile: the invitation leaves the snapshot.
        failBatch(accept, TasksCloudRemote.Failure.Kind.OFFLINE, "offline", 0, 0);
        h.drain();
        h.engine.refresh();
        h.drain();
        changes4(h.remote.take("changes"), "c2", remove(5, "invitation", inv));
        h.drain();
        check(meta(h, "auto_accept." + inv) != null, "the record stays while its entry is in the outbox, whatever the snapshot says");
        h.advance(TasksCloudPolicy.backoffMs(1));
        Request retry = takeBatch(h);
        answer(retry, null, err(retry.batch.items.get(0).mutation, 404, "tasks_not_found", ""));
        h.drain();
        quiet(h);
        check(h.ledger.entries().isEmpty() && h.state().pending.isEmpty() && h.state().notices.isEmpty(),
                "its 404 is still the automatic entry's: no row, no access-lost notice");
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        check(meta(h, "auto_accept." + inv) == null, "and the record goes once the entry and the invitation are gone");
        h.sql.close();

        // A one-entry batch the server refuses as a whole (422): the automatic accept ends quietly too.
        String refusedInv = TasksWire.newId();
        Harness r = r4(invitationFrom(refusedInv, TasksWire.newId(), "4", BOB, LATER));
        r.engine.runAutoAccept(bobIsAContact());
        r.drain();
        Request refused = batchOf(r, 1, "the accept");
        failBatch(refused, TasksCloudRemote.Failure.Kind.PROTOCOL, "request_rejected", 422, 0);
        r.drain();
        quiet(r);
        check(r.ledger.entries().isEmpty() && r.state().pending.isEmpty() && r.state().notices.isEmpty()
                && meta(r, "auto_accept." + refusedInv).endsWith(":" + TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS), "no invalid row; given up");
        r.sql.close();
    }

    // ---- discarding a create discards the assignment that waited behind it ----

    static void discardedCreateTakesItsAssignmentWithIt() {
        Harness h = r4();
        h.engine.stop();
        String task = h.engine.createTask(content(null, "Never created"), BOB);
        check(h.ledger.entries().size() == 2 && shown(h, task) == TasksAssigneeDisplay.State.SENDING, "the create and its request");
        h.engine.discard(h.ledger.entries().get(0).mutationId);
        check(h.ledger.entries().isEmpty() && h.state().task(task) == null && h.state().notices.isEmpty() && h.state().pending.isEmpty(),
                "both are gone: no conflict row and no notice for an assignment that has nothing to assign");
        h.sql.close();
    }

    // ---- a withdrawal and a rebind forget the markers ----

    static void withdrawalAndRebindForgetMarkers() {
        String task = TasksWire.newId(), inv = TasksWire.newId();
        Harness h = r4(ownerTask(task, "2", "W", null, null, false), invitationFrom(inv, TasksWire.newId(), "4", BOB, LATER));
        h.engine.assign(task, CAROL);
        h.drain();
        Request request = batchOf(h, 1, "the request");
        throttle(request, 0, 1800, null);
        h.drain();
        quiet(h);
        h.engine.stop();
        h.engine.start();
        h.drain();
        quiet(h);
        h.engine.stop();
        h.engine.runAutoAccept(bobIsAContact());
        check(h.ledger.entries().isEmpty(), "a stopped engine accepts nothing");
        h.engine.start();
        h.drain();
        quiet(h);
        h.engine.runAutoAccept(bobIsAContact());
        h.drain();
        check(meta(h, "assign_deferred." + task) != null && meta(h, "auto_accept." + inv) != null, "a deferral and an automatic accept are recorded");
        int[] discarded = h.engine.discardAfterConsentWithdrawal();
        check(discarded[0] == 2, "the withdrawal discards both as unsent changes: " + discarded[0]);
        check(meta(h, "assign_deferred." + task) == null && meta(h, "auto_accept." + inv) == null && h.state().unsentUserChanges == 0,
                "and forgets the markers");
        check(shown(h, task) == null && h.state().provisional.isEmpty(), "nothing queued, nothing provisional");
        h.sql.close();

        Harness r = r4(ownerTask(task, "2", "W", null, null, false));
        r.ledger.writeMeta("assign_deferred." + task, TasksCloudLedger.Deferral.encode(BOB, r.now + 1000));
        r.ledger.writeMeta("assign_resend." + task, TasksWire.newId());
        r.ledger.writeMeta("auto_accept." + inv, TasksWire.newId() + ":1");
        check(r.ledger.bindPrincipal(B), "rebound to another principal");
        check(meta(r, "assign_deferred." + task) == null && meta(r, "assign_resend." + task) == null && meta(r, "auto_accept." + inv) == null,
                "a rebind forgets them");
        r.sql.close();
    }

    public static void main(String[] args) throws Exception {
        bridge = args[0];
        dir = new File(args[1]);
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        pacerTable();
        contactRuleTable();
        manualAnswersRecord();
        displayTable();
        createWithAssigneeIsOneChain();
        refusalsQueueNothing();
        resendAndExactRemoval();
        requestConflictReseals();
        longRateLimitDefers();
        deferralEdges();
        deferralDoesNotOrphanSuccessors();
        restartKeepsDeferralsAndForgetsInputs();
        assignmentHoldsItsListsOperations();
        legacyAnnotation();
        automaticAccept();
        automaticAcceptRespectsManualAnswers();
        automaticAcceptResealsAndGivesUp();
        nobodyLosesTheTaskWithoutTheOwnersWord();
        removeIsShownAtOnceAndNeverStrandsThePickBehindIt();
        automaticAcceptIsWithdrawnOrKeptByWhatHappened();
        discardedCreateTakesItsAssignmentWithIt();
        withdrawalAndRebindForgetMarkers();
        System.out.println("Tasks assignment (the assignee pick is the invitation; host SQLite bridge, scripted R4 remote): " + checks + " checks passed");
    }
}

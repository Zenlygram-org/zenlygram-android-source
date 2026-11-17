package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Durable manual answers to invitations (iOS SGTasksInviteAnswers, wf15 §5.3, D2). What the user decided by hand about
 * an invitation must never be undone by the automatic accept of a contact's task ({@link TasksAutoAcceptPolicy}): a
 * manual Accept, Decline or Block records the invitation id; a manual Decline or Leave also records the task id, so
 * that every later epoch of that task (the owner's Send again) waits in Invitations even from a contact. The record is
 * the JSON {@code {"i":{invitationId: unixSeconds},"t":{taskId: unixSeconds}}} — content-free UUIDs and times, nothing
 * about the task or the people. It is kept outside the per-account cloud database, because that file is deleted by
 * every logout and the server hides non-pending invitations, so after a re-login nothing could rebuild it; it goes only
 * with the app's data or by its own prune: invitation ids older than 8 days (an invitation lives 7), task ids beyond
 * the 500 newest. Only manual answers are recorded; an invitation the policy merely left for the user never is, so
 * adding a contact later still accepts every invitation the user never answered.
 */
public final class TasksInviteAnswers {
    /** An invitation lives 7 UTC days; a recorded id is kept one day longer. */
    public static final long INVITATION_RETENTION_SECONDS = 8 * 86400L;
    /** Refused task ids are capped at the newest this many. */
    public static final int MAXIMUM_REFUSED_TASKS = 500;

    /** Where the JSON lives (production: SharedPreferences, one key per raw Telegram id). */
    public interface Store {
        /** The stored JSON, or null. */
        String load();

        /** {@code json} null removes the record. */
        void save(String json);
    }

    /** A store that keeps nothing (host tests that do not care, and a closed repository). */
    public static final Store NO_STORE = new Store() {
        @Override public String load() { return null; }

        @Override public void save(String json) { }
    };

    private final TreeMap<String, Long> invitationIds = new TreeMap<>();
    private final TreeMap<String, Long> refusedTaskIds = new TreeMap<>();

    public TasksInviteAnswers() { }

    public boolean isEmpty() {
        return invitationIds.isEmpty() && refusedTaskIds.isEmpty();
    }

    /** This invitation was answered by hand (queued, in flight, sent or resealed). */
    public boolean answered(String invitationId) {
        return invitationIds.containsKey(invitationId);
    }

    /** This task was declined or left by hand: no later epoch of it is accepted automatically. */
    public boolean refused(String taskId) {
        return refusedTaskIds.containsKey(taskId);
    }

    public void recordInvitation(String invitationId, long nowSeconds) {
        invitationIds.put(invitationId, nowSeconds);
    }

    public void recordRefusedTask(String taskId, long nowSeconds) {
        refusedTaskIds.put(taskId, nowSeconds);
    }

    public int invitationCount() {
        return invitationIds.size();
    }

    public int refusedTaskCount() {
        return refusedTaskIds.size();
    }

    /** Invitation ids older than the retention go; task ids are capped at the newest (ties by id: deterministic). */
    public void prune(long nowSeconds) {
        long cutoff = nowSeconds - INVITATION_RETENTION_SECONDS;
        for (Iterator<Map.Entry<String, Long>> it = invitationIds.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= cutoff) it.remove();
        }
        if (refusedTaskIds.size() > MAXIMUM_REFUSED_TASKS) {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(refusedTaskIds.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, Long>>() {
                @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    int byTime = b.getValue().compareTo(a.getValue());
                    return byTime != 0 ? byTime : a.getKey().compareTo(b.getKey());
                }
            });
            TreeMap<String, Long> kept = new TreeMap<>();
            for (int i = 0; i < MAXIMUM_REFUSED_TASKS; i++) kept.put(sorted.get(i).getKey(), sorted.get(i).getValue());
            refusedTaskIds.clear();
            refusedTaskIds.putAll(kept);
        }
    }

    // ---- encoding ----

    /** Unreadable or foreign input decodes as empty; entries that are not a UUID with a number are skipped. */
    public static TasksInviteAnswers decode(String json) {
        TasksInviteAnswers answers = new TasksInviteAnswers();
        if (json == null || json.isEmpty()) return answers;
        try {
            Object parsed = TasksWireJson.parse(json, 1 << 20);
            if (!(parsed instanceof Map)) return answers;
            decodeTable(((Map<?, ?>) parsed).get("i"), answers.invitationIds);
            decodeTable(((Map<?, ?>) parsed).get("t"), answers.refusedTaskIds);
        } catch (RuntimeException unreadable) {
            return new TasksInviteAnswers();
        }
        return answers;
    }

    private static void decodeTable(Object value, TreeMap<String, Long> into) {
        if (!(value instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof TasksWireJson.Num)) continue;
            String key = (String) entry.getKey();
            try {
                TasksWireText.uuid(key, "id");
                into.put(key, Long.parseLong(((TasksWireJson.Num) entry.getValue()).literal));
            } catch (RuntimeException skipped) {
                // not a UUID, or not an integer: skipped
            }
        }
    }

    /** {@code {"i":{…},"t":{…}}}, keys sorted. */
    public String encode() {
        StringBuilder out = new StringBuilder(64 + 52 * (invitationIds.size() + refusedTaskIds.size()));
        out.append("{\"i\":");
        encodeTable(out, invitationIds);
        out.append(",\"t\":");
        encodeTable(out, refusedTaskIds);
        return out.append('}').toString();
    }

    private static void encodeTable(StringBuilder out, TreeMap<String, Long> table) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Long> entry : table.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(entry.getKey()).append("\":").append(entry.getValue().longValue());
        }
        out.append('}');
    }

    // ---- persistence: every read prunes; every write prunes and stores ----

    public static TasksInviteAnswers load(Store store, long nowSeconds) {
        TasksInviteAnswers answers = decode(store == null ? null : store.load());
        answers.prune(nowSeconds);
        return answers;
    }

    private static void save(Store store, TasksInviteAnswers answers) {
        if (store != null) store.save(answers.isEmpty() ? null : answers.encode());
    }

    /** A manual Accept, Decline or Block of {@code invitationId}; {@code refusedTaskId} for a Decline (its task). */
    public static void recordInvitation(Store store, String invitationId, String refusedTaskId, long nowSeconds) {
        TasksInviteAnswers answers = load(store, nowSeconds);
        answers.recordInvitation(invitationId, nowSeconds);
        if (refusedTaskId != null) answers.recordRefusedTask(refusedTaskId, nowSeconds);
        answers.prune(nowSeconds);
        save(store, answers);
    }

    /** A manual Leave of the task (its accepted epoch's id is recorded too). */
    public static void recordRefusedTask(Store store, String taskId, String invitationId, long nowSeconds) {
        TasksInviteAnswers answers = load(store, nowSeconds);
        answers.recordRefusedTask(taskId, nowSeconds);
        if (invitationId != null) answers.recordInvitation(invitationId, nowSeconds);
        answers.prune(nowSeconds);
        save(store, answers);
    }
}

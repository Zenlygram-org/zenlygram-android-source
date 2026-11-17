package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Engine-side view of the named Tasks transport. Production adapter: TasksCloudRepository over
 * {@code org.telegram.messenger.zenlygram.pro.TasksApi} (same kinds/codes, no credentials). Every request carries the
 * exact identity captured for that step (the one the shadow/outbox was bound to); the transport refuses it if the
 * live binding differs. Replies may arrive on any thread; the engine re-enters its own serial executor and fences them.
 * A SESSION failure (a real 401) arrives only after the existing session handling durably dropped the refresh
 * credential of the binding it was answered for, so that binding is never linked again (TasksCloudEngine ends a 401
 * pause persisted before a restart on the next linked identity).
 */
public interface TasksCloudRemote {
    final class Identity {
        /** 0 = signed out. */
        public final long telegramUserId;
        /** Canonical backend principal B; null = no linked binding. */
        public final String principal;
        /** Process-local Pro generation: fences one request; never persisted. */
        public final long generation;
        /** Adapter-owned transport identity object captured with these values; opaque to the engine. */
        final Object handle;

        public Identity(long telegramUserId, String principal, long generation) {
            this(telegramUserId, principal, generation, null);
        }

        Identity(long telegramUserId, String principal, long generation, Object handle) {
            this.telegramUserId = telegramUserId;
            this.principal = principal;
            this.generation = generation;
            this.handle = handle;
        }

        public boolean isLinked() {
            return telegramUserId > 0 && principal != null && !principal.isEmpty();
        }
    }

    final class Failure {
        public enum Kind { UNLINKED, SESSION, STALE, BUSY, LOCAL, OFFLINE, RATE_LIMITED, UNAVAILABLE, TASK, PROTOCOL }

        public final Kind kind;
        public final String code;
        public final TasksWire.ConflictReason reason;
        public final int httpStatus;
        public final int retryAfterSeconds;
        /** R4 tasks_conflict field_changed: the conflicting fields in content order; empty otherwise. Unmodifiable. */
        public final List<TasksWire.ContentField> fields;
        /** R4 tasks_consent_required: when the standing withdrawal took effect (a wire Instant); null otherwise. */
        public final String effectiveAt;
        /** The answer's X-Tasks-Wire echo; null when absent or local. */
        public final Integer serverWire;

        public Failure(Kind kind, String code, TasksWire.ConflictReason reason, int httpStatus, int retryAfterSeconds) {
            this(kind, code, reason, httpStatus, retryAfterSeconds, null, null, null);
        }

        public Failure(Kind kind, String code, TasksWire.ConflictReason reason, int httpStatus, int retryAfterSeconds,
                       List<TasksWire.ContentField> fields, String effectiveAt, Integer serverWire) {
            if (kind == null || code == null) throw new IllegalArgumentException("failure");
            this.kind = kind;
            this.code = code;
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
            this.fields = fields == null || fields.isEmpty() ? Collections.<TasksWire.ContentField>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(fields));
            this.effectiveAt = effectiveAt;
            this.serverWire = serverWire;
        }
    }

    /**
     * One answered batch as the engine consumes it: the strictly decoded response (every result answers its own item,
     * in request order) and, per ERROR item, the Failure the single route would have given for the same answer
     * (production: TasksApi.classifyItem), so TasksCloudPolicy treats an item exactly like a single mutation.
     */
    final class BatchReply {
        public final TasksWire.BatchResponse response;
        /** Same size and order as {@code response.results}; non-null exactly for ERROR items. Unmodifiable. */
        public final List<Failure> itemFailures;

        public BatchReply(TasksWire.BatchResponse response, List<Failure> itemFailures) {
            if (response == null || itemFailures == null || itemFailures.size() != response.results.size()) {
                throw new IllegalArgumentException("batch_reply");
            }
            for (int i = 0; i < itemFailures.size(); i++) {
                boolean error = response.results.get(i).kind == TasksWire.BatchItemResult.Kind.ERROR;
                if (error != (itemFailures.get(i) != null)) throw new IllegalArgumentException("batch_reply");
            }
            this.response = response;
            this.itemFailures = Collections.unmodifiableList(new ArrayList<>(itemFailures));
        }
    }

    interface Reply<T> {
        void success(T value);

        void failure(Failure failure);
    }

    Identity identity();

    void mutate(Identity expected, TasksWire.Mutation mutation, Reply<TasksWire.MutationResponse> reply);

    /**
     * R4 POST /v1/tasks/mutations/batch with exactly {@code batch} (one principal, 1..100 sealed mutations). A failure
     * is the whole call's (envelope) answer: no item ran (404, 503 batch_disabled) or every item's outcome is unknown.
     */
    void mutateBatch(Identity expected, TasksWire.Batch batch, Reply<BatchReply> reply);

    void snapshot(Identity expected, String cursor, int limit, Reply<TasksWire.SnapshotResponse> reply);

    void changes(Identity expected, String cursor, int limit, Reply<TasksWire.ChangesResponse> reply);
}

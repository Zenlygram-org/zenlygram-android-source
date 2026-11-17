package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable validated models of the Zenlygram Tasks wire v1 (R3 canonical contract
 * zenlygram-tasks-wire-v1-r3.json, SHA-256 377a4c1d…648da), with typed factories for all 12
 * operation kinds, the sealed {@link Mutation} (exact request bytes + canonical business bytes, no
 * credentials, proof or headers) and strict decoders for every response. Decimal identifiers,
 * revisions and positions stay Strings; Instants stay their exact wire Strings.
 *
 * R4 (tasks-sync.json revision 4, X-Tasks-Wire: 4) is additive: {@code task.patch}, the optional
 * {@code list.delete} base_members_revision, {@code members_revision} in list projections, the conflict
 * reasons field_changed / members_changed, the R4 error extras and the batch route ({@link Batch},
 * {@link BatchResponse}). Every R3 body and hash stays byte-identical.
 *
 * Android omits the optional creation_proof (no cross-account sponsor): the server authorizes the
 * current linked principal. Every validation failure is an unchecked {@link Invalid} naming the
 * field only, never the value.
 */
public final class TasksWire {
    public static final int SCHEMA_VERSION = 1;
    /** x-wire-negotiation: the X-Tasks-Wire value this client sends once on every Tasks request (R4). */
    public static final int WIRE = 4;
    /** x-endpoints: 65536 UTF-8 request bytes per mutation request. */
    public static final int MAX_MUTATION_BYTES = 65536;
    /** x-endpoints[3] (R4 batch): 524288 UTF-8 request bytes and 1..100 items. */
    public static final int MAX_BATCH_BYTES = 524288, MAX_BATCH_ITEMS = 100;
    /** A batch answer: at most 100 receipts or item errors and no content; read and parsed no further. */
    public static final int MAX_BATCH_RESPONSE_BYTES = 262144;
    public static final int MIN_LIMIT = 1, MAX_LIMIT = 100, DEFAULT_LIMIT = 50;
    /** Deadline: nullable Unix seconds 0..2147483647. */
    public static final long MAX_DEADLINE = 2147483647L;
    static final String BUSINESS_TAG = "zenlygram-tasks-business-v1";
    /**
     * Worst JSON cost of one schema-maxLength scalar: a supplementary code point written as an escaped
     * surrogate pair ("\\uD83D\\uDE00") is 12 bytes (an escaped BMP scalar is 6, raw UTF-8 at most 4).
     */
    static final int MAX_BYTES_PER_SCALAR = 12;
    /**
     * Fixed allowance per item for member names, identifiers, revisions, numbers, booleans, Instants and
     * structure, even when every one of their characters is \\u-escaped (a fully escaped compact task
     * without its free-text strings stays under ~4.5 KB). Insignificant whitespace beyond this
     * allowance is not assumed: the server emits compact JSON.
     */
    static final int ITEM_FIXED_BYTES = 8192;
    /** A change's position/action/kind wrapper around its item, fully escaped. */
    static final int CHANGE_WRAPPER_BYTES = 1024;
    /** Envelope members, schema_version, has_more and server_time, fully escaped. */
    static final int ENVELOPE_BYTES = 4096;
    /** Cursor: maxLength 4096 scalars. */
    static final int MAX_CURSOR_BYTES = MAX_BYTES_PER_SCALAR * 4096;
    /**
     * UTF-8 ceiling of one schema-valid $defs.Projection. Task is the largest kind: Title 8192 +
     * description 16384 + annotation display_name 4096 scalars at 12 bytes each, plus the fixed allowance.
     */
    public static final int MAX_ITEM_BYTES = MAX_BYTES_PER_SCALAR * (8192 + 16384 + 4096) + ITEM_FIXED_BYTES;

    /**
     * UTF-8 ceiling of one schema-valid snapshot or changes page requested with {@code limit} (items are
     * at most limit): limit maximal items with their change wrapper, two maximal cursors and the
     * envelope. Transport reads at most this before strict parsing, so a small limit never admits the
     * 100-item budget. Never drops a valid page: anything larger is not schema-valid compact JSON.
     */
    public static int maxPageBytes(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) throw new Invalid("limit");
        return limit * (MAX_ITEM_BYTES + CHANGE_WRAPPER_BYTES) + 2 * MAX_CURSOR_BYTES + ENVELOPE_BYTES;
    }

    /**
     * maxPageBytes(100), about 33.8 MiB. Memory: a page is held as bytes, String and tree at once;
     * callers should prefer small limits (e.g. 20 is about 6.8 MiB) on constrained devices.
     */
    public static final int MAX_PAGE_BYTES = MAX_LIMIT * (MAX_ITEM_BYTES + CHANGE_WRAPPER_BYTES) + 2 * MAX_CURSOR_BYTES + ENVELOPE_BYTES;
    /** Decoded text is never longer in UTF-16 units than its UTF-8 bytes. */
    static final int MAX_DOCUMENT_CHARS = MAX_PAGE_BYTES;
    static final int MAX_PROJECTION_CHARS = MAX_ITEM_BYTES;

    private TasksWire() { }

    /** A wire value outside the canonical contract. Names the field, never echoes the value. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final String field;
        Invalid(String field) {
            super("Invalid Tasks wire value: " + field);
            this.field = field;
        }
    }

    /** Random lowercase UUIDv4 for mutation, list, task and invitation identities. */
    public static String newId() { return UUID.randomUUID().toString(); }

    /** Canonical Instant ("YYYY-MM-DDTHH:MM:SSZ", years 0000..9999) of Unix seconds. */
    public static String instant(long epochSeconds) { return TasksWireText.formatInstant(epochSeconds); }

    /** Unix seconds of a canonical Instant; strict pattern and real calendar date. */
    public static long epochSeconds(String instant) { return TasksWireText.epochSeconds(instant, "instant"); }

    // ---- Enumerations ---------------------------------------------------------------------

    public enum OperationKind {
        LIST_CREATE("list.create", "list_id", "title", "sort_order", "created_at"),
        LIST_UPDATE("list.update", "list_id", "base_revision", "title", "sort_order"),
        /** base_members_revision is R4-only and optional (ListDeleteR4): present only when the delete is members-fenced. */
        LIST_DELETE("list.delete", "list_id", "base_revision", "base_members_revision"),
        TASK_CREATE("task.create", "task_id", "content", "created_at", "is_completed", "completed_at"),
        TASK_UPDATE("task.update", "task_id", "base_revision", "content"),
        TASK_COMPLETE("task.complete", "task_id", "base_revision", "invitation_id", "is_completed"),
        TASK_DELETE("task.delete", "task_id", "base_revision"),
        INVITATION_REQUEST("invitation.request", "task_id", "base_revision", "invitation_id", "recipient_tg_id"),
        INVITATION_RESPOND("invitation.respond", "task_id", "base_revision", "invitation_id", "decision"),
        INVITATION_REVOKE("invitation.revoke", "task_id", "base_revision", "invitation_id"),
        SENDER_BLOCK("sender.block", "invitation_id"),
        SENDER_UNBLOCK("sender.unblock", "sender_tg_id"),
        /** R4 only ($defs.TaskPatch, X-Tasks-Wire: 4): field-level edit of an own task; single route or batch. */
        TASK_PATCH("task.patch", "task_id", "set", "expect");

        private final String wire;
        /** Wire members after "kind", in $defs order; also the x-mutation-identity argument order. */
        final List<String> fields;

        OperationKind(String wire, String... fields) {
            this.wire = wire;
            this.fields = Collections.unmodifiableList(Arrays.asList(fields));
        }

        public String wire() { return wire; }

        public static OperationKind fromWire(String value) {
            for (OperationKind kind : values()) if (kind.wire.equals(value)) return kind;
            throw new Invalid("operation.kind");
        }

        /** Trailing members of {@link #fields} that may be absent (and are then not hashed either). */
        List<String> optional() {
            return this == LIST_DELETE ? Collections.singletonList("base_members_revision") : Collections.<String>emptyList();
        }

        /** list.create and task.create: the only kinds a batch item may mark origin "migration". */
        public boolean isCreate() { return this == LIST_CREATE || this == TASK_CREATE; }

        /** Receipt resource kind for this operation (x-mutation-identity.receipt_projection). */
        ResourceKind resourceKind() {
            switch (this) {
                case LIST_CREATE: case LIST_UPDATE: case LIST_DELETE: return ResourceKind.LIST;
                case SENDER_BLOCK: case SENDER_UNBLOCK: return ResourceKind.BLOCK;
                default: return ResourceKind.TASK;
            }
        }
    }

    public enum Decision {
        ACCEPT("accept"), DECLINE("decline"), LEAVE("leave");
        private final String wire;
        Decision(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static Decision fromWire(String value) {
            for (Decision item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("decision");
        }
    }

    public enum ProjectionKind {
        LIST("list"), TASK("task"), INVITATION("invitation"), BLOCK("block");
        private final String wire;
        ProjectionKind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static ProjectionKind fromWire(String value) {
            for (ProjectionKind item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("kind");
        }
    }

    public enum ViewerRole {
        OWNER("owner"), ASSIGNEE("assignee");
        private final String wire;
        ViewerRole(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static ViewerRole fromWire(String value) {
            for (ViewerRole item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("viewer_role");
        }
    }

    public enum AssignmentState {
        PENDING("pending"), ACCEPTED("accepted"), DECLINED("declined"), REVOKED("revoked"), LEFT("left"), EXPIRED("expired");
        private final String wire;
        AssignmentState(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static AssignmentState fromWire(String value) {
            for (AssignmentState item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("assignment.state");
        }
    }

    public enum Outcome {
        COMMITTED("committed"), UNCHANGED("unchanged");
        private final String wire;
        Outcome(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static Outcome fromWire(String value) {
            for (Outcome item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("receipt.outcome");
        }
    }

    public enum ResourceKind {
        LIST("list"), TASK("task"), BLOCK("block");
        private final String wire;
        ResourceKind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static ResourceKind fromWire(String value) {
            for (ResourceKind item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("receipt.resource_kind");
        }
    }

    public enum ChangeAction {
        UPSERT("upsert"), REMOVE("remove");
        private final String wire;
        ChangeAction(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static ChangeAction fromWire(String value) {
            for (ChangeAction item : values()) if (item.wire.equals(value)) return item;
            throw new Invalid("change.action");
        }
    }

    /**
     * $defs.Error.reason of tasks_conflict, where it is required. R4 adds field_changed (task.patch; the
     * error names the fields) and members_changed (members-fenced list.delete); both only on wire 4.
     */
    public enum ConflictReason {
        REVISION_CHANGED("revision_changed"), INVITATION_CHANGED("invitation_changed"),
        INVITATION_EXPIRED("invitation_expired"), COMPLETED_TASK("completed_task"),
        FIELD_CHANGED("field_changed"), MEMBERS_CHANGED("members_changed");
        private final String wire;
        ConflictReason(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        /** null for an unknown value (the caller decides whether that is a protocol violation). */
        public static ConflictReason fromWireOrNull(String value) {
            for (ConflictReason item : values()) if (item.wire.equals(value)) return item;
            return null;
        }
    }

    /**
     * $defs.ContentField: a Content member, declared in x-mutation-identity.content_order (task.patch keys,
     * and the fields of a field_changed conflict). A field's value has its Content type: list_id and
     * description String or null, title String, due_at Long or null, priority and sort_order Integer,
     * annotation {@link Annotation} or null.
     */
    public enum ContentField {
        LIST_ID("list_id"), TITLE("title"), DESCRIPTION("description"), DUE_AT("due_at"), PRIORITY("priority"),
        SORT_ORDER("sort_order"), ANNOTATION("annotation");
        private final String wire;
        ContentField(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        public static ContentField fromWire(String value) {
            ContentField field = fromWireOrNull(value);
            if (field == null) throw new Invalid("field");
            return field;
        }

        static ContentField fromWireOrNull(String value) {
            for (ContentField item : values()) if (item.wire.equals(value)) return item;
            return null;
        }

        /** This field's value in {@code content}, typed as a patch carries it. */
        public Object valueIn(Content content) {
            switch (this) {
                case LIST_ID: return content.listId;
                case TITLE: return content.title;
                case DESCRIPTION: return content.description;
                case DUE_AT: return content.dueAt;
                case PRIORITY: return content.priority;
                case SORT_ORDER: return content.sortOrder;
                default: return content.annotation;
            }
        }

        /** Schema rule of this field (its $defs.Content rule); returns the value in its canonical Java type. */
        Object value(Object raw, String where) {
            String field = where + "." + wire;
            switch (this) {
                case LIST_ID:
                    if (raw != null && !(raw instanceof String)) throw new Invalid(field);
                    return TasksWireText.nullableUuid((String) raw, field);
                case TITLE:
                    if (!(raw instanceof String)) throw new Invalid(field);
                    return TasksWireText.codePoints((String) raw, 1, 8192, field);
                case DESCRIPTION:
                    if (raw == null) return null;
                    if (!(raw instanceof String)) throw new Invalid(field);
                    return TasksWireText.codePoints((String) raw, 0, 16384, field);
                case DUE_AT: {
                    if (raw == null) return null;
                    long value = integral(raw, field);
                    if (value < 0 || value > MAX_DEADLINE) throw new Invalid(field);
                    return value;
                }
                case PRIORITY: {
                    long value = integral(raw, field);
                    if (value < 0 || value > 4) throw new Invalid(field);
                    return (int) value;
                }
                case SORT_ORDER: {
                    long value = integral(raw, field);
                    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new Invalid(field);
                    return (int) value;
                }
                default:
                    if (raw != null && !(raw instanceof Annotation)) throw new Invalid(field);
                    return raw;
            }
        }

        /** Client sealing rule of a value about to be stored (as Content.requireSealable). */
        void requireSealable(Object value, String where) {
            String field = where + "." + wire;
            if (this == TITLE) TasksWireText.sealText((String) value, 200, 8192, field);
            else if (this == DESCRIPTION && value != null) TasksWireText.sealLength((String) value, 4096, 16384, field);
            else if (this == ANNOTATION && value != null) TasksWireText.sealText(((Annotation) value).displayName, 256, 4096, field);
        }

        private static long integral(Object raw, String field) {
            if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) return ((Number) raw).longValue();
            throw new Invalid(field);
        }
    }

    // ---- Content ---------------------------------------------------------------------------

    public static final class Annotation {
        public final String telegramUserId;
        public final String displayName;

        public Annotation(String telegramUserId, String displayName) {
            this.telegramUserId = TasksWireText.positive(telegramUserId, "annotation.telegram_user_id");
            this.displayName = TasksWireText.codePoints(displayName, 1, 4096, "annotation.display_name");
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Annotation)) return false;
            Annotation that = (Annotation) other;
            return telegramUserId.equals(that.telegramUserId) && displayName.equals(that.displayName);
        }

        @Override public int hashCode() { return 31 * telegramUserId.hashCode() + displayName.hashCode(); }
    }

    /** $defs.Content. listId null is the virtual Cloud Inbox; description null and "" are distinct. */
    public static final class Content {
        public final String listId;
        public final String title;
        public final String description;
        public final Long dueAt;
        public final int priority;
        public final int sortOrder;
        public final Annotation annotation;

        public Content(String listId, String title, String description, Long dueAt, int priority, int sortOrder, Annotation annotation) {
            this.listId = TasksWireText.nullableUuid(listId, "content.list_id");
            this.title = TasksWireText.codePoints(title, 1, 8192, "content.title");
            this.description = description == null ? null : TasksWireText.codePoints(description, 0, 16384, "content.description");
            if (dueAt != null && (dueAt < 0 || dueAt > MAX_DEADLINE)) throw new Invalid("content.due_at");
            if (priority < 0 || priority > 4) throw new Invalid("content.priority");
            this.dueAt = dueAt;
            this.priority = priority;
            this.sortOrder = sortOrder;
            this.annotation = annotation;
        }

        /** Client sealing rules (limits/visible_text) before a mutation carrying this content is sealed. */
        void requireSealable() {
            TasksWireText.sealText(title, 200, 8192, "content.title");
            if (description != null) TasksWireText.sealLength(description, 4096, 16384, "content.description");
            if (annotation != null) TasksWireText.sealText(annotation.displayName, 256, 4096, "annotation.display_name");
        }

        /** x-mutation-identity.content_order, annotation as annotation_order. */
        List<Object> businessArray() {
            Object note = annotation == null ? null : Arrays.<Object>asList(annotation.telegramUserId, annotation.displayName);
            return Arrays.<Object>asList(listId, title, description, dueAt, priority, sortOrder, note);
        }

        /**
         * This content with {@code values} (e.g. a patch's set) in place of its own; schema rules only. The
         * R3 re-encoding of a task.patch is task.update with the shadow content applying the set values.
         */
        public Content applying(Map<ContentField, Object> values) {
            if (values == null) throw new Invalid("set");
            Object[] fields = new Object[ContentField.values().length];
            for (ContentField field : ContentField.values()) fields[field.ordinal()] = field.valueIn(this);
            for (Map.Entry<ContentField, Object> entry : values.entrySet()) {
                if (entry.getKey() == null) throw new Invalid("set");
                fields[entry.getKey().ordinal()] = entry.getKey().value(entry.getValue(), "set");
            }
            return new Content((String) fields[0], (String) fields[1], (String) fields[2], (Long) fields[3], (Integer) fields[4],
                    (Integer) fields[5], (Annotation) fields[6]);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Content && businessArray().equals(((Content) other).businessArray());
        }

        @Override public int hashCode() { return businessArray().hashCode(); }
    }

    // ---- Operations ------------------------------------------------------------------------

    /**
     * One of the 12 R3 $defs.Operation kinds, or the R4 task.patch. Construct only through the static
     * factories, which also apply the client sealing rules; decoders (restore) use the schema rules only.
     */
    public abstract static class Operation {
        public final OperationKind kind;

        Operation(OperationKind kind) { this.kind = kind; }

        /** List or task UUID; invitation UUID for sender.block; sender Telegram id for sender.unblock. */
        public abstract String resourceId();

        /**
         * Values in kind.fields order (an absent optional trailing member is left out); content stays a
         * Content (object on the wire, array in the hash), a patch's set/expect a content-ordered Map
         * (object on the wire, [[field, value]…] in the hash).
         */
        abstract List<Object> values();

        /**
         * Carries something only an X-Tasks-Wire: 4 server accepts (task.patch, list.delete with
         * base_members_revision). An R3 server answers it 422 tasks_invalid_request, a definite non-commit
         * (x-wire-negotiation.downgrade); every other operation is valid on every wire.
         */
        public boolean requiresWire4() { return false; }

        @Override public final boolean equals(Object other) {
            return other instanceof Operation && ((Operation) other).kind == kind && TasksWireEncode.business(this).equals(TasksWireEncode.business((Operation) other));
        }

        @Override public final int hashCode() { return 31 * kind.hashCode() + TasksWireEncode.business(this).hashCode(); }

        public static ListCreate listCreate(String listId, String title, int sortOrder, String createdAt) {
            ListCreate result = new ListCreate(listId, title, sortOrder, createdAt);
            TasksWireText.sealText(result.title, 100, 8192, "title");
            return result;
        }

        public static ListUpdate listUpdate(String listId, String baseRevision, String title, int sortOrder) {
            ListUpdate result = new ListUpdate(listId, baseRevision, title, sortOrder);
            TasksWireText.sealText(result.title, 100, 8192, "title");
            return result;
        }

        public static ListDelete listDelete(String listId, String baseRevision) { return new ListDelete(listId, baseRevision, null); }

        /** R4 members-fenced list.delete (ListDeleteR4): a different members_revision answers 409 members_changed. */
        public static ListDelete listDelete(String listId, String baseRevision, String baseMembersRevision) {
            if (baseMembersRevision == null) throw new Invalid("base_members_revision");
            return new ListDelete(listId, baseRevision, baseMembersRevision);
        }

        /**
         * R4 task.patch: {@code set} holds the new values and must satisfy the sealing rules (it will be
         * stored); {@code expect} holds the values the client last saw (its server shadow) and only the
         * schema rules, so a stricter local rule never makes a stored task unpatchable. {@code expect} has
         * exactly set's fields; both are kept in content order whatever the maps' own order.
         */
        public static TaskPatch taskPatch(String taskId, Map<ContentField, Object> set, Map<ContentField, Object> expect) {
            TaskPatch result = new TaskPatch(taskId, set, expect);
            for (Map.Entry<ContentField, Object> entry : result.set.entrySet()) entry.getKey().requireSealable(entry.getValue(), "set");
            return result;
        }

        public static TaskCreate taskCreate(String taskId, Content content, String createdAt, boolean isCompleted, String completedAt) {
            TaskCreate result = new TaskCreate(taskId, content, createdAt, isCompleted, completedAt);
            result.content.requireSealable();
            return result;
        }

        public static TaskUpdate taskUpdate(String taskId, String baseRevision, Content content) {
            TaskUpdate result = new TaskUpdate(taskId, baseRevision, content);
            result.content.requireSealable();
            return result;
        }

        public static TaskComplete taskComplete(String taskId, String baseRevision, String invitationId, boolean isCompleted) {
            return new TaskComplete(taskId, baseRevision, invitationId, isCompleted);
        }

        public static TaskDelete taskDelete(String taskId, String baseRevision) { return new TaskDelete(taskId, baseRevision); }

        public static InvitationRequest invitationRequest(String taskId, String baseRevision, String invitationId, String recipientTgId) {
            return new InvitationRequest(taskId, baseRevision, invitationId, recipientTgId);
        }

        public static InvitationRespond invitationRespond(String taskId, String baseRevision, String invitationId, Decision decision) {
            return new InvitationRespond(taskId, baseRevision, invitationId, decision);
        }

        public static InvitationRevoke invitationRevoke(String taskId, String baseRevision, String invitationId) {
            return new InvitationRevoke(taskId, baseRevision, invitationId);
        }

        public static SenderBlock senderBlock(String invitationId) { return new SenderBlock(invitationId); }

        public static SenderUnblock senderUnblock(String senderTgId) { return new SenderUnblock(senderTgId); }

        public static final class ListCreate extends Operation {
            public final String listId, title, createdAt;
            public final int sortOrder;
            ListCreate(String listId, String title, int sortOrder, String createdAt) {
                super(OperationKind.LIST_CREATE);
                this.listId = TasksWireText.uuid(listId, "list_id");
                this.title = TasksWireText.codePoints(title, 1, 8192, "title");
                this.sortOrder = sortOrder;
                this.createdAt = TasksWireText.nullableInstant(createdAt, "created_at");
            }
            @Override public String resourceId() { return listId; }
            @Override List<Object> values() { return Arrays.<Object>asList(listId, title, sortOrder, createdAt); }
        }

        public static final class ListUpdate extends Operation {
            public final String listId, baseRevision, title;
            public final int sortOrder;
            ListUpdate(String listId, String baseRevision, String title, int sortOrder) {
                super(OperationKind.LIST_UPDATE);
                this.listId = TasksWireText.uuid(listId, "list_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                this.title = TasksWireText.codePoints(title, 1, 8192, "title");
                this.sortOrder = sortOrder;
            }
            @Override public String resourceId() { return listId; }
            @Override List<Object> values() { return Arrays.<Object>asList(listId, baseRevision, title, sortOrder); }
        }

        public static final class ListDelete extends Operation {
            public final String listId, baseRevision;
            /** R4 members fence; null for the R3 shape (no key on the wire, nothing in the hash). */
            public final String baseMembersRevision;
            ListDelete(String listId, String baseRevision, String baseMembersRevision) {
                super(OperationKind.LIST_DELETE);
                this.listId = TasksWireText.uuid(listId, "list_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                this.baseMembersRevision = baseMembersRevision == null ? null
                        : TasksWireText.positive(baseMembersRevision, "base_members_revision");
            }
            @Override public String resourceId() { return listId; }
            @Override List<Object> values() {
                return baseMembersRevision == null ? Arrays.<Object>asList(listId, baseRevision)
                        : Arrays.<Object>asList(listId, baseRevision, baseMembersRevision);
            }
            @Override public boolean requiresWire4() { return baseMembersRevision != null; }
        }

        public static final class TaskCreate extends Operation {
            public final String taskId, createdAt, completedAt;
            public final Content content;
            public final boolean isCompleted;
            TaskCreate(String taskId, Content content, String createdAt, boolean isCompleted, String completedAt) {
                super(OperationKind.TASK_CREATE);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                if (content == null) throw new Invalid("content");
                this.content = content;
                this.createdAt = TasksWireText.nullableInstant(createdAt, "created_at");
                this.isCompleted = isCompleted;
                // is_completed=false requires completed_at=null; true accepts an Instant or null (unknown).
                if (!isCompleted && completedAt != null) throw new Invalid("completed_at");
                this.completedAt = TasksWireText.nullableInstant(completedAt, "completed_at");
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, content, createdAt, isCompleted, completedAt); }
        }

        public static final class TaskUpdate extends Operation {
            public final String taskId, baseRevision;
            public final Content content;
            TaskUpdate(String taskId, String baseRevision, Content content) {
                super(OperationKind.TASK_UPDATE);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                if (content == null) throw new Invalid("content");
                this.content = content;
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, baseRevision, content); }
        }

        public static final class TaskComplete extends Operation {
            public final String taskId, baseRevision, invitationId;
            public final boolean isCompleted;
            TaskComplete(String taskId, String baseRevision, String invitationId, boolean isCompleted) {
                super(OperationKind.TASK_COMPLETE);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                this.invitationId = TasksWireText.nullableUuid(invitationId, "invitation_id");
                this.isCompleted = isCompleted;
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, baseRevision, invitationId, isCompleted); }
        }

        public static final class TaskDelete extends Operation {
            public final String taskId, baseRevision;
            TaskDelete(String taskId, String baseRevision) {
                super(OperationKind.TASK_DELETE);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, baseRevision); }
        }

        public static final class InvitationRequest extends Operation {
            public final String taskId, baseRevision, invitationId, recipientTgId;
            InvitationRequest(String taskId, String baseRevision, String invitationId, String recipientTgId) {
                super(OperationKind.INVITATION_REQUEST);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                this.invitationId = TasksWireText.uuid(invitationId, "invitation_id");
                this.recipientTgId = TasksWireText.positive(recipientTgId, "recipient_tg_id");
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, baseRevision, invitationId, recipientTgId); }
        }

        public static final class InvitationRespond extends Operation {
            public final String taskId, baseRevision, invitationId;
            public final Decision decision;
            InvitationRespond(String taskId, String baseRevision, String invitationId, Decision decision) {
                super(OperationKind.INVITATION_RESPOND);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                this.invitationId = TasksWireText.uuid(invitationId, "invitation_id");
                if (decision == null) throw new Invalid("decision");
                this.decision = decision;
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, baseRevision, invitationId, decision.wire()); }
        }

        public static final class InvitationRevoke extends Operation {
            public final String taskId, baseRevision, invitationId;
            InvitationRevoke(String taskId, String baseRevision, String invitationId) {
                super(OperationKind.INVITATION_REVOKE);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.baseRevision = TasksWireText.positive(baseRevision, "base_revision");
                this.invitationId = TasksWireText.uuid(invitationId, "invitation_id");
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, baseRevision, invitationId); }
        }

        public static final class SenderBlock extends Operation {
            public final String invitationId;
            SenderBlock(String invitationId) {
                super(OperationKind.SENDER_BLOCK);
                this.invitationId = TasksWireText.uuid(invitationId, "invitation_id");
            }
            @Override public String resourceId() { return invitationId; }
            @Override List<Object> values() { return Collections.<Object>singletonList(invitationId); }
        }

        public static final class SenderUnblock extends Operation {
            public final String senderTgId;
            SenderUnblock(String senderTgId) {
                super(OperationKind.SENDER_UNBLOCK);
                this.senderTgId = TasksWireText.positive(senderTgId, "sender_tg_id");
            }
            @Override public String resourceId() { return senderTgId; }
            @Override List<Object> values() { return Collections.<Object>singletonList(senderTgId); }
        }

        /**
         * R4 $defs.TaskPatch. For each field the server requires the stored value to equal expect (or
         * already set), else 409 tasks_conflict field_changed naming those fields; only stored values
         * answer 200 unchanged. Owner only: anyone else gets 404.
         */
        public static final class TaskPatch extends Operation {
            public final String taskId;
            /** New values, unmodifiable, keys in content order; a null value is an explicit JSON null. */
            public final Map<ContentField, Object> set;
            /** The values the client last saw, unmodifiable, exactly set's keys in content order. */
            public final Map<ContentField, Object> expect;
            TaskPatch(String taskId, Map<ContentField, Object> set, Map<ContentField, Object> expect) {
                super(OperationKind.TASK_PATCH);
                this.taskId = TasksWireText.uuid(taskId, "task_id");
                this.set = patchFields(set, "set");
                this.expect = patchFields(expect, "expect");
                if (!this.set.keySet().equals(this.expect.keySet())) throw new Invalid("expect");
            }
            @Override public String resourceId() { return taskId; }
            @Override List<Object> values() { return Arrays.<Object>asList(taskId, set, expect); }
            @Override public boolean requiresWire4() { return true; }

            /** The patched fields in content order. */
            public List<ContentField> fields() { return Collections.unmodifiableList(new ArrayList<>(set.keySet())); }

            /** $defs.PatchFields: non-empty, every value under its field's schema rule, re-ordered to content order. */
            private static Map<ContentField, Object> patchFields(Map<ContentField, Object> fields, String where) {
                if (fields == null || fields.isEmpty()) throw new Invalid(where);
                LinkedHashMap<ContentField, Object> ordered = new LinkedHashMap<>();
                for (ContentField field : ContentField.values()) {
                    if (fields.containsKey(field)) ordered.put(field, field.value(fields.get(field), where));
                }
                if (ordered.size() != fields.size()) throw new Invalid(where); // a null (or foreign) key
                return Collections.unmodifiableMap(ordered);
            }
        }
    }

    // ---- Sealed mutation -------------------------------------------------------------------

    /**
     * One durable business command for one backend principal: exact request body bytes (Idempotency-Key =
     * mutationId) and the canonical business bytes/hash of x-mutation-identity. Never contains
     * credentials, proof, device headers or server_time. Bound to its principal: transports must refuse
     * to send it for another principal instead of retagging.
     */
    public static final class Mutation {
        public final String principal;
        public final String mutationId;
        public final Operation operation;
        private final byte[] body;
        private final byte[] business;
        private final String hash;

        private Mutation(String principal, String mutationId, Operation operation) {
            this.principal = TasksWireText.principal(principal);
            this.mutationId = TasksWireText.uuid(mutationId, "mutation_id");
            if (operation == null) throw new Invalid("operation");
            this.operation = operation;
            this.body = TasksWireEncode.requestBody(this.mutationId, operation);
            if (body.length > MAX_MUTATION_BYTES) throw new Invalid("size");
            this.business = TasksWireEncode.businessBytes(this.principal, this.mutationId, operation);
            this.hash = sha256Hex(business);
        }

        /** Seals an operation built by an {@link Operation} factory under the current binding principal. */
        public static Mutation seal(String principal, String mutationId, Operation operation) {
            return new Mutation(principal, mutationId, operation);
        }

        /**
         * Restores a persisted sealed body: strict parse (UTF-8, duplicate keys, unknown members, types,
         * ranges) and the re-encoding must be byte-identical, so the same bytes are resent under the same id.
         */
        public static Mutation restore(String principal, byte[] sealedBody) {
            String text = TasksWireJson.utf8(sealedBody, MAX_MUTATION_BYTES);
            TasksWireDecode.Request request = TasksWireDecode.request(TasksWireJson.parse(text, MAX_MUTATION_BYTES));
            Mutation restored = new Mutation(principal, request.mutationId, request.operation);
            if (!Arrays.equals(restored.body, sealedBody)) throw new Invalid("not_canonical");
            return restored;
        }

        /** Exact UTF-8 request body (copy). */
        public byte[] body() { return body.clone(); }

        /** Canonical business bytes (copy). */
        public byte[] businessBytes() { return business.clone(); }

        /** Lowercase hex SHA-256 of {@link #businessBytes()}. */
        public String businessHash() { return hash; }

        /** See {@link Operation#requiresWire4()}. */
        public boolean requiresWire4() { return operation.requiresWire4(); }

        /** UTF-8 size of this mutation as a batch item with {@code origin} (see {@link Batch}). */
        public int itemBytes(Origin origin) {
            int inner = body.length - MUTATION_PREFIX.length - 1;
            return origin == Origin.MIGRATION ? inner + MIGRATION_PREFIX.length - 1 : inner;
        }

        /** Appends the batch item: the sealed mutation object exactly, behind "origin":"migration" for a migration create. */
        void appendItem(ByteArrayOutputStream out, Origin origin) {
            int start = MUTATION_PREFIX.length, end = body.length - 1;
            if (origin == Origin.MIGRATION) {
                out.write(MIGRATION_PREFIX, 0, MIGRATION_PREFIX.length);
                start++; // the sealed object's own '{'
            }
            out.write(body, start, end - start);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Mutation && principal.equals(((Mutation) other).principal) && Arrays.equals(body, ((Mutation) other).body);
        }

        @Override public int hashCode() { return 31 * principal.hashCode() + Arrays.hashCode(body); }
    }

    /** {"mutation": in front of every sealed body; the mutation object itself follows, then the closing '}'. */
    private static final byte[] MUTATION_PREFIX = ascii("{\"mutation\":");
    /** Replaces a migration item's leading '{'. */
    private static final byte[] MIGRATION_PREFIX = ascii("{\"origin\":\"migration\",");
    private static final byte[] BATCH_HEAD = ascii("{\"schema_version\":1,\"batch_id\":\"");
    private static final byte[] BATCH_MUTATIONS = ascii("\",\"mutations\":[");
    private static final byte[] BATCH_TAIL = ascii("]}");
    private static final int UUID_BYTES = 36;

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    // ---- Batch (R4) --------------------------------------------------------------------------

    /** $defs.BatchItem.origin. Not hashed: a later single-route resend of the same mutation is a replay. */
    public enum Origin {
        USER("user"),
        /** A create of data that existed locally before the account's cloud (import budget); list.create/task.create only. */
        MIGRATION("migration");
        private final String wire;
        Origin(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /**
     * POST /v1/tasks/mutations/batch (R4 only): 1..100 sealed mutations of ONE principal sent as one request,
     * each executed and answered on its own (Idempotency-Key = batchId). The body is
     * {"schema_version":1,"batch_id":"…","mutations":[item…]}; a user item is its mutation's sealed object
     * byte for byte (the single-route body without its {"mutation": wrapper), a migration item
     * {"origin":"migration", followed by the same bytes without their first '{'. The sealed bytes therefore
     * always travel unchanged, and a single-route resend of the same mutation is a replay. Android never sends
     * a creation_proof. Validated when made; nothing transport-related is part of it.
     */
    public static final class Batch {
        public static final class Item {
            public final Mutation mutation;
            public final Origin origin;

            public Item(Mutation mutation, Origin origin) {
                if (mutation == null) throw new Invalid("mutation");
                if (origin == null) throw new Invalid("origin");
                this.mutation = mutation;
                this.origin = origin;
            }
        }

        public final String batchId;
        /** The principal every item was sealed for. */
        public final String principal;
        /** Unmodifiable, in request (and results) order. */
        public final List<Item> items;
        private final byte[] body;

        private Batch(String batchId, String principal, List<Item> items, byte[] body) {
            this.batchId = batchId;
            this.principal = principal;
            this.items = items;
            this.body = body;
        }

        /**
         * Refuses 0 or more than 100 items, items of different principals, a repeated mutation id, a migration
         * origin on anything but a create, and a body over 524288 bytes.
         */
        public static Batch of(String batchId, List<Item> items) {
            String id = TasksWireText.uuid(batchId, "batch_id");
            if (items == null || items.isEmpty() || items.size() > MAX_BATCH_ITEMS) throw new Invalid("mutations");
            List<Item> copy = new ArrayList<>(items);
            Set<String> seen = new HashSet<>();
            String principal = null;
            for (Item item : copy) {
                if (item == null) throw new Invalid("mutations");
                if (principal == null) principal = item.mutation.principal;
                else if (!principal.equals(item.mutation.principal)) throw new Invalid("principal");
                if (!seen.add(item.mutation.mutationId)) throw new Invalid("mutations.mutation_id");
                if (item.origin == Origin.MIGRATION && !item.mutation.operation.kind.isCreate()) throw new Invalid("mutations.origin");
            }
            if (bodyBytes(copy) > MAX_BATCH_BYTES) throw new Invalid("size");
            ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes(copy));
            out.write(BATCH_HEAD, 0, BATCH_HEAD.length);
            byte[] idBytes = ascii(id);
            out.write(idBytes, 0, idBytes.length);
            out.write(BATCH_MUTATIONS, 0, BATCH_MUTATIONS.length);
            for (int i = 0; i < copy.size(); i++) {
                if (i > 0) out.write(',');
                copy.get(i).mutation.appendItem(out, copy.get(i).origin);
            }
            out.write(BATCH_TAIL, 0, BATCH_TAIL.length);
            return new Batch(id, principal, Collections.unmodifiableList(copy), out.toByteArray());
        }

        /**
         * UTF-8 size of the body these items make (85 envelope bytes, the separators and every item), for
         * sizing a batch before it is made; validity is not checked here.
         */
        public static int bodyBytes(List<Item> items) {
            int total = BATCH_HEAD.length + UUID_BYTES + BATCH_MUTATIONS.length + BATCH_TAIL.length + Math.max(items.size() - 1, 0);
            for (Item item : items) total += item.mutation.itemBytes(item.origin);
            return total;
        }

        /** Exact UTF-8 request body (copy). */
        public byte[] body() { return body.clone(); }

        public int bodyBytes() { return body.length; }
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- Projections -----------------------------------------------------------------------

    /** $defs.Projection: ListItem, TaskItem, InvitationItem or BlockItem. Equal by canonical encoding. */
    public abstract static class Projection {
        public final ProjectionKind kind;
        public final String id;

        Projection(ProjectionKind kind, String id) {
            this.kind = kind;
            this.id = id;
        }

        @Override public final boolean equals(Object other) {
            return other instanceof Projection && encodeProjection(this).equals(encodeProjection((Projection) other));
        }

        @Override public final int hashCode() { return encodeProjection(this).hashCode(); }
    }

    public static final class ListItem extends Projection {
        public final String revision, title, createdAt, updatedAt;
        public final int sortOrder;
        /**
         * R4 ($defs.ListR4): advances on every member-task and invitation change, while revision covers
         * title and sort_order only. null in an R3 projection (no key on the wire).
         */
        public final String membersRevision;

        public ListItem(String id, String revision, String title, int sortOrder, String createdAt, String updatedAt) {
            this(id, revision, null, title, sortOrder, createdAt, updatedAt);
        }

        public ListItem(String id, String revision, String membersRevision, String title, int sortOrder, String createdAt, String updatedAt) {
            super(ProjectionKind.LIST, TasksWireText.uuid(id, "id"));
            this.revision = TasksWireText.positive(revision, "revision");
            this.membersRevision = membersRevision == null ? null : TasksWireText.positive(membersRevision, "members_revision");
            this.title = TasksWireText.codePoints(title, 1, 8192, "title");
            this.sortOrder = sortOrder;
            this.createdAt = TasksWireText.instant(createdAt, "created_at");
            this.updatedAt = TasksWireText.instant(updatedAt, "updated_at");
        }
    }

    public static final class Assignment {
        public final String invitationId, recipientTgId, expiresAt;
        public final AssignmentState state;

        public Assignment(String invitationId, String recipientTgId, AssignmentState state, String expiresAt) {
            this.invitationId = TasksWireText.uuid(invitationId, "assignment.invitation_id");
            this.recipientTgId = TasksWireText.positive(recipientTgId, "assignment.recipient_tg_id");
            if (state == null) throw new Invalid("assignment.state");
            this.state = state;
            this.expiresAt = TasksWireText.instant(expiresAt, "assignment.expires_at");
        }
    }

    public static final class TaskItem extends Projection {
        public final String revision, ownerTgId, completedAt, createdAt, updatedAt;
        public final ViewerRole viewerRole;
        public final Content content;
        public final boolean isCompleted;
        public final Assignment assignment;

        public TaskItem(String id, String revision, String ownerTgId, ViewerRole viewerRole, Content content, boolean isCompleted,
                        String completedAt, String createdAt, String updatedAt, Assignment assignment) {
            super(ProjectionKind.TASK, TasksWireText.uuid(id, "id"));
            this.revision = TasksWireText.positive(revision, "revision");
            this.ownerTgId = TasksWireText.positive(ownerTgId, "owner_tg_id");
            if (viewerRole == null) throw new Invalid("viewer_role");
            if (content == null) throw new Invalid("content");
            this.viewerRole = viewerRole;
            this.content = content;
            this.isCompleted = isCompleted;
            if (!isCompleted && completedAt != null) throw new Invalid("completed_at");
            this.completedAt = TasksWireText.nullableInstant(completedAt, "completed_at");
            this.createdAt = TasksWireText.instant(createdAt, "created_at");
            this.updatedAt = TasksWireText.instant(updatedAt, "updated_at");
            // An assignee never sees the owner's list or private annotation, and only while accepted.
            if (viewerRole == ViewerRole.ASSIGNEE && (content.listId != null || content.annotation != null
                    || assignment == null || assignment.state != AssignmentState.ACCEPTED)) throw new Invalid("viewer_role");
            this.assignment = assignment;
        }
    }

    public static final class InvitationItem extends Projection {
        public final String taskId, taskRevision, ownerTgId, title, expiresAt;
        public final Long dueAt;

        public InvitationItem(String id, String taskId, String taskRevision, String ownerTgId, String title, Long dueAt, String expiresAt) {
            super(ProjectionKind.INVITATION, TasksWireText.uuid(id, "id"));
            this.taskId = TasksWireText.uuid(taskId, "task_id");
            this.taskRevision = TasksWireText.positive(taskRevision, "task_revision");
            this.ownerTgId = TasksWireText.positive(ownerTgId, "owner_tg_id");
            this.title = TasksWireText.codePoints(title, 1, 8192, "title");
            if (dueAt != null && (dueAt < 0 || dueAt > MAX_DEADLINE)) throw new Invalid("due_at");
            this.dueAt = dueAt;
            this.expiresAt = TasksWireText.instant(expiresAt, "expires_at");
        }
    }

    public static final class BlockItem extends Projection {
        public final String createdAt;

        public BlockItem(String id, String createdAt) {
            super(ProjectionKind.BLOCK, TasksWireText.positive(id, "id"));
            this.createdAt = TasksWireText.instant(createdAt, "created_at");
        }
    }

    /** Canonical compact wire JSON of a projection, for durable shadow storage. */
    public static String encodeProjection(Projection projection) {
        if (projection == null) throw new Invalid("item");
        return TasksWireJson.canonical(TasksWireEncode.projection(projection));
    }

    /** Strict decode of one stored or received projection object. */
    public static Projection decodeProjection(String json) {
        return TasksWireDecode.projection(TasksWireJson.parse(json, MAX_PROJECTION_CHARS), "item");
    }

    // ---- Responses -------------------------------------------------------------------------

    public static final class SnapshotResponse {
        public final List<Projection> items;
        public final String nextCursor;
        public final String changesCursor;
        public final String serverTime;
        /** The answer's X-Tasks-Wire echo (x-wire-negotiation.response_echo); null when absent. */
        public final Integer serverWire;

        SnapshotResponse(List<Projection> items, String nextCursor, String changesCursor, String serverTime, Integer serverWire) {
            this.items = items;
            this.nextCursor = nextCursor;
            this.changesCursor = changesCursor;
            this.serverTime = serverTime;
            this.serverWire = serverWire;
        }
    }

    public static final class Change {
        public final String position;
        public final ChangeAction action;
        public final ProjectionKind kind;
        public final String id;
        /** The projection for UPSERT; null for REMOVE. */
        public final Projection item;

        Change(String position, ChangeAction action, ProjectionKind kind, String id, Projection item) {
            this.position = position;
            this.action = action;
            this.kind = kind;
            this.id = id;
            this.item = item;
        }
    }

    public static final class ChangesResponse {
        public final List<Change> changes;
        public final String nextCursor;
        public final boolean hasMore;
        public final String serverTime;
        /** The answer's X-Tasks-Wire echo; null when absent. */
        public final Integer serverWire;

        ChangesResponse(List<Change> changes, String nextCursor, boolean hasMore, String serverTime, Integer serverWire) {
            this.changes = changes;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
            this.serverTime = serverTime;
            this.serverWire = serverWire;
        }
    }

    public static final class Receipt {
        public final String mutationId;
        public final OperationKind operation;
        public final Outcome outcome;
        public final ResourceKind resourceKind;
        public final String resourceId;
        /** null exactly for resource_kind=block. */
        public final String resourceRevision;
        public final String committedAt;

        Receipt(String mutationId, OperationKind operation, Outcome outcome, ResourceKind resourceKind, String resourceId,
                String resourceRevision, String committedAt) {
            this.mutationId = mutationId;
            this.operation = operation;
            this.outcome = outcome;
            this.resourceKind = resourceKind;
            this.resourceId = resourceId;
            this.resourceRevision = resourceRevision;
            this.committedAt = committedAt;
        }
    }

    public static final class MutationResponse {
        public final Receipt receipt;
        public final String serverTime;
        /** The answer's X-Tasks-Wire echo; null when absent. */
        public final Integer serverWire;

        MutationResponse(Receipt receipt, String serverTime, Integer serverWire) {
            this.receipt = receipt;
            this.serverTime = serverTime;
            this.serverWire = serverWire;
        }
    }

    /**
     * One $defs.BatchItemResult, in the request's order. RECEIPT: committed, unchanged or a durable replay
     * (checked to answer exactly this item's mutation). ERROR: the item's own status and error, which
     * classify exactly as the same answer on the single route would. SKIPPED: never started; resend later
     * with the SAME mutation, no attempt counted.
     */
    public static final class BatchItemResult {
        public enum Kind { RECEIPT, ERROR, SKIPPED }

        public final String mutationId;
        public final Kind kind;
        /** 200 (RECEIPT), the item's status (ERROR: 401, 403, 404, 409, 422, 429 or 503), 0 (SKIPPED, wire null). */
        public final int status;
        /** RECEIPT only. */
        public final Receipt receipt;
        /** ERROR only: a strict $defs.BatchItemError carrying the batch's server_time (see ErrorEnvelope.retryAfter). */
        public final ErrorEnvelope error;

        BatchItemResult(String mutationId, Kind kind, int status, Receipt receipt, ErrorEnvelope error) {
            this.mutationId = mutationId;
            this.kind = kind;
            this.status = status;
            this.receipt = receipt;
            this.error = error;
        }
    }

    /** $defs.BatchResponse, validated against the batch it answers. */
    public static final class BatchResponse {
        public final String batchId;
        /** Unmodifiable; exactly one per request item, in request order. */
        public final List<BatchItemResult> results;
        /** Seconds (>= 1) of the item that stopped the batch with a 429 or 503; null otherwise. */
        public final Integer retryAfter;
        public final String serverTime;
        /** The answer's X-Tasks-Wire echo; null when absent. */
        public final Integer serverWire;

        BatchResponse(String batchId, List<BatchItemResult> results, Integer retryAfter, String serverTime, Integer serverWire) {
            this.batchId = batchId;
            this.results = results;
            this.retryAfter = retryAfter;
            this.serverTime = serverTime;
            this.serverWire = serverWire;
        }
    }

    /**
     * An error body as far as it can be trusted. {@code strict} is true only for an exact $defs.Error
     * object (keys, types and $defs.ErrorRules: which reason, fields and effective_at each code carries);
     * existing middleware envelopes are accepted with strict=false. A batch item's error is always strict
     * (it is part of a 200 body, decoded as $defs.BatchItemError). The message is never kept (no input
     * echo reaches app state).
     */
    public static final class ErrorEnvelope {
        public final String code;
        /** The reason when it is a known tasks_conflict reason, else null. */
        public final ConflictReason reason;
        /** Validated Instant or null; for a batch item, the batch's server_time. */
        public final String serverTime;
        public final boolean strict;
        /**
         * The body's reason exactly as sent (1..80 scalars), else null: a Tasks reason (a conflict reason,
         * withdrawn, batch_disabled, migration_paused) or the reason of an existing 401 envelope (account_erased).
         */
        public final String wireReason;
        /** reason field_changed only: the conflicting fields in content order; empty otherwise. Unmodifiable. */
        public final List<ContentField> fields;
        /** tasks_consent_required only: when the standing withdrawal took effect (an Instant); null otherwise. */
        public final String effectiveAt;
        /** A 429/503 batch item's retry_after (its own, else the batch's); 0 otherwise (envelopes use Retry-After). */
        public final int retryAfter;

        ErrorEnvelope(String code, ConflictReason reason, String serverTime, boolean strict, String wireReason,
                      List<ContentField> fields, String effectiveAt, int retryAfter) {
            this.code = code;
            this.reason = reason;
            this.serverTime = serverTime;
            this.strict = strict;
            this.wireReason = wireReason;
            this.fields = fields;
            this.effectiveAt = effectiveAt;
            this.retryAfter = retryAfter;
        }
    }

    public static SnapshotResponse decodeSnapshotResponse(String json, int requestedLimit) {
        return decodeSnapshotResponse(json, requestedLimit, null);
    }

    /** {@code serverWire}: the answer's X-Tasks-Wire header, recorded on the result (null when absent). */
    public static SnapshotResponse decodeSnapshotResponse(String json, int requestedLimit, Integer serverWire) {
        return TasksWireDecode.snapshot(TasksWireJson.parse(json, maxPageBytes(requestedLimit)), requestedLimit, serverWire);
    }

    public static ChangesResponse decodeChangesResponse(String json, int requestedLimit) {
        return decodeChangesResponse(json, requestedLimit, null);
    }

    public static ChangesResponse decodeChangesResponse(String json, int requestedLimit, Integer serverWire) {
        return TasksWireDecode.changes(TasksWireJson.parse(json, maxPageBytes(requestedLimit)), requestedLimit, serverWire);
    }

    /** The receipt must identify exactly the sent mutation (id, kind, resource kind and id). */
    public static MutationResponse decodeMutationResponse(String json, Mutation sent) {
        return decodeMutationResponse(json, sent, null);
    }

    public static MutationResponse decodeMutationResponse(String json, Mutation sent, Integer serverWire) {
        if (sent == null) throw new Invalid("mutation");
        return TasksWireDecode.mutationResponse(TasksWireJson.parse(json, MAX_DOCUMENT_CHARS), sent, serverWire);
    }

    /**
     * Strict $defs.BatchResponse as the answer to exactly {@code sent}: its batch_id, one result per item in
     * request order with that item's mutation id, every receipt for its own item's mutation, every error a
     * valid $defs.BatchItemError under an item status. Anything else is {@link Invalid}: the whole batch's
     * outcome is then unknown (resend the same mutations).
     */
    public static BatchResponse decodeBatchResponse(String json, Batch sent, Integer serverWire) {
        if (sent == null) throw new Invalid("batch");
        return TasksWireDecode.batchResponse(TasksWireJson.parse(json, MAX_BATCH_RESPONSE_BYTES), sent, serverWire);
    }

    /** Never throws: null when the body is not a strict-JSON object with a valid error code. */
    public static ErrorEnvelope decodeErrorEnvelope(String json) {
        try {
            return TasksWireDecode.error(TasksWireJson.parse(json, MAX_DOCUMENT_CHARS));
        } catch (Invalid invalid) {
            return null;
        }
    }
}

package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict tree -> model decoding of the R3 $defs and the R4 additions: exact member sets
 * (additionalProperties=false, no missing-field defaults; an optional member only where the schema has
 * one), exact JSON types (a string ID is never accepted as a number or vice versa), integer literals
 * only where the schema says integer, and cross-field rules.
 */
final class TasksWireDecode {
    private TasksWireDecode() { }

    static final class Request {
        final String mutationId;
        final TasksWire.Operation operation;
        Request(String mutationId, TasksWire.Operation operation) {
            this.mutationId = mutationId;
            this.operation = operation;
        }
    }

    /** One JSON object whose member names must be exactly required ∪ (a subset of) optional (also TasksRealtimeWire). */
    static final class Fields {
        private final Map<String, Object> map;
        private final String where;

        @SuppressWarnings("unchecked")
        Fields(Object node, String where, List<String> required, List<String> optional) {
            if (!(node instanceof Map)) throw new TasksWire.Invalid(where);
            this.map = (Map<String, Object>) node;
            this.where = where;
            for (String key : required) if (!map.containsKey(key)) throw new TasksWire.Invalid(where + "." + key);
            for (String key : map.keySet()) {
                if (!required.contains(key) && !optional.contains(key)) throw new TasksWire.Invalid(where + ".unknown_member");
            }
        }

        static Fields exact(Object node, String where, String... keys) {
            return new Fields(node, where, Arrays.asList(keys), Collections.<String>emptyList());
        }

        static Fields exact(Object node, String where, List<String> keys) {
            return new Fields(node, where, keys, Collections.<String>emptyList());
        }

        private String name(String key) { return where + "." + key; }

        boolean has(String key) { return map.containsKey(key); }

        Object raw(String key) { return map.get(key); }

        boolean isNull(String key) { return map.get(key) == TasksWireJson.NULL; }

        String string(String key) {
            Object value = map.get(key);
            if (!(value instanceof String)) throw new TasksWire.Invalid(name(key));
            return (String) value;
        }

        String nullableString(String key) { return isNull(key) ? null : string(key); }

        boolean bool(String key) {
            Object value = map.get(key);
            if (!(value instanceof Boolean)) throw new TasksWire.Invalid(name(key));
            return (Boolean) value;
        }

        long integer(String key, long min, long max) {
            Object value = map.get(key);
            if (!(value instanceof TasksWireJson.Num)) throw new TasksWire.Invalid(name(key));
            return TasksWireText.integer(((TasksWireJson.Num) value).literal, min, max, name(key));
        }

        int int32(String key) { return (int) integer(key, Integer.MIN_VALUE, Integer.MAX_VALUE); }

        Long nullableInteger(String key, long min, long max) { return isNull(key) ? null : integer(key, min, max); }

        @SuppressWarnings("unchecked")
        List<Object> array(String key) {
            Object value = map.get(key);
            if (!(value instanceof List)) throw new TasksWire.Invalid(name(key));
            return (List<Object>) value;
        }
    }

    private static String kindOf(Object node, String where) {
        if (!(node instanceof Map)) throw new TasksWire.Invalid(where);
        Object kind = ((Map<?, ?>) node).get("kind");
        if (!(kind instanceof String)) throw new TasksWire.Invalid(where + ".kind");
        return (String) kind;
    }

    private static void schemaVersion(Fields fields) {
        if (fields.integer("schema_version", TasksWire.SCHEMA_VERSION, TasksWire.SCHEMA_VERSION) != TasksWire.SCHEMA_VERSION) {
            throw new TasksWire.Invalid("schema_version");
        }
    }

    // ---- Mutation request -----------------------------------------------------------------

    static Request request(Object root) {
        Fields envelope = Fields.exact(root, "request", "mutation");
        Fields mutation = Fields.exact(envelope.raw("mutation"), "mutation", "schema_version", "mutation_id", "operation");
        schemaVersion(mutation);
        String mutationId = TasksWireText.uuid(mutation.string("mutation_id"), "mutation_id");
        return new Request(mutationId, operation(mutation.raw("operation")));
    }

    static TasksWire.Operation operation(Object node) {
        TasksWire.OperationKind kind = TasksWire.OperationKind.fromWire(kindOf(node, "operation"));
        List<String> optional = kind.optional();
        List<String> required = new ArrayList<>(kind.fields.size() + 1);
        required.add("kind");
        for (String field : kind.fields) if (!optional.contains(field)) required.add(field);
        Fields f = new Fields(node, "operation", required, optional);
        switch (kind) {
            case LIST_CREATE:
                return new TasksWire.Operation.ListCreate(f.string("list_id"), f.string("title"), f.int32("sort_order"), f.nullableString("created_at"));
            case LIST_UPDATE:
                return new TasksWire.Operation.ListUpdate(f.string("list_id"), f.string("base_revision"), f.string("title"), f.int32("sort_order"));
            case LIST_DELETE:
                return new TasksWire.Operation.ListDelete(f.string("list_id"), f.string("base_revision"),
                        f.has("base_members_revision") ? f.string("base_members_revision") : null);
            case TASK_CREATE:
                return new TasksWire.Operation.TaskCreate(f.string("task_id"), content(f.raw("content")), f.nullableString("created_at"),
                        f.bool("is_completed"), f.nullableString("completed_at"));
            case TASK_UPDATE:
                return new TasksWire.Operation.TaskUpdate(f.string("task_id"), f.string("base_revision"), content(f.raw("content")));
            case TASK_COMPLETE:
                return new TasksWire.Operation.TaskComplete(f.string("task_id"), f.string("base_revision"),
                        f.nullableString("invitation_id"), f.bool("is_completed"));
            case TASK_DELETE:
                return new TasksWire.Operation.TaskDelete(f.string("task_id"), f.string("base_revision"));
            case INVITATION_REQUEST:
                return new TasksWire.Operation.InvitationRequest(f.string("task_id"), f.string("base_revision"),
                        f.string("invitation_id"), f.string("recipient_tg_id"));
            case INVITATION_RESPOND:
                return new TasksWire.Operation.InvitationRespond(f.string("task_id"), f.string("base_revision"),
                        f.string("invitation_id"), TasksWire.Decision.fromWire(f.string("decision")));
            case INVITATION_REVOKE:
                return new TasksWire.Operation.InvitationRevoke(f.string("task_id"), f.string("base_revision"), f.string("invitation_id"));
            case SENDER_BLOCK:
                return new TasksWire.Operation.SenderBlock(f.string("invitation_id"));
            case TASK_PATCH:
                return new TasksWire.Operation.TaskPatch(f.string("task_id"), patchFields(f.raw("set"), "set"),
                        patchFields(f.raw("expect"), "expect"));
            default:
                return new TasksWire.Operation.SenderUnblock(f.string("sender_tg_id"));
        }
    }

    static TasksWire.Content content(Object node) {
        Fields f = Fields.exact(node, "content", "list_id", "title", "description", "due_at", "priority", "sort_order", "annotation");
        return new TasksWire.Content(f.nullableString("list_id"), f.string("title"), f.nullableString("description"),
                f.nullableInteger("due_at", 0, TasksWire.MAX_DEADLINE), (int) f.integer("priority", 0, 4), f.int32("sort_order"),
                f.isNull("annotation") ? null : annotation(f.raw("annotation")));
    }

    private static TasksWire.Annotation annotation(Object node) {
        Fields a = Fields.exact(node, "annotation", "telegram_user_id", "display_name");
        return new TasksWire.Annotation(a.string("telegram_user_id"), a.string("display_name"));
    }

    private static final List<String> CONTENT_FIELDS;
    static {
        List<String> names = new ArrayList<>();
        for (TasksWire.ContentField field : TasksWire.ContentField.values()) names.add(field.wire());
        CONTENT_FIELDS = Collections.unmodifiableList(names);
    }

    /** $defs.PatchFields: any subset of the Content members, each of its Content JSON type (TaskPatch checks the rest). */
    private static Map<TasksWire.ContentField, Object> patchFields(Object node, String where) {
        Fields f = new Fields(node, where, Collections.<String>emptyList(), CONTENT_FIELDS);
        Map<TasksWire.ContentField, Object> fields = new LinkedHashMap<>();
        for (TasksWire.ContentField field : TasksWire.ContentField.values()) {
            String key = field.wire();
            if (!f.has(key)) continue;
            switch (field) {
                case LIST_ID:
                case DESCRIPTION:
                    fields.put(field, f.nullableString(key));
                    break;
                case TITLE:
                    fields.put(field, f.string(key));
                    break;
                case DUE_AT:
                    fields.put(field, f.nullableInteger(key, 0, TasksWire.MAX_DEADLINE));
                    break;
                case PRIORITY:
                    fields.put(field, (int) f.integer(key, 0, 4));
                    break;
                case SORT_ORDER:
                    fields.put(field, f.int32(key));
                    break;
                default:
                    fields.put(field, f.isNull(key) ? null : annotation(f.raw(key)));
            }
        }
        return fields;
    }

    // ---- Projections -----------------------------------------------------------------------

    static TasksWire.Projection projection(Object node, String where) {
        TasksWire.ProjectionKind kind = TasksWire.ProjectionKind.fromWire(kindOf(node, where));
        switch (kind) {
            case LIST: {
                // members_revision: R4 projections only ($defs.ListR4).
                Fields f = new Fields(node, where, Arrays.asList("kind", "id", "revision", "title", "sort_order", "created_at", "updated_at"),
                        Collections.singletonList("members_revision"));
                return new TasksWire.ListItem(f.string("id"), f.string("revision"), f.has("members_revision") ? f.string("members_revision") : null,
                        f.string("title"), f.int32("sort_order"), f.string("created_at"), f.string("updated_at"));
            }
            case TASK: {
                Fields f = Fields.exact(node, where, "kind", "id", "revision", "owner_tg_id", "viewer_role", "content", "is_completed",
                        "completed_at", "created_at", "updated_at", "assignment");
                TasksWire.Assignment assignment = null;
                if (!f.isNull("assignment")) {
                    Fields a = Fields.exact(f.raw("assignment"), "assignment", "invitation_id", "recipient_tg_id", "state", "expires_at");
                    assignment = new TasksWire.Assignment(a.string("invitation_id"), a.string("recipient_tg_id"),
                            TasksWire.AssignmentState.fromWire(a.string("state")), a.string("expires_at"));
                }
                return new TasksWire.TaskItem(f.string("id"), f.string("revision"), f.string("owner_tg_id"),
                        TasksWire.ViewerRole.fromWire(f.string("viewer_role")), content(f.raw("content")), f.bool("is_completed"),
                        f.nullableString("completed_at"), f.string("created_at"), f.string("updated_at"), assignment);
            }
            case INVITATION: {
                Fields f = Fields.exact(node, where, "kind", "id", "task_id", "task_revision", "owner_tg_id", "title", "due_at", "expires_at");
                return new TasksWire.InvitationItem(f.string("id"), f.string("task_id"), f.string("task_revision"), f.string("owner_tg_id"),
                        f.string("title"), f.nullableInteger("due_at", 0, TasksWire.MAX_DEADLINE), f.string("expires_at"));
            }
            default: {
                Fields f = Fields.exact(node, where, "kind", "id", "created_at");
                return new TasksWire.BlockItem(f.string("id"), f.string("created_at"));
            }
        }
    }

    private static int pageLimit(int requestedLimit) {
        if (requestedLimit < TasksWire.MIN_LIMIT || requestedLimit > TasksWire.MAX_LIMIT) throw new TasksWire.Invalid("limit");
        return requestedLimit;
    }

    static TasksWire.SnapshotResponse snapshot(Object root, int requestedLimit, Integer serverWire) {
        int limit = pageLimit(requestedLimit);
        Fields f = Fields.exact(root, "snapshot", "schema_version", "items", "next_cursor", "changes_cursor", "server_time");
        schemaVersion(f);
        List<Object> raw = f.array("items");
        if (raw.size() > limit) throw new TasksWire.Invalid("snapshot.items");
        List<TasksWire.Projection> items = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (Object node : raw) {
            TasksWire.Projection item = projection(node, "snapshot.item");
            if (!seen.add(item.kind.wire() + "\n" + item.id)) throw new TasksWire.Invalid("snapshot.duplicate_item");
            items.add(item);
        }
        String next = f.isNull("next_cursor") ? null : TasksWireText.cursor(f.string("next_cursor"), "snapshot.next_cursor");
        return new TasksWire.SnapshotResponse(Collections.unmodifiableList(items), next,
                TasksWireText.cursor(f.string("changes_cursor"), "snapshot.changes_cursor"),
                TasksWireText.instant(f.string("server_time"), "snapshot.server_time"), serverWire);
    }

    static TasksWire.ChangesResponse changes(Object root, int requestedLimit, Integer serverWire) {
        int limit = pageLimit(requestedLimit);
        Fields f = Fields.exact(root, "changes", "schema_version", "changes", "next_cursor", "has_more", "server_time");
        schemaVersion(f);
        List<Object> raw = f.array("changes");
        if (raw.size() > limit) throw new TasksWire.Invalid("changes.changes");
        List<TasksWire.Change> changes = new ArrayList<>(raw.size());
        String previous = null;
        for (Object node : raw) {
            TasksWire.Change change = change(node);
            // Positions are ascending and unique within a page (x-sync.changes).
            if (previous != null && TasksWireText.compareSequence(previous, change.position) >= 0) {
                throw new TasksWire.Invalid("changes.position_order");
            }
            previous = change.position;
            changes.add(change);
        }
        return new TasksWire.ChangesResponse(Collections.unmodifiableList(changes),
                TasksWireText.cursor(f.string("next_cursor"), "changes.next_cursor"), f.bool("has_more"),
                TasksWireText.instant(f.string("server_time"), "changes.server_time"), serverWire);
    }

    private static TasksWire.Change change(Object node) {
        if (!(node instanceof Map)) throw new TasksWire.Invalid("change");
        Object action = ((Map<?, ?>) node).get("action");
        if (!(action instanceof String)) throw new TasksWire.Invalid("change.action");
        TasksWire.ChangeAction kind = TasksWire.ChangeAction.fromWire((String) action);
        if (kind == TasksWire.ChangeAction.UPSERT) {
            Fields f = Fields.exact(node, "change", "position", "action", "item");
            TasksWire.Projection item = projection(f.raw("item"), "change.item");
            return new TasksWire.Change(TasksWireText.sequence(f.string("position"), "change.position"), kind, item.kind, item.id, item);
        }
        Fields f = Fields.exact(node, "change", "position", "action", "kind", "id");
        TasksWire.ProjectionKind removed = TasksWire.ProjectionKind.fromWire(f.string("kind"));
        String id = removed == TasksWire.ProjectionKind.BLOCK ? TasksWireText.positive(f.string("id"), "change.id")
                : TasksWireText.uuid(f.string("id"), "change.id");
        return new TasksWire.Change(TasksWireText.sequence(f.string("position"), "change.position"), kind, removed, id, null);
    }

    // ---- Mutation response -----------------------------------------------------------------

    static TasksWire.MutationResponse mutationResponse(Object root, TasksWire.Mutation sent, Integer serverWire) {
        Fields f = Fields.exact(root, "response", "schema_version", "receipt", "server_time");
        schemaVersion(f);
        TasksWire.Receipt receipt = receipt(f.raw("receipt"), sent);
        return new TasksWire.MutationResponse(receipt, TasksWireText.instant(f.string("server_time"), "response.server_time"), serverWire);
    }

    /** $defs.Receipt identifying exactly {@code sent} (mutation id, kind, resource kind and id). */
    private static TasksWire.Receipt receipt(Object node, TasksWire.Mutation sent) {
        Fields r = Fields.exact(node, "receipt", "mutation_id", "operation", "outcome", "resource_kind",
                "resource_id", "resource_revision", "committed_at");
        String mutationId = TasksWireText.uuid(r.string("mutation_id"), "receipt.mutation_id");
        TasksWire.OperationKind operation = TasksWire.OperationKind.fromWire(r.string("operation"));
        TasksWire.ResourceKind resourceKind = TasksWire.ResourceKind.fromWire(r.string("resource_kind"));
        if (!mutationId.equals(sent.mutationId)) throw new TasksWire.Invalid("receipt.mutation_id");
        if (operation != sent.operation.kind) throw new TasksWire.Invalid("receipt.operation");
        if (resourceKind != operation.resourceKind()) throw new TasksWire.Invalid("receipt.resource_kind");
        String resourceId, revision;
        if (resourceKind == TasksWire.ResourceKind.BLOCK) {
            // sender.* identifies the inviter Telegram id; resource_revision is null.
            resourceId = TasksWireText.positive(r.string("resource_id"), "receipt.resource_id");
            if (operation == TasksWire.OperationKind.SENDER_UNBLOCK && !resourceId.equals(sent.operation.resourceId())) {
                throw new TasksWire.Invalid("receipt.resource_id");
            }
            if (!r.isNull("resource_revision")) throw new TasksWire.Invalid("receipt.resource_revision");
            revision = null;
        } else {
            resourceId = TasksWireText.uuid(r.string("resource_id"), "receipt.resource_id");
            if (!resourceId.equals(sent.operation.resourceId())) throw new TasksWire.Invalid("receipt.resource_id");
            revision = TasksWireText.positive(r.string("resource_revision"), "receipt.resource_revision");
        }
        return new TasksWire.Receipt(mutationId, operation, TasksWire.Outcome.fromWire(r.string("outcome")),
                resourceKind, resourceId, revision, TasksWireText.instant(r.string("committed_at"), "receipt.committed_at"));
    }

    // ---- Batch response (R4) ---------------------------------------------------------------

    /** $defs.BatchItemResult statuses of an error result. */
    private static final List<Long> ITEM_ERROR_STATUSES = Arrays.asList(401L, 403L, 404L, 409L, 422L, 429L, 503L);

    static TasksWire.BatchResponse batchResponse(Object root, TasksWire.Batch sent, Integer serverWire) {
        Fields f = Fields.exact(root, "batch", "schema_version", "batch_id", "results", "retry_after", "server_time");
        schemaVersion(f);
        if (!TasksWireText.uuid(f.string("batch_id"), "batch.batch_id").equals(sent.batchId)) throw new TasksWire.Invalid("batch.batch_id");
        String serverTime = TasksWireText.instant(f.string("server_time"), "batch.server_time");
        Integer retryAfter = f.isNull("retry_after") ? null : seconds(f.integer("retry_after", 1, Long.MAX_VALUE));
        List<Object> raw = f.array("results");
        if (raw.size() != sent.items.size()) throw new TasksWire.Invalid("batch.results");
        List<TasksWire.BatchItemResult> results = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) results.add(batchItem(raw.get(i), sent.items.get(i).mutation, serverTime, retryAfter));
        return new TasksWire.BatchResponse(sent.batchId, Collections.unmodifiableList(results), retryAfter, serverTime, serverWire);
    }

    private static TasksWire.BatchItemResult batchItem(Object node, TasksWire.Mutation sent, String serverTime, Integer batchRetryAfter) {
        if (!(node instanceof Map) || !((Map<?, ?>) node).containsKey("status")) throw new TasksWire.Invalid("result");
        Object status = ((Map<?, ?>) node).get("status");
        if (status == TasksWireJson.NULL) {
            Fields f = Fields.exact(node, "result", "mutation_id", "status", "skipped");
            requireMutation(f, sent);
            if (!f.bool("skipped")) throw new TasksWire.Invalid("result.skipped");
            return new TasksWire.BatchItemResult(sent.mutationId, TasksWire.BatchItemResult.Kind.SKIPPED, 0, null, null);
        }
        if (!(status instanceof TasksWireJson.Num)) throw new TasksWire.Invalid("result.status");
        long code = TasksWireText.integer(((TasksWireJson.Num) status).literal, 100, 599, "result.status");
        if (code == 200) {
            Fields f = Fields.exact(node, "result", "mutation_id", "status", "receipt");
            requireMutation(f, sent);
            return new TasksWire.BatchItemResult(sent.mutationId, TasksWire.BatchItemResult.Kind.RECEIPT, 200, receipt(f.raw("receipt"), sent), null);
        }
        if (!ITEM_ERROR_STATUSES.contains(code)) throw new TasksWire.Invalid("result.status");
        Fields f = Fields.exact(node, "result", "mutation_id", "status", "error");
        requireMutation(f, sent);
        return new TasksWire.BatchItemResult(sent.mutationId, TasksWire.BatchItemResult.Kind.ERROR, (int) code,
                null, itemError(f.raw("error"), (int) code, serverTime, batchRetryAfter));
    }

    private static void requireMutation(Fields result, TasksWire.Mutation sent) {
        if (!TasksWireText.uuid(result.string("mutation_id"), "result.mutation_id").equals(sent.mutationId)) {
            throw new TasksWire.Invalid("result.mutation_id");
        }
    }

    /**
     * $defs.BatchItemError, strictly: error and message, then reason / fields / effective_at by
     * $defs.ErrorRules and retry_after (>= 1) only under 429/503. No server_time: the batch's is used. A
     * 429/503 item naming no retry_after gets the batch's top-level one.
     */
    private static TasksWire.ErrorEnvelope itemError(Object node, int status, String serverTime, Integer batchRetryAfter) {
        Fields f = new Fields(node, "error", Arrays.asList("error", "message"), Arrays.asList("reason", "fields", "retry_after", "effective_at"));
        String code = f.string("error");
        if (!scalars(code, 1, 80)) throw new TasksWire.Invalid("error.error");
        if (!scalars(f.string("message"), 0, 256)) throw new TasksWire.Invalid("error.message");
        Extras extras = extras(code, (Map<?, ?>) node);
        if (extras == null) throw new TasksWire.Invalid("error");
        boolean throttled = status == 429 || status == 503;
        int retryAfter = 0;
        if (f.has("retry_after")) {
            if (!throttled) throw new TasksWire.Invalid("error.retry_after");
            retryAfter = seconds(f.integer("retry_after", 1, Long.MAX_VALUE));
        } else if (throttled && batchRetryAfter != null) {
            retryAfter = batchRetryAfter;
        }
        return new TasksWire.ErrorEnvelope(code, TasksWire.ConflictReason.fromWireOrNull(extras.reason), serverTime, true, extras.reason,
                extras.fields, extras.effectiveAt, retryAfter);
    }

    private static int seconds(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }

    // ---- Errors ----------------------------------------------------------------------------

    private static final List<String> ERROR_KEYS = Arrays.asList("error", "message", "server_time", "reason", "fields", "effective_at");
    /** $defs.Error.reason (R4). */
    private static final List<String> ERROR_REASONS = Arrays.asList("revision_changed", "invitation_changed", "invitation_expired",
            "completed_task", "field_changed", "members_changed", "withdrawn", "batch_disabled", "migration_paused");
    private static final List<String> UNAVAILABLE_REASONS = Arrays.asList("batch_disabled", "migration_paused");

    /** What $defs.ErrorRules governs in one error object, already checked against its code. */
    private static final class Extras {
        final String reason;
        final List<TasksWire.ContentField> fields;
        final String effectiveAt;
        Extras(String reason, List<TasksWire.ContentField> fields, String effectiveAt) {
            this.reason = reason;
            this.fields = fields;
            this.effectiveAt = effectiveAt;
        }
    }

    /**
     * $defs.ErrorRules (shared by Error and BatchItemError), or null when broken: tasks_conflict requires one
     * of the six conflict reasons; tasks_consent_required requires reason withdrawn and effective_at;
     * tasks_unavailable allows batch_disabled / migration_paused; every other code carries no reason; fields
     * exactly with field_changed; effective_at only with tasks_consent_required.
     */
    private static Extras extras(String code, Map<?, ?> map) {
        String reason = null;
        if (map.containsKey("reason")) {
            Object raw = map.get("reason");
            if (!(raw instanceof String) || !ERROR_REASONS.contains(raw)) return null;
            reason = (String) raw;
        }
        switch (code) {
            case "tasks_conflict":
                if (reason == null || TasksWire.ConflictReason.fromWireOrNull(reason) == null) return null;
                break;
            case "tasks_consent_required":
                if (!"withdrawn".equals(reason) || !map.containsKey("effective_at")) return null;
                break;
            case "tasks_unavailable":
                if (reason != null && !UNAVAILABLE_REASONS.contains(reason)) return null;
                break;
            default:
                if (reason != null) return null;
        }
        String effectiveAt = null;
        if (map.containsKey("effective_at")) {
            Object raw = map.get("effective_at");
            if (!"tasks_consent_required".equals(code) || !(raw instanceof String)) return null;
            try { effectiveAt = TasksWireText.instant((String) raw, "effective_at"); }
            catch (TasksWire.Invalid invalid) { return null; }
        }
        List<TasksWire.ContentField> fields = Collections.emptyList();
        if ("field_changed".equals(reason)) {
            fields = conflictFields(map.get("fields"));
            if (fields == null) return null;
        } else if (map.containsKey("fields")) {
            return null;
        }
        return new Extras(reason, fields, effectiveAt);
    }

    /** $defs.Error.fields: 1..7 distinct ContentFields in content order, else null. */
    private static List<TasksWire.ContentField> conflictFields(Object node) {
        if (!(node instanceof List)) return null;
        List<?> raw = (List<?>) node;
        if (raw.isEmpty() || raw.size() > TasksWire.ContentField.values().length) return null;
        List<TasksWire.ContentField> fields = new ArrayList<>(raw.size());
        for (Object item : raw) {
            TasksWire.ContentField field = item instanceof String ? TasksWire.ContentField.fromWireOrNull((String) item) : null;
            if (field == null || (!fields.isEmpty() && fields.get(fields.size() - 1).ordinal() >= field.ordinal())) return null;
            fields.add(field);
        }
        return Collections.unmodifiableList(fields);
    }

    /** null unless a JSON object with a string error code of 1..80 scalars. */
    static TasksWire.ErrorEnvelope error(Object root) {
        if (!(root instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) root;
        Object code = map.get("error");
        if (!(code instanceof String) || !scalars((String) code, 1, 80)) return null;
        String value = (String) code;
        String serverTime = null;
        Object time = map.get("server_time");
        if (time instanceof String) {
            try { serverTime = TasksWireText.instant((String) time, "server_time"); }
            catch (TasksWire.Invalid ignored) { serverTime = null; }
        }
        Object rawReason = map.get("reason");
        String wireReason = rawReason instanceof String && scalars((String) rawReason, 1, 80) ? (String) rawReason : null;
        TasksWire.ConflictReason reason = wireReason == null ? null : TasksWire.ConflictReason.fromWireOrNull(wireReason);
        Extras extras = extras(value, map);
        boolean strict = ERROR_KEYS.containsAll(keys(map)) && map.get("message") instanceof String
                && scalars((String) map.get("message"), 0, 256) && serverTime != null && extras != null;
        return new TasksWire.ErrorEnvelope(value, reason, serverTime, strict, wireReason,
                extras == null ? Collections.<TasksWire.ContentField>emptyList() : extras.fields, extras == null ? null : extras.effectiveAt, 0);
    }

    private static boolean scalars(String value, int min, int max) {
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }

    private static Set<Object> keys(Map<?, ?> map) { return new HashSet<Object>(map.keySet()); }
}

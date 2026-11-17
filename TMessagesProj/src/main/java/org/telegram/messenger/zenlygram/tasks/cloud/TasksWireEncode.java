package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Model -> canonical tree. Object members follow $defs order; every nullable member is present as
 * an explicit null (an absent optional member, such as an R3 list.delete's base_members_revision, is
 * left out). Business arguments follow x-mutation-identity.arguments with structured content replaced
 * by the content_order/annotation_order arrays and a patch's set/expect by [[field, value]…] pairs.
 */
final class TasksWireEncode {
    private TasksWireEncode() { }

    /** ordered_arguments of x-mutation-identity. */
    static List<Object> business(TasksWire.Operation operation) {
        List<Object> values = operation.values();
        List<Object> result = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof TasksWire.Content) result.add(((TasksWire.Content) value).businessArray());
            else if (value instanceof Map) result.add(patchPairs((Map<?, ?>) value));
            else result.add(value);
        }
        return result;
    }

    /** ["zenlygram-tasks-business-v1", B.id, mutation_id, operation.kind, ordered_arguments], no final LF. */
    static byte[] businessBytes(String principal, String mutationId, TasksWire.Operation operation) {
        return TasksWireJson.canonicalBytes(Arrays.<Object>asList(TasksWire.BUSINESS_TAG, principal, mutationId,
                operation.kind.wire(), business(operation)));
    }

    /** {"mutation":{"schema_version":1,"mutation_id":…,"operation":{…}}}; Android never sends creation_proof. */
    static byte[] requestBody(String mutationId, TasksWire.Operation operation) {
        TasksWireJson.Obj mutation = new TasksWireJson.Obj().put("schema_version", TasksWire.SCHEMA_VERSION)
                .put("mutation_id", mutationId).put("operation", operation(operation));
        return TasksWireJson.canonicalBytes(new TasksWireJson.Obj().put("mutation", mutation));
    }

    static TasksWireJson.Obj operation(TasksWire.Operation operation) {
        TasksWireJson.Obj out = new TasksWireJson.Obj().put("kind", operation.kind.wire());
        List<Object> values = operation.values();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof TasksWire.Content) value = content((TasksWire.Content) value);
            else if (value instanceof Map) value = patchFields((Map<?, ?>) value);
            out.put(operation.kind.fields.get(i), value);
        }
        return out;
    }

    static TasksWireJson.Obj content(TasksWire.Content content) {
        return new TasksWireJson.Obj().put("list_id", content.listId).put("title", content.title)
                .put("description", content.description).put("due_at", content.dueAt).put("priority", content.priority)
                .put("sort_order", content.sortOrder).put("annotation", annotation(content.annotation));
    }

    private static TasksWireJson.Obj annotation(TasksWire.Annotation annotation) {
        return annotation == null ? null : new TasksWireJson.Obj()
                .put("telegram_user_id", annotation.telegramUserId).put("display_name", annotation.displayName);
    }

    /** A patch's set/expect object: its (already content-ordered) fields, each value as in Content. */
    private static TasksWireJson.Obj patchFields(Map<?, ?> fields) {
        TasksWireJson.Obj out = new TasksWireJson.Obj();
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            Object value = field.getValue();
            out.put(((TasksWire.ContentField) field.getKey()).wire(),
                    value instanceof TasksWire.Annotation ? annotation((TasksWire.Annotation) value) : value);
        }
        return out;
    }

    /** x-mutation-identity.arguments["task.patch"]: [[field, value]…] in content order, an annotation as annotation_order. */
    private static List<Object> patchPairs(Map<?, ?> fields) {
        List<Object> pairs = new ArrayList<>(fields.size());
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            Object value = field.getValue();
            if (value instanceof TasksWire.Annotation) {
                TasksWire.Annotation annotation = (TasksWire.Annotation) value;
                value = Arrays.<Object>asList(annotation.telegramUserId, annotation.displayName);
            }
            pairs.add(Arrays.<Object>asList(((TasksWire.ContentField) field.getKey()).wire(), value));
        }
        return pairs;
    }

    static TasksWireJson.Obj projection(TasksWire.Projection projection) {
        TasksWireJson.Obj out = new TasksWireJson.Obj().put("kind", projection.kind.wire()).put("id", projection.id);
        if (projection instanceof TasksWire.ListItem) {
            TasksWire.ListItem list = (TasksWire.ListItem) projection;
            out.put("revision", list.revision);
            if (list.membersRevision != null) out.put("members_revision", list.membersRevision); // $defs.ListR4 order
            return out.put("title", list.title).put("sort_order", list.sortOrder)
                    .put("created_at", list.createdAt).put("updated_at", list.updatedAt);
        }
        if (projection instanceof TasksWire.TaskItem) {
            TasksWire.TaskItem task = (TasksWire.TaskItem) projection;
            TasksWireJson.Obj assignment = task.assignment == null ? null : new TasksWireJson.Obj()
                    .put("invitation_id", task.assignment.invitationId).put("recipient_tg_id", task.assignment.recipientTgId)
                    .put("state", task.assignment.state.wire()).put("expires_at", task.assignment.expiresAt);
            return out.put("revision", task.revision).put("owner_tg_id", task.ownerTgId).put("viewer_role", task.viewerRole.wire())
                    .put("content", content(task.content)).put("is_completed", task.isCompleted).put("completed_at", task.completedAt)
                    .put("created_at", task.createdAt).put("updated_at", task.updatedAt).put("assignment", assignment);
        }
        if (projection instanceof TasksWire.InvitationItem) {
            TasksWire.InvitationItem invitation = (TasksWire.InvitationItem) projection;
            return out.put("task_id", invitation.taskId).put("task_revision", invitation.taskRevision)
                    .put("owner_tg_id", invitation.ownerTgId).put("title", invitation.title).put("due_at", invitation.dueAt)
                    .put("expires_at", invitation.expiresAt);
        }
        TasksWire.BlockItem block = (TasksWire.BlockItem) projection;
        return out.put("created_at", block.createdAt);
    }
}

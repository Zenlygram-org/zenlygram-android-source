package org.telegram.messenger.zenlygram.tasks.cloud;

/** Local repository refusal. Never carries task content, credentials or account identifiers. */
public final class TasksCloudError extends RuntimeException {
    /**
     * CONSENT_WITHDRAWN (R4): every outbox insert is refused while the account's consent is withdrawn, and when a caller
     * that captured the consent epoch (the migration step) finds it changed.
     */
    public enum Code { NOT_LINKED, NOT_READY, NOT_FOUND, NOT_OWNER, INVALID, BUSY_STATE, PRINCIPAL_CHANGED, IMPORT_STALE, STORAGE, CLOSED, CONSENT_WITHDRAWN,
        /** Assignments: the pick names the account itself. */
        SELF_ASSIGNMENT,
        /** Assignments: the client pacer refused the pick; {@link #field} is the TasksInvitationPacer.Limit name. */
        ASSIGNMENT_LIMIT,
        /** Assignments: the assignment the screen showed is no longer the task's current one. */
        CHANGED_ELSEWHERE,
        /** Assignments: the action does not apply to the assignment's state (replace it, or pick again). */
        NOT_ALLOWED }

    public final Code code;
    /** TasksWire.Invalid field name for INVALID, else null. */
    public final String field;

    public TasksCloudError(Code code) {
        this(code, null);
    }

    public TasksCloudError(Code code, String field) {
        super(code.name());
        this.code = code;
        this.field = field;
    }
}

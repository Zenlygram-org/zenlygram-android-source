/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

/**
 * Why a Tasks change did not happen, for either store. It names only the kind of refusal (and, for a Cloud field
 * rule, the field): never task text, an id or an exception message. The UI shows its own text for each kind.
 */
public final class ZgTasksError {

    public enum Kind {
        /** Device: the copy of the device tasks to Cloud is running its local step; the same change works in a moment. */
        MIGRATING,
        /** Device: the board is a read-only archive of its Cloud copy. */
        ARCHIVED,
        /** Cloud: the account's data consent is off, withdrawn or being confirmed; nothing can be queued. */
        CONSENT,
        /** A title, name or date the store cannot keep ({@link #field} names it for Cloud). */
        INVALID,
        /** The task or list no longer exists. */
        NOT_FOUND,
        /** Creating needs Zenlygram Pro. */
        PRO_REQUIRED,
        /** Cloud: no linked Zenlygram account, or the account's Cloud tasks are closed (signed out). */
        NOT_LINKED,
        /** Cloud: the account's Zenlygram link changed while the change was being made. */
        LINK_CHANGED,
        /** Cloud: still loading. */
        NOT_READY,
        /** Cloud: only the task's owner may do this. */
        NOT_OWNER,
        /** Cloud: the row is being changed (e.g. an Undo that came too late). */
        BUSY,
        /** Cloud: the device tasks changed while their copy was being confirmed. */
        IMPORT_STALE,
        /** Cloud: the assignee picked is the account itself. */
        ASSIGN_SELF,
        /** Cloud: the pick would exceed an assignment limit ({@link #field} names it: PER_PERSON, PER_MINUTE, TOTAL, PENDING). */
        ASSIGN_LIMIT,
        /** Cloud: the task's assignee changed while the screen showed another one. */
        ASSIGN_CHANGED,
        /** Cloud: a completed task cannot be assigned. */
        ASSIGN_COMPLETED,
        /** Device: assigning needs sync; nothing is stored. */
        ASSIGN_NEEDS_SYNC,
        /** The account behind the store is no longer the one signed in here. */
        ACCOUNT_CHANGED,
        /** The store could not be read or written. */
        STORAGE
    }

    public final Kind kind;
    /**
     * Cloud {@link Kind#INVALID}: the wire name of the field ("title", "due_at", ...); {@link Kind#ASSIGN_LIMIT}: the
     * limit's name; else null.
     */
    public final String field;

    public ZgTasksError(Kind kind) {
        this(kind, null);
    }

    public ZgTasksError(Kind kind, String field) {
        this.kind = kind;
        this.field = field;
    }

    /** A refusal that passes by itself or is explained by the board: a short note, never an error alert. */
    public boolean isFence() {
        return kind == Kind.MIGRATING || kind == Kind.ARCHIVED || kind == Kind.CONSENT;
    }

    /**
     * A pick the store refused with its own sentence (the account itself, a limit, a completed task, an assignee that
     * changed meanwhile): nothing was queued and nothing is wrong, so it is a short note too.
     */
    public boolean isAssignRefusal() {
        return kind == Kind.ASSIGN_SELF || kind == Kind.ASSIGN_LIMIT || kind == Kind.ASSIGN_CHANGED || kind == Kind.ASSIGN_COMPLETED
            || kind == Kind.ASSIGN_NEEDS_SYNC;
    }

    @Override
    public String toString() {
        return field == null ? kind.name() : kind + "(" + field + ")";
    }
}

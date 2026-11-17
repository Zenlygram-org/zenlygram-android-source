/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything one store has to show on the Tasks screens, as one immutable value: the lists, the tasks in the board's
 * order, and the state of the store itself (iOS TasksBoardState's data + TasksCloudBoardState's gate, banner and
 * read-only reason). A source publishes a new board after every change; the screens only render it.
 *
 * <ul>
 *   <li>{@link #gate}: the store cannot be shown at all; the board offers the matching recovery instead of content.</li>
 *   <li>{@link #banner}: one line about the store's state above the tasks; content stays visible underneath.</li>
 *   <li>{@link #readOnly}: content is shown, nothing can be changed (the composer and every row action go).</li>
 *   <li>{@link #complete}: nothing is known to be missing. Until then an empty board never claims "No tasks yet".</li>
 * </ul>
 */
public final class ZgBoard {

    /** Cloud cannot be shown; the board shows a card with the recovery instead (iOS TasksCloudGate). */
    public enum Gate {
        /** No linked Zenlygram account and nothing cached: Link Account, or use the tasks on this device. */
        UNLINKED,
        /** The account this store belongs to is no longer signed in here. */
        SIGNED_OUT,
        /** The store cannot be opened. */
        STORAGE
    }

    /** The one state line above the tasks (iOS TasksCloudBanner). */
    public enum Banner {
        LOADING,
        /** The Tasks service says the account is not linked: everything is kept, linking resumes. */
        PAUSED,
        /** The Zenlygram session was refused; signing in again goes through linking. */
        SESSION,
        OFFLINE,
        UNAVAILABLE,
        CONSENT_OFF,
        WITHDRAWN,
        CONSENT_FENCED,
        /** Consent is given, the account is not confirmed yet; changes queue on this device (after the cut-over). */
        NOT_LINKED_YET,
        /** Consent is given and the account is linked; the confirmation is on its way. */
        PREPARING
    }

    /** Why nothing can be changed (iOS TasksCloudReadOnlyReason, and the Device archive). */
    public enum ReadOnly {
        CONSENT_OFF,
        WITHDRAWN,
        CONSENT_FENCED,
        /** The device board after its rows were copied to Cloud. */
        ARCHIVE
    }

    /** Something the store did by itself that the user should know; dismissed by id. */
    public static final class Notice {
        public enum Kind {
            /** A task shared with this account is no longer available. */
            ACCESS_LOST,
            /** Something changed here no longer exists on the server. */
            NOT_FOUND,
            /** A change was dropped because the change it depended on was discarded. */
            DEPENDENCY_DROPPED,
            /** A task copied from this device was removed in Cloud. */
            REMOVED_IN_CLOUD,
            /** A deleted task had changed on another device, so it was kept. */
            DELETE_KEPT
        }

        public final long id;
        public final Kind kind;

        public Notice(long id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Notice && ((Notice) o).id == id && ((Notice) o).kind == kind;
        }

        @Override
        public int hashCode() {
            return (int) (id ^ (id >>> 32)) * 31 + kind.hashCode();
        }
    }

    public final ZgTasksScope scope;
    public final boolean complete;
    /** The last read of the store failed (the device store); what was shown before stays. */
    public final boolean failed;
    public final Gate gate;
    public final Banner banner;
    public final ReadOnly readOnly;
    /** The device board's archive banner; null while it is writable, and always for Cloud. */
    public final ZgTasksArchive archive;
    public final List<ZgBoardList> lists;
    /** Every task, in the order the open tasks are shown (the group of each task first, then its place in it). */
    public final List<ZgBoardTask> tasks;
    /** Invitations that can still be answered. */
    public final int invitations;
    /** Changes waiting to be sent or confirmed / changes that wait for a choice (Unsent Changes). */
    public final int unsentWaiting;
    public final int unsentAttention;
    public final List<Notice> notices;
    /** The Cloud board's "Copy Device Tasks to Cloud…": a copy would queue at least one device row now. */
    public final boolean offersDeviceCopy;

    private ZgBoard(Builder b) {
        this.scope = b.scope;
        this.complete = b.complete;
        this.failed = b.failed;
        this.gate = b.gate;
        this.banner = b.banner;
        this.readOnly = b.readOnly;
        this.archive = b.archive;
        this.lists = Collections.unmodifiableList(new ArrayList<>(b.lists));
        this.tasks = Collections.unmodifiableList(new ArrayList<>(b.tasks));
        this.invitations = Math.max(0, b.invitations);
        this.unsentWaiting = Math.max(0, b.unsentWaiting);
        this.unsentAttention = Math.max(0, b.unsentAttention);
        this.notices = Collections.unmodifiableList(new ArrayList<>(b.notices));
        this.offersDeviceCopy = b.offersDeviceCopy;
    }

    /** Nothing loaded yet. */
    public static ZgBoard empty(ZgTasksScope scope) {
        return new Builder(scope).build();
    }

    /** Content (lists, chips, tasks) is shown: no gate replaces it. */
    public boolean isUsable() {
        return gate == null;
    }

    public boolean isReadOnly() {
        return readOnly != null;
    }

    /** The composer, "New List" and every other change: a usable board that is not read-only. */
    public boolean canCompose() {
        return gate == null && readOnly == null;
    }

    public ZgBoardList list(String id) {
        if (id == null) {
            return null;
        }
        for (ZgBoardList list : lists) {
            if (list.id.equals(id)) {
                return list;
            }
        }
        return null;
    }

    public ZgBoardTask task(String id) {
        if (id == null) {
            return null;
        }
        for (ZgBoardTask task : tasks) {
            if (task.id.equals(id)) {
                return task;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgBoard)) {
            return false;
        }
        final ZgBoard b = (ZgBoard) o;
        return scope == b.scope && complete == b.complete && failed == b.failed && gate == b.gate && banner == b.banner
            && readOnly == b.readOnly && invitations == b.invitations && unsentWaiting == b.unsentWaiting
            && unsentAttention == b.unsentAttention && offersDeviceCopy == b.offersDeviceCopy
            && Objects.equals(archive, b.archive) && lists.equals(b.lists) && tasks.equals(b.tasks) && notices.equals(b.notices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, complete, gate, banner, readOnly, lists.size(), tasks.size());
    }

    public static final class Builder {
        private final ZgTasksScope scope;
        private boolean complete;
        private boolean failed;
        private Gate gate;
        private Banner banner;
        private ReadOnly readOnly;
        private ZgTasksArchive archive;
        private List<ZgBoardList> lists = Collections.emptyList();
        private List<ZgBoardTask> tasks = Collections.emptyList();
        private int invitations;
        private int unsentWaiting;
        private int unsentAttention;
        private List<Notice> notices = Collections.emptyList();
        private boolean offersDeviceCopy;

        public Builder(ZgTasksScope scope) {
            this.scope = scope;
        }

        public Builder complete(boolean complete) {
            this.complete = complete;
            return this;
        }

        public Builder failed(boolean failed) {
            this.failed = failed;
            return this;
        }

        public Builder gate(Gate gate) {
            this.gate = gate;
            return this;
        }

        public Builder banner(Banner banner) {
            this.banner = banner;
            return this;
        }

        public Builder readOnly(ReadOnly readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder archive(ZgTasksArchive archive) {
            this.archive = archive;
            return this;
        }

        public Builder lists(List<ZgBoardList> lists) {
            this.lists = lists;
            return this;
        }

        public Builder tasks(List<ZgBoardTask> tasks) {
            this.tasks = tasks;
            return this;
        }

        public Builder invitations(int count) {
            this.invitations = count;
            return this;
        }

        public Builder unsent(int waiting, int attention) {
            this.unsentWaiting = waiting;
            this.unsentAttention = attention;
            return this;
        }

        public Builder notices(List<Notice> notices) {
            this.notices = notices;
            return this;
        }

        public Builder offersDeviceCopy(boolean offers) {
            this.offersDeviceCopy = offers;
            return this;
        }

        public ZgBoard build() {
            return new ZgBoard(this);
        }
    }
}

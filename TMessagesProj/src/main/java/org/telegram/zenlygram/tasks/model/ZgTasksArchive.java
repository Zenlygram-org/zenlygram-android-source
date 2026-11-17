/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;

/**
 * Why the device board is a read-only "Device archive" and what its banner says (iOS TasksBoardArchive). Pure: built
 * from the account's cut-over, its migration record, whether the record's copy is in this account's Cloud store on
 * this device (the sync owner's I1) and the unsent counts of that store.
 *
 * <ul>
 *   <li>{@link Reason#MIGRATING}: the copy is queued. "Copying device tasks to Cloud: X of Y" only while an enqueued
 *       copy sits in this account's store ({@link TasksCutOver#archiveShowsProgress}); when the copy is not in that
 *       store (a logout purged it, or it is not checked yet) the banner says that the Cloud copy returns
 *       ({@link TasksCutOver#archiveAwaitsCopy}).</li>
 *   <li>{@link Reason#NEW_ROWS}: the copy is confirmed, but newer device rows are not in Cloud: "Copy to Cloud".</li>
 *   <li>{@link Reason#RETIRED}: every row is in Cloud. The board is hidden then; this is what it says if reached.</li>
 * </ul>
 */
public final class ZgTasksArchive {

    public enum Reason {
        MIGRATING,
        NEW_ROWS,
        RETIRED
    }

    public final Reason reason;
    /** {@link Reason#MIGRATING}: {@code done} of {@code total} lists and tasks are confirmed; total 0 = no progress line. */
    public final int done;
    public final int total;
    /** {@link Reason#NEW_ROWS}: device rows that are not in Cloud yet. */
    public final int newRows;
    /** Rows the copy could not take; they stay on this device. */
    public final int keptLocal;
    /** {@link Reason#MIGRATING}: the copy is not in this account's Cloud store on this device (yet). */
    public final boolean awaitsCopy;

    public ZgTasksArchive(Reason reason, int done, int total, int newRows, int keptLocal, boolean awaitsCopy) {
        this.reason = reason;
        this.total = reason == Reason.MIGRATING ? Math.max(0, total) : 0;
        this.done = Math.min(Math.max(0, done), this.total);
        this.newRows = reason == Reason.NEW_ROWS ? Math.max(0, newRows) : 0;
        this.keptLocal = Math.max(0, keptLocal);
        this.awaitsCopy = reason == Reason.MIGRATING && awaitsCopy;
    }

    /** The banner offers the explicit "Copy to Cloud" for the rows that are not in Cloud yet. */
    public boolean offersCopy() {
        return reason == Reason.NEW_ROWS;
    }

    /**
     * The archive of a cut-over, or null while the device board is writable (cut-over NONE).
     *
     * @param record  the account's migration record (null = not read or none: no counts)
     * @param ownCopy the sync owner's I1 of its last cut-over evaluation (ZenlyTasksSyncOwner.ownCopyOnDevice)
     * @param unsent  {user, migration} unsent entries of the account's Cloud store, null = unknown
     */
    public static ZgTasksArchive of(TasksCutOver cutOver, ZenlyTaskImport.MigrationRecord record, boolean ownCopy, int[] unsent) {
        if (cutOver == null || !cutOver.isReadOnly()) {
            return null;
        }
        final int keptLocal = record == null ? 0 : record.keptLocal;
        if (cutOver.kind == TasksCutOver.Kind.RETIRED) {
            return new ZgTasksArchive(Reason.RETIRED, 0, 0, 0, keptLocal, false);
        }
        if (cutOver.reason == TasksCutOver.Reason.NEW_ROWS) {
            return new ZgTasksArchive(Reason.NEW_ROWS, 0, 0, cutOver.newRows, keptLocal, false);
        }
        TasksCutOver.Record state = record == null ? null : TasksCutOver.Record.fromWire(record.state);
        final int total = record == null ? 0 : record.lists + record.tasks;
        int done = 0;
        int shown = 0;
        // Only an enqueued copy in this store is being copied; a confirmed one, or one a logout purged, would read "N of N".
        if (TasksCutOver.archiveShowsProgress(cutOver, state, ownCopy) && total > 0 && unsent != null && unsent.length > 1) {
            done = Math.max(0, Math.min(total, total - unsent[1]));
            shown = total;
        }
        return new ZgTasksArchive(Reason.MIGRATING, done, shown, 0, keptLocal, TasksCutOver.archiveAwaitsCopy(cutOver, ownCopy));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgTasksArchive)) {
            return false;
        }
        final ZgTasksArchive a = (ZgTasksArchive) o;
        return reason == a.reason && done == a.done && total == a.total && newRows == a.newRows && keptLocal == a.keptLocal
            && awaitsCopy == a.awaitsCopy;
    }

    @Override
    public int hashCode() {
        return ((((reason.hashCode() * 31 + done) * 31 + total) * 31 + newRows) * 31 + keptLocal) * 31 + (awaitsCopy ? 1 : 0);
    }
}

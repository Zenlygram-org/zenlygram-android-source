/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport.MigrationRecord;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate.Permission;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.zenlygram.tasks.model.ZgTasksArchive.Reason;

/**
 * What the Device archive's banner says for each cut-over (iOS TasksBoardArchive): the progress of a queued copy only
 * while that copy sits in this account's Cloud store, "the Cloud copy returns" while it does not, "Copy to Cloud" for
 * rows added later.
 */
public class ZgTasksArchiveTest {

    private static final String SCOPE = "o-token/principal";
    private static final TasksCutOver MIGRATING = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.ENQUEUED, SCOPE, SCOPE, 0);
    private static final TasksCutOver NEW_ROWS = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.COMPLETED, SCOPE, SCOPE, 4);
    /** Confirmed, but the copy is not in this account's store on this device (a logout purged it). */
    private static final TasksCutOver COMPLETED_AWAY = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.COMPLETED, SCOPE, SCOPE, 0, false);

    /** 2 lists and 10 tasks enqueued, 1 row kept local. */
    private static MigrationRecord enqueued() {
        return MigrationRecord.claimed(SCOPE, MigrationRecord.LOCALE_EN, 100).enqueued(120L, 2, 10, 1);
    }

    private static MigrationRecord completed() {
        return enqueued().completed(180L, 7);
    }

    @Test
    public void aWritableBoardHasNoArchive() {
        assertNull(ZgTasksArchive.of(null, enqueued(), true, new int[]{0, 5}));
        assertNull(ZgTasksArchive.of(TasksCutOver.NONE, enqueued(), true, new int[]{0, 5}));
    }

    @Test
    public void aQueuedCopyInThisStoreShowsItsProgress() {
        assertEquals(TasksCutOver.Reason.MIGRATING, MIGRATING.reason);
        final ZgTasksArchive archive = ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{3, 5});
        assertEquals(Reason.MIGRATING, archive.reason);
        // 12 rows were queued, 5 migration creates are still unsent: 7 are confirmed.
        assertEquals(12, archive.total);
        assertEquals(7, archive.done);
        assertEquals(1, archive.keptLocal);
        assertFalse(archive.awaitsCopy);
        assertFalse(archive.offersCopy());
        assertEquals(0, archive.newRows);
    }

    @Test
    public void theProgressStaysInsideItsTotal() {
        assertEquals(12, ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{0, 0}).done);
        // More unsent entries than rows (other creates in the outbox): never a negative count.
        assertEquals(0, ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{0, 40}).done);
        assertEquals(12, ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{0, -3}).done);
    }

    @Test
    public void withoutCountsThereIsNoProgressLine() {
        // The unsent counts are not known yet.
        assertEquals(0, ZgTasksArchive.of(MIGRATING, enqueued(), true, null).total);
        assertEquals(0, ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{4}).total);
        // The record is not read yet.
        final ZgTasksArchive unread = ZgTasksArchive.of(MIGRATING, null, true, new int[]{0, 5});
        assertEquals(Reason.MIGRATING, unread.reason);
        assertEquals(0, unread.total);
        assertEquals(0, unread.keptLocal);
        // Nothing was queued (an empty copy).
        final MigrationRecord empty = MigrationRecord.claimed(SCOPE, MigrationRecord.LOCALE_EN, 100).enqueued(120L, 0, 0, 0);
        assertEquals(0, ZgTasksArchive.of(MIGRATING, empty, true, new int[]{0, 0}).total);
    }

    @Test
    public void aCopyThatIsNotInThisStoreSaysThatItReturns() {
        // A logout purged the account's Cloud store: "N of N" would be a lie, so no progress, and the copy returns.
        final ZgTasksArchive purged = ZgTasksArchive.of(MIGRATING, enqueued(), false, new int[]{0, 0});
        assertEquals(Reason.MIGRATING, purged.reason);
        assertEquals(0, purged.total);
        assertEquals(0, purged.done);
        assertTrue(purged.awaitsCopy);
        assertEquals(1, purged.keptLocal);

        // The same archive once the copy was confirmed before the purge: still no progress.
        assertEquals(TasksCutOver.Kind.ARCHIVE, COMPLETED_AWAY.kind);
        assertEquals(TasksCutOver.Reason.MIGRATING, COMPLETED_AWAY.reason);
        final ZgTasksArchive away = ZgTasksArchive.of(COMPLETED_AWAY, completed(), false, new int[]{0, 0});
        assertEquals(0, away.total);
        assertTrue(away.awaitsCopy);
        // And a confirmed copy is never "being copied", wherever it is.
        assertEquals(0, ZgTasksArchive.of(COMPLETED_AWAY, completed(), true, new int[]{0, 0}).total);
    }

    @Test
    public void rowsAddedLaterOfferTheCopy() {
        final ZgTasksArchive archive = ZgTasksArchive.of(NEW_ROWS, completed(), true, new int[]{0, 0});
        assertEquals(Reason.NEW_ROWS, archive.reason);
        assertEquals(4, archive.newRows);
        assertTrue(archive.offersCopy());
        assertEquals(0, archive.total);
        assertFalse(archive.awaitsCopy);
        assertEquals(1, archive.keptLocal);
        // Whether the copy is on this device does not change what the new rows need.
        assertFalse(ZgTasksArchive.of(NEW_ROWS, completed(), false, null).awaitsCopy);
    }

    @Test
    public void aRetiredBoardOnlySaysThatEverythingIsInCloud() {
        final ZgTasksArchive archive = ZgTasksArchive.of(TasksCutOver.RETIRED, completed(), true, new int[]{0, 0});
        assertEquals(Reason.RETIRED, archive.reason);
        assertEquals(0, archive.total);
        assertEquals(0, archive.newRows);
        assertFalse(archive.awaitsCopy);
        assertFalse(archive.offersCopy());
        assertEquals(1, archive.keptLocal);
    }

    @Test
    public void equalArchivesAreEqualSoAnUnchangedBannerIsNotRedrawn() {
        final ZgTasksArchive a = ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{3, 5});
        final ZgTasksArchive b = ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{9, 5});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ZgTasksArchive.of(MIGRATING, enqueued(), true, new int[]{3, 4}));
        assertNotEquals(a, ZgTasksArchive.of(MIGRATING, enqueued(), false, new int[]{3, 5}));
    }
}

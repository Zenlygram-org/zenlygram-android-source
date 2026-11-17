/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

/**
 * Per-account housekeeping of the retained history on the storage queue (iOS sgManagedDeletedMessagesRetentionSweep):
 * <ol>
 *   <li>stamps whose message left the cache (cache resets, range deletions, clear history) are dropped;</li>
 *   <li>kept copies older than 30 days, or stamped before a "Turn Off and Delete", are deleted for real;</li>
 *   <li>above 5,000 kept copies the oldest are deleted for real;</li>
 *   <li>revisions older than 30 days, or captured before a "Turn Off and Delete", are deleted;</li>
 *   <li>the next run is scheduled for the next due time (between 1 minute and 6 hours).</li>
 * </ol>
 * It runs 30 s after the account's database is opened, soon after the cap is exceeded, and after purges. Each run does
 * at most one batch of each kind, so no storage task runs long; a run that leaves work behind queues the next one
 * right behind the tasks that are waiting.
 */
final class ZgRetentionSweeper {

    private static final long FIRST_DELAY_MS = 30_000L;
    private static final long SOON_DELAY_MS = 2_000L;
    private static final long MIN_DELAY_MS = 60_000L;
    private static final long MAX_DELAY_MS = 6L * 60L * 60L * 1000L;
    /** Delay of the next run while a backlog is left: other storage tasks run in between the batches. */
    private static final long BACKLOG_DELAY_MS = 100L;

    /** The pending run of each account (touched on that account's storage thread only). */
    private static final Runnable[] scheduled = new Runnable[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgRetentionSweeper() {
    }

    /** Storage thread: the account's database was (re)opened and prepared. */
    static void onDatabasePrepared(int account) {
        schedule(account, FIRST_DELAY_MS);
    }

    /** Storage thread: run soon (cap exceeded, backlog left). */
    static void scheduleSoon(int account) {
        schedule(account, SOON_DELAY_MS);
    }

    private static void schedule(int account, long delayMs) {
        if (account < 0 || account >= scheduled.length) {
            return;
        }
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final Runnable old = scheduled[account];
        if (old != null) {
            storage.getStorageQueue().cancelRunnable(old);
        }
        final Runnable run = new Runnable() {
            @Override
            public void run() {
                if (scheduled[account] == this) {
                    scheduled[account] = null;
                }
                sweep(account, storage);
            }
        };
        scheduled[account] = run;
        storage.getStorageQueue().postRunnable(run, Math.max(0, delayMs));
    }

    private static void sweep(int account, MessagesStorage storage) {
        long nextDelay = MAX_DELAY_MS;
        try {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            final SQLiteDatabase db = ZgHistoryStore.prepare(account, storage);
            if (db == null) {
                return;
            }
            final int now = ZgHistorySettings.localNow();
            boolean backlog = false;

            // 1. Stamps of messages that are no longer in the cache.
            if (ZgHistoryStore.deleteKeptOrphans(db) > 0) {
                ZgHistoryStore.reloadKeptIndex(account, db);
            }

            // 2. Expired or purged kept copies: one batch.
            final int keptCutoff = ZgHistorySettings.keptCutoff(now);
            final ArrayList<ZgHistoryStore.KeptRow> expired = ZgHistoryStore.selectKeptUpTo(db, keptCutoff, ZgHistorySettings.SWEEP_BATCH);
            if (!expired.isEmpty()) {
                ZgKeptDeleted.reallyDelete(account, storage, db, expired);
                backlog = expired.size() >= ZgHistorySettings.SWEEP_BATCH;
            }

            // 3. The per-account cap: the oldest kept copies go, one batch (after the expired ones are done).
            if (!backlog) {
                final int over = ZgHistoryStore.countKeptAll(db) - ZgHistorySettings.KEPT_MAX_PER_ACCOUNT;
                if (over > 0) {
                    final ArrayList<ZgHistoryStore.KeptRow> oldest = ZgHistoryStore.selectKeptOldest(db, Math.min(over, ZgHistorySettings.SWEEP_BATCH));
                    if (!oldest.isEmpty()) {
                        ZgKeptDeleted.reallyDelete(account, storage, db, oldest);
                        backlog = over > oldest.size();
                    }
                }
            }

            // 4. Expired or purged revisions: one batch.
            final int editCutoff = ZgHistorySettings.editCutoff(now);
            if (ZgHistoryStore.deleteRevisionsUpTo(db, editCutoff, ZgHistorySettings.PURGE_BATCH) >= ZgHistorySettings.PURGE_BATCH) {
                backlog = true;
            }
            final ZgHistoryIndex index = ZgHistoryIndex.get(account);
            if (index != null) {
                index.pruneEdits(editCutoff);
            }

            // 5. When something is due next.
            if (backlog) {
                nextDelay = BACKLOG_DELAY_MS;
            } else {
                long nextDue = Long.MAX_VALUE;
                final int oldestKept = ZgHistoryStore.minDeletedAt(db);
                if (oldestKept > 0) {
                    nextDue = Math.min(nextDue, (long) oldestKept + ZgHistorySettings.RETENTION_MAX_AGE);
                }
                final int oldestRevision = ZgHistoryStore.minCapturedAt(db);
                if (oldestRevision > 0) {
                    nextDue = Math.min(nextDue, (long) oldestRevision + ZgHistorySettings.RETENTION_MAX_AGE);
                }
                if (nextDue != Long.MAX_VALUE) {
                    nextDelay = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, (nextDue - now) * 1000L + 1000L));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        schedule(account, nextDelay);
    }
}

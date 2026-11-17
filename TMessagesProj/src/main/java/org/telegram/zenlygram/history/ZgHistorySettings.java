/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import org.telegram.messenger.FileLog;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.pro.ZgPro;

/**
 * Settings and limits of the retained history (Show Deleted Messages, Save Edit History), the same as iOS
 * SGSimpleSettings + SGMessageRetention.
 *
 * <p>The switches are device-wide (preferences file "zenlygram", iOS keys): {@code showDeletedMessages} and
 * {@code editHistoryEnabled}, both on by default. Turning one off persists a purge timestamp
 * ({@code keptDeletedPurgeBefore} / {@code editHistoryPurgeBefore}): everything stamped or captured at or before it is
 * deleted by the purge and by every later sweep, so an interrupted purge finishes on the next launch. No destructive
 * purge is ever derived from a boolean read, only from the 30-day TTL and these timestamps.
 *
 * <p>Pro: only creating NEW records is gated ({@link #mayCapture()}, read once per update batch). Reading, showing and
 * removing what was kept never consults Pro.
 */
public final class ZgHistorySettings {

    /** Kept copies and revisions disappear this long after they were stamped / captured (local wall clock). */
    public static final int RETENTION_MAX_AGE = 30 * 86400;
    /** At most this many kept copies per account; the oldest are deleted for real. */
    public static final int KEPT_MAX_PER_ACCOUNT = 5000;
    /** Newest revisions kept per message. */
    public static final int EDIT_MAX_REVISIONS = 20;
    /** DMs / basic groups: one batch removing this many local messages of one chat is a history clear. */
    public static final int CLEAR_LIKE_THRESHOLD = 50;
    /** Kept copies really deleted per sweeper step. */
    public static final int SWEEP_BATCH = 256;
    /** Rows removed per step of an explicit purge. */
    public static final int PURGE_BATCH = 500;
    /** Message ids per SQL IN (...) list. */
    public static final int IN_LIST_CHUNK = 1000;

    private ZgHistorySettings() {
    }

    public static boolean isShowDeletedMessages() {
        return ZgSettings.showDeletedMessages.get();
    }

    public static boolean isEditHistoryEnabled() {
        return ZgSettings.editHistoryEnabled.get();
    }

    /**
     * Whether NEW kept copies and NEW revisions may be recorded right now (Pro capability "edithistory"). True while
     * Pro is free. Never used on a read or remove path.
     */
    public static boolean mayCapture() {
        try {
            return ZgPro.canCaptureRetainedHistory();
        } catch (Throwable e) {
            FileLog.e(e);
            return true; // free-first: an unreadable decision never blocks the user's own switch
        }
    }

    /** Local wall clock in Unix seconds. Never server time (iOS sgRetentionNow). */
    public static int localNow() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    public static long getKeptDeletedPurgeBefore() {
        return ZgSettings.keptDeletedPurgeBefore.get();
    }

    public static long getEditHistoryPurgeBefore() {
        return ZgSettings.editHistoryPurgeBefore.get();
    }

    /** Kept copies stamped at or before this time are due for deletion. */
    public static int keptCutoff(int now) {
        return clampToInt(Math.max((long) now - RETENTION_MAX_AGE, getKeptDeletedPurgeBefore()));
    }

    /** Revisions captured at or before this time are hidden and due for deletion. */
    public static int editCutoff(int now) {
        return clampToInt(Math.max((long) now - RETENTION_MAX_AGE, getEditHistoryPurgeBefore()));
    }

    /** Persists "delete every kept copy stamped until now" as max(current, now) and returns the cutoff. */
    public static synchronized int requestKeptDeletedPurge() {
        final long value = Math.max(getKeptDeletedPurgeBefore(), localNow());
        ZgSettings.keptDeletedPurgeBefore.set(value);
        return clampToInt(value);
    }

    /** Persists "delete every revision captured until now" as max(current, now) and returns the cutoff. */
    public static synchronized int requestEditHistoryPurge() {
        final long value = Math.max(getEditHistoryPurgeBefore(), localNow());
        ZgSettings.editHistoryPurgeBefore.set(value);
        return clampToInt(value);
    }

    static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}

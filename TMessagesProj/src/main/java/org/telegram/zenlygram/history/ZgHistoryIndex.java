/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;

import java.util.ArrayList;

/**
 * Per-account in-memory mirror of the history tables, the only thing the UI thread reads (chat bubbles, menus).
 *
 * <ul>
 *   <li>kept: dialog id → message id → deletion time of the kept copy;</li>
 *   <li>edits: dialog id → message id → newest capture time of its revisions.</li>
 * </ul>
 * Written on the account's storage queue by {@link ZgHistoryStore} (always together with the table), read from any
 * thread. Lookups are O(log n), never touch the database and never allocate. Before the first load it is empty, so a
 * kept copy briefly looks like a normal message right after start.
 */
public final class ZgHistoryIndex {

    private static final ZgHistoryIndex[] instances = new ZgHistoryIndex[UserConfig.MAX_ACCOUNT_COUNT];

    private final LongSparseArray<LongSparseIntArray> kept = new LongSparseArray<>();
    private final LongSparseArray<LongSparseIntArray> edits = new LongSparseArray<>();
    private volatile int keptCount;
    private volatile int editsCount;
    /** Loaded from the account's tables at least once since the database was opened. */
    private volatile boolean loaded;

    ZgHistoryIndex() {
    }

    /** The index of {@code account}, created on first use. */
    public static ZgHistoryIndex get(int account) {
        if (account < 0 || account >= instances.length) {
            return null;
        }
        ZgHistoryIndex index = instances[account];
        if (index == null) {
            synchronized (ZgHistoryIndex.class) {
                index = instances[account];
                if (index == null) {
                    instances[account] = index = new ZgHistoryIndex();
                }
            }
        }
        return index;
    }

    /** The index of {@code account} if it exists, else null. Never allocates (hot UI paths). */
    public static ZgHistoryIndex peek(int account) {
        if (account < 0 || account >= instances.length) {
            return null;
        }
        return instances[account];
    }

    // ---------------------------------------------------------------------------------------------------------
    // Kept copies
    // ---------------------------------------------------------------------------------------------------------

    public boolean hasAnyKept() {
        return keptCount > 0;
    }

    /** Whether the index mirrors the tables yet (false right after start, until the storage queue loaded it). */
    public boolean isLoaded() {
        return loaded;
    }

    public int getKeptCount() {
        return keptCount;
    }

    /** Deletion time of the kept copy (uid, mid), or 0 if it is not a kept copy. */
    public int getDeletedAt(long uid, int mid) {
        if (keptCount == 0) {
            return 0;
        }
        synchronized (this) {
            final LongSparseIntArray mids = kept.get(uid);
            return mids == null ? 0 : mids.get(mid, 0);
        }
    }

    public boolean isKept(long uid, int mid) {
        return getDeletedAt(uid, mid) != 0;
    }

    /** Whether the dialog has at least one kept copy. */
    public boolean hasKeptIn(long uid) {
        if (keptCount == 0) {
            return false;
        }
        synchronized (this) {
            final LongSparseIntArray mids = kept.get(uid);
            return mids != null && mids.size() > 0;
        }
    }

    synchronized void putKept(long uid, int mid, int deletedAt) {
        if (deletedAt == 0) {
            deletedAt = 1;
        }
        LongSparseIntArray mids = kept.get(uid);
        if (mids == null) {
            kept.put(uid, mids = new LongSparseIntArray());
        }
        if (mids.indexOfKey(mid) < 0) {
            keptCount++;
        }
        mids.put(mid, deletedAt);
    }

    synchronized void removeKept(long uid, int mid) {
        final LongSparseIntArray mids = kept.get(uid);
        if (mids == null) {
            return;
        }
        final int index = mids.indexOfKey(mid);
        if (index >= 0) {
            mids.removeAt(index);
            keptCount--;
            if (mids.size() == 0) {
                kept.remove(uid);
            }
        }
    }

    synchronized void removeKept(long uid, ArrayList<Integer> mids) {
        for (int i = 0, n = mids.size(); i < n; i++) {
            removeKept(uid, mids.get(i));
        }
    }

    /** The kept message ids of a dialog (a copy). */
    synchronized ArrayList<Integer> getKeptIn(long uid) {
        final ArrayList<Integer> result = new ArrayList<>();
        final LongSparseIntArray mids = kept.get(uid);
        if (mids != null) {
            for (int i = 0, n = mids.size(); i < n; i++) {
                result.add((int) mids.keyAt(i));
            }
        }
        return result;
    }

    /** Replaces the kept entries of one dialog (after a reload from the table). */
    synchronized void replaceKeptIn(long uid, LongSparseIntArray mids) {
        final LongSparseIntArray old = kept.get(uid);
        if (old != null) {
            keptCount -= old.size();
            kept.remove(uid);
        }
        if (mids != null && mids.size() > 0) {
            kept.put(uid, mids);
            keptCount += mids.size();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Edit history
    // ---------------------------------------------------------------------------------------------------------

    public boolean hasAnyEdits() {
        return editsCount > 0;
    }

    /** Newest capture time of the revisions of (uid, mid), or 0 if it has none. */
    public int getMaxCapturedAt(long uid, int mid) {
        if (editsCount == 0) {
            return 0;
        }
        synchronized (this) {
            final LongSparseIntArray mids = edits.get(uid);
            return mids == null ? 0 : mids.get(mid, 0);
        }
    }

    synchronized void putEdit(long uid, int mid, int capturedAt) {
        if (capturedAt == 0) {
            capturedAt = 1;
        }
        LongSparseIntArray mids = edits.get(uid);
        if (mids == null) {
            edits.put(uid, mids = new LongSparseIntArray());
        }
        final int index = mids.indexOfKey(mid);
        if (index < 0) {
            editsCount++;
            mids.put(mid, capturedAt);
        } else if (mids.valueAt(index) < capturedAt) {
            mids.put(mid, capturedAt);
        }
    }

    synchronized void removeEdits(long uid, int mid) {
        final LongSparseIntArray mids = edits.get(uid);
        if (mids == null) {
            return;
        }
        final int index = mids.indexOfKey(mid);
        if (index >= 0) {
            mids.removeAt(index);
            editsCount--;
            if (mids.size() == 0) {
                edits.remove(uid);
            }
        }
    }

    synchronized void removeEdits(long uid, ArrayList<Integer> mids) {
        for (int i = 0, n = mids.size(); i < n; i++) {
            removeEdits(uid, mids.get(i));
        }
    }

    /** Replaces the revision entries of one dialog (after a reload from the table). */
    synchronized void replaceEditsIn(long uid, LongSparseIntArray mids) {
        final LongSparseIntArray old = edits.get(uid);
        if (old != null) {
            editsCount -= old.size();
            edits.remove(uid);
        }
        if (mids != null && mids.size() > 0) {
            edits.put(uid, mids);
            editsCount += mids.size();
        }
    }

    /** Drops messages whose newest revision is at or before {@code cutoff} (they show nothing any more). */
    synchronized void pruneEdits(int cutoff) {
        for (int i = edits.size() - 1; i >= 0; i--) {
            final LongSparseIntArray mids = edits.valueAt(i);
            for (int j = mids.size() - 1; j >= 0; j--) {
                if (mids.valueAt(j) <= cutoff) {
                    mids.removeAt(j);
                    editsCount--;
                }
            }
            if (mids.size() == 0) {
                edits.removeAt(i);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Replaces everything (a new database was opened). {@code null} maps mean empty. */
    synchronized void replaceAll(LongSparseArray<LongSparseIntArray> newKept, LongSparseArray<LongSparseIntArray> newEdits) {
        kept.clear();
        edits.clear();
        int k = 0;
        int e = 0;
        if (newKept != null) {
            for (int i = 0, n = newKept.size(); i < n; i++) {
                final LongSparseIntArray mids = newKept.valueAt(i);
                if (mids != null && mids.size() > 0) {
                    kept.put(newKept.keyAt(i), mids);
                    k += mids.size();
                }
            }
        }
        if (newEdits != null) {
            for (int i = 0, n = newEdits.size(); i < n; i++) {
                final LongSparseIntArray mids = newEdits.valueAt(i);
                if (mids != null && mids.size() > 0) {
                    edits.put(newEdits.keyAt(i), mids);
                    e += mids.size();
                }
            }
        }
        keptCount = k;
        editsCount = e;
        loaded = true;
    }

    /** Replaces all kept entries, keeping the revision entries. */
    synchronized void replaceAllKept(LongSparseArray<LongSparseIntArray> newKept) {
        kept.clear();
        int k = 0;
        if (newKept != null) {
            for (int i = 0, n = newKept.size(); i < n; i++) {
                final LongSparseIntArray mids = newKept.valueAt(i);
                if (mids != null && mids.size() > 0) {
                    kept.put(newKept.keyAt(i), mids);
                    k += mids.size();
                }
            }
        }
        keptCount = k;
    }

    synchronized void clear() {
        kept.clear();
        edits.clear();
        keptCount = 0;
        editsCount = 0;
        loaded = false;
    }
}

package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.HashSet;
import java.util.Set;

/**
 * Logout/launch cleanup obligations for per-user cloud databases. A wipe obligation lives both in memory and as a
 * durable marker; it is discharged ONLY after every owned file is gone AND the marker removal committed. Android's
 * SharedPreferences.commit() may return false after already changing its in-memory value, so a failed commit always
 * re-asserts the in-memory obligation: no database may be (re)created while a stale on-disk marker could delete it on
 * the next launch.
 */
final class TasksCloudCleanup {
    interface Markers {
        /** Durable write; false when not committed (the in-memory value may still have changed). */
        boolean commit(long rawTelegramId, boolean present);

        /** True when a marker is present or cannot be read. */
        boolean present(long rawTelegramId);

        /** Raw ids with a marker (best effort). */
        Set<Long> all();
    }

    interface Files {
        /** Deletes every file owned by this user's cloud database; true iff none remains. */
        boolean deleteAll(long rawTelegramId);
    }

    private final Object lock = new Object();
    private final Set<Long> pending = new HashSet<>();
    private final Markers markers;
    private final Files files;

    TasksCloudCleanup(Markers markers, Files files) {
        this.markers = markers;
        this.files = files;
    }

    /** Synchronous part of logout: in-memory obligation first, then the durable marker (returned). */
    boolean markForLogout(long rawTelegramId) {
        require(rawTelegramId);
        return markers.commit(rawTelegramId, true);
    }

    void require(long rawTelegramId) {
        synchronized (lock) {
            pending.add(rawTelegramId);
        }
    }

    boolean mustWipe(long rawTelegramId) {
        synchronized (lock) {
            if (pending.contains(rawTelegramId)) return true;
        }
        return markers.present(rawTelegramId);
    }

    /** Discharges the obligation only after files are gone and the marker removal committed. */
    boolean wipe(long rawTelegramId) {
        require(rawTelegramId);
        if (!files.deleteAll(rawTelegramId) || !markers.commit(rawTelegramId, false)) return false;
        synchronized (lock) {
            pending.remove(rawTelegramId);
        }
        return true;
    }

    /** Before opening a database: false means it must NOT be opened/created now. */
    boolean clearBeforeOpen(long rawTelegramId) {
        return !mustWipe(rawTelegramId) || wipe(rawTelegramId);
    }

    /** Launch: cloud databases of users no longer signed in, plus every user with a surviving marker. */
    Set<Long> launchTargets(Set<Long> live, Iterable<Long> databaseOwners) {
        HashSet<Long> targets = new HashSet<>();
        for (Long owner : databaseOwners) if (owner != null && owner > 0 && !live.contains(owner)) targets.add(owner);
        for (Long marked : markers.all()) if (marked != null && marked > 0) targets.add(marked);
        return targets;
    }
}

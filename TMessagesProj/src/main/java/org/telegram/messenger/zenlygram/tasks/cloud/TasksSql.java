package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.List;

/**
 * Narrow SQL seam used by {@link TasksCloudLedger}. Production: Android SQLiteDatabase (TasksCloudRepository).
 * Bind values for exec/update: String, Long, Integer, byte[] or null. Query arguments must be non-null Strings
 * (Android rawQuery binds text only). Result cells: String, Long, byte[] or null.
 */
public interface TasksSql {
    interface Work<T> { T run(); }

    /** Thrown when the account fence closed: nothing in the open transaction is committed. */
    final class ClosedException extends RuntimeException {
        public ClosedException() { super("tasks_cloud_closed"); }
    }

    void exec(String sql, Object... args);

    /** Rows changed by an INSERT/UPDATE/DELETE. */
    int update(String sql, Object... args);

    List<Object[]> query(String sql, String... args);

    /** Runs work atomically; any exception rolls back. Implementations re-check the fence before committing. */
    <T> T transaction(Work<T> work);
}

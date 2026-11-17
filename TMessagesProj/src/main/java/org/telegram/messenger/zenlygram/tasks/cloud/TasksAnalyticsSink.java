package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * Typed Tasks analytics (taxonomy v1, design 30 §7.6) for pure Tasks code, which never references the customer-data
 * facade. ZenlyTasksSyncOwner.onPostInit (TR4-A3) installs the adapter onto ZenlyStats (pattern of ProFunnelSink);
 * until then, and in host tests, the no-op default drops every call. Calls carry no content and no identifiers beyond
 * the account slot; implementations must not throw.
 */
public interface TasksAnalyticsSink {
    /** {@link #shared} actions (tasks.shared.action). */
    String SHARED_INVITE_SENT = "invite_sent", SHARED_INVITE_ACCEPTED = "invite_accepted",
            SHARED_INVITE_DECLINED = "invite_declined", SHARED_LEFT = "left", SHARED_REVOKED = "revoked",
            SHARED_BLOCKED = "blocked";
    /** {@link #syncIssue} kinds (tasks.sync_issue.kind). */
    String ISSUE_CONFLICT_AUTO_RESOLVED = "conflict_auto_resolved", ISSUE_CONFLICT_MANUAL = "conflict_manual",
            ISSUE_STALLED = "stalled", ISSUE_BLOCKED_CREATION = "blocked_creation", ISSUE_INVALID = "invalid";
    /** {@link #migration} phases (tasks.migration.phase). */
    String MIGRATION_STARTED = "started", MIGRATION_COMPLETED = "completed", MIGRATION_FAILED = "failed";
    /** {@link #error} domains (error.occurred.domain). */
    String DOMAIN_SYNC = "tasks_sync", DOMAIN_MIGRATION = "tasks_migration";

    void taskCreated(int account, boolean cloud, boolean hasDue, int priority, boolean inList);

    void taskCompleted(int account, boolean cloud, boolean reopened);

    void taskDeleted(int account, boolean cloud);

    void listCreated(int account, boolean cloud);

    void listDeleted(int account, boolean cloud, int tasks);

    void shared(int account, String action);

    void migration(int account, String phase, Integer lists, Integer tasks, Integer skipped, Long durationS, String errorCode);

    void syncIssue(int account, String kind);

    /** {@code code}: a backend code, {@code http_<n>}, {@code timeout}, {@code offline} or {@code sqlite_<n>}. */
    void error(int account, String domain, String code);

    /** The installed sink (no-op by default). */
    final class Holder {
        private static final TasksAnalyticsSink NONE = new TasksAnalyticsSink() {
            @Override public void taskCreated(int account, boolean cloud, boolean hasDue, int priority, boolean inList) { }

            @Override public void taskCompleted(int account, boolean cloud, boolean reopened) { }

            @Override public void taskDeleted(int account, boolean cloud) { }

            @Override public void listCreated(int account, boolean cloud) { }

            @Override public void listDeleted(int account, boolean cloud, int tasks) { }

            @Override public void shared(int account, String action) { }

            @Override public void migration(int account, String phase, Integer lists, Integer tasks, Integer skipped, Long durationS, String errorCode) { }

            @Override public void syncIssue(int account, String kind) { }

            @Override public void error(int account, String domain, String code) { }
        };
        private static volatile TasksAnalyticsSink sink = NONE;

        private Holder() { }

        public static TasksAnalyticsSink get() {
            return sink;
        }

        /** {@code null} restores the no-op default. */
        public static void set(TasksAnalyticsSink value) {
            sink = value == null ? NONE : value;
        }
    }
}

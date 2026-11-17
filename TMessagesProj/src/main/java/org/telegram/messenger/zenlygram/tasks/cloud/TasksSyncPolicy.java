package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

/**
 * The Tasks sync owner's decisions (design 30 §6.1, §7.5), identical to iOS SGTasksSyncPolicy, plus the repository's
 * send-time consent check ({@link #sendAllowed}). Pure and host-tested
 * (zenlygram-tests/tasks/sync): no ZenlyCustomer, ZenlyConsent or ZenlyStats. ZenlyTasksSyncOwner gathers the inputs
 * (the customer layer's readiness as plain booleans, the repository's flags, the migration record) and performs what
 * these functions decide.
 */
public final class TasksSyncPolicy {
    private TasksSyncPolicy() { }

    /**
     * The consent gate of one account: Tasks traffic only once ZenlyCustomer.tasksMayRun (linked, grant acked for this
     * binding, a 2xx of it confirmed); with recording consent but not ready yet WAITING (the cut-over holds, no request);
     * anything else FORBIDDEN (exactly the pre-R4 board).
     */
    public static TasksCloudGate.Permission permission(boolean tasksMayRun, boolean recordingAllowed) {
        if (tasksMayRun) return TasksCloudGate.Permission.ALLOWED;
        return recordingAllowed ? TasksCloudGate.Permission.WAITING : TasksCloudGate.Permission.FORBIDDEN;
    }

    public enum DriveAction {
        /** startApp() (foreground) + requeue on the occasion. */
        DRIVE,
        /** stopApp(). */
        STOP,
        NONE
    }

    /** Per account, per input change: only ALLOWED is driven; a driven repository whose permission dropped stops. */
    public static DriveAction driveAction(TasksCloudGate.Permission permission, boolean isDriven) {
        if (permission == TasksCloudGate.Permission.ALLOWED) return DriveAction.DRIVE;
        return isDriven ? DriveAction.STOP : DriveAction.NONE;
    }

    /** The gate is back (ALLOWED) for a repository whose consent was withdrawn locally: resumeAfterConsentRegrant(). */
    public static boolean needsRegrantResume(TasksCloudGate.Permission permission, boolean consentWithdrawnFlag) {
        return permission == TasksCloudGate.Permission.ALLOWED && consentWithdrawnFlag;
    }

    /**
     * Send time (contract §6.7; the repository's TasksApi.SendGate, asked on the Pro worker right before a request is
     * signed): it still goes out only while the gate is ALLOWED and its database's consent is neither withdrawn nor at
     * another epoch than when the request was made. A withdrawal's discard advances the epoch and a re-grant never resets
     * it, so nothing queued before a withdrawal is sent after it, even past a quick re-grant.
     */
    public static boolean sendAllowed(TasksCloudGate.Permission permission, boolean consentWithdrawnFlag, long epochAtRequest, long consentEpoch) {
        return permission == TasksCloudGate.Permission.ALLOWED && !consentWithdrawnFlag && epochAtRequest == consentEpoch;
    }

    /**
     * The fence of the account's checked local writes: MIGRATING while its migration step's local part runs (per
     * account on Android: every account has its own database), else ARCHIVED while its cut-over is read-only, else OPEN.
     */
    public static ZenlyTaskModel.WriteState writeState(boolean migrationStepRunning, TasksCutOver cutOver) {
        if (migrationStepRunning) return ZenlyTaskModel.WriteState.MIGRATING;
        return cutOver != null && cutOver.isReadOnly() ? ZenlyTaskModel.WriteState.ARCHIVED : ZenlyTaskModel.WriteState.OPEN;
    }
}

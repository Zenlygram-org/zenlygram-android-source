/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;

/**
 * Which board the Tasks screen shows and what its Cloud / device switch offers (iOS TasksScopePreference.effectiveScope
 * and TasksScopeSwitch; the import's ZenlyTasksRouter). Pure: the inputs are the consent gate of Cloud Tasks
 * (ZenlyTasksSyncOwner.permission), the account's cut-over (ZenlyTasksSyncOwner.cutOver, design 30 §7.5) and the two
 * remembered picks.
 *
 * <pre>
 *   cut-over   switch                        shown by default            the pick is remembered
 *   NONE       Cloud / On This Device        On This Device              as the choice before the cut-over
 *   ARCHIVE    Cloud / Device archive        Cloud                       separately, as the choice after it
 *   RETIRED    none                          Cloud, always               not at all
 * </pre>
 * A FORBIDDEN gate (no data consent) reads as NONE whatever the cut-over was: the device board is writable again, as
 * the sync owner and the store's write fence decide it too. Without a Cloud service in this build there is no switch
 * and only the device board.
 */
public final class ZgTasksRouting {

    private ZgTasksRouting() {
    }

    /** What the switch under the navigation bar offers. */
    public enum Switch {
        /** No switch: this build has no Cloud Tasks. Only the device board. */
        DEVICE_ONLY,
        /** "Cloud" / "On This Device": the device board is writable. */
        CLOUD_AND_DEVICE,
        /** "Cloud" / "Device archive": the device board is a read-only archive of its Cloud copy. */
        CLOUD_AND_ARCHIVE,
        /** No switch: every device row is in Cloud. Only Cloud. */
        CLOUD_ONLY
    }

    /** Which of the two remembered picks a choice made now is stored as. */
    public enum PickSlot {
        /** The choice while the device board is writable (iOS sgtasks.scope.&lt;account&gt;). */
        BEFORE_CUT_OVER,
        /** The choice after the device rows were copied (iOS sgtasks.scope.v2.&lt;account&gt;). */
        AFTER_CUT_OVER,
        /** Nothing to remember: there is no switch. */
        NONE
    }

    /** The cut-over the routing reads: NONE for a missing one and under a FORBIDDEN (or missing) gate. */
    public static TasksCutOver effectiveCutOver(TasksCloudGate.Permission permission, TasksCutOver cutOver) {
        if (cutOver == null || permission == null || permission == TasksCloudGate.Permission.FORBIDDEN) {
            return TasksCutOver.NONE;
        }
        return cutOver;
    }

    public static Switch scopeSwitch(boolean cloudAvailable, TasksCloudGate.Permission permission, TasksCutOver cutOver) {
        if (!cloudAvailable) {
            return Switch.DEVICE_ONLY;
        }
        switch (effectiveCutOver(permission, cutOver).kind) {
            case ARCHIVE:
                return Switch.CLOUD_AND_ARCHIVE;
            case RETIRED:
                return Switch.CLOUD_ONLY;
            default:
                return Switch.CLOUD_AND_DEVICE;
        }
    }

    /**
     * The board that is shown.
     *
     * @param pick        the choice remembered before the cut-over, null = never chosen (the device board)
     * @param archivePick the choice remembered after the cut-over, null = never chosen (Cloud)
     */
    public static ZgTasksScope scope(boolean cloudAvailable, TasksCloudGate.Permission permission, TasksCutOver cutOver,
                                     ZgTasksScope pick, ZgTasksScope archivePick) {
        switch (scopeSwitch(cloudAvailable, permission, cutOver)) {
            case CLOUD_AND_DEVICE:
                return pick != null ? pick : ZgTasksScope.DEVICE;
            case CLOUD_AND_ARCHIVE:
                return archivePick != null ? archivePick : ZgTasksScope.CLOUD;
            case CLOUD_ONLY:
                return ZgTasksScope.CLOUD;
            default:
                return ZgTasksScope.DEVICE;
        }
    }

    /** Where the user's explicit pick goes under this switch. A scope that only follows the cut-over is never stored. */
    public static PickSlot pickSlot(Switch scopeSwitch) {
        if (scopeSwitch == Switch.CLOUD_AND_DEVICE) {
            return PickSlot.BEFORE_CUT_OVER;
        }
        if (scopeSwitch == Switch.CLOUD_AND_ARCHIVE) {
            return PickSlot.AFTER_CUT_OVER;
        }
        return PickSlot.NONE;
    }

    /** The device segment reads "Device archive" instead of "On This Device". */
    public static boolean deviceIsArchive(Switch scopeSwitch) {
        return scopeSwitch == Switch.CLOUD_AND_ARCHIVE || scopeSwitch == Switch.CLOUD_ONLY;
    }
}

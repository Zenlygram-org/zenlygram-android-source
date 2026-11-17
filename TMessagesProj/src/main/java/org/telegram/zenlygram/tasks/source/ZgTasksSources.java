/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.source;

import androidx.annotation.UiThread;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.zenlygram.cloud.ZgBackend;
import org.telegram.zenlygram.tasks.ZgTasksPrefs;
import org.telegram.zenlygram.tasks.model.ZgTasksRouting;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;

/**
 * The two sources of every account slot, and which of them the Tasks screens show (the Android side of
 * {@link ZgTasksRouting}). One source per slot and Telegram user: every screen of an account shares it, so a change
 * made on one screen reaches the others as a new board. When another user takes the slot (logout, account change) the
 * old sources are disposed and new ones made.
 * Main thread only.
 */
public final class ZgTasksSources {

    private static final ZgDeviceTasksSource[] DEVICE = new ZgDeviceTasksSource[UserConfig.MAX_ACCOUNT_COUNT];
    private static final ZgCloudTasksSource[] CLOUD = new ZgCloudTasksSource[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgTasksSources() {
    }

    private static long userId(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    @UiThread
    public static ZgDeviceTasksSource device(int account) {
        final long userId = userId(account);
        ZgDeviceTasksSource source = DEVICE[account];
        if (source == null || source.userId() != userId) {
            if (source != null) {
                source.dispose();
            }
            DEVICE[account] = source = new ZgDeviceTasksSource(account, userId);
        }
        return source;
    }

    @UiThread
    public static ZgCloudTasksSource cloud(int account) {
        final long userId = userId(account);
        ZgCloudTasksSource source = CLOUD[account];
        if (source == null || source.userId() != userId) {
            if (source != null) {
                source.dispose();
            }
            CLOUD[account] = source = new ZgCloudTasksSource(account, userId);
        }
        return source;
    }

    public static ZgTasksSource of(int account, ZgTasksScope scope) {
        return scope == ZgTasksScope.CLOUD ? cloud(account) : device(account);
    }

    /** The slot's user logged out: its sources stop and are forgotten. */
    @UiThread
    public static void forget(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        if (DEVICE[account] != null) {
            DEVICE[account].dispose();
            DEVICE[account] = null;
        }
        if (CLOUD[account] != null) {
            CLOUD[account].dispose();
            CLOUD[account] = null;
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Which board
    // -----------------------------------------------------------------------------------------------------------

    /**
     * This build can show Cloud Tasks at all: the cloud client is on and has a server to talk to. Without it there is
     * no switch and only the device board (iOS shows a "not available" card instead; a switch that leads nowhere is
     * left out here).
     */
    public static boolean cloudAvailable() {
        try {
            return ZgBackend.enabled() && ZenlyPro.configuredStorageToken() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static TasksCloudGate.Permission permission(int account) {
        return ZenlyTasksSyncOwner.permission(account);
    }

    private static TasksCutOver cutOver(int account) {
        return ZenlyTasksSyncOwner.cutOver(account);
    }

    /** What the Cloud / device switch of the account offers now. */
    public static ZgTasksRouting.Switch scopeSwitch(int account) {
        return ZgTasksRouting.scopeSwitch(cloudAvailable(), permission(account), cutOver(account));
    }

    /** The board the account's Tasks screen shows now: the remembered pick, or what the cut-over makes the default. */
    public static ZgTasksScope scope(int account) {
        final long userId = userId(account);
        return ZgTasksRouting.scope(cloudAvailable(), permission(account), cutOver(account),
            ZgTasksPrefs.getPick(ZgTasksRouting.PickSlot.BEFORE_CUT_OVER, userId),
            ZgTasksPrefs.getPick(ZgTasksRouting.PickSlot.AFTER_CUT_OVER, userId));
    }

    /** The user picked a segment: remembered for the account, under the switch as it is now. */
    public static void pick(int account, ZgTasksScope scope) {
        ZgTasksPrefs.setPick(ZgTasksRouting.pickSlot(scopeSwitch(account)), userId(account), scope);
    }

    /**
     * The source a task added from outside the board ("Add to Tasks") goes to: the board the user works on, when it
     * takes a new task now; otherwise the other one, when that does (an unlinked Cloud board sends the user to the
     * tasks on this device itself). Null when neither takes one (the caller says why).
     */
    @UiThread
    public static ZgTasksSource forNewTask(int account) {
        final ZgTasksScope shown = scope(account);
        final boolean deviceTakes = !cutOver(account).isReadOnly();
        final boolean cloudTakes = cloudAvailable() && cloud(account).peek().canCompose();
        if (shown == ZgTasksScope.CLOUD) {
            return cloudTakes ? cloud(account) : deviceTakes ? device(account) : null;
        }
        return deviceTakes ? device(account) : cloudTakes ? cloud(account) : null;
    }
}

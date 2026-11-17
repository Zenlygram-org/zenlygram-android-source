/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.UiThread;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksStore;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.zenlygram.tasks.data.ZgLegacyTasksImport;
import org.telegram.zenlygram.tasks.data.ZgTaskAssigneeStore;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;
import org.telegram.zenlygram.tasks.source.ZgTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;
import org.telegram.zenlygram.tasks.ui.ZgTasksBoardActivity;
import org.telegram.zenlygram.tasks.ui.ZgTasksNameAlert;
import org.telegram.zenlygram.tasks.ui.ZgTasksUi;

import java.util.HashSet;

/**
 * Entry points of Zenlygram Tasks (iOS SGTasks), for the settings screen, the main tabs and the logout cleanup.
 *
 * <ul>
 *   <li>Data: every Telegram account has its tasks on this device (the engine's per-user store, ZenlyTasksStore) and,
 *       with the account's data consent and a linked Zenlygram account, its Cloud tasks (TasksCloudRepository). The
 *       screens reach both through {@link ZgTasksSource}; which one is shown follows the account's cut-over
 *       ({@link ZgTasksSources}). Nothing leaves the device without the consent: the engine checks it before every
 *       request. There are no reminders or notifications.</li>
 *   <li>Screens: the board ({@link ZgTasksBoardActivity}) is the Tasks main tab and also opens from Settings &gt;
 *       Zenlygram &gt; Tools &gt; Tasks with a back arrow.</li>
 *   <li>Pro: only creating a task or a list asks {@link ZgTasksGate}.</li>
 *   <li>The board of the builds before Cloud Tasks (one for the whole device, zg_tasks.db) is imported once into the
 *       device store of the account that is selected then ({@link ZgLegacyTasksImport}).</li>
 * </ul>
 *
 * <p><b>Logout</b> ({@link #onAccountLoggedOut}). The device tasks are per Telegram user now, so the iOS rule "the last
 * logout wipes Tasks" reads as follows here:
 * <ul>
 *   <li>One of several accounts logs out: its device store stays on the device, as the import left it and as the
 *       engine needs it (the store holds the account's migration record: the copy to Cloud resumes at the next sign-in
 *       instead of starting over, and a deleted copy is never made twice). Another account cannot read it: every read
 *       and write is bound to the signed-in user. Its Cloud store is purged by the engine (ZgBackend.onLogout), its
 *       remembered Cloud / device pick is forgotten, and its sources are released.</li>
 *   <li>The last account logs out: every user's device store is deleted, with the assignee side store and what is
 *       left of the old device-wide board (also its kept copy, zg_tasks.imported.db). Nothing of Tasks remains.</li>
 * </ul>
 */
public final class ZgTasks {

    public interface ListCallback {
        /** The new list's id, or null when the store could not tell which one it is (it is on the board either way). */
        void onCreated(String listId);
    }

    private ZgTasks() {
    }

    /** Opens the board as a screen with a back arrow (Settings &gt; Zenlygram &gt; Tasks), on the board the account uses. Never gated. */
    @UiThread
    public static void open(BaseFragment from) {
        open(from, null);
    }

    /** Opens the board on {@code scope} when the account's switch offers it (null: the board the account uses). */
    @UiThread
    public static void open(BaseFragment from, ZgTasksScope scope) {
        if (from == null) {
            return;
        }
        Bundle args = null;
        if (scope != null) {
            args = new Bundle();
            args.putString(ZgTasksBoardActivity.ARG_SCOPE, scope.wire);
        }
        from.presentFragment(new ZgTasksBoardActivity(args));
    }

    /** The Show Tasks Tab switch: stored, then applied live by the main tabs. Never touches a task. */
    public static void setTabVisible(boolean visible) {
        ZgTasksPrefs.setTabVisible(visible);
    }

    public static boolean isTabVisible() {
        return ZgTasksPrefs.isTabVisible();
    }

    /**
     * The main screen of {@code account} is up: the one-time import of the old device-wide board starts now, before
     * any Tasks screen is opened and before the account's copy to Cloud can look at an empty device store.
     */
    @UiThread
    public static void prepare(int account) {
        try {
            if (UserConfig.getInstance(account).isClientActivated()) {
                ZgLegacyTasksImport.run(account, null);
            }
        } catch (Throwable ignore) {
            // The board starts the import again before its first read; never break the main screen.
        }
    }

    /**
     * The gated New List flow on {@code source}: gate, name alert, gate again right before the write (Pro may lapse
     * while the alert is open), then the store. {@code onCreated} runs on the UI thread once the list is stored.
     */
    @UiThread
    public static void createList(BaseFragment fragment, ZgTasksSource source, ListCallback onCreated) {
        createList(fragment, source, null, null, onCreated);
    }

    /**
     * {@code value} / {@code text}: the name typed before and why it is not created yet (the copy of the device tasks
     * to Cloud was running its local step), so another try keeps it (iOS presentTasksCreateListAlert).
     */
    private static void createList(final BaseFragment fragment, final ZgTasksSource source, String value, String text, final ListCallback onCreated) {
        if (fragment == null || source == null || fragment.getParentActivity() == null) {
            return;
        }
        if (!source.board().canCompose()) {
            // A read-only board has no "New List"; reached only when the board changed under the tap.
            if (source.board().readOnly != null) {
                ZgTasksUi.note(fragment, ZgTasksUi.readOnlyText(source.board().readOnly));
            }
            return;
        }
        if (!ZgTasksGate.allowCreate(fragment)) {
            return;
        }
        final int limit = source.listTitleLimit();
        ZgTasksNameAlert.show(fragment,
            LocaleController.getString(R.string.ZG_tasks_filter_newList),
            text != null ? text : LocaleController.getString(R.string.ZG_tasks_newFolder_prompt),
            LocaleController.getString(R.string.ZG_tasks_newFolder_placeholder),
            value, limit, name -> {
                if (!ZgTasksGate.allowCreate(fragment)) {
                    return;
                }
                source.createList(name, (listId, error) -> {
                    if (error == null) {
                        if (onCreated != null) {
                            onCreated.onCreated(listId);
                        }
                        return;
                    }
                    if (error.kind == ZgTasksError.Kind.MIGRATING) {
                        // The alert has closed: the same name again, with why it is not there yet.
                        AndroidUtilities.runOnUIThread(() -> createList(fragment, source, name,
                            LocaleController.getString(R.string.ZG_tasks_migration_busy), onCreated), 200);
                        return;
                    }
                    ZgTasksUi.report(fragment, error, source.board(), limit);
                });
            });
    }

    /** A plain alert with OK (iOS tasksErrorAlert); the text is always one of the Tasks messages, never a raw error. */
    @UiThread
    public static void showError(BaseFragment fragment, String text) {
        if (fragment == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }

    /**
     * Called by MessagesController.performLogout after the account's config was cleared (iOS SGAccountCleanup). See
     * the class comment for what a logout removes: the picks and sources of the account that left, and, when no
     * activated account is left, every task of this device.
     */
    public static void onAccountLoggedOut(final int account) {
        try {
            final HashSet<Long> signedIn = new HashSet<>();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final UserConfig config = UserConfig.getInstance(a);
                if (config.isClientActivated() && config.getClientUserId() != 0) {
                    signedIn.add(config.getClientUserId());
                }
            }
            ZgTasksPrefs.forgetPicksExcept(signedIn);
            // The sources belong to the main thread; a logout forced by the server may arrive on another one.
            AndroidUtilities.runOnUIThread(() -> ZgTasksSources.forget(account));
            if (UserConfig.getActivatedAccountsCount() == 0) {
                ZenlyTasksStore.deleteAllStores(null);
                ZgTaskAssigneeStore.get().wipe();
                ZgLegacyTasksImport.wipe();
            }
        } catch (Throwable ignore) {
            // never break the logout itself
        }
    }
}

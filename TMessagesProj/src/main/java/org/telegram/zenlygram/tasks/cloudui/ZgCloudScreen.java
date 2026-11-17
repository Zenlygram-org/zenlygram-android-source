/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudError;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

/**
 * What the three Cloud Tasks screens share: one account's {@link TasksCloudRepository}, fixed when the screen is made.
 *
 * <ul>
 *   <li>The screen is a view of the repository's immutable {@link TasksCloudState}: it subscribes while it exists and is
 *       an owner of the repository ({@code start} / {@code stop}) while it is resumed, so the account syncs and its
 *       realtime hints run while one of these screens is in front, as on the board. It never touches SQL, JSON or the
 *       network; every change is a repository intent that is stored on this device first.</li>
 *   <li>Account fence: results and states of a repository other than the one opened, and anything after the screen
 *       closed, are ignored. If the slot holds another Telegram user when the screen comes back, it stops following
 *       (nothing more is shown or sent from it).</li>
 *   <li>While a consent hold makes the account read-only ({@link #readOnly}) nothing is queued: the action is refused
 *       with the reason before it reaches the repository.</li>
 * </ul>
 */
abstract class ZgCloudScreen extends UniversalFragment implements ZenlyStats.NamedScreen, NotificationCenter.NotificationCenterDelegate {

    protected TasksCloudRepository repository;
    /** The repository's latest state; null while this account's cloud tasks are not available to this screen. */
    protected TasksCloudState state;
    protected boolean destroyed;

    private TasksCloudRepository.Subscription subscription;
    private boolean owner;
    private final HashSet<String> askedNames = new HashSet<>();

    ZgCloudScreen(int account) {
        super();
        this.currentAccount = account;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean onFragmentCreate() {
        destroyed = false;
        getNotificationCenter().addObserver(this, NotificationCenter.zenlygramCustomerStateChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zenlygramConsentChanged);
        attach();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return fragmentView;
    }

    private void attach() {
        final TasksCloudRepository opened;
        try {
            opened = TasksCloudRepository.forAccount(currentAccount);
        } catch (RuntimeException notSignedIn) {
            repository = null;
            state = null;
            return;
        }
        repository = opened;
        // The first state arrives inside subscribe(), before any view exists: onStateChanged only reads what is there.
        subscription = opened.subscribe(value -> {
            if (destroyed || opened != repository) {
                return;
            }
            state = value;
            onStateChanged();
        });
    }

    /** Stops following an account that is no longer this slot's. */
    private void detach() {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        if (repository != null && owner) {
            repository.stop(this);
        }
        owner = false;
        repository = null;
        state = null;
        onStateChanged();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (repository == null) {
            return;
        }
        if (UserConfig.getInstance(currentAccount).getClientUserId() != repository.rawTelegramId()) {
            detach();
            return;
        }
        repository.start(this);
        owner = true;
        // Today, the time zone and the server's clock may have moved while the screen was away.
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (repository != null && owner) {
            repository.stop(this);
        }
        owner = false;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        getNotificationCenter().removeObserver(this, NotificationCenter.zenlygramCustomerStateChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.zenlygramConsentChanged);
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        if (repository != null && owner) {
            repository.stop(this);
        }
        owner = false;
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        // The consent gate decides whether the account is read-only; it can move without a new repository state.
        if (!destroyed) {
            onStateChanged();
        }
    }

    /** A new state, or something else the rows are drawn from, changed. Also called before the view exists. */
    protected void onStateChanged() {
        render();
    }

    protected final void render() {
        if (listView != null && !destroyed) {
            listView.adapter.update(true);
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // The account's state
    // -----------------------------------------------------------------------------------------------------------

    /** Why nothing can be changed right now, or null. */
    protected final ZgCloudReadOnly readOnly() {
        final TasksCloudState value = state;
        if (value == null || (value.status != TasksCloudState.Status.CONSENT_REQUIRED && value.status != TasksCloudState.Status.CONSENT_FENCED)) {
            return null;
        }
        return ZgCloudReadOnly.of(value.status, ZenlyTasksSyncOwner.permission(currentAccount), consentWithdrawn());
    }

    private boolean consentWithdrawn() {
        try {
            final long user = UserConfig.getInstance(currentAccount).getClientUserId();
            if (user <= 0) {
                return false;
            }
            final ConsentMachine.State consent = ZenlyConsent.state(user);
            return consent == ConsentMachine.State.WITHDRAWN || consent == ConsentMachine.State.WITHDRAWN_REMOTE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True (after saying why) while the account is read-only: the caller queues nothing. */
    protected final boolean refuseReadOnly() {
        final ZgCloudReadOnly reason = readOnly();
        if (reason == null) {
            return false;
        }
        showNotice(getString(ZgCloudText.readOnlyRes(reason)));
        return true;
    }

    /** Nothing of the account can be shown: it is closed, its storage failed, or it was never linked. */
    protected final boolean contentHidden() {
        final TasksCloudState value = state;
        return value == null || value.status == TasksCloudState.Status.CLOSED || value.status == TasksCloudState.Status.STORAGE_FAILED
            || (value.status == TasksCloudState.Status.NOT_LINKED && value.contentFree());
    }

    /** What the screen shows instead of its content, or 0 when the content can be trusted. */
    protected final int unavailableText() {
        final TasksCloudState value = state;
        if (value == null) {
            return R.string.ZenlyCloudTasksStatusClosed;
        }
        return ZgCloudText.unavailableRes(repository != null, value.status, value.shadowComplete, value.syncing, value.contentFree());
    }

    /** The row that says why nothing can be changed; nothing while changes may be made. */
    protected final void addReadOnlyNotice(ArrayList<UItem> items, int id) {
        final ZgCloudReadOnly reason = readOnly();
        if (reason != null) {
            items.add(UItem.asShadow(id, getString(ZgCloudText.readOnlyRes(reason))));
        }
    }

    /**
     * The whole task content an entry carries: a create's or a full update's own. A field edit alone is not a task: its
     * content is its task's, as shown while the edit is part of what the repository shows (with everything confirmed
     * before it), else the task as the server holds it with the edit's fields. Null otherwise.
     */
    protected static TasksWire.Content taskContent(TasksCloudState value, TasksCloudState.Pending pending) {
        final TasksWire.Operation draft = pending.draft;
        if (draft instanceof TasksWire.Operation.TaskCreate) {
            return ((TasksWire.Operation.TaskCreate) draft).content;
        }
        if (draft instanceof TasksWire.Operation.TaskUpdate) {
            return ((TasksWire.Operation.TaskUpdate) draft).content;
        }
        if (draft instanceof TasksWire.Operation.TaskPatch) {
            final TasksWire.Operation.TaskPatch patch = (TasksWire.Operation.TaskPatch) draft;
            final TasksCloudState.TaskView task = value.task(patch.taskId);
            if (task == null || !task.owner) {
                return null;
            }
            if (ZgCloudIssues.applied(pending.state)) {
                return task.content;
            }
            if (task.server == null) {
                return null;
            }
            try {
                return task.server.content.applying(patch.set);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Results
    // -----------------------------------------------------------------------------------------------------------

    /** A repository result fenced to this screen and the repository it opened; a refusal is explained. */
    protected final <T> TasksCloudRepository.Result<T> result(Utilities.Callback<T> onValue) {
        return result(onValue, this::showError);
    }

    protected final <T> TasksCloudRepository.Result<T> result(Utilities.Callback<T> onValue, Utilities.Callback<TasksCloudError> onError) {
        final TasksCloudRepository opened = repository;
        return (value, error) -> {
            if (destroyed || opened == null || opened != repository) {
                return;
            }
            if (error != null) {
                if (onError != null) {
                    onError.run(error);
                }
            } else if (onValue != null) {
                onValue.run(value);
            }
        };
    }

    protected final void showError(TasksCloudError error) {
        showNotice(ZgCloudText.error(error));
    }

    /** A passing note under the content (a refusal, a hold): several lines, so no text is cut. */
    protected final void showNotice(CharSequence text) {
        if (destroyed || fragmentView == null || getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, text, 4, Bulletin.DURATION_PROLONG).show();
    }

    // -----------------------------------------------------------------------------------------------------------
    // Dialogs and people
    // -----------------------------------------------------------------------------------------------------------

    /**
     * A two-button confirmation. {@code onConfirm} runs at most once, even on a fast double tap, and never after the
     * screen closed. Returns the shown dialog, or null when it could not be shown (a transition is running).
     */
    protected final AlertDialog confirm(CharSequence title, CharSequence message, CharSequence action, boolean destructive, CharSequence dismiss,
                                        Runnable onConfirm) {
        final Activity activity = getParentActivity();
        if (activity == null || destroyed) {
            return null;
        }
        final boolean[] confirmed = {false};
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setNegativeButton(dismiss != null ? dismiss : getString(R.string.Cancel), null);
        builder.setPositiveButton(action, (d, which) -> {
            if (confirmed[0] || destroyed) {
                return;
            }
            confirmed[0] = true;
            onConfirm.run();
        });
        final AlertDialog dialog = builder.create();
        if (showDialog(dialog) == null) {
            return null;
        }
        if (destructive) {
            dialog.redPositive();
        }
        return dialog;
    }

    /** A user as this account knows them (the name with the Telegram id), else "Telegram user id". */
    protected final String person(String telegramId) {
        return ZgCloudText.person(currentAccount, telegramId);
    }

    /** Reads the names of users that are only in the local Telegram database, then draws again. */
    protected final void loadNames(Collection<String> telegramIds) {
        ZgCloudText.loadNames(currentAccount, telegramIds, askedNames, () -> {
            if (!destroyed) {
                render();
            }
        });
    }

    /**
     * A tappable row: its text in the accent colour (an action), or red (a destructive one), and what it does. The
     * payload goes in object2: a String in a text row's object would be read as a sticker.
     */
    protected static UItem action(int id, int icon, CharSequence text, boolean destructive, ZgCloudTap tap) {
        final UItem item = UItem.asButton(id, icon, text);
        item.object2 = tap;
        return destructive ? item.red() : item.accent();
    }

    /** A stable row id for a row of {@code key} (a mutation, invitation or sender id) in {@code slot}. */
    protected static int rowId(String key, int slot) {
        // Above every fixed row id of the screens.
        return (Objects.hash(key, slot) & 0x3fffffff) | 0x40000000;
    }
}

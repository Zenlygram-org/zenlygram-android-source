/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.source.ZgCloudTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit consent to copy this device's tasks into Cloud tasks: the Cloud board's "Copy Device Tasks to Cloud…" and,
 * after the automatic copy, the Device archive's "Copy to Cloud" for the rows that are not in Cloud yet (iOS
 * TasksCloudImportController). Shows the repository's immutable preview as it is: which lists and tasks can be
 * copied, which already were, and which stay on this device and why.
 *
 * <p>Choices exist only under the R3 copy rules (a server that has not proved wire 4): where a list not copied yet
 * goes (a new cloud list, under its own or a chosen name, or the Cloud Inbox); nothing is shortened to fit, and the
 * device Inbox stays on the device unless the user picks a destination. Under the R4 copy rules every row is
 * normalized as the automatic copy builds it, every list becomes a list under its own normalized name (rename it in
 * Cloud afterwards) and the device Inbox goes to the Cloud Inbox; the summary, rules and skipped lines follow the
 * preview's rules. A list without a choice keeps its new tasks on this device when it makes no list (removed in
 * Cloud, or refused). Nothing is copied before Copy, and the tasks on this device are never changed.
 */
public final class ZgTasksCopyDialog {

    interface Host {
        Theme.ResourcesProvider resources();

        void choose(CharSequence title, CharSequence[] labels, int selected, Utilities.Callback<Integer> picked);

        /** Runs the copy with the user's choices; {@code failed} gets the message to show in the open dialog. */
        void confirm(TasksCloudImport.ImportChoices choices, Utilities.Callback<CharSequence> failed);

        /** The dialog went away, whoever closed it. */
        void closed();
    }

    private static final int TARGET_DEFAULT = 0;
    private static final int TARGET_NAMED = 1;
    private static final int TARGET_INBOX = 2;

    private final List<FolderChoice> folders = new ArrayList<>();
    private final Host host;
    private final TextView error;
    private final AlertDialog dialog;
    private boolean confirming;

    // -----------------------------------------------------------------------------------------------------------
    // One run of the copy, for a screen
    // -----------------------------------------------------------------------------------------------------------

    /**
     * One run of the copy for a screen: the preview is asked for, shown, and confirmed or closed. The dialogs are the
     * flow's own (not the screen's one visible dialog: the destination choice opens above the preview), so the screen
     * cancels the flow when it goes away.
     */
    public static final class Flow {
        private final BaseFragment fragment;
        private final int account;
        private final long userId;
        private ZgTasksCopyDialog dialog;
        private AlertDialog picker;
        private boolean ended;

        private Flow(BaseFragment fragment, int account) {
            this.fragment = fragment;
            this.account = account;
            this.userId = UserConfig.getInstance(account).getClientUserId();
        }

        /** Still asking for the preview or showing it. */
        public boolean isActive() {
            return !ended;
        }

        /** The screen goes away: nothing is copied and nothing more is shown. */
        public void cancel() {
            if (ended) {
                return;
            }
            ended = true;
            if (picker != null) {
                picker.dismiss();
                picker = null;
            }
            if (dialog != null) {
                dialog.dismiss();
            }
        }

        private void start() {
            final ZgCloudTasksSource cloud = ZgTasksSources.cloud(account);
            final TasksCloudRepository repository = cloud.repository();
            if (repository == null) {
                ended = true;
                ZgTasks.showError(fragment, text(R.string.ZenlyCloudTasksErrorAccount));
                return;
            }
            // Copying queues creates: under a consent hold nothing can be queued, and the board says why.
            final ZgBoard board = cloud.peek();
            if (board.readOnly != null) {
                ended = true;
                ZgTasksUi.note(fragment, ZgTasksUi.readOnlyText(board.readOnly));
                return;
            }
            repository.previewImport((preview, failure) -> {
                if (ended) {
                    return;
                }
                final Activity activity = fragment.getParentActivity();
                if (activity == null || fragment.isRemovingFromStack()) {
                    ended = true;
                    return;
                }
                if (failure != null) {
                    ended = true;
                    ZgTasksUi.report(fragment, ZgCloudTasksSource.error(failure), cloud.board(), 0);
                    return;
                }
                // The consent names the account the preview was made for; one that isn't this screen's is never offered.
                if (preview.telegramUserId != userId || UserConfig.getInstance(account).getClientUserId() != userId) {
                    ended = true;
                    ZgTasks.showError(fragment, text(R.string.ZenlyCloudTasksErrorAccount));
                    return;
                }
                dialog = new ZgTasksCopyDialog(activity, accountLabel(account), preview, new Host() {
                    @Override
                    public Theme.ResourcesProvider resources() {
                        return fragment.getResourceProvider();
                    }

                    @Override
                    public void choose(CharSequence title, CharSequence[] labels, int selected, Utilities.Callback<Integer> picked) {
                        if (ended) {
                            return;
                        }
                        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
                        builder.setTitle(title);
                        builder.setItems(labels, (d, which) -> picked.run(which));
                        builder.setNegativeButton(text(R.string.ZenlyCloudTasksCancel), null);
                        picker = builder.create();
                        picker.show();
                    }

                    @Override
                    public void confirm(TasksCloudImport.ImportChoices choices, Utilities.Callback<CharSequence> failed) {
                        if (ended) {
                            return;
                        }
                        if (cloud.repository() != repository || UserConfig.getInstance(account).getClientUserId() != userId) {
                            failed.run(text(R.string.ZenlyCloudTasksErrorAccount));
                            return;
                        }
                        repository.confirmImport(preview, choices, (result, error) -> {
                            if (ended) {
                                return;
                            }
                            if (error != null) {
                                final ZgTasksError mapped = ZgCloudTasksSource.error(error);
                                failed.run(ZgTasksUi.errorText(mapped, cloud.board(), 0));
                                return;
                            }
                            ended = true;
                            dialog.dismiss();
                            // What was queued, said as it is: each copy shows its state until the server confirms it.
                            ZgTasks.showError(fragment, LocaleController.formatString(R.string.ZenlyCloudTasksImportDone,
                                result.queuedLists, result.queuedTasks, result.leftLocal));
                            // The offer ends with the copy; the originals stay on the device.
                            cloud.checkDeviceCopy();
                        });
                    }

                    @Override
                    public void closed() {
                        ended = true;
                        if (picker != null) {
                            picker.dismiss();
                            picker = null;
                        }
                    }
                });
            });
        }
    }

    /** Starts the copy for {@code fragment}'s screen. Main thread. */
    public static Flow open(BaseFragment fragment, int account) {
        final Flow flow = new Flow(fragment, account);
        flow.start();
        return flow;
    }

    /** The account as the user knows it: its name and its Telegram ID. Shown, never logged. */
    private static String accountLabel(int account) {
        final UserConfig config = UserConfig.getInstance(account);
        final TLRPC.User self = config.getCurrentUser();
        final String id = String.valueOf(config.getClientUserId());
        final String name = self == null ? null : UserObject.getUserName(self);
        return name == null || name.isEmpty() ? id : name + " (" + id + ")";
    }

    // -----------------------------------------------------------------------------------------------------------
    // The dialog
    // -----------------------------------------------------------------------------------------------------------

    void dismiss() {
        dialog.dismiss();
    }

    /** {@code account} is how the user knows the account the copy goes to (its name and Telegram ID). */
    private ZgTasksCopyDialog(Context context, CharSequence account, TasksCloudImport.ImportPreview preview, Host host) {
        this.host = host;
        final Theme.ResourcesProvider resources = host.resources();
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), dp(8));
        boolean r4 = preview.usesR4Rules();
        // Where the copy goes, in the consent itself: the Zenlygram server, for exactly this account.
        content.addView(info(context, resources, LocaleController.formatString(R.string.ZenlyCloudTasksImportDestination, account)), spaced(0));
        content.addView(info(context, resources, LocaleController.formatString(
            r4 ? R.string.ZenlyCloudTasksImportSummaryR4 : R.string.ZenlyCloudTasksImportSummary,
            preview.importableLists, preview.importableTasks)), spaced(8));
        // R4: only device rows not in Cloud yet are offered; the copy follows Cloud's text rules, the originals stay.
        if (r4) content.addView(info(context, resources, text(R.string.ZenlyCloudTasksImportRules)), spaced(8));

        boolean actionable = preview.importableLists > 0 || preview.importableTasks > 0;
        for (TasksCloudImport.FolderRow folder : preview.folders) {
            FolderChoice choice = new FolderChoice(context, folder, blockedTasks(preview, folder), preview.offersFolderChoice(folder), host);
            folders.add(choice);
            content.addView(choice.view, spaced(16));
            actionable |= choice.canChoose;
        }

        final List<TasksCloudImport.TaskRow> skipped = new ArrayList<>();
        for (TasksCloudImport.TaskRow task : preview.tasks) {
            if (task.status == TasksCloudImport.RowStatus.INVALID) {
                skipped.add(task);
            }
        }
        if (!skipped.isEmpty()) {
            content.addView(label(context, resources, text(r4 ? R.string.ZenlyCloudTasksImportSkippedR4 : R.string.ZenlyCloudTasksImportSkipped)), spaced(16));
            for (TasksCloudImport.TaskRow task : skipped) {
                content.addView(info(context, resources, task.title + " — " + reason(task.invalidField)), spaced(4));
            }
        }
        if (!actionable) {
            content.addView(info(context, resources, text(R.string.ZenlyCloudTasksImportNothing)), spaced(16));
        }

        error = info(context, resources, "");
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resources));
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        content.addView(error, spaced(12));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resources);
        builder.setTitle(text(R.string.ZenlyCloudTasksImportTitle));
        builder.setView(content);
        builder.setNegativeButton(text(actionable ? R.string.ZenlyCloudTasksCancel : R.string.ZenlyCloudTasksClose), (d, which) -> d.dismiss());
        if (actionable) builder.setPositiveButton(text(R.string.ZenlyCloudTasksImportConfirm), (d, which) -> copy());
        dialog = builder.create();
        // Copy keeps the dialog open until the copy is queued: a refusal is shown in it.
        dialog.setDismissDialogByButtons(false);
        dialog.setOnDismissListener(d -> host.closed());
        dialog.show();
    }

    private void copy() {
        final TasksCloudImport.ImportChoices choices = choices();
        if (choices == null || confirming) {
            return;
        }
        confirming = true;
        setCopyEnabled(false);
        error.setVisibility(View.GONE);
        host.confirm(choices, message -> {
            confirming = false;
            setCopyEnabled(true);
            showError(message);
        });
    }

    private void setCopyEnabled(boolean enabled) {
        final View copy = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (copy != null) {
            copy.setEnabled(enabled);
            copy.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void showError(CharSequence message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    /** The user's explicit choices, or null after showing that a chosen name is missing. */
    private TasksCloudImport.ImportChoices choices() {
        TasksCloudImport.ImportChoices choices = TasksCloudImport.ImportChoices.none();
        for (FolderChoice folder : folders) {
            if (folder.target == TARGET_INBOX) {
                choices = choices.withFolderInCloudInbox(folder.row.incarnation);
            } else if (folder.target == TARGET_NAMED) {
                final String name = ZgTasksTitlePolicy.trim(folder.name.getText());
                if (!ZgTasksTitlePolicy.containsVisible(name)) {
                    showError(text(R.string.ZenlyCloudTasksFieldRequired));
                    AndroidUtilities.shakeViewSpring(folder.name, -3);
                    folder.name.requestFocus();
                    return null;
                }
                choices = choices.withFolderTitle(folder.row.incarnation, name);
            }
        }
        return choices;
    }

    private static int blockedTasks(TasksCloudImport.ImportPreview preview, TasksCloudImport.FolderRow folder) {
        int count = 0;
        for (TasksCloudImport.TaskRow task : preview.tasks) {
            if (task.status == TasksCloudImport.RowStatus.BLOCKED_BY_FOLDER && folder.localFolderId.equals(task.localFolderId)) {
                count++;
            }
        }
        return count;
    }

    static String status(TasksCloudImport.RowStatus status, String invalidField) {
        switch (status) {
            case NEW: return text(R.string.ZenlyCloudTasksImportStatusNew);
            case ALREADY_IMPORTED: return text(R.string.ZenlyCloudTasksImportStatusAlready);
            case PENDING: return text(R.string.ZenlyCloudTasksImportStatusPending);
            case RESEND: return text(R.string.ZenlyCloudTasksImportStatusResend);
            case INVALID: return LocaleController.formatString(R.string.ZenlyCloudTasksImportStatusInvalid, reason(invalidField));
            // R4: the built-in Inbox makes no list, its tasks go to the Cloud Inbox; a copy removed in Cloud is not made again.
            case CLOUD_INBOX: return text(R.string.ZenlyCloudTasksImportStatusCloudInbox);
            case REMOVED_IN_CLOUD: return text(R.string.ZenlyCloudTasksImportStatusRemoved);
            default: return text(R.string.ZenlyCloudTasksImportStatusBlocked);
        }
    }

    private static String reason(String field) {
        if ("title".equals(field)) {
            return text(R.string.ZenlyCloudTasksImportReasonTitle);
        }
        if ("description".equals(field)) {
            return text(R.string.ZenlyCloudTasksImportReasonDescription);
        }
        if ("due_at".equals(field)) {
            return text(R.string.ZenlyCloudTasksImportReasonDue);
        }
        return text(R.string.ZenlyCloudTasksImportReasonOther);
    }

    private static String text(int resource) {
        return LocaleController.getString(resource);
    }

    /** A paragraph of the dialog. The app does not mirror layouts: the text side follows the language by hand. */
    private static TextView info(Context context, Theme.ResourcesProvider resources, CharSequence text) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resources));
        view.setLineSpacing(dp(2), 1f);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        view.setText(text);
        return view;
    }

    private static TextView label(Context context, Theme.ResourcesProvider resources, CharSequence text) {
        final TextView view = info(context, resources, text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
        return view;
    }

    private static LinearLayout.LayoutParams spaced(int topDp) {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, topDp, 0, 0);
    }

    /** One list of the device: its state and, while the R3 copy can still take a choice for it, where it goes. */
    private static final class FolderChoice {
        final TasksCloudImport.FolderRow row;
        final LinearLayout view;
        final boolean canChoose;
        final EditTextBoldCursor name;
        int target = TARGET_DEFAULT;

        /** {@code choosable}: the preview offers a destination choice for this list (ImportPreview.offersFolderChoice). */
        FolderChoice(Context context, TasksCloudImport.FolderRow row, int blocked, boolean choosable, Host host) {
            this.row = row;
            final Theme.ResourcesProvider resources = host.resources();
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            final String title = row.inbox ? text(R.string.ZG_tasks_inbox_title) : row.title;
            view.addView(label(context, resources, title), spaced(0));
            view.addView(info(context, resources, status(row.status, row.invalidField)), spaced(2));
            if (blocked > 0) {
                // No choice for it (R4, or removed in Cloud): its new tasks stay on this device rather than wait for one.
                final int count = choosable ? R.string.ZenlyCloudTasksImportBlockedCount : R.string.ZenlyCloudTasksImportStaysCount;
                view.addView(info(context, resources, LocaleController.formatString(count, blocked)), spaced(2));
            }
            canChoose = choosable;
            name = new EditTextBoldCursor(context);
            if (!canChoose) return;
            final boolean valid = row.status == TasksCloudImport.RowStatus.NEW;
            final CharSequence[] targets = {
                valid ? LocaleController.formatString(R.string.ZenlyCloudTasksImportTargetList, row.title) : text(R.string.ZenlyCloudTasksImportTargetKeep),
                text(R.string.ZenlyCloudTasksImportTargetNamed),
                text(R.string.ZenlyCloudTasksInbox)
            };
            final String targetTitle = text(R.string.ZenlyCloudTasksImportTarget);
            // The destination as a row of its own: what is chosen now, and a tap changes it.
            final TextView picker = info(context, resources, targetTitle + ": " + targets[target]);
            picker.setTextColor(Theme.getColor(Theme.key_dialogTextLink, resources));
            picker.setPadding(0, dp(6), 0, dp(6));
            picker.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resources), 0.06f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            view.addView(picker, spaced(4));

            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
            name.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resources));
            name.setHint(text(R.string.ZenlyCloudTasksListName));
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setSingleLine(true);
            name.setPadding(dp(16), dp(11), dp(16), dp(11));
            name.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resources));
            name.setCursorWidth(1.5f);
            name.setBackground(Theme.createRoundRectDrawable(dp(22), Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resources), 0.06f)));
            // The Cloud limit in characters as the user sees them; nothing is cut in half (as ZgTasksNameAlert).
            name.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
                final String updated = TextUtils.concat(dest.subSequence(0, dstart), source.subSequence(start, end),
                    dest.subSequence(dend, dest.length())).toString();
                final int length = ZgTasksTitlePolicy.graphemeCount(updated);
                return length <= ZgTasksTitlePolicy.FOLDER_LIMIT || length < ZgTasksTitlePolicy.graphemeCount(dest) ? null : dest.subSequence(dstart, dend);
            }});
            name.setText(row.inbox ? "" : row.title);
            name.setVisibility(View.GONE);
            view.addView(name, spaced(6));
            picker.setOnClickListener(v -> host.choose(targetTitle, targets, target, position -> {
                target = position;
                picker.setText(targetTitle + ": " + targets[position]);
                name.setVisibility(position == TARGET_NAMED ? View.VISIBLE : View.GONE);
            }));
        }
    }
}

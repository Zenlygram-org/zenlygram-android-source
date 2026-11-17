/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.zenlygram.tasks.model.ZgBoardTask;
import org.telegram.zenlygram.tasks.model.ZgTasksDeadline;

import java.util.Objects;

/**
 * One task row of the board (iOS TasksTaskCell): the round check (its own 48dp target, sends the explicit target
 * state), the title (grey and struck through when done), a line under it (the list name under "All"; for a Cloud task
 * also "Cloud Inbox" or "Shared with you by …"), a metadata line with the assignee and the deadline, and for a Cloud
 * task a status line ("Not sent yet", "Invitation pending", …). The deadline of an open overdue task is red. A tap on
 * the body opens the task (the list's item click), a long press opens the row menu. A row that may not be completed
 * (a read-only board) shows its check dimmed and does not take the tap.
 */
public class ZgTaskCell extends FrameLayout implements Theme.Colorable {

    public interface Delegate {
        /** The check was tapped: set the task to {@code completed} (never a toggle of a stale state). */
        void onSetCompleted(String taskId, boolean completed);
    }

    /** What a row shows; equal models need no rebind. */
    public static final class Model {
        final ZgBoardTask task;
        final String listTitle;
        final String deadlineText;
        final boolean overdue;
        /** The Cloud row's status line (sync, inactive access), already joined; null for none. */
        final String statusText;
        /**
         * The assignee as the row says it: the device note's name, or the Cloud assignment's "Name · state"; null for
         * none. Resolved by the board (it knows the account's people), never by the cell.
         */
        final String assigneeText;

        public Model(ZgBoardTask task, String listTitle, String deadlineText, boolean overdue, String statusText, String assigneeText) {
            this.task = task;
            this.listTitle = listTitle;
            this.deadlineText = deadlineText;
            this.overdue = overdue;
            this.statusText = statusText;
            this.assigneeText = assigneeText;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Model)) {
                return false;
            }
            final Model m = (Model) o;
            return overdue == m.overdue && Objects.equals(task, m.task) && Objects.equals(listTitle, m.listTitle)
                && Objects.equals(deadlineText, m.deadlineText) && Objects.equals(statusText, m.statusText)
                && Objects.equals(assigneeText, m.assigneeText);
        }

        @Override
        public int hashCode() {
            return Objects.hash(task, listTitle, deadlineText, overdue, statusText, assigneeText);
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout checkFrame;
    private final CheckBox2 checkBox;
    private final TextView titleView;
    private final TextView listView;
    private final TextView metaView;
    private final TextView statusView;
    private Model model;
    private Delegate delegate;
    private boolean needDivider;

    public ZgTaskCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);

        checkFrame = new FrameLayout(context);
        checkBox = new CheckBox2(context, 24, resourcesProvider);
        checkBox.setDrawUnchecked(true);
        checkBox.setDrawBackgroundAsArc(10);
        checkBox.setColor(Theme.key_checkbox, Theme.key_checkbox, Theme.key_checkboxCheck);
        checkBox.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        checkFrame.addView(checkBox, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        checkFrame.setOnClickListener(v -> {
            if (model != null && delegate != null && model.task.canComplete) {
                delegate.onSetCompleted(model.task.id, !model.task.completed);
            }
        });
        addView(checkFrame, LayoutHelper.createFrame(52, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 6, 0, 6, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final int gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setGravity(gravity);
        titleView.setMaxLines(6);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        listView = new TextView(context);
        listView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        listView.setGravity(gravity);
        listView.setSingleLine(true);
        listView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        metaView = new TextView(context);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        metaView.setGravity(gravity);
        texts.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setGravity(gravity);
        texts.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity | Gravity.TOP,
            LocaleController.isRTL ? 18 : 64, 11, LocaleController.isRTL ? 64 : 18, 11));
        setMinimumHeight(dp(50));
        updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A row always spans the list. The list gives its rows WRAP_CONTENT, so an AT_MOST width would shrink the row
        // to its text and leave the rest of the section card unpainted (a black or ghost area beside the row).
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    public void set(Model model, Delegate delegate, boolean divider) {
        final boolean animated = this.model != null && model != null && this.model.task.id.equals(model.task.id);
        this.model = model;
        this.delegate = delegate;
        this.needDivider = divider;
        final ZgBoardTask task = model.task;
        titleView.setText(task.title);
        if (task.completed) {
            titleView.setPaintFlags(titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            titleView.setPaintFlags(titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }
        checkBox.setChecked(task.completed, animated);
        if (model.listTitle != null) {
            listView.setText(model.listTitle);
            listView.setVisibility(VISIBLE);
        } else {
            listView.setVisibility(GONE);
        }
        final CharSequence meta = metadataText(model);
        if (meta != null) {
            metaView.setText(meta);
            metaView.setVisibility(VISIBLE);
        } else {
            metaView.setVisibility(GONE);
        }
        if (model.statusText != null) {
            statusView.setText(model.statusText);
            statusView.setVisibility(VISIBLE);
        } else {
            statusView.setVisibility(GONE);
        }
        checkFrame.setContentDescription(LocaleController.getString(task.completed ? R.string.ZG_tasks_a11y_markNotDone : R.string.ZG_tasks_a11y_markDone));
        // A row of a read-only board (or a shared task without access) cannot be completed: no target, a dimmed check.
        checkFrame.setClickable(task.canComplete);
        checkFrame.setImportantForAccessibility(task.canComplete ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
        checkBox.setAlpha(task.canComplete ? 1f : 0.45f);
        updateColors();
        invalidate();
    }

    private CharSequence metadataText(Model model) {
        final ZgBoardTask task = model.task;
        if (model.assigneeText == null && model.deadlineText == null) {
            return null;
        }
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        if (model.assigneeText != null) {
            appendIcon(sb, R.drawable.msg_contacts);
            sb.append(' ').append(ZgTasksDeadline.isolatedDisplayText(model.assigneeText));
        }
        if (model.deadlineText != null) {
            if (sb.length() > 0) {
                sb.append("   ");
            }
            final int start = sb.length();
            appendIcon(sb, R.drawable.msg_calendar2);
            sb.append(' ').append(ZgTasksDeadline.isolatedDisplayText(model.deadlineText));
            if (model.overdue) {
                sb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private static void appendIcon(SpannableStringBuilder sb, int res) {
        final int start = sb.length();
        sb.append('i');
        final ColoredImageSpan span = new ColoredImageSpan(res);
        span.setScale(0.7f, 0.7f);
        sb.setSpan(span, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Override
    public void updateColors() {
        final boolean done = model != null && model.task.completed;
        titleView.setTextColor(Theme.getColor(done ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        listView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        // "Needs your attention" is said in words; the color only adds to it.
        statusView.setTextColor(Theme.getColor(model != null && model.task.sync == ZgBoardTask.Sync.NEEDS_ATTENTION
            ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        checkFrame.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            final float left = LocaleController.isRTL ? 0 : dp(64);
            final float right = getMeasuredWidth() - (LocaleController.isRTL ? dp(64) : 0);
            canvas.drawLine(left, getMeasuredHeight() - 1, right, getMeasuredHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (model == null) {
            return;
        }
        final ZgBoardTask task = model.task;
        final StringBuilder sb = new StringBuilder(task.title);
        if (model.assigneeText != null) {
            sb.append(", ").append(LocaleController.formatString(R.string.ZG_tasks_a11y_assigneeValue, model.assigneeText));
        }
        if (model.deadlineText != null) {
            sb.append(", ").append(LocaleController.formatString(R.string.ZG_tasks_a11y_deadlineValue, model.deadlineText));
        }
        if (model.listTitle != null) {
            sb.append(", ").append(model.listTitle);
        }
        if (model.statusText != null) {
            sb.append(", ").append(model.statusText);
        }
        sb.append(", ").append(LocaleController.getString(task.completed ? R.string.ZG_tasks_a11y_stateDone : R.string.ZG_tasks_a11y_stateOpen));
        info.setContentDescription(sb);
        // A task that opens read-only must not promise editing.
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
            LocaleController.getString(task.canEdit ? R.string.ZG_tasks_a11y_editHint : R.string.ZG_tasks_a11y_openReadOnlyHint)));
        if (task.canComplete) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.acc_action_small_button,
                LocaleController.getString(task.completed ? R.string.ZG_tasks_a11y_markNotDone : R.string.ZG_tasks_a11y_markDone)));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.acc_action_small_button && model != null && delegate != null && model.task.canComplete) {
            delegate.onSetCompleted(model.task.id, !model.task.completed);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    /** The UItem factory of task rows. */
    public static class Factory extends UItem.UItemFactory<ZgTaskCell> {
        static {
            setup(new Factory());
        }

        @Override
        public ZgTaskCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new ZgTaskCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((ZgTaskCell) view).set((Model) item.object, (Delegate) item.object2, divider);
        }

        public static UItem of(Model model, Delegate delegate) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = model.task.id.hashCode();
            item.object = model;
            item.object2 = delegate;
            return item;
        }

        public static boolean is(UItem item) {
            return item != null && item.instanceOf(Factory.class);
        }

        public static ZgBoardTask taskOf(UItem item) {
            return is(item) && item.object instanceof Model ? ((Model) item.object).task : null;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            final ZgBoardTask ta = taskOf(a);
            final ZgBoardTask tb = taskOf(b);
            return ta != null && tb != null && ta.id.equals(tb.id);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return Objects.equals(a.object, b.object);
        }
    }
}

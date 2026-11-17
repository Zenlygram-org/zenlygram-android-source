/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.tasks.model.ZgTasksCalendar;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The top of the Calendar view (iOS TasksCalendarHeaderView): the Today and No Deadline choices (No Deadline with the
 * undated count), the summary "&lt;day&gt; · N tasks" that shows or hides the month (presentation only), and the month
 * card itself. Counts cover the current list filter, open and completed together.
 */
public class ZgTasksCalendarHeaderView extends LinearLayout implements Theme.Colorable {

    public interface Delegate extends ZgTasksMonthGridView.Delegate {
        void onToday();

        void onNoDeadline();

        void onToggleMonth();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Choice todayChoice;
    private final Choice undatedChoice;
    private final FrameLayout summaryRow;
    private final TextView summaryText;
    private final ImageView summaryArrow;
    private final ZgTasksMonthGridView grid;
    private Delegate delegate;
    private boolean monthExpanded;
    private boolean todaySelected;
    private boolean undatedSelected;

    public ZgTasksCalendarHeaderView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);
        setPadding(dp(12), 0, dp(12), dp(4));

        final LinearLayout choices = new LinearLayout(context);
        choices.setOrientation(HORIZONTAL);
        todayChoice = new Choice(context, R.drawable.msg_calendar2);
        todayChoice.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onToday();
            }
        });
        undatedChoice = new Choice(context, R.drawable.msg_clear);
        undatedChoice.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onNoDeadline();
            }
        });
        choices.addView(todayChoice, LayoutHelper.createLinear(0, 40, 1f, 0, 0, 4, 0));
        choices.addView(undatedChoice, LayoutHelper.createLinear(0, 40, 1f, 4, 0, 0, 0));
        addView(choices, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 6));

        summaryRow = new FrameLayout(context);
        summaryText = new TextView(context);
        summaryText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        summaryText.setTypeface(AndroidUtilities.bold());
        summaryText.setSingleLine(true);
        summaryText.setEllipsize(TextUtils.TruncateAt.END);
        summaryRow.addView(summaryText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 36 : 8, 0, LocaleController.isRTL ? 8 : 36, 0));
        summaryArrow = new ImageView(context);
        summaryArrow.setScaleType(ImageView.ScaleType.CENTER);
        summaryArrow.setImageResource(R.drawable.arrow_more);
        summaryRow.addView(summaryArrow, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 6, 0, 6, 0));
        summaryRow.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onToggleMonth();
            }
        });
        summaryRow.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(summaryText.getText() + ", " + LocaleController.getString(monthExpanded
                    ? R.string.ZG_tasks_calendar_monthShown : R.string.ZG_tasks_calendar_monthHidden));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                    LocaleController.getString(R.string.ZG_tasks_calendar_monthToggleHint)));
            }
        });
        addView(summaryRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        grid = new ZgTasksMonthGridView(context, resourcesProvider);
        addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
        updateColors();
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
        grid.setDelegate(delegate);
    }

    public ZgTasksMonthGridView getGrid() {
        return grid;
    }

    /**
     * @param selectedDay the selected day, or null when No Deadline is chosen
     * @param summaryCount tasks of the current selection, open and completed
     */
    public void set(LocalDate selectedDay, LocalDate today, ZgTasksCalendar.DueCounts counts, int summaryCount,
                    boolean monthExpanded, YearMonth shownMonth) {
        this.monthExpanded = monthExpanded;
        todaySelected = selectedDay != null && selectedDay.equals(today);
        undatedSelected = selectedDay == null;
        todayChoice.set(LocaleController.getString(R.string.ZG_tasks_relative_today), todaySelected);
        todayChoice.setContentDescription(LocaleController.getString(R.string.ZG_tasks_relative_today) + ". "
            + LocaleController.getString(R.string.ZG_tasks_calendar_todayHint));
        final String undated = LocaleController.getString(R.string.ZG_tasks_calendar_noDeadline);
        undatedChoice.set(counts.undated > 0 ? undated + "  " + counts.undated : undated, undatedSelected);
        undatedChoice.setContentDescription(undated + ", " + ZgTasksText.taskCount(counts.undated) + ". "
            + LocaleController.getString(R.string.ZG_tasks_calendar_noDeadlineHint));
        final String selection = selectedDay != null ? ZgTasksText.shortDayTitle(selectedDay, today) : undated;
        summaryText.setText(selection + " · " + ZgTasksText.taskCount(summaryCount));
        summaryArrow.setRotation(monthExpanded ? 180 : 0);
        grid.setVisibility(monthExpanded ? VISIBLE : GONE);
        grid.set(shownMonth, selectedDay, today, counts);
        updateColors();
    }

    @Override
    public void updateColors() {
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        summaryText.setTextColor(black);
        summaryArrow.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
        summaryRow.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(black, 0.08f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        grid.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        grid.updateColors();
        todayChoice.updateColors();
        undatedChoice.updateColors();
    }

    /** A Today / No Deadline choice: icon and text; chosen = card fill, accent text and a check. */
    private final class Choice extends LinearLayout {
        private final ImageView icon;
        private final TextView textView;
        private final ImageView check;
        private boolean chosen;

        Choice(Context context, int iconRes) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(10), 0, dp(10), 0);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setImageResource(iconRes);
            addView(icon, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            check = new ImageView(context);
            check.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            check.setImageResource(R.drawable.msg_check_s);
            addView(check, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        }

        void set(CharSequence text, boolean chosen) {
            this.chosen = chosen;
            textView.setText(text);
            check.setVisibility(chosen ? VISIBLE : GONE);
            setSelected(chosen);
        }

        void updateColors() {
            final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
            final int color = chosen ? accent : black;
            textView.setTextColor(color);
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            check.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            setBackground(Theme.createRoundRectDrawable(dp(20), chosen
                ? Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)
                : Theme.multAlpha(black, 0.06f)));
        }
    }
}

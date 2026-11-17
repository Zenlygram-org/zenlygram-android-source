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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.zenlygram.tasks.model.ZgTasksCalendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The month card of the Calendar view (iOS TasksCalendarMonthGridView), drawn on a canvas: the month title (a tap
 * opens a month / year picker), previous / next arrows (mirrored in RTL), the weekday row from the date language's
 * first day of the week, and day cells with the task count (99+), a Today bar and the selected-day highlight.
 * A horizontal swipe changes the month. TalkBack sees every day, the title and the arrows as separate items.
 */
public class ZgTasksMonthGridView extends View implements Theme.Colorable {

    public interface Delegate {
        void onDaySelected(LocalDate day);

        void onMonthChanged(YearMonth month);

        void onChooseMonth();
    }

    public static final YearMonth MIN_MONTH = YearMonth.of(1969, 12);
    public static final YearMonth MAX_MONTH = YearMonth.of(2038, 1);

    private static final int ROWS = 6;
    private static final int ID_TITLE = 1;
    private static final int ID_PREV = 2;
    private static final int ID_NEXT = 3;
    private static final int ID_DAY_BASE = 100;

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint weekPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint countPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Drawable prevIcon;
    private final Drawable nextIcon;
    private final GridAccessibility accessibility;
    private final int touchSlop;

    private Delegate delegate;
    private YearMonth month = YearMonth.now();
    private LocalDate selected;
    private LocalDate today = LocalDate.now();
    private ZgTasksCalendar.DueCounts counts;
    private int firstDayOfWeek = 1;

    private float downX;
    private float downY;
    private boolean swiping;

    public ZgTasksMonthGridView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        titlePaint.setTextSize(dp(16));
        titlePaint.setTypeface(AndroidUtilities.bold());
        weekPaint.setTextSize(dp(12));
        dayPaint.setTextSize(dp(15));
        countPaint.setTextSize(dp(10));
        countPaint.setTypeface(AndroidUtilities.bold());
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1, dp(1)));
        prevIcon = ContextCompat.getDrawable(context, R.drawable.msg_arrow_back).mutate();
        nextIcon = ContextCompat.getDrawable(context, R.drawable.msg_arrow_forward).mutate();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        accessibility = new GridAccessibility(this);
        ViewCompat.setAccessibilityDelegate(this, accessibility);
        setContentDescription(LocaleController.getString(R.string.ZG_tasks_calendar_label));
        updateColors();
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** {@code selected} null: no day is selected ("No deadline" is chosen). */
    public void set(YearMonth month, LocalDate selected, LocalDate today, ZgTasksCalendar.DueCounts counts) {
        this.month = clamp(month);
        this.selected = selected;
        this.today = today;
        this.counts = counts;
        this.firstDayOfWeek = ZgTasksText.firstDayOfWeek();
        accessibility.invalidateRoot();
        invalidate();
    }

    public YearMonth getMonth() {
        return month;
    }

    static YearMonth clamp(YearMonth m) {
        if (m.isBefore(MIN_MONTH)) {
            return MIN_MONTH;
        }
        if (m.isAfter(MAX_MONTH)) {
            return MAX_MONTH;
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------------------------

    private int titleHeight() {
        return dp(46);
    }

    private int weekHeight() {
        return dp(22);
    }

    private int cellHeight() {
        return dp(46);
    }

    private int pad() {
        return dp(8);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, pad() * 2 + titleHeight() + weekHeight() + cellHeight() * ROWS);
    }

    private float cellWidth() {
        return (getMeasuredWidth() - pad() * 2) / 7f;
    }

    /** Column of a day of the week (java.time numbering, Monday = 1) in this locale's order, RTL mirrored. */
    private int columnOf(int isoDay) {
        int column = ((isoDay - firstDayOfWeek) % 7 + 7) % 7;
        if (LocaleController.isRTL) {
            column = 6 - column;
        }
        return column;
    }

    /** Index of the first day of the month in the grid (0..6). */
    private int leadingBlanks() {
        return ((month.atDay(1).getDayOfWeek().getValue() - firstDayOfWeek) % 7 + 7) % 7;
    }

    private void dayBounds(int dayOfMonth, RectF out) {
        final int index = leadingBlanks() + dayOfMonth - 1;
        final int row = index / 7;
        final int isoDay = month.atDay(dayOfMonth).getDayOfWeek().getValue();
        final int column = columnOf(isoDay);
        final float cw = cellWidth();
        final float left = pad() + column * cw;
        final float top = pad() + titleHeight() + weekHeight() + row * cellHeight();
        out.set(left, top, left + cw, top + cellHeight());
    }

    private void prevBounds(RectF out) {
        final float size = dp(40);
        final float top = pad() + (titleHeight() - size) / 2f;
        if (LocaleController.isRTL) {
            out.set(getMeasuredWidth() - pad() - size, top, getMeasuredWidth() - pad(), top + size);
        } else {
            out.set(pad(), top, pad() + size, top + size);
        }
    }

    private void nextBounds(RectF out) {
        final float size = dp(40);
        final float top = pad() + (titleHeight() - size) / 2f;
        if (LocaleController.isRTL) {
            out.set(pad(), top, pad() + size, top + size);
        } else {
            out.set(getMeasuredWidth() - pad() - size, top, getMeasuredWidth() - pad(), top + size);
        }
    }

    private void titleBounds(RectF out) {
        out.set(pad() + dp(44), pad(), getMeasuredWidth() - pad() - dp(44), pad() + titleHeight());
    }

    private boolean canGoPrev() {
        return month.isAfter(MIN_MONTH);
    }

    private boolean canGoNext() {
        return month.isBefore(MAX_MONTH);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public void updateColors() {
        titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        weekPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        dayPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        countPaint.setColor(accent);
        strokePaint.setColor(accent);
        final int icon = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        prevIcon.setColorFilter(new PorterDuffColorFilter(icon, PorterDuff.Mode.SRC_IN));
        nextIcon.setColorFilter(new PorterDuffColorFilter(icon, PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);

        // Title and arrows
        final String title = ZgTasksText.monthTitle(month.getYear(), month.getMonthValue());
        titleBounds(rect);
        final CharSequence ellipsized = TextUtils.ellipsize(title, titlePaint, rect.width(), TextUtils.TruncateAt.END);
        final float titleWidth = titlePaint.measureText(ellipsized, 0, ellipsized.length());
        canvas.drawText(ellipsized, 0, ellipsized.length(), rect.centerX() - titleWidth / 2f,
            rect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint);
        prevBounds(rect);
        drawIcon(canvas, LocaleController.isRTL ? nextIcon : prevIcon, rect, canGoPrev());
        nextBounds(rect);
        drawIcon(canvas, LocaleController.isRTL ? prevIcon : nextIcon, rect, canGoNext());

        // Weekdays
        final float cw = cellWidth();
        final float weekBase = pad() + titleHeight() + weekHeight() / 2f - (weekPaint.descent() + weekPaint.ascent()) / 2f;
        for (int iso = 1; iso <= 7; iso++) {
            final String symbol = ZgTasksText.weekdaySymbol(iso);
            final float x = pad() + columnOf(iso) * cw + cw / 2f;
            canvas.drawText(symbol, x - weekPaint.measureText(symbol) / 2f, weekBase, weekPaint);
        }

        // Days
        final boolean dark = Theme.isCurrentThemeDark();
        final int days = month.lengthOfMonth();
        for (int d = 1; d <= days; d++) {
            final LocalDate date = month.atDay(d);
            dayBounds(d, rect);
            final boolean isSelected = date.equals(selected);
            final boolean isToday = date.equals(today);
            if (isSelected) {
                final float inset = dp(3);
                rect.inset(inset, inset);
                fillPaint.setColor(Theme.multAlpha(accent, dark ? 0.28f : 0.14f));
                canvas.drawRoundRect(rect, dp(10), dp(10), fillPaint);
                canvas.drawRoundRect(rect, dp(10), dp(10), strokePaint);
                rect.inset(-inset, -inset);
            }
            final String number = String.valueOf(d);
            dayPaint.setTypeface(isToday || isSelected ? AndroidUtilities.bold() : null);
            dayPaint.setColor(isToday ? accent : black);
            final int count = counts != null ? counts.count(date) : 0;
            final float numberY = count > 0 ? rect.centerY() - dp(1) : rect.centerY() + dp(5);
            canvas.drawText(number, rect.centerX() - dayPaint.measureText(number) / 2f, numberY, dayPaint);
            if (count > 0) {
                final String text = count > 99 ? "99+" : String.valueOf(count);
                canvas.drawText(text, rect.centerX() - countPaint.measureText(text) / 2f, rect.centerY() + dp(12), countPaint);
            }
            if (isToday) {
                fillPaint.setColor(accent);
                final float barW = dp(12);
                final float barH = dp(3);
                final float bottom = rect.bottom - dp(4);
                rect.set(rect.centerX() - barW / 2f, bottom - barH, rect.centerX() + barW / 2f, bottom);
                canvas.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
            }
        }
    }

    private void drawIcon(Canvas canvas, Drawable icon, RectF bounds, boolean enabled) {
        final int w = icon.getIntrinsicWidth();
        final int h = icon.getIntrinsicHeight();
        final int l = (int) (bounds.centerX() - w / 2f);
        final int t = (int) (bounds.centerY() - h / 2f);
        icon.setBounds(l, t, l + w, t + h);
        icon.setAlpha(enabled ? 255 : 70);
        icon.draw(canvas);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swiping = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float dx = event.getX() - downX;
                final float dy = event.getY() - downY;
                if (!swiping && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    swiping = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                final float dx = event.getX() - downX;
                final float dy = event.getY() - downY;
                if (swiping) {
                    if (Math.abs(dx) > dp(48)) {
                        final boolean forward = LocaleController.isRTL ? dx > 0 : dx < 0;
                        stepMonth(forward);
                    }
                } else if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) {
                    handleTap(event.getX(), event.getY());
                }
                swiping = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleTap(float x, float y) {
        final int id = hitTest(x, y);
        performVirtualClick(id);
    }

    private boolean performVirtualClick(int id) {
        if (delegate == null) {
            return false;
        }
        if (id == ID_TITLE) {
            delegate.onChooseMonth();
            return true;
        } else if (id == ID_PREV) {
            return stepMonth(false);
        } else if (id == ID_NEXT) {
            return stepMonth(true);
        } else if (id >= ID_DAY_BASE + 1 && id <= ID_DAY_BASE + 31) {
            final int day = id - ID_DAY_BASE;
            if (day <= month.lengthOfMonth()) {
                AndroidUtilities.vibrateCursor(this);
                delegate.onDaySelected(month.atDay(day));
                return true;
            }
        }
        return false;
    }

    private boolean stepMonth(boolean forward) {
        if (forward ? !canGoNext() : !canGoPrev()) {
            return false;
        }
        final YearMonth target = forward ? month.plusMonths(1) : month.minusMonths(1);
        month = target;
        accessibility.invalidateRoot();
        invalidate();
        if (delegate != null) {
            delegate.onMonthChanged(target);
        }
        return true;
    }

    private int hitTest(float x, float y) {
        prevBounds(rect);
        if (rect.contains(x, y)) {
            return ID_PREV;
        }
        nextBounds(rect);
        if (rect.contains(x, y)) {
            return ID_NEXT;
        }
        titleBounds(rect);
        if (rect.contains(x, y)) {
            return ID_TITLE;
        }
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            dayBounds(d, rect);
            if (rect.contains(x, y)) {
                return ID_DAY_BASE + d;
            }
        }
        return ExploreByTouchHelper.INVALID_ID;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    /** Whether a horizontal finger move here belongs to the grid (month swipe), not to the main tabs. */
    public boolean wantsHorizontalTouch() {
        return true;
    }

    /** The virtual TalkBack items: title, arrows, and one per day with its count. */
    private final class GridAccessibility extends ExploreByTouchHelper {
        private final Rect tmp = new Rect();

        GridAccessibility(View host) {
            super(host);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            return hitTest(x, y);
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            ids.add(ID_PREV);
            ids.add(ID_TITLE);
            ids.add(ID_NEXT);
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                ids.add(ID_DAY_BASE + d);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int id, @NonNull AccessibilityNodeInfoCompat node) {
            if (id == ID_TITLE) {
                titleBounds(rect);
                node.setContentDescription(ZgTasksText.monthTitle(month.getYear(), month.getMonthValue()) + ", "
                    + LocaleController.getString(R.string.ZG_tasks_calendar_chooseMonth));
            } else if (id == ID_PREV) {
                prevBounds(rect);
                node.setContentDescription(LocaleController.getString(R.string.ZG_tasks_calendar_previousMonth));
                node.setEnabled(canGoPrev());
            } else if (id == ID_NEXT) {
                nextBounds(rect);
                node.setContentDescription(LocaleController.getString(R.string.ZG_tasks_calendar_nextMonth));
                node.setEnabled(canGoNext());
            } else {
                final int day = Math.max(1, Math.min(month.lengthOfMonth(), id - ID_DAY_BASE));
                final LocalDate date = month.atDay(day);
                dayBounds(day, rect);
                final int count = counts != null ? counts.count(date) : 0;
                node.setContentDescription(ZgTasksText.fullDayTitle(date, today) + ", " + ZgTasksText.taskCount(count));
                node.setSelected(date.equals(selected));
            }
            rect.round(tmp);
            node.setBoundsInParent(tmp);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            node.setClickable(true);
        }

        @Override
        protected boolean onPerformActionForVirtualView(int id, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                final boolean done = performVirtualClick(id);
                if (done) {
                    invalidateVirtualView(id);
                    sendEventForVirtualView(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                }
                return done;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Month / year picker
    // ------------------------------------------------------------------------------------------------------------

    public interface MonthCallback {
        void onMonth(YearMonth month);
    }

    /** A sheet with month and year wheels and Done (iOS TasksCalendarMonthPicker). Back or swipe down cancel. */
    public static BottomSheet createMonthPicker(Context context, Theme.ResourcesProvider resourcesProvider, YearMonth current, MonthCallback callback) {
        final BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.fixNavigationBar();
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        final TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.ZG_tasks_calendar_chooseMonth));
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 8));

        final LinearLayout wheels = new LinearLayout(context);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        final NumberPicker monthPicker = new NumberPicker(context, resourcesProvider);
        monthPicker.setTextColor(textColor);
        monthPicker.setItemCount(5);
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setWrapSelectorWheel(true);
        monthPicker.setFormatter(ZgTasksText::monthName);
        final NumberPicker yearPicker = new NumberPicker(context, resourcesProvider);
        yearPicker.setTextColor(textColor);
        yearPicker.setItemCount(5);
        yearPicker.setMinValue(MIN_MONTH.getYear());
        yearPicker.setMaxValue(MAX_MONTH.getYear());
        yearPicker.setWrapSelectorWheel(false);
        final YearMonth start = clamp(current);
        monthPicker.setValue(start.getMonthValue());
        yearPicker.setValue(start.getYear());
        wheels.addView(monthPicker, LayoutHelper.createLinear(0, NumberPicker.DEFAULT_SIZE_PER_COUNT * 5, 0.6f));
        wheels.addView(yearPicker, LayoutHelper.createLinear(0, NumberPicker.DEFAULT_SIZE_PER_COUNT * 5, 0.4f));
        content.addView(wheels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));

        final ButtonWithCounterView done = new ButtonWithCounterView(context, resourcesProvider);
        done.setText(LocaleController.getString(R.string.Done), false);
        final boolean[] applied = {false};
        done.setOnClickListener(v -> {
            if (applied[0]) {
                return;
            }
            applied[0] = true;
            sheet.dismiss();
            callback.onMonth(clamp(YearMonth.of(yearPicker.getValue(), monthPicker.getValue())));
        });
        content.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 4, 16, 12));
        sheet.setCustomView(content);
        return sheet;
    }
}

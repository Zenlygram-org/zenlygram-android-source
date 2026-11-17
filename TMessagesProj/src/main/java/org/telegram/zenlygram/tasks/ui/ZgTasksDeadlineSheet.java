/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.zenlygram.tasks.model.ZgTasksDeadline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The deadline picker (iOS TasksDeadlinePickerController): Today / Tomorrow / In a Week shortcuts that keep the time,
 * Telegram's day / hour / minute wheels (as in the schedule-message picker), Save, and a red Remove Deadline when one
 * is set. It changes nothing until Save or Remove; back, an outside tap or a swipe down cancel.
 *
 * <ul>
 *   <li>It opens on the stored deadline exactly (even when overdue), otherwise on the start of the next hour.</li>
 *   <li>Save without touching anything returns the stored value to the second.</li>
 *   <li>Save is disabled while the chosen moment cannot be stored (before 1970 or after 2038-01-19 UTC).</li>
 *   <li>The device time zone; Telegram's 12/24-hour setting.</li>
 * </ul>
 */
public class ZgTasksDeadlineSheet extends BottomSheet {

    public interface Callback {
        void onSet(long dueAt);

        void onClear();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Callback callback;
    private final ZoneId zone;
    private final long nowSeconds;
    private final LocalDate today;
    private final LocalDate minDay;
    private final NumberPicker dayPicker;
    private final NumberPicker hourPicker;
    private final NumberPicker minutePicker;
    private final TextView[] shortcutViews = new TextView[3];
    private final ButtonWithCounterView saveButton;
    private final TextView rangeNotice;
    private long selectedSeconds;
    private boolean committed;

    public ZgTasksDeadlineSheet(Context context, Theme.ResourcesProvider resourcesProvider, Long current, Callback callback) {
        super(context, false, resourcesProvider);
        this.resourcesProvider = resourcesProvider;
        this.callback = callback;
        this.zone = ZgTasksText.zone();
        this.nowSeconds = System.currentTimeMillis() / 1000L;
        this.today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate();
        this.minDay = Instant.ofEpochSecond(ZgTasksDeadline.MIN_TIMESTAMP).atZone(zone).toLocalDate();
        final LocalDate maxDay = Instant.ofEpochSecond(ZgTasksDeadline.MAX_TIMESTAMP).atZone(zone).toLocalDate();
        this.selectedSeconds = ZgTasksDeadline.initialDate(current, nowSeconds, zone);
        fixNavigationBar();

        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);

        final LinearLayout content = new LinearLayout(context) {
            private boolean ignoreLayout;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                ignoreLayout = true;
                final int count = AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y ? 3 : 5;
                for (NumberPicker picker : new NumberPicker[]{dayPickerRef(), hourPickerRef(), minutePickerRef()}) {
                    if (picker != null) {
                        picker.setItemCount(count);
                        picker.getLayoutParams().height = dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * count;
                    }
                }
                ignoreLayout = false;
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            public void requestLayout() {
                if (!ignoreLayout) {
                    super.requestLayout();
                }
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);

        final TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString(R.string.ZG_tasks_deadline_title));
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 16, 22, 10));

        final LinearLayout shortcuts = new LinearLayout(context);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        final ZgTasksDeadline.Shortcut[] values = ZgTasksDeadline.Shortcut.values();
        final int[] titles = {R.string.ZG_tasks_relative_today, R.string.ZG_tasks_relative_tomorrow, R.string.ZG_tasks_deadline_nextWeek};
        for (int i = 0; i < values.length; i++) {
            final ZgTasksDeadline.Shortcut shortcut = values[i];
            final TextView pill = new TextView(context);
            pill.setText(LocaleController.getString(titles[i]));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            pill.setTypeface(AndroidUtilities.bold());
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setEllipsize(TextUtils.TruncateAt.END);
            pill.setPadding(dp(6), 0, dp(6), 0);
            pill.setContentDescription(LocaleController.getString(titles[i]) + ". " + LocaleController.getString(R.string.ZG_tasks_a11y_deadlineShortcutHint));
            pill.setOnClickListener(v -> applyShortcut(shortcut));
            shortcutViews[i] = pill;
            shortcuts.addView(pill, LayoutHelper.createLinear(0, 38, 1f, i == 0 ? 0 : 4, 0, i == values.length - 1 ? 0 : 4, 0));
        }
        content.addView(shortcuts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 0, 18, 6));

        final LinearLayout wheels = new LinearLayout(context);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setWeightSum(1.0f);

        dayPicker = new NumberPicker(context, resourcesProvider);
        dayPicker.setTextColor(textColor);
        dayPicker.setTextOffset(dp(10));
        dayPicker.setItemCount(5);
        dayPicker.setMinValue(0);
        dayPicker.setMaxValue((int) Math.max(0, ChronoUnit.DAYS.between(minDay, maxDay)));
        dayPicker.setWrapSelectorWheel(false);
        dayPicker.setFormatter(value -> ZgTasksText.wheelDayTitle(minDay.plusDays(value), today));

        hourPicker = new NumberPicker(context, resourcesProvider);
        hourPicker.setWrapSelectorWheel(true);
        hourPicker.setAllItemsCount(24);
        hourPicker.setItemCount(5);
        hourPicker.setTextColor(textColor);
        hourPicker.setTextOffset(-dp(10));
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setFormatter(ZgTasksText::hourLabel);

        minutePicker = new NumberPicker(context, resourcesProvider);
        minutePicker.setWrapSelectorWheel(true);
        minutePicker.setAllItemsCount(60);
        minutePicker.setItemCount(5);
        minutePicker.setTextColor(textColor);
        minutePicker.setTextOffset(-dp(34));
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setFormatter(value -> String.format(Locale.US, "%02d", value));

        wheels.addView(dayPicker, LayoutHelper.createLinear(0, 54 * 5, 0.5f));
        wheels.addView(hourPicker, LayoutHelper.createLinear(0, 54 * 5, 0.2f));
        wheels.addView(minutePicker, LayoutHelper.createLinear(0, 54 * 5, 0.3f));
        final NumberPicker.OnValueChangeListener onChange = (picker, oldVal, newVal) -> onWheelChanged();
        dayPicker.setOnValueChangedListener(onChange);
        hourPicker.setOnValueChangedListener(onChange);
        minutePicker.setOnValueChangedListener(onChange);
        content.addView(wheels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));

        rangeNotice = new TextView(context);
        rangeNotice.setText(LocaleController.getString(R.string.ZG_tasks_deadline_outOfRange));
        rangeNotice.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        rangeNotice.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rangeNotice.setGravity(Gravity.CENTER);
        rangeNotice.setVisibility(View.GONE);
        content.addView(rangeNotice, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 6));

        saveButton = new ButtonWithCounterView(context, resourcesProvider);
        saveButton.setText(LocaleController.getString(R.string.Save), false);
        saveButton.setOnClickListener(v -> {
            if (!ZgTasksDeadline.isStorable(selectedSeconds) || committed) {
                return;
            }
            committed = true;
            dismiss();
            callback.onSet(selectedSeconds);
        });
        content.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 6, 16, current != null ? 4 : 12));

        if (current != null) {
            final TextView remove = new TextView(context);
            remove.setText(LocaleController.getString(R.string.ZG_tasks_deadline_remove));
            remove.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
            remove.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            remove.setTypeface(AndroidUtilities.bold());
            remove.setGravity(Gravity.CENTER);
            remove.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(textColor, 0.08f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            remove.setOnClickListener(v -> {
                if (committed) {
                    return;
                }
                committed = true;
                dismiss();
                callback.onClear();
            });
            content.addView(remove, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 0, 16, 10));
        }

        setCustomView(content);
        showWheels(selectedSeconds);
        updateState();
    }

    private NumberPicker dayPickerRef() {
        return dayPicker;
    }

    private NumberPicker hourPickerRef() {
        return hourPicker;
    }

    private NumberPicker minutePickerRef() {
        return minutePicker;
    }

    /** Moves the wheels to {@code seconds} without treating it as a user change. */
    private void showWheels(long seconds) {
        final ZonedDateTime at = Instant.ofEpochSecond(seconds).atZone(zone);
        final long day = ChronoUnit.DAYS.between(minDay, at.toLocalDate());
        dayPicker.setValue((int) Math.max(dayPicker.getMinValue(), Math.min(dayPicker.getMaxValue(), day)));
        hourPicker.setValue(at.getHour());
        minutePicker.setValue(at.getMinute());
    }

    private void onWheelChanged() {
        final LocalDate day = minDay.plusDays(dayPicker.getValue());
        final LocalTime time = LocalTime.of(hourPicker.getValue(), minutePicker.getValue(), 0);
        selectedSeconds = ZonedDateTime.of(day, time, zone).toEpochSecond();
        updateState();
    }

    private void applyShortcut(ZgTasksDeadline.Shortcut shortcut) {
        final Long result = ZgTasksDeadline.shortcutDate(shortcut, selectedSeconds, nowSeconds, zone);
        if (result == null) {
            return;
        }
        selectedSeconds = result;
        showWheels(result);
        updateState();
    }

    private void updateState() {
        final boolean storable = ZgTasksDeadline.isStorable(selectedSeconds);
        saveButton.setEnabled(storable);
        saveButton.setAlpha(storable ? 1f : 0.5f);
        rangeNotice.setVisibility(storable ? View.GONE : View.VISIBLE);
        LocalDate selectedDay = null;
        if (storable) {
            selectedDay = Instant.ofEpochSecond(selectedSeconds).atZone(zone).toLocalDate();
        }
        final ZgTasksDeadline.Shortcut[] values = ZgTasksDeadline.Shortcut.values();
        final int text = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        for (int i = 0; i < values.length; i++) {
            final boolean enabled = ZgTasksDeadline.shortcutDate(values[i], storable ? selectedSeconds : ZgTasksDeadline.initialDate(null, nowSeconds, zone), nowSeconds, zone) != null;
            final boolean selected = selectedDay != null && selectedDay.equals(today.plusDays(values[i].dayOffset));
            final TextView view = shortcutViews[i];
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.4f);
            view.setSelected(selected);
            view.setTextColor(selected ? accent : text);
            if (selected) {
                final android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setCornerRadius(dp(19));
                border.setStroke(dp(2), accent);
                border.setColor(Theme.multAlpha(accent, 0.12f));
                view.setBackground(border);
            } else {
                view.setBackground(Theme.createRoundRectDrawable(dp(19), Theme.multAlpha(text, 0.06f)));
            }
        }
    }
}

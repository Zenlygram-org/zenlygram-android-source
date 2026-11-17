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
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;

/**
 * The quick-add composer pinned at the bottom of the board, above the tabs or the keyboard (iOS TasksQuickAddView):
 * a title field, the round + button, and the Assignee / Deadline pills with a clear button each. The pills sit side by
 * side when both fit and stack otherwise.
 *
 * <ul>
 *   <li>The keyboard's Done key submits and keeps the keyboard up for the next task.</li>
 *   <li>Typing or pasting past 200 characters is refused unless the edit shortens the text.</li>
 *   <li>+ is disabled only for a title with nothing visible, so a too-long title can still be sent to show why.</li>
 * </ul>
 */
public class ZgTasksComposerView extends LinearLayout implements Theme.Colorable {

    public interface Delegate {
        void onSubmit();

        void onPickAssignee();

        void onClearAssignee();

        void onPickDeadline();

        void onClearDeadline();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    public final EditTextBoldCursor editText;
    private final ImageView submitButton;
    private final Pill assigneePill;
    private final Pill deadlinePill;
    private Delegate delegate;
    private boolean deadlineOverdue;
    private String assigneeName;
    /** What the assignee pill says it does: kept on this device only, or saved with the Cloud task for the owner alone. */
    private int assigneeHint = R.string.ZG_tasks_a11y_pickAssigneeHint;

    public ZgTasksComposerView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(8), dp(10), dp(8));
        setClickable(true); // the touches on the card never reach the list under it

        final LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        // No multi-line flag, so the keyboard shows Done; the text still wraps on up to 4 lines.
        editText.setHorizontallyScrolling(false);
        editText.setMaxLines(4);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setPadding(dp(14), dp(9), dp(14), dp(9));
        editText.setCursorSize(dp(18));
        editText.setCursorWidth(1.5f);
        editText.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        final InputFilter[] existing = editText.getFilters();
        final InputFilter[] filters = new InputFilter[existing.length + 1];
        System.arraycopy(existing, 0, filters, 0, existing.length);
        filters[existing.length] = new DraftFilter();
        editText.setFilters(filters);
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                if (delegate != null) {
                    delegate.onSubmit();
                }
                return true; // keep the keyboard and the focus for rapid entry
            }
            return false;
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSubmitEnabled();
            }
        });
        inputRow.addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        submitButton = new ImageView(context);
        submitButton.setScaleType(ImageView.ScaleType.CENTER);
        submitButton.setImageResource(R.drawable.msg_add);
        submitButton.setContentDescription(LocaleController.getString(R.string.ZG_tasks_quickAdd_submit));
        submitButton.setOnClickListener(v -> {
            if (delegate != null && submitButton.isEnabled()) {
                delegate.onSubmit();
            }
        });
        // Always right of the field: the app does not mirror layouts (supportsRtl="false"), as Telegram's input bar.
        final LinearLayout.LayoutParams submitLp = LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL);
        submitLp.leftMargin = dp(8);
        inputRow.addView(submitButton, submitLp);
        addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        assigneePill = new Pill(context, R.drawable.msg_contacts);
        assigneePill.body.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onPickAssignee();
            }
        });
        assigneePill.clear.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onClearAssignee();
            }
        });
        deadlinePill = new Pill(context, R.drawable.msg_calendar2);
        deadlinePill.body.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onPickDeadline();
            }
        });
        deadlinePill.clear.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onClearDeadline();
            }
        });
        final PillsLayout pills = new PillsLayout(context);
        pills.addView(assigneePill);
        pills.addView(deadlinePill);
        addView(pills, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        setAssignee(null);
        setDeadline(null, false);
        updateColors();
        updateSubmitEnabled();
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setPlaceholder(CharSequence placeholder) {
        editText.setHint(placeholder);
    }

    /** The chosen name, or null for the "Assignee" placeholder. */
    public void setAssignee(String name) {
        assigneeName = name;
        assigneePill.set(name != null ? name : LocaleController.getString(R.string.ZG_tasks_assignee_title), name != null);
        assigneePill.body.setContentDescription((name != null
            ? LocaleController.formatString(R.string.ZG_tasks_a11y_assigneeValue, name)
            : LocaleController.getString(R.string.ZG_tasks_assignee_title)) + ". " + LocaleController.getString(assigneeHint));
    }

    /**
     * The Cloud board's composer: its assignee goes to Cloud with the task as the owner-only annotation, so the hint
     * never claims "saved only on this device" there.
     */
    public void setCloud(boolean cloud) {
        final int hint = cloud ? R.string.ZG_tasks_cloud_a11y_pickAssigneeHint : R.string.ZG_tasks_a11y_pickAssigneeHint;
        if (hint != assigneeHint) {
            assigneeHint = hint;
            setAssignee(assigneeName);
        }
    }

    /** The other board's draft comes back when the Cloud / device switch changes. */
    public void setText(CharSequence text) {
        editText.setText(text == null ? "" : text);
        editText.setSelection(editText.length());
    }

    /** The deadline text, or null for the "Deadline" placeholder. */
    public void setDeadline(String text, boolean overdue) {
        deadlineOverdue = overdue && text != null;
        deadlinePill.set(text != null ? text : LocaleController.getString(R.string.ZG_tasks_deadline_title), text != null);
        deadlinePill.body.setContentDescription((text != null
            ? LocaleController.formatString(R.string.ZG_tasks_a11y_deadlineValue, text)
            : LocaleController.getString(R.string.ZG_tasks_deadline_title)) + ". " + LocaleController.getString(R.string.ZG_tasks_a11y_pickDeadlineHint));
        updateColors();
    }

    public CharSequence getText() {
        return editText.getText();
    }

    public void clearText() {
        editText.setText("");
    }

    private void updateSubmitEnabled() {
        final boolean enabled = ZgTasksTitlePolicy.containsVisible(ZgTasksTitlePolicy.trim(editText.getText()));
        submitButton.setEnabled(enabled);
        submitButton.setAlpha(enabled ? 1f : 0.45f);
    }

    @Override
    public void updateColors() {
        setBackground(Theme.createRoundRectDrawable(dp(22), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        editText.setTextColor(text);
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        editText.setBackground(Theme.createRoundRectDrawable(dp(19), Theme.multAlpha(text, 0.06f)));
        submitButton.setBackground(Theme.createCircleDrawable(dp(38), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        submitButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        assigneePill.updateColors(false);
        deadlinePill.updateColors(deadlineOverdue);
    }

    /** Filters typing and pastes through the draft rule (iOS tasksAcceptsDraftEdit). */
    private static final class DraftFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            final String updated = TextUtils.concat(dest.subSequence(0, dstart), source.subSequence(start, end),
                dest.subSequence(dend, dest.length())).toString();
            if (ZgTasksTitlePolicy.acceptsDraftEdit(dest, updated)) {
                return null;
            }
            return dest.subSequence(dstart, dend);
        }
    }

    /** One metadata pill: icon + text, and a clear button while a value is set. */
    private final class Pill extends LinearLayout {
        final LinearLayout body;
        final ImageView icon;
        final TextView textView;
        final ImageView clear;
        private boolean hasValue;

        Pill(Context context, int iconRes) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            body = new LinearLayout(context);
            body.setOrientation(HORIZONTAL);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(dp(10), 0, dp(10), 0);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setImageResource(iconRes);
            body.addView(icon, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams textLp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
            textLp.leftMargin = dp(6);
            body.addView(textView, textLp);
            addView(body, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 1f, Gravity.CENTER_VERTICAL));

            clear = new ImageView(context);
            clear.setScaleType(ImageView.ScaleType.CENTER);
            clear.setImageResource(R.drawable.msg_close);
            clear.setContentDescription(LocaleController.getString(R.string.Clear));
            addView(clear, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL));
        }

        void set(String text, boolean hasValue) {
            this.hasValue = hasValue;
            textView.setText(text);
            clear.setVisibility(hasValue ? View.VISIBLE : View.GONE);
            body.setPadding(dp(10), 0, hasValue ? dp(2) : dp(10), 0);
        }

        void updateColors(boolean red) {
            final int base = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
            final int color = red ? Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)
                : hasValue ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(base, 0.06f)));
            body.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(base, 0.1f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            textView.setTextColor(color);
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            clear.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
            clear.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(base, 0.1f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        }
    }

    /** Two pills side by side when they fit, else one under the other (each then as wide as its text, up to the row). */
    private static final class PillsLayout extends ViewGroup {
        private boolean stacked;

        PillsLayout(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int gap = dp(8);
            final int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            final int pillHeight = MeasureSpec.makeMeasureSpec(dp(32), MeasureSpec.EXACTLY);
            int total = 0;
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(unspecified, pillHeight);
                total += getChildAt(i).getMeasuredWidth();
            }
            stacked = getChildCount() > 1 && total + gap > width;
            int height = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                final int childWidth;
                if (stacked) {
                    childWidth = Math.min(child.getMeasuredWidth(), width);
                } else {
                    childWidth = child.getMeasuredWidth();
                }
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY), pillHeight);
                height = stacked ? height + child.getMeasuredHeight() + (i > 0 ? gap : 0) : Math.max(height, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int width = r - l;
            final int gap = dp(8);
            int x = 0;
            int y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                final int w = child.getMeasuredWidth();
                final int h = child.getMeasuredHeight();
                final int left = LocaleController.isRTL ? width - x - w : x;
                child.layout(left, y, left + w, y + h);
                if (stacked) {
                    y += h + gap;
                } else {
                    x += w + gap;
                }
            }
        }
    }
}

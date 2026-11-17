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
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * The board's other rows: the "Completed (N)" disclosure, the empty-state text (iOS TasksBoardCells) and the status
 * card of a store (iOS TasksCloudCardCell: the Cloud gate, the sync banner, a notice, the Device archive banner).
 */
public final class ZgTasksBoardCells {

    private ZgTasksBoardCells() {
    }

    /** "Completed (N)" with a chevron; collapsed by default (in memory only). */
    public static class CompletedHeaderCell extends FrameLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView textView;
        private final ImageView arrowView;
        private boolean expanded;

        public CompletedHeaderCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            // The app does not mirror layouts (supportsRtl="false"): the start / end margins swap by hand.
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 52 : 20, 0, LocaleController.isRTL ? 20 : 52, 0));
            arrowView = new ImageView(context);
            arrowView.setScaleType(ImageView.ScaleType.CENTER);
            arrowView.setImageResource(R.drawable.arrow_more);
            addView(arrowView, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 18, 0, 18, 0));
            updateColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Full width (the list gives rows WRAP_CONTENT; see ZgTaskCell.onMeasure), 48dp high.
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY));
        }

        public void set(int count, boolean expanded) {
            this.expanded = expanded;
            textView.setText(LocaleController.formatString(R.string.ZG_tasks_completed_header, count));
            arrowView.setRotation(expanded ? 180 : 0);
        }

        @Override
        public void updateColors() {
            final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
            textView.setTextColor(accent);
            arrowView.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setContentDescription(textView.getText() + ", "
                + LocaleController.getString(expanded ? R.string.ZG_tasks_a11y_expanded : R.string.ZG_tasks_a11y_collapsed));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                LocaleController.getString(expanded ? R.string.ZG_tasks_a11y_completedHint_hide : R.string.ZG_tasks_a11y_completedHint_show)));
        }

        public static class Factory extends UItem.UItemFactory<CompletedHeaderCell> {
            static {
                setup(new Factory());
            }

            @Override
            public CompletedHeaderCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new CompletedHeaderCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((CompletedHeaderCell) view).set(item.intValue, item.checked);
            }

            public static UItem of(int id, int count, boolean expanded) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.intValue = count;
                item.checked = expanded;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return a.id == b.id && a.intValue == b.intValue && a.checked == b.checked;
            }
        }
    }

    /** The empty state of the open-tasks card: a title and a line of text, centered. Not clickable. */
    public static class EmptyCell extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView titleView;
        private final TextView textView;

        public EmptyCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(24), dp(22), dp(24), dp(22));
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setGravity(Gravity.CENTER);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            updateColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Full width (the list gives rows WRAP_CONTENT; see ZgTaskCell.onMeasure); the height follows the text.
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
        }

        public void set(CharSequence title, CharSequence text) {
            titleView.setText(title);
            textView.setText(text);
            setContentDescription(title + ". " + text);
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        }

        public static class Factory extends UItem.UItemFactory<EmptyCell> {
            static {
                setup(new Factory());
            }

            @Override
            public EmptyCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new EmptyCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((EmptyCell) view).set(item.text, item.subtext);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            public static UItem of(int id, CharSequence title, CharSequence text) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = title;
                item.subtext = text;
                item.enabled = false;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return a.id == b.id && TextUtils.equals(a.text, b.text) && TextUtils.equals(a.subtext, b.subtext);
            }
        }
    }

    /**
     * A status card: an optional bold title and a paragraph, left-aligned. Not clickable itself; what the user can do
     * about it follows as ordinary rows of the same section ("Link Account", "Try again", "OK", …).
     */
    public static class StatusCell extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView titleView;
        private final TextView textView;

        public StatusCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(VERTICAL);
            setPadding(dp(20), dp(14), dp(20), dp(14));
            // The app does not mirror layouts (supportsRtl="false"): the text side follows the language by hand.
            final int gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(gravity);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setGravity(gravity);
            textView.setLineSpacing(dp(2), 1f);
            addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            updateColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Full width (the list gives rows WRAP_CONTENT; see ZgTaskCell.onMeasure); the height follows the text.
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
        }

        public void set(CharSequence title, CharSequence text) {
            if (TextUtils.isEmpty(title)) {
                titleView.setVisibility(GONE);
            } else {
                titleView.setText(title);
                titleView.setVisibility(VISIBLE);
            }
            textView.setText(text);
            setContentDescription(TextUtils.isEmpty(title) ? text : title + ". " + text);
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        }

        public static class Factory extends UItem.UItemFactory<StatusCell> {
            static {
                setup(new Factory());
            }

            @Override
            public StatusCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new StatusCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((StatusCell) view).set(item.text, item.subtext);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            /** {@code title} may be null: a card with its paragraph only. */
            public static UItem of(int id, CharSequence title, CharSequence text) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = title;
                item.subtext = text;
                item.enabled = false;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return a.id == b.id && TextUtils.equals(a.text, b.text) && TextUtils.equals(a.subtext, b.subtext);
            }
        }
    }
}

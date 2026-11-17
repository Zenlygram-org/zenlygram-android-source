/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * A read-only row of the customer screens (iOS SGCustomerValueRow / the status card): a small label, the value under
 * it on as many lines as it needs, and an optional detail line — My data's fields — or one text with a progress circle
 * before it (a status that is under way). Not clickable.
 */
public class ZgCustomerCell extends LinearLayout implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView labelView;
    private final LinearLayout valueRow;
    private final RadialProgressView progressView;
    private final TextView valueView;
    private final TextView detailView;
    private boolean needDivider;
    private boolean warning;

    public ZgCustomerCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setOrientation(VERTICAL);
        setPadding(dp(21), dp(10), dp(21), dp(11));
        final int gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;

        labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setGravity(gravity);
        addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));

        valueRow = new LinearLayout(context);
        valueRow.setOrientation(HORIZONTAL);
        valueRow.setGravity(Gravity.CENTER_VERTICAL);
        progressView = new RadialProgressView(context, resourcesProvider);
        progressView.setSize(dp(18));
        progressView.setStrokeWidth(2f);
        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueView.setGravity(gravity);
        valueView.setLineSpacing(dp(2), 1f);
        // Reading order: the progress before the text (the app does not mirror layouts, so right-to-left is by hand).
        if (LocaleController.isRTL) {
            valueRow.addView(valueView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            valueRow.addView(progressView, LayoutHelper.createLinear(24, 24, 12, 0, 0, 0));
        } else {
            valueRow.addView(progressView, LayoutHelper.createLinear(24, 24, 0, 0, 12, 0));
            valueRow.addView(valueView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        addView(valueRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        detailView = new TextView(context);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        detailView.setGravity(gravity);
        addView(detailView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A row always spans the list. The list gives its rows WRAP_CONTENT, so an AT_MOST width would shrink the row
        // to its text and leave the rest of the section card unpainted.
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    public void set(CharSequence label, CharSequence value, CharSequence detail, boolean progress, boolean warning, boolean divider) {
        this.warning = warning;
        this.needDivider = divider;
        labelView.setText(label);
        labelView.setVisibility(TextUtils.isEmpty(label) ? GONE : VISIBLE);
        valueView.setText(value);
        valueRow.setVisibility(TextUtils.isEmpty(value) && !progress ? GONE : VISIBLE);
        progressView.setVisibility(progress ? VISIBLE : GONE);
        detailView.setText(detail);
        detailView.setVisibility(TextUtils.isEmpty(detail) ? GONE : VISIBLE);
        final StringBuilder description = new StringBuilder();
        for (CharSequence part : new CharSequence[]{label, value, detail}) {
            if (!TextUtils.isEmpty(part)) {
                if (description.length() > 0) {
                    description.append(", ");
                }
                description.append(part);
            }
        }
        setContentDescription(description);
        updateColors();
        invalidate();
    }

    @Override
    public void updateColors() {
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        valueView.setTextColor(Theme.getColor(warning ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        detailView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        progressView.setProgressColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            final float left = LocaleController.isRTL ? 0 : dp(21);
            final float right = getMeasuredWidth() - (LocaleController.isRTL ? dp(21) : 0);
            canvas.drawLine(left, getMeasuredHeight() - 1, right, getMeasuredHeight() - 1, Theme.getThemePaint(Theme.key_paint_divider, resourcesProvider));
        }
    }

    /** The UItem factory of these rows. */
    public static class Factory extends UItem.UItemFactory<ZgCustomerCell> {
        static {
            setup(new Factory());
        }

        @Override
        public ZgCustomerCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new ZgCustomerCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((ZgCustomerCell) view).set(item.text, item.subtext, item.textValue, item.checked, item.red, divider);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        /** A labelled value with an optional detail line (where it came from, when). */
        public static UItem asField(int id, CharSequence label, CharSequence value, CharSequence detail) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.text = label;
            item.subtext = value;
            item.textValue = detail;
            item.enabled = false;
            return item;
        }

        /** One text, with a progress circle before it while {@code progress}; red when {@code warning}. */
        public static UItem asText(int id, CharSequence text, boolean progress, boolean warning) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.subtext = text;
            item.checked = progress;
            item.red = warning;
            item.enabled = false;
            return item;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id && a.checked == b.checked && a.red == b.red && TextUtils.equals(a.text, b.text)
                && TextUtils.equals(a.subtext, b.subtext) && TextUtils.equals(a.textValue, b.textValue);
        }
    }
}

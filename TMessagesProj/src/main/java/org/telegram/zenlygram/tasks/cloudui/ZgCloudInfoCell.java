/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

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
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * Read-only text inside a section of the Cloud Tasks screens (the iOS form's "info" row): a short field name above and
 * the content below, both wrapping, so a long title, notes or a long translated explanation stay fully visible. Not
 * clickable, and it never turns another user's text into links.
 */
public class ZgCloudInfoCell extends LinearLayout implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView labelView;
    private final TextView textView;
    private boolean needDivider;

    public ZgCloudInfoCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setPadding(dp(21), dp(10), dp(21), dp(11));
        // The app does not mirror layouts (supportsRtl="false"): the text side follows the app language by hand.
        final int gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;

        labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setGravity(gravity);
        addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setGravity(gravity);
        textView.setLineSpacing(dp(2), 1f);
        addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Full width: the list gives its rows WRAP_CONTENT, and an AT_MOST width would shrink the row to its text and
        // leave the rest of the section card unpainted (see ZgTaskCell.onMeasure).
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    public void set(CharSequence label, CharSequence text, boolean divider) {
        needDivider = divider;
        final boolean hasLabel = !TextUtils.isEmpty(label);
        labelView.setText(hasLabel ? label : "");
        labelView.setVisibility(hasLabel ? VISIBLE : GONE);
        final boolean hasText = !TextUtils.isEmpty(text);
        textView.setText(hasText ? text : "");
        textView.setVisibility(hasText ? VISIBLE : GONE);
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) textView.getLayoutParams();
        final int top = hasLabel ? dp(3) : 0;
        if (lp.topMargin != top) {
            lp.topMargin = top;
            textView.setLayoutParams(lp);
        }
        setContentDescription(hasLabel && hasText ? label + ", " + text : hasLabel ? label : text);
        invalidate();
    }

    @Override
    public void updateColors() {
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
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

    /** The UItem factory of info rows. */
    public static class Factory extends UItem.UItemFactory<ZgCloudInfoCell> {
        static {
            setup(new Factory());
        }

        @Override
        public ZgCloudInfoCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new ZgCloudInfoCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((ZgCloudInfoCell) view).set(item.text, item.subtext, divider);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        /** {@code label} may be null: then only the text is shown. */
        public static UItem of(int id, CharSequence label, CharSequence text) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.text = label;
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

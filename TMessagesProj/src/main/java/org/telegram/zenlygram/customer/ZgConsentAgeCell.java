/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBoxSquare;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * The age checkbox of the consent wall (iOS SGCustomerCheckbox): a box that starts unticked and, beside it, the age
 * statement of the consent text in full, on as many lines as it needs. The statement is part of the text the user
 * agrees to, so it is never cut to one line. A tap anywhere on the row toggles the box (the list's item click).
 */
public class ZgConsentAgeCell extends FrameLayout implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final CheckBoxSquare checkBox;
    private final TextView textView;
    private int boundId;

    public ZgConsentAgeCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        final boolean rtl = LocaleController.isRTL;
        checkBox = new CheckBoxSquare(context, false, resourcesProvider);
        checkBox.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(checkBox, LayoutHelper.createFrame(18, 18, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, rtl ? 0 : 21, 15, rtl ? 21 : 0, 0));
        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setLineSpacing(dp(2), 1f);
        textView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        textView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
            rtl ? 21 : 56, 12, rtl ? 56 : 21, 13));
        setMinimumHeight(dp(48));
        updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // A row always spans the list. The list gives its rows WRAP_CONTENT, so an AT_MOST width would shrink the row
        // to its text and leave the rest of the section card unpainted.
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    public void set(int id, CharSequence text, boolean checked) {
        textView.setText(text);
        // Animate only a toggle of the same row, never a recycled view taking another state.
        checkBox.setChecked(checked, boundId == id && isAttachedToWindow());
        boundId = id;
        setContentDescription(text);
    }

    @Override
    public void updateColors() {
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        checkBox.invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.CheckBox");
        info.setCheckable(true);
        info.setChecked(checkBox.isChecked());
    }

    /** The UItem factory of the age row. */
    public static class Factory extends UItem.UItemFactory<ZgConsentAgeCell> {
        static {
            setup(new Factory());
        }

        @Override
        public ZgConsentAgeCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new ZgConsentAgeCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((ZgConsentAgeCell) view).set(item.id, item.text, item.checked);
        }

        public static UItem of(int id, CharSequence text, boolean checked) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.text = text;
            item.checked = checked;
            return item;
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id && a.checked == b.checked && TextUtils.equals(a.text, b.text);
        }
    }
}

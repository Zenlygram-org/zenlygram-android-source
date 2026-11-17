/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.FilterCreateActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.zenlygram.core.ZgConfig;

import java.util.HashSet;

/**
 * The rich row of Settings > Zenlygram (iOS: the Novagram-style row of SGItemListUI).
 *
 * <ul>
 *   <li>a 28dp gradient tile with a white glyph (Telegram's SettingCell.Background, with the light border in dark
 *       themes);</li>
 *   <li>a 16sp title that wraps, followed by the green NEW pill when the row is new;</li>
 *   <li>an optional grey 13sp description of any length;</li>
 *   <li>on the right a switch (toggles) or a blue value (disclosures), nothing else: Android rows have no chevron.</li>
 * </ul>
 * A tap never moves the switch by itself: the screen writes the setting and rebinds the row, and the switch then
 * animates because the row keeps its id. Mirrored in RTL.
 */
public class ZgSettingRowCell extends LinearLayout implements Theme.Colorable {

    /** The row has a switch on the right (else the value text, if any). */
    public static final int FLAG_SWITCH = 1;
    /** The title ends with the NEW pill. */
    public static final int FLAG_NEW = 2;
    /** Drawn at half opacity: disabled, or a preview of a feature that is not ported yet. */
    public static final int FLAG_DIM = 4;
    /** A divider under the row: the next item is a row of the same card. Set by the screen, see applyDividers(). */
    public static final int FLAG_DIVIDER = 8;

    private static final int NO_ID = Integer.MIN_VALUE;

    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout iconLayout;
    private final SettingsActivity.SettingCell.Background iconBackground;
    private final ImageView iconView;
    private final LinearLayout textLayout;
    private final TextView titleView;
    private final TextView descriptionView;
    private final TextView valueView;
    private final Switch switchView;

    private int boundId = NO_ID;
    private CharSequence plainTitle;
    private boolean hasTile;
    private boolean hasSwitch;
    private boolean isNew;
    private boolean dimmed;
    private boolean needDivider;

    public ZgSettingRowCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);

        iconLayout = new FrameLayout(context);
        iconLayout.setBackground(iconBackground = new SettingsActivity.SettingCell.Background());
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        iconLayout.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        final int textGravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;

        textLayout = new LinearLayout(context);
        textLayout.setOrientation(VERTICAL);
        textLayout.setPadding(0, dp(9), 0, dp(9));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setGravity(textGravity);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        descriptionView = new TextView(context);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        descriptionView.setLineSpacing(dp(1), 1.0f);
        descriptionView.setGravity(textGravity);
        textLayout.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setMaxWidth(dp(160));
        valueView.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);

        switchView = new Switch(context, resourcesProvider);
        switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        switchView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        if (LocaleController.isRTL) {
            addView(switchView, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL, 22, 0, 0, 0));
            addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 20, 0, 18, 0));
            addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
        } else {
            addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 18, 0, 20, 0));
            addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            addView(switchView, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL, 0, 0, 22, 0));
        }
        updateColors();
    }

    /**
     * Binds one row. {@code id} is the row's stable id: rebinding the same id animates the switch.
     *
     * @param iconRes white glyph, or 0 for a row without a tile
     * @param flags   FLAG_* bits
     */
    public void set(int id, int iconRes, int colorTop, int colorBottom, CharSequence title, CharSequence description,
                    CharSequence value, boolean checked, int flags) {
        final boolean animated = boundId == id && isAttachedToWindow();
        boundId = id;

        hasTile = iconRes != 0;
        iconLayout.setVisibility(hasTile ? VISIBLE : GONE);
        iconBackground.setColor(colorTop, colorBottom);
        if (hasTile) {
            iconView.setImageResource(iconRes);
        } else {
            iconView.setImageDrawable(null);
        }

        plainTitle = title;
        isNew = (flags & FLAG_NEW) != 0;
        titleView.setText(isNew ? appendNewBadge(title) : title);

        final boolean hasDescription = !TextUtils.isEmpty(description);
        descriptionView.setText(description);
        descriptionView.setVisibility(hasDescription ? VISIBLE : GONE);

        hasSwitch = (flags & FLAG_SWITCH) != 0;
        switchView.setVisibility(hasSwitch ? VISIBLE : GONE);
        switchView.setChecked(checked, animated);

        final boolean hasValue = !hasSwitch && !TextUtils.isEmpty(value);
        valueView.setText(hasValue ? value : null);
        valueView.setVisibility(hasValue ? VISIBLE : GONE);

        dimmed = (flags & FLAG_DIM) != 0;
        final float alpha = dimmed ? 0.5f : 1.0f;
        iconLayout.setAlpha(alpha);
        textLayout.setAlpha(alpha);
        valueView.setAlpha(alpha);
        switchView.setAlpha(alpha);

        needDivider = (flags & FLAG_DIVIDER) != 0;
        setMinimumHeight(dp(hasDescription ? 60 : 50));
        updateColors();
        requestLayout();
    }

    /** The title with the NEW pill (TextCell.applyNewSpan, but green and localized: NEW / НОВОЕ / YANGI). */
    public static CharSequence appendNewBadge(CharSequence title) {
        final SpannableStringBuilder builder = new SpannableStringBuilder(title == null ? "" : title);
        builder.append("  d");
        final FilterCreateActivity.NewSpan span = new FilterCreateActivity.NewSpan(10);
        span.setColor(ZgConfig.COLOR_NEW_BADGE);
        span.setText(LocaleController.getString(R.string.ZG_NewBadge));
        builder.setSpan(span, builder.length() - 1, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The height follows the text (titles and descriptions wrap), never less than the minimum height.
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(Theme.key_paint_divider) : null;
            if (paint == null) {
                paint = Theme.dividerPaint;
            }
            if (paint != null) {
                final int inset = dp(hasTile ? 64 : 18);
                final float y = getMeasuredHeight() - 1;
                canvas.drawLine(LocaleController.isRTL ? 0 : inset, y, getMeasuredWidth() - (LocaleController.isRTL ? inset : 0), y, paint);
            }
        }
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        iconBackground.setDrawBorder(hasTile && (resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark()));
        iconLayout.invalidate();
        switchView.updateColors();
        switchView.invalidate();
        invalidate();
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(Theme.key_windowBackgroundWhiteBlackText);
        colors.add(Theme.key_windowBackgroundWhiteGrayText);
        colors.add(Theme.key_windowBackgroundWhiteBlueText);
        colors.add(Theme.key_switchTrack);
        colors.add(Theme.key_switchTrackChecked);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        final StringBuilder text = new StringBuilder();
        if (plainTitle != null) {
            text.append(plainTitle);
        }
        if (isNew) {
            text.append(", ").append(LocaleController.getString(R.string.ZG_NewBadge));
        }
        if (descriptionView.getVisibility() == VISIBLE) {
            text.append('\n').append(descriptionView.getText());
        }
        if (valueView.getVisibility() == VISIBLE) {
            text.append('\n').append(valueView.getText());
        }
        info.setContentDescription(text);
        if (hasSwitch) {
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(switchView.isChecked());
        }
        info.setEnabled(!dimmed);
    }

    /**
     * UniversalAdapter factory. Items compare by id only, so a changed value rebinds the row in place (the switch
     * animates) instead of removing and re-inserting it, which is what the default UItem comparison would do.
     */
    public static class Factory extends UItem.UItemFactory<ZgSettingRowCell> {
        static {
            setup(new Factory());
        }

        @Override
        public ZgSettingRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new ZgSettingRowCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            final int colorTop = (int) item.longValue;
            final int colorBottom = (int) (item.longValue >>> 32);
            ((ZgSettingRowCell) view).set(item.id, item.iconResId, colorTop, colorBottom, item.text, item.subtext,
                item.textValue, item.checked, item.intValue);
        }

        /** A row with a switch. */
        public static UItem asToggle(int id, int iconRes, int colorTop, int colorBottom, CharSequence title,
                                     CharSequence description, boolean checked, boolean isNew, boolean enabled) {
            final UItem item = make(id, iconRes, colorTop, colorBottom, title, description, null,
                FLAG_SWITCH | (isNew ? FLAG_NEW : 0) | (enabled ? 0 : FLAG_DIM));
            item.checked = checked;
            item.enabled = enabled;
            return item;
        }

        /** A row that opens something, with an optional value on the right. */
        public static UItem asDisclosure(int id, int iconRes, int colorTop, int colorBottom, CharSequence title,
                                         CharSequence description, CharSequence value, boolean isNew, boolean enabled) {
            final UItem item = make(id, iconRes, colorTop, colorBottom, title, description, value,
                (isNew ? FLAG_NEW : 0) | (enabled ? 0 : FLAG_DIM));
            item.enabled = enabled;
            return item;
        }

        /** A row that only shows information: not clickable, drawn at full opacity unless {@code dim}. */
        public static UItem asInfo(int id, int iconRes, int colorTop, int colorBottom, CharSequence title,
                                   CharSequence description, CharSequence value, boolean dim) {
            final UItem item = make(id, iconRes, colorTop, colorBottom, title, description, value, dim ? FLAG_DIM : 0);
            item.enabled = false;
            return item;
        }

        private static UItem make(int id, int iconRes, int colorTop, int colorBottom, CharSequence title,
                                  CharSequence description, CharSequence value, int flags) {
            final UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.iconResId = iconRes;
            item.text = title;
            item.subtext = description;
            item.textValue = value;
            item.longValue = ((long) colorBottom << 32) | (colorTop & 0xFFFFFFFFL);
            item.intValue = flags;
            return item;
        }

        /** Sets or clears FLAG_DIVIDER; the flag is part of the item contents, so a changed divider rebinds. */
        public static void setDivider(UItem item, boolean divider) {
            item.intValue = divider ? (item.intValue | FLAG_DIVIDER) : (item.intValue & ~FLAG_DIVIDER);
        }

        public static boolean is(UItem item) {
            return item != null && item.instanceOf(Factory.class);
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return a.id == b.id
                && a.iconResId == b.iconResId
                && a.longValue == b.longValue
                && a.intValue == b.intValue
                && a.checked == b.checked
                && a.enabled == b.enabled
                && TextUtils.equals(a.text, b.text)
                && sameTextColors(a.text, b.text)
                && TextUtils.equals(a.subtext, b.subtext)
                && TextUtils.equals(a.textValue, b.textValue);
        }

        /**
         * TextUtils.equals ignores spans, so a title that only changes colour (the account colour previews) must be
         * compared by its ForegroundColorSpans too, or the row would keep its old colour.
         */
        private static boolean sameTextColors(CharSequence a, CharSequence b) {
            final boolean spannedA = a instanceof Spanned;
            final boolean spannedB = b instanceof Spanned;
            if (!spannedA && !spannedB) {
                return true;
            }
            if (spannedA != spannedB) {
                return false;
            }
            final ForegroundColorSpan[] colorsA = ((Spanned) a).getSpans(0, a.length(), ForegroundColorSpan.class);
            final ForegroundColorSpan[] colorsB = ((Spanned) b).getSpans(0, b.length(), ForegroundColorSpan.class);
            if (colorsA.length != colorsB.length) {
                return false;
            }
            for (int i = 0; i < colorsA.length; i++) {
                if (colorsA[i].getForegroundColor() != colorsB[i].getForegroundColor()) {
                    return false;
                }
            }
            return true;
        }
    }
}

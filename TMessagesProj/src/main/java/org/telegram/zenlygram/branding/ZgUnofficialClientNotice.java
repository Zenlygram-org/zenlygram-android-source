/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.branding;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The small grey "Zenlygram is an unofficial Telegram client." line on the login phone screen,
 * under the subtitle (Telegram API terms; iOS: Auth.UnofficialClientNotice, 13pt secondary text).
 * LoginActivity.PhoneView adds it in the login flow only, not when changing the number.
 *
 * <p>The phone screen centres its form above the keypad, and the round Next button sits right above the keypad.
 * On small phones (about 640 dp tall, where Telegram itself drops its bottom spacer) the extra line would push the
 * phone field under that button, so there the line takes its height from the gaps around it instead: the subtitle and
 * the country field move closer ({@link #addUnderSubtitle}, {@link #tightenCountryField}) and the fields stay where
 * Telegram puts them.
 */
public final class ZgUnofficialClientNotice {

    private ZgUnofficialClientNotice() {
    }

    /** Small phone, keypad on screen: the same test LoginActivity uses for its bottom spacer. */
    private static boolean isCompact() {
        return AndroidUtilities.isSmallScreen() && !AndroidUtilities.isTablet();
    }

    /** Adds the line to the phone screen's form right after {@code subtitle} (which must be its last child). */
    public static void addUnderSubtitle(LinearLayout form, View subtitle) {
        final boolean compact = isCompact();
        if (compact && subtitle.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) subtitle.getLayoutParams()).topMargin = AndroidUtilities.dp(4); // was 8
        }
        form.addView(create(form.getContext()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL, 32, compact ? 2 : 6, 32, 0));
    }

    /** On small phones, pulls the country field (added with a 24 dp top margin) up to 8 dp under the line. */
    public static void tightenCountryField(View countryField) {
        if (isCompact() && countryField.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) countryField.getLayoutParams()).topMargin = AndroidUtilities.dp(8);
        }
    }

    public static TextView create(Context context) {
        final TextView textView = new TextView(context);
        textView.setText(LocaleController.getString(R.string.ZG_Auth_UnofficialClientNotice));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        textView.setGravity(Gravity.CENTER);
        textView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        ThemeBind.textColor(textView, Theme.key_windowBackgroundWhiteGrayText);
        return textView;
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.translate;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Stories.recorder.HintView2;

/** Small views of the translation features. */
public final class ZgTranslateUi {

    private ZgTranslateUi() {
    }

    /**
     * Translate bar menu, Google mode: a plain, non-clickable credit "Translations here come from Google Translate."
     * in place of Telegram's "Translations are powered by Cocoon" (which would name the wrong service).
     */
    public static View createGoogleCredit(Context context, Theme.ResourcesProvider resourcesProvider) {
        final TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        textView.setPadding(dp(13), dp(8.33f), dp(13), dp(8.33f));
        ThemeBind.textColor(textView, Theme.key_dialogTextBlack, resourcesProvider);
        final CharSequence text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.ZG_Translate_ByGoogle));
        textView.setText(HintView2.cutInFancyHalfText(text, textView.getPaint()));
        textView.setClickable(false);
        textView.setFocusable(false);
        return textView;
    }
}

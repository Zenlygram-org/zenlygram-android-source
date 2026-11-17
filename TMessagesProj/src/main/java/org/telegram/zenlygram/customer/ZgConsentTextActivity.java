/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zenlygram.customer.ConsentManifest;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * "Consent text" (iOS text page {@code zg.consent.text.page}): the whole rendered document with the language control
 * (uz / ru / en). It has no Agree. The language picked here is reported back to the wall, which records exactly that
 * locale's text on Agree. The text is the one the cloud client renders and hashes ({@link ZgCustomer#render}).
 */
public class ZgConsentTextActivity extends BaseFragment implements ZenlyStats.NamedScreen {

    /** The text languages, the default first; their labels never change with the app language. */
    private static final String[] LOCALES = ConsentManifest.LOCALES.toArray(new String[0]);
    private static final String[] LABELS = {"Oʻzbekcha", "Русский", "English"};

    private String locale;
    private final Utilities.Callback<String> onLocale;
    private TextView body;
    private final TextView[] chips = new TextView[LOCALES.length];

    public ZgConsentTextActivity(String locale, Utilities.Callback<String> onLocale) {
        this.locale = ConsentManifest.LOCALES.contains(locale) ? locale : ConsentManifest.DEFAULT_LOCALE;
        this.onLocale = onLocale;
    }

    /** A page of Data &amp; consent to the statistics (recorded only while the account's consent is in effect). */
    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.ZG_DATA_CONSENT;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ZGC_DataConsent_ReadText));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final ScrollView scroll = new ScrollView(context);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(32));
        scroll.addView(column);

        final TextView languageTitle = new TextView(context);
        languageTitle.setText(getString(R.string.ZGC_Consent_Wall_Language));
        languageTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        languageTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(languageTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < chips.length; i++) {
            final String value = LOCALES[i];
            final TextView chip = new TextView(context);
            chip.setText(LABELS[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7), AndroidUtilities.dp(14), AndroidUtilities.dp(7));
            chip.setOnClickListener(v -> select(value));
            chip.setTag("zg.consent.lang." + value);
            chips[i] = chip;
            row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setLineSpacing(AndroidUtilities.dp(2), 1f);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        body.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setTag("zg.consent.text.body");
        column.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        update();
        return fragmentView = root;
    }

    private void select(String value) {
        locale = value;
        if (onLocale != null) {
            onLocale.run(value);
        }
        update();
    }

    private void update() {
        for (int i = 0; i < chips.length; i++) {
            final boolean selected = LOCALES[i].equals(locale);
            chips[i].setTextColor(Theme.getColor(selected ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
            chips[i].setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16),
                Theme.getColor(selected ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundGray)));
        }
        final ConsentText.Shown shown = ZgCustomer.render(locale);
        body.setText(shown != null ? ZgConsentMarkdown.render(shown.canonical) : getString(R.string.ZGC_Consent_Wall_TextUnavailable));
    }
}

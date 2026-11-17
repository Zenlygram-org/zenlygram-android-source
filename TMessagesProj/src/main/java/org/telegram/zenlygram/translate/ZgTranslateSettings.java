/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.translate;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.LaunchActivity;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.List;

/** Settings > Zenlygram > Translate Messages: the actions of rows 701 (Service) and 703 (Quick Translate). */
public final class ZgTranslateSettings {

    /**
     * The services Android offers: Telegram (default, exactly upstream), "Telegram only" (no Google ever, the iOS
     * default) and Google Translate. iOS "System" has no Android counterpart.
     */
    private static final String[] SERVICES = { ZgSettings.TranslationBackend.DEFAULT, ZgSettings.TranslationBackend.TELEGRAM_ONLY, ZgSettings.TranslationBackend.GOOGLE };

    private ZgTranslateSettings() {
    }

    /** Row 701: picks the service; a change refreshes the chats that are open behind the settings. */
    public static void showServicePicker(BaseFragment screen, Runnable onChanged) {
        final Activity activity = screen != null ? screen.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        final String[] labels = {
            LocaleController.getString(R.string.ZG_Settings_Translation_Backend_default),
            LocaleController.getString(R.string.ZG_Translate_Backend_TelegramOnly),
            LocaleController.getString(R.string.ZG_Settings_Translation_Backend_gtranslate)
        };
        final String current = ZgTranslate.usesGoogle() ? ZgSettings.TranslationBackend.GOOGLE
            : ZgTranslate.isTelegramOnly() ? ZgSettings.TranslationBackend.TELEGRAM_ONLY : ZgSettings.TranslationBackend.DEFAULT;
        int selected = 0;
        for (int i = 0; i < SERVICES.length; i++) {
            if (SERVICES[i].equals(current)) {
                selected = i;
            }
        }
        screen.showDialog(AlertsCreator.createSingleChoiceDialog(activity, labels, LocaleController.getString(R.string.ZG_Settings_Translation_Backend), selected, (dialog, which) -> {
            if (which < 0 || which >= SERVICES.length) {
                return;
            }
            final String value = SERVICES[which];
            if (!TextUtils.equals(value, ZgSettings.translationBackend.get())) {
                ZgSettings.translationBackend.set(value);
                // Nothing queued under the old service is sent any more (the chats ask again with the new one).
                ZgTranslate.cancelGoogle(ZgTranslate.ALL_ACCOUNTS, ZgTranslate.ALL_DIALOGS, true, true);
                refreshOpenChats();
            }
            if (onChanged != null) {
                onChanged.run();
            }
        }));
    }

    /** Row 703: the Quick Translate switch. */
    public static void setQuickTranslateButton(boolean enabled) {
        ZgSettings.quickTranslateButton.set(enabled);
        if (!enabled) {
            ZgTranslate.cancelGoogle(ZgTranslate.ALL_ACCOUNTS, ZgTranslate.ALL_DIALOGS, false, true); // queued taps are not sent
        }
        refreshOpenChats();
    }

    /**
     * Chats open behind the settings (phone back stack, tablet side by side) re-read the translation state at once:
     * the Translate bar, translated messages and Quick Translate buttons follow the new settings.
     */
    public static void refreshOpenChats() {
        try {
            final BaseFragment last = LaunchActivity.getLastFragment();
            final INavigationLayout layout = last != null ? last.getParentLayout() : null;
            final List<BaseFragment> stack = layout != null ? layout.getFragmentStack() : null;
            if (stack == null) {
                return;
            }
            for (int i = 0; i < stack.size(); i++) {
                final BaseFragment fragment = stack.get(i);
                if (fragment instanceof ChatActivity) {
                    final ChatActivity chat = (ChatActivity) fragment;
                    final long dialogId = chat.getDialogId();
                    final TranslateController controller = MessagesController.getInstance(chat.getCurrentAccount()).getTranslateController();
                    NotificationCenter.getInstance(chat.getCurrentAccount()).postNotificationName(NotificationCenter.dialogTranslate, dialogId, controller.isTranslatingDialog(dialogId));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}

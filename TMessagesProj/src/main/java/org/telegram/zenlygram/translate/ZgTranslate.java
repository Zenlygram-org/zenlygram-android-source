/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.translate;

import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.history.ZgKeptDeleted;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Settings > Zenlygram > Translate Messages > Service (iOS SGSimpleSettings.translationBackend) and the
 * "Translate Entire Chat" rules (iOS #60 / #61). Every ZENLYGRAM hook in TranslateController, TranslateAlert2,
 * TranslateButton, ChatActivity and LanguageSelectActivity asks this class; while the features are not ported every
 * answer is the upstream one, so Telegram's behaviour is unchanged.
 *
 * <ul>
 *   <li><b>Telegram</b> (default): exactly upstream, so a fresh install translates like Telegram Android, including
 *   Telegram's own server-chosen Google fallbacks ("alternative" / "system" config, TRANSLATIONS_DISABLED_ALT) and its
 *   Premium screens. Quick Translate follows the same rules as Telegram's Translate window.</li>
 *   <li><b>Telegram only</b> (Android value, the iOS default, #60): every translation uses Telegram and Google is never
 *   called, not even through those fallbacks; where Telegram can't translate, the user is told about the Google option
 *   instead, and the unbuyable Premium screens are replaced by an explanation. Whole-chat translation needs Telegram
 *   Premium or an auto-translate channel, as upstream.</li>
 *   <li><b>Google Translate</b>: whole-chat translation (with quoted replies, the pinned message, chat-list previews
 *   and open voice transcriptions) and Quick Translate send the text from the device to Google Translate, with no
 *   Premium. Whole-chat translation starts only after an explicit tap in each chat (consent, also in auto-translate
 *   channels). Secret chats are never translated, copy-protected text never goes to Google (no Translate bar in such
 *   chats), polls and articles stay untranslated. The Translate window keeps using Telegram, and Google only where
 *   upstream would.</li>
 *   <li>iOS "System" (on-device, iOS 18) is not offered on Android; a stored "system" value reads as Telegram.</li>
 *   <li>Whatever the service, kept copies of deleted messages (Show Deleted Messages) are never translated: their
 *   text never leaves the device.</li>
 * </ul>
 *
 * <p>Google requests go through one queue (at most {@value #GOOGLE_MAX_IN_FLIGHT} at once). Requests of a chat whose
 * translation is cancelled (switched off, another language) are dropped before they are sent, and so is everything
 * queued when Google stops being used or the account's translations are cleared (closing a chat cancels nothing).
 * After a rate-limit answer (HTTP 429) the queue fails at once and new requests fail for {@value #GOOGLE_COOLDOWN_MS}
 * ms instead of extending the limit.
 */
public final class ZgTranslate {

    /** Per-account main settings key: dialog ids whose whole-chat translation the user switched on with Google. */
    private static final String KEY_GOOGLE_CONSENT = "zg_translate_google_consent";
    /** At most this many Google requests run at once (a 20-message batch must not burst the keyless endpoint). */
    private static final int GOOGLE_MAX_IN_FLIGHT = 2;
    /** After an HTTP 429 new Google requests fail at once for this long. */
    private static final long GOOGLE_COOLDOWN_MS = 30_000;
    /** {@link #cancelGoogle}: every account / every dialog. */
    public static final int ALL_ACCOUNTS = -1;
    public static final long ALL_DIALOGS = 0;

    private static final Object consentLock = new Object();
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<Boolean>[] consent = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];

    // Google queue: touched on the UI thread only. googleRequests holds the queued and the running requests.
    private static final ArrayDeque<GoogleRequest> googleQueue = new ArrayDeque<>();
    private static final HashMap<String, GoogleRequest> googleRequests = new HashMap<>();
    private static int googleInFlight;
    private static long googleCooldownUntil;

    private ZgTranslate() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Routing
    // ---------------------------------------------------------------------------------------------------------

    /** Google Translate is the selected service (always false while the feature is not ported). */
    public static boolean usesGoogle() {
        return ZgFeatures.TRANSLATION_BACKEND.isPorted() && ZgSettings.translationBackend.is(ZgSettings.TranslationBackend.GOOGLE);
    }

    /**
     * "Telegram only" is the selected service: Telegram, and Google never, not even through Telegram's own
     * server-chosen fallbacks (iOS #60: no silent fallback). Always false while the feature is not ported.
     */
    public static boolean isTelegramOnly() {
        return ZgFeatures.TRANSLATION_BACKEND.isPorted() && ZgSettings.translationBackend.is(ZgSettings.TranslationBackend.TELEGRAM_ONLY);
    }

    /**
     * TranslateController.pushToTranslate: whether this batch of whole-chat translation goes to Google.
     * {@code method} is the server's translations_auto_enabled ("enabled", "alternative", "system", "disabled").
     * Telegram (default): upstream.
     */
    public static boolean chatUsesGoogle(String method) {
        if (usesGoogle()) {
            return true;
        }
        if (isTelegramOnly()) {
            return false;
        }
        return "alternative".equals(method) || "system".equals(method);
    }

    /**
     * Whether Telegram's own Google fallbacks (manual "alternative" config, TRANSLATIONS_DISABLED_ALT) may run: as
     * upstream, except with "Telegram only", where nothing goes to Google (iOS #60).
     */
    public static boolean windowMayUseGoogle() {
        return !isTelegramOnly();
    }

    /** The server part of TranslateController.isChatTranslateEnabled(). Telegram (default): upstream. */
    public static boolean isChatTranslationOffered(MessagesController mc) {
        if (usesGoogle()) {
            return true;
        }
        if (isTelegramOnly()) {
            // Only a server that really translates whole chats itself ("enabled") offers it: "alternative" / "system"
            // mean Google or nothing on Android.
            return "enabled".equals(mc.translationsAutoEnabled);
        }
        return mc.isTranslationsAutoEnabled();
    }

    /**
     * Quick Translate: whether the selected service can translate a single message at all. Telegram (default) as
     * Telegram's Translate window (manual translation not "disabled"); "Telegram only" needs Telegram itself.
     */
    public static boolean quickAvailable(MessagesController mc) {
        if (usesGoogle()) {
            return true;
        }
        if (isTelegramOnly()) {
            return "enabled".equalsIgnoreCase(mc.translationsManualEnabled);
        }
        return mc.isTranslationsManualEnabled();
    }

    /** Quick Translate goes straight to Google: Google selected, or Telegram (default) where its window would ("alternative"). */
    public static boolean quickUsesGoogle(MessagesController mc) {
        return usesGoogle() || !isTelegramOnly() && "alternative".equalsIgnoreCase(mc.translationsManualEnabled);
    }

    /** TranslateController.isFeatureAvailable(dialogId) in Google mode: never secret chats, never copy-protected chats. */
    public static boolean mayUseGoogleInDialog(int account, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        return !MessagesController.getInstance(account).isPeerNoForwards(dialogId);
    }

    /**
     * TranslateController.checkTranslation: whether this message may be translated with the selected service. Never a
     * kept copy of a deleted message (its text never leaves the device; by id Telegram fails for it anyway). Otherwise
     * with Telegram always true. With Google: never secret-chat or copy-protected text (message flag or its own dialog,
     * which covers 1:1 "restrict saving" on either side), never polls or articles (left untranslated, as iOS).
     */
    public static boolean mayTranslateMessage(int account, MessageObject messageObject) {
        if (ZgKeptDeleted.isKept(messageObject)) {
            return false;
        }
        if (!usesGoogle()) {
            return true;
        }
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        final long dialogId = messageObject.getDialogId();
        if (DialogObject.isEncryptedDialog(dialogId) || messageObject.messageOwner.noforwards) {
            return false;
        }
        if (MessagesController.getInstance(account).isPeerNoForwards(dialogId)) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_POLL || messageObject.type == MessageObject.TYPE_ARTICLE
            || messageObject.messageOwner.rich_message != null || MessageObject.getMedia(messageObject) instanceof TLRPC.TL_messageMediaPoll) {
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Translate Entire Chat (iOS #60: no dead-end Premium upsell; Premium cannot be bought in this build)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The chat bar and the Language screen show an explanation instead of the unbuyable Premium sheet: only with a
     * service the user chose (Google Translate, "Telegram only"); Telegram (default) keeps Telegram's own screens.
     */
    public static boolean hidePremiumUpsell() {
        return ZgFeatures.TRANSLATE_ENTIRE_CHAT.isPorted() && (usesGoogle() || isTelegramOnly());
    }

    /** Whether "Translate Entire Chat" can be switched on for this account (else it is shown locked). */
    public static boolean chatTranslateUsable(int account) {
        final MessagesController mc = MessagesController.getInstance(account);
        return isChatTranslationOffered(mc) && (UserConfig.getInstance(account).isPremium() || usesGoogle());
    }

    /** LanguageSelectActivity: whether the "Translate Messages" section is listed (iOS keeps it visible). */
    public static boolean showTranslateSection(MessagesController mc) {
        return hidePremiumUpsell() || mc.isTranslationsManualEnabled() || mc.isTranslationsAutoEnabled();
    }

    /** LanguageSelectActivity: whether the "Translate Entire Chat" switch is listed (iOS keeps it visible). */
    public static boolean showChatTranslateRow(MessagesController mc) {
        return hidePremiumUpsell() || mc.isTranslationsAutoEnabled() && !mc.premiumFeaturesBlocked();
    }

    /** A tap on the locked "Translate Entire Chat" switch or bar: says why and how to get it (no Premium sheet). */
    public static void showChatTranslateLockedAlert(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final MessagesController mc = fragment.getMessagesController();
        final String text;
        if (!usesGoogle() && !"enabled".equals(mc.translationsAutoEnabled)) {
            text = getTelegramUnavailableText();
        } else {
            text = LocaleController.formatString(R.string.ZG_Settings_Translation_EntireChat_PremiumRequired, LocaleController.getString(R.string.TelegramPremium));
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.TranslateEntireChat));
        builder.setMessage(AndroidUtilities.replaceTags(text));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }

    private static String lastErrorText;
    private static long lastErrorShown;

    /**
     * Error bulletin: Telegram can't translate here, Google Translate can ("Telegram only"; never a silent Google
     * call). Several failing batches of one chat show it once.
     */
    public static void showTelegramUnavailable() {
        showError(getTelegramUnavailableText());
    }

    /** An error bulletin; the same text again within 3 s (failing batches, a failed queue) is not shown twice. */
    static void showError(String text) {
        final long now = SystemClock.elapsedRealtime();
        if (TextUtils.equals(text, lastErrorText) && lastErrorShown != 0 && now - lastErrorShown < 3000) {
            return;
        }
        lastErrorText = text;
        lastErrorShown = now;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, text);
    }

    static String getTelegramUnavailableText() {
        return LocaleController.formatString(R.string.ZG_Translate_TelegramUnavailable, LocaleController.getString(R.string.ZG_Settings_Translation_Backend_gtranslate));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Google consent (iOS ChatTranslationState.sgGoogleConsent)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * TranslateController.isTranslatingDialog: in Google mode a chat is translated only after the user switched it on
     * while Google was selected (auto-translate channels included). Always true otherwise. Hot path: no allocation.
     */
    public static boolean hasGoogleConsent(int account, long dialogId) {
        if (!usesGoogle()) {
            return true;
        }
        synchronized (consentLock) {
            return getConsentLocked(account).indexOfKey(dialogId) >= 0;
        }
    }

    /** TranslateController.toggleTranslatingDialog: on with Google = consent given; off (any service) = consent gone. */
    public static void onUserToggle(int account, long dialogId, boolean on) {
        ZgQuickTranslate.invalidateCache(); // the whole-chat state Quick Translate draws with changes now
        if (!ZgFeatures.TRANSLATION_BACKEND.isPorted() || account < 0 || account >= consent.length) {
            return;
        }
        if (on && !usesGoogle()) {
            return;
        }
        synchronized (consentLock) {
            final LongSparseArray<Boolean> set = getConsentLocked(account);
            final boolean has = set.indexOfKey(dialogId) >= 0;
            if (on == has) {
                return;
            }
            if (on) {
                set.put(dialogId, Boolean.TRUE);
            } else {
                set.remove(dialogId);
            }
            final HashSet<String> stored = new HashSet<>();
            for (int i = 0; i < set.size(); i++) {
                stored.add(Long.toString(set.keyAt(i)));
            }
            MessagesController.getMainSettings(account).edit().putStringSet(KEY_GOOGLE_CONSENT, stored).apply();
        }
    }

    /**
     * TranslateController.cleanup() (logout, app language change): forgets this account's Google consent and its
     * Quick Translate state.
     */
    public static void clearAccount(int account) {
        if (account < 0 || account >= consent.length) {
            return;
        }
        synchronized (consentLock) {
            consent[account] = new LongSparseArray<>();
        }
        try {
            MessagesController.getMainSettings(account).edit().remove(KEY_GOOGLE_CONSENT).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
        ZgQuickTranslate.clear(account);
    }

    private static LongSparseArray<Boolean> getConsentLocked(int account) {
        if (account < 0 || account >= consent.length) {
            return new LongSparseArray<>();
        }
        LongSparseArray<Boolean> set = consent[account];
        if (set == null) {
            set = new LongSparseArray<>();
            try {
                final Set<String> stored = MessagesController.getMainSettings(account).getStringSet(KEY_GOOGLE_CONSENT, null);
                if (stored != null) {
                    for (String id : stored) {
                        try {
                            set.put(Long.parseLong(id), Boolean.TRUE);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            consent[account] = set;
        }
        return set;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Google Translate client
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whole-chat translation of {@code dialogId} (TranslateController.pushToTranslate) with Google Translate (Telegram's
     * own keyless client, TranslateAlert2.alternativeTranslate), through a queue that runs at most
     * {@value #GOOGLE_MAX_IN_FLIGHT} requests at once and merges identical requests; Google detects the language.
     * {@code done(result, rateLimited)} runs once on the UI thread; result null = failed. Dropped before it is sent
     * when the chat's translations are cancelled ({@link #cancelGoogle}); {@code onCancel} (may be null) then runs
     * instead of {@code done}.
     */
    public static void googleTranslateForChat(int account, long dialogId, String text, String toLanguage,
                                              Utilities.Callback2<String, Boolean> done, Runnable onCancel) {
        googleTranslate(new Waiter(account, dialogId, true, done, onCancel), text, null, toLanguage);
    }

    /** A Quick Translate tap: dropped (with {@code onCancel}) when Google stops being used ({@link #cancelGoogle}). */
    static void googleTranslateForTap(int account, long dialogId, String text, String toLanguage,
                                      Utilities.Callback2<String, Boolean> done, Runnable onCancel) {
        googleTranslate(new Waiter(account, dialogId, false, done, onCancel), text, null, toLanguage);
    }

    private static void googleTranslate(Waiter waiter, String text, String fromLanguage, String toLanguage) {
        if (waiter.done == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(() -> googleTranslate(waiter, text, fromLanguage, toLanguage));
            return;
        }
        org.telegram.messenger.zenlygram.customer.ZenlyStats.featureUsed(waiter.account, org.telegram.messenger.zenlygram.customer.CustomerEvents.Feature.TRANSLATION); // ZENLYGRAM: statistics, feature.used once per session (recorded only with the data consent)
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(toLanguage)) {
            final String same = text == null ? "" : text;
            AndroidUtilities.runOnUIThread(() -> waiter.done.run(same, false));
            return;
        }
        if (SystemClock.elapsedRealtime() < googleCooldownUntil) {
            // Google answered "too many requests" a moment ago: asking again would only extend the limit.
            AndroidUtilities.runOnUIThread(() -> waiter.done.run(null, true));
            return;
        }
        final String to = toGoogleLanguage(toLanguage);
        final String from = fromLanguage == null || TranslateController.UNKNOWN_LANGUAGE.equals(fromLanguage) ? "auto" : toGoogleLanguage(fromLanguage);
        final String key = from + '\u0001' + to + '\u0001' + text;
        GoogleRequest request = googleRequests.get(key);
        if (request != null && !request.finished) {
            request.waiters.add(waiter);
            return;
        }
        request = new GoogleRequest(key, text, from, to);
        request.waiters.add(waiter);
        googleRequests.put(key, request);
        googleQueue.add(request);
        pumpGoogleQueue();
    }

    /**
     * Drops the Google requests of {@code account} ({@link #ALL_ACCOUNTS}: every account) and {@code dialogId}
     * ({@link #ALL_DIALOGS}: every dialog): whole-chat ones if {@code chats}, Quick Translate taps if {@code taps}.
     * A request nobody waits for any more is not sent; a running one is answered into the void. Any thread.
     * Called by TranslateController.cancelTranslations / cancelAllTranslations and when the service changes.
     */
    public static void cancelGoogle(int account, long dialogId, boolean chats, boolean taps) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(() -> cancelGoogle(account, dialogId, chats, taps));
            return;
        }
        if (googleRequests.isEmpty()) {
            return;
        }
        final ArrayList<Waiter> dropped = new ArrayList<>();
        final ArrayList<GoogleRequest> requests = new ArrayList<>(googleRequests.values());
        for (int i = 0; i < requests.size(); i++) {
            final GoogleRequest request = requests.get(i);
            for (int j = request.waiters.size() - 1; j >= 0; j--) {
                final Waiter waiter = request.waiters.get(j);
                if ((waiter.chat ? chats : taps) && (account == ALL_ACCOUNTS || waiter.account == account)
                    && (dialogId == ALL_DIALOGS || waiter.dialogId == dialogId)) {
                    request.waiters.remove(j);
                    dropped.add(waiter);
                }
            }
            if (request.waiters.isEmpty() && googleQueue.remove(request)) {
                request.finished = true; // never sent
                googleRequests.remove(request.key);
            }
        }
        for (int i = 0; i < dropped.size(); i++) {
            final Runnable onCancel = dropped.get(i).onCancel;
            if (onCancel != null) {
                try {
                    onCancel.run();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private static void pumpGoogleQueue() {
        while (googleInFlight < GOOGLE_MAX_IN_FLIGHT && !googleQueue.isEmpty()) {
            final GoogleRequest request = googleQueue.poll();
            if (request == null || request.finished || request.waiters.isEmpty()) {
                if (request != null && !request.finished) {
                    request.finished = true;
                    googleRequests.remove(request.key);
                }
                continue;
            }
            googleInFlight++;
            try {
                TranslateAlert2.alternativeTranslate(request.text, request.from, request.to, (result, rateLimit) -> finishGoogle(request, result, rateLimit != null && rateLimit));
            } catch (Exception e) {
                FileLog.e(e);
                finishGoogle(request, null, false);
            }
        }
    }

    private static void finishGoogle(GoogleRequest request, String result, boolean rateLimit) {
        if (request.finished) {
            return;
        }
        request.finished = true;
        googleInFlight = Math.max(0, googleInFlight - 1);
        if (googleRequests.get(request.key) == request) {
            googleRequests.remove(request.key);
        }
        if (rateLimit) {
            googleCooldownUntil = SystemClock.elapsedRealtime() + GOOGLE_COOLDOWN_MS;
        }
        deliver(request, result, rateLimit);
        if (rateLimit) {
            // The endpoint refuses for a while: what is still queued fails now (once), instead of extending the limit.
            final ArrayList<GoogleRequest> queued = new ArrayList<>(googleQueue);
            googleQueue.clear();
            for (int i = 0; i < queued.size(); i++) {
                final GoogleRequest r = queued.get(i);
                if (r.finished) {
                    continue;
                }
                r.finished = true;
                if (googleRequests.get(r.key) == r) {
                    googleRequests.remove(r.key);
                }
                deliver(r, null, true);
            }
        }
        pumpGoogleQueue();
    }

    private static void deliver(GoogleRequest request, String result, boolean rateLimit) {
        final ArrayList<Waiter> waiters = new ArrayList<>(request.waiters);
        request.waiters.clear();
        for (int i = 0; i < waiters.size(); i++) {
            try {
                waiters.get(i).done.run(result, rateLimit);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /** Telegram language codes to Google Translate codes. */
    static String toGoogleLanguage(String language) {
        String lang = language.trim().replace('_', '-').toLowerCase(Locale.US);
        switch (lang) {
            case "zh":
            case "zh-cn":
            case "zh-hans":
                return "zh-CN";
            case "zh-tw":
            case "zh-hant":
            case "zh-hk":
                return "zh-TW";
            case "nb":
                return "no";
            case "pt-br":
                return "pt";
            default:
                final int dash = lang.indexOf('-');
                return dash > 0 ? lang.substring(0, dash) : lang;
        }
    }

    private static final class GoogleRequest {
        final String key;
        final String text;
        final String from;
        final String to;
        final ArrayList<Waiter> waiters = new ArrayList<>(1);
        boolean finished;

        GoogleRequest(String key, String text, String from, String to) {
            this.key = key;
            this.text = text;
            this.from = from;
            this.to = to;
        }
    }

    /** One caller of a Google request: whose it is (for {@link #cancelGoogle}) and what to run. */
    private static final class Waiter {
        final int account;
        final long dialogId;
        /** Whole-chat translation (else a Quick Translate tap or an untied call). */
        final boolean chat;
        final Utilities.Callback2<String, Boolean> done;
        final Runnable onCancel;

        Waiter(int account, long dialogId, boolean chat, Utilities.Callback2<String, Boolean> done, Runnable onCancel) {
            this.account = account;
            this.dialogId = dialogId;
            this.chat = chat;
            this.done = done;
            this.onCancel = onCancel;
        }
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.translate;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.SparseBooleanArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.RestrictedLanguagesSelectActivity;
import org.telegram.zenlygram.chat.ZgChatAppearance;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.history.ZgKeptDeleted;

import java.util.ArrayList;

/**
 * Settings > Zenlygram > Translate Messages > Quick Translate button (iOS SGSimpleSettings.quickTranslateButton).
 *
 * <p>A round translate button next to incoming messages whose text is in another language. One tap translates the
 * message in place with the selected service (Telegram by message id, or Google Translate by text, see
 * {@link ZgTranslate}); a second tap shows the original again. The state survives restarts (per account, the last
 * {@value #MAX_REMEMBERED} messages). Never shown in secret chats, for copy-protected messages or chats, ads, service
 * messages, polls and articles, or while the whole chat is being translated. A kept copy of a deleted message is never
 * translated (its text never leaves the device); one that was shown translated before the deletion keeps only the way
 * back to its original. An album translates its caption (drawn by its bottom cell, {@link #target}). Wide channel
 * posts have no room beside them: there the same action is in the message menu (ZgMessageMenu).
 *
 * <p>Drawing and touch handling live in ChatMessageCell (ZENLYGRAM hooks) and {@link ZgQuickTranslateButton}; the
 * translated text is Telegram's own per-message translation storage (messageOwner.translatedText), shown by
 * MessageObject.updateTranslation through {@link #isShown(MessageObject)}.
 */
public final class ZgQuickTranslate {

    /** Per-account main settings key: "dialogId:messageId;..." of the messages shown translated, oldest first. */
    private static final String KEY_SHOWN = "zg_quick_translated";
    static final int MAX_REMEMBERED = 500;

    private static final Object lock = new Object();
    private static final AccountState[] states = new AccountState[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgQuickTranslate() {
    }

    private static final class AccountState {
        /** dialog -> message ids shown translated (fast lookups from any thread, no allocation). */
        final LongSparseArray<SparseBooleanArray> shown = new LongSparseArray<>();
        /** Same entries, oldest first, for the size limit and saving. */
        final ArrayList<String> order = new ArrayList<>();
        final LongSparseArray<SparseBooleanArray> loading = new LongSparseArray<>();
        final LongSparseArray<SparseBooleanArray> detecting = new LongSparseArray<>();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Questions (any thread)
    // ---------------------------------------------------------------------------------------------------------

    /** The switch is on (and the feature is in this build). */
    public static boolean isEnabled() {
        return ZgFeatures.QUICK_TRANSLATE.isPorted() && ZgSettings.quickTranslateButton.get();
    }

    /**
     * Static checks of a message, done when a cell binds it (ChatMessageCell.setMessageObject): incoming text with a
     * real id, no ads / service / scheduled / admin-log / quick-reply / repost previews, no polls, articles, big emoji,
     * voice or round videos, never secret chats or copy-protected messages and chats (iOS #61).
     */
    public static boolean isCandidate(int account, MessageObject msg) {
        if (!ZgFeatures.QUICK_TRANSLATE.isPorted() || msg == null || msg.messageOwner == null) {
            return false;
        }
        if (msg.isOutOwner() || msg.getId() <= 0 || msg.isSponsored() || msg.scheduled || msg.eventId != 0
            || msg.isQuickReply() || msg.isRepostPreview || msg.messageOwner.action != null) {
            return false;
        }
        if (ZgKeptDeleted.isKept(msg)) {
            // Kept deleted data never leaves the device (Phase 1A: no Translate on kept copies either). A copy that was
            // shown translated before the deletion keeps its button as the way back: onPress reverts without a request.
            return isShown(account, msg.getDialogId(), msg.getId());
        }
        if (TextUtils.isEmpty(msg.messageOwner.message) || !TranslateController.isTranslatable(msg)) {
            return false;
        }
        if (msg.type == MessageObject.TYPE_POLL || msg.type == MessageObject.TYPE_ARTICLE || msg.type == MessageObject.TYPE_EMOJIS
            || msg.type == MessageObject.TYPE_VOICE || msg.type == MessageObject.TYPE_ROUND_VIDEO || msg.messageOwner.rich_message != null) {
            return false;
        }
        final long dialogId = msg.getDialogId();
        if (DialogObject.isEncryptedDialog(dialogId) || msg.messageOwner.noforwards) {
            return false;
        }
        return !MessagesController.getInstance(account).isPeerNoForwards(dialogId);
    }

    /**
     * The message Quick Translate works on for a chat cell: an album's caption message (the album's bottom cell draws
     * the one caption; null when the album has none or several), else the cell's own message. Bind time, not drawing.
     */
    public static MessageObject target(MessageObject msg, MessageObject.GroupedMessages group) {
        if (group == null || group.isDocuments || group.messages.size() < 2) {
            return msg;
        }
        // GroupedMessages.findCaptionMessageObject without its calculate() (the cell binds an album already laid out).
        MessageObject caption = null;
        for (int i = 0, n = group.messages.size(); i < n; i++) {
            final MessageObject m = group.messages.get(i);
            if (m != null && !TextUtils.isEmpty(m.caption)) {
                if (caption != null) {
                    return null; // several captions: the album shows none
                }
                caption = m;
            }
        }
        return caption;
    }

    /**
     * Draw-time checks (ChatMessageCell): the switch, the service, whole-chat translation, the message language
     * (detected once with ML Kit and stored like Telegram does) against the Do Not Translate list and the target.
     * A message that is shown translated always keeps its button, so it can be switched back; a kept copy of a deleted
     * message gets it only then. No button beside a wide channel post (its action is in the message menu,
     * {@link #menuTarget}), but its language is detected here, so that item is ready at the first long-press. UI thread.
     */
    public static boolean isReady(int account, MessageObject msg) {
        return isReady(account, msg, false);
    }

    private static boolean isReady(int account, MessageObject msg, boolean allowWide) {
        if (!isEnabled() || msg == null || msg.messageOwner == null) {
            return false;
        }
        final long dialogId = msg.getDialogId();
        final MessagesController mc = MessagesController.getInstance(account);
        refreshDialogCache(account, dialogId, mc.getTranslateController());
        if (cachedTranslating) {
            return false;
        }
        if (msg.translated && isShown(account, dialogId, msg.getId())) {
            return true;
        }
        if (ZgKeptDeleted.isKept(msg)) {
            return false; // a kept copy only keeps the way back above: it is never translated again
        }
        if (!ZgTranslate.quickAvailable(mc)) {
            return false;
        }
        final String language = msg.messageOwner.originalLanguage;
        if (language == null) {
            // On-device (ML Kit), also for a wide post: its menu item (menuTarget) needs the language at the first
            // long-press, and Telegram detects it itself only where its own chat translation is available.
            detect(account, msg);
            return false;
        }
        // Wide posts in channels: no button beside the post (iOS sgLocalNeedsQuickTranslateButton = false; spec
        // swiftgram-chatlist-chats, WIDE_CHANNEL_POSTS interactions). A post already shown translated keeps it above.
        if (!allowWide && ZgChatAppearance.isWideChannelPost(msg)) {
            return false;
        }
        if (TranslateController.UNKNOWN_LANGUAGE.equals(language) || RestrictedLanguagesSelectActivity.getRestrictedLanguages().contains(language)) {
            return false;
        }
        final String target = cachedTarget;
        return !TextUtils.isEmpty(target) && !sameLanguage(language, target);
    }

    /**
     * ZgMessageMenu, for a wide channel post (no button beside it): the message the menu's Quick Translate / Show
     * Original item acts on (the album's caption message), or null when the item is not offered.
     */
    public static MessageObject menuTarget(int account, MessageObject selected, MessageObject.GroupedMessages group) {
        if (!isEnabled() || selected == null || !ZgChatAppearance.isWideChannelPost(selected)) {
            return null;
        }
        final MessageObject msg = target(selected, group);
        if (msg == null || !isCandidate(account, msg) || !isReady(account, msg, true)) {
            return null;
        }
        return msg;
    }

    /** ZgMessageMenu: {@code msg} is shown translated by Quick Translate (the item then reads "Show Original"). */
    public static boolean isShownTranslated(MessageObject msg) {
        return msg != null && msg.translated && isShown(msg);
    }

    // Draw-path cache (isReady runs for every candidate cell on every frame): the whole-chat state and the target
    // language of the last dialog asked. Refreshed after CACHE_MS, or at once after invalidateCache(). UI thread only.
    private static final long CACHE_MS = 500;
    private static volatile int cacheEpoch;
    private static int cachedEpoch = -1;
    private static int cachedAccount = -1;
    private static long cachedDialogId;
    private static long cachedAt;
    private static boolean cachedTranslating;
    private static String cachedTarget;

    /**
     * The dialog's translation state changed (ChatActivity on dialogTranslate, which also follows the settings, a
     * whole-chat toggle and a language change): the next draw reads it again. Any thread.
     */
    public static void invalidateCache() {
        cacheEpoch++;
    }

    private static void refreshDialogCache(int account, long dialogId, TranslateController tc) {
        final long now = SystemClock.elapsedRealtime();
        final int epoch = cacheEpoch;
        if (cachedAccount == account && cachedDialogId == dialogId && cachedEpoch == epoch && now >= cachedAt && now - cachedAt < CACHE_MS) {
            return;
        }
        cachedAccount = account;
        cachedDialogId = dialogId;
        cachedEpoch = epoch;
        cachedAt = now;
        cachedTranslating = tc.isTranslatingDialog(dialogId);
        cachedTarget = cachedTranslating ? null : tc.getDialogTranslateTo(dialogId);
    }

    /**
     * MessageObject.updateTranslation: whether this message is shown translated by Quick Translate. Any thread
     * (MessageObject constructors run on storage threads); no allocation once loaded.
     */
    public static boolean isShown(MessageObject msg) {
        if (msg == null || !isEnabled()) {
            return false;
        }
        return isShown(msg.currentAccount, msg.getDialogId(), msg.getId());
    }

    /** ChatMessageCell: the bubble shimmers while a quick translation loads. */
    public static boolean isLoading(MessageObject msg) {
        if (msg == null || !ZgFeatures.QUICK_TRANSLATE.isPorted()) {
            return false;
        }
        synchronized (lock) {
            final AccountState state = peekLocked(msg.currentAccount);
            return state != null && contains(state.loading, msg.getDialogId(), msg.getId());
        }
    }

    private static boolean isShown(int account, long dialogId, int messageId) {
        synchronized (lock) {
            final AccountState state = getLocked(account);
            return state != null && contains(state.shown, dialogId, messageId);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // The tap (UI thread)
    // ---------------------------------------------------------------------------------------------------------

    /** ChatActivity delegate: the Quick Translate button of {@code msg} was tapped. */
    public static void onPress(BaseFragment fragment, MessageObject msg) {
        if (fragment == null || msg == null || msg.messageOwner == null || !isEnabled()) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final long dialogId = msg.getDialogId();
        final int messageId = msg.getId();
        final NotificationCenter center = NotificationCenter.getInstance(account);

        if (isShown(account, dialogId, messageId)) {
            // Second tap (or a tap while loading): back to the original.
            setShown(account, dialogId, messageId, false);
            setLoading(account, dialogId, messageId, false);
            center.postNotificationName(NotificationCenter.messageTranslated, msg);
            return;
        }
        // Gates again: the chat may have become copy-protected or translated since the button was drawn.
        if (!isCandidate(account, msg) || MessagesController.getInstance(account).getTranslateController().isTranslatingDialog(dialogId)) {
            return;
        }
        final String target = MessagesController.getInstance(account).getTranslateController().getDialogTranslateTo(dialogId);
        if (TextUtils.isEmpty(target)) {
            return;
        }
        setShown(account, dialogId, messageId, true);
        if (msg.messageOwner.translatedText != null && TextUtils.equals(target, msg.messageOwner.translatedToLanguage)) {
            center.postNotificationName(NotificationCenter.messageTranslated, msg);
            return;
        }
        setLoading(account, dialogId, messageId, true);
        center.postNotificationName(NotificationCenter.messageTranslating, msg);

        final TLRPC.TL_textWithEntities source = new TLRPC.TL_textWithEntities();
        source.text = msg.messageOwner.message;
        source.entities = msg.messageOwner.entities != null ? msg.messageOwner.entities : new ArrayList<>();

        if (ZgTranslate.quickUsesGoogle(MessagesController.getInstance(account))) {
            requestGoogle(account, msg, source, target);
        } else {
            requestTelegram(account, msg, source, target);
        }
    }

    private static void requestGoogle(int account, MessageObject msg, TLRPC.TL_textWithEntities source, String target) {
        // Google detects the source language. Dropped (back to the original) if Google stops being used meanwhile.
        ZgTranslate.googleTranslateForTap(account, msg.getDialogId(), source.text, target, (result, rateLimit) -> {
            if (result == null) {
                onFailed(account, msg, rateLimit != null && rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2, null);
                return;
            }
            final TLRPC.TL_textWithEntities translated = new TLRPC.TL_textWithEntities();
            translated.text = result;
            onTranslated(account, msg, translated, target);
        }, () -> onCancelled(account, msg));
    }

    /** Telegram translates by message id only: the text itself is never sent (a message gone from the server fails). */
    private static void requestTelegram(int account, MessageObject msg, TLRPC.TL_textWithEntities source, String target) {
        final long dialogId = msg.getDialogId();
        final TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            onFailed(account, msg, R.string.TranslationFailedAlert2, null);
            return;
        }
        final TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 1;
        req.peer = peer;
        req.id.add(msg.getId());
        req.to_lang = TranslateController.normalizeLanguage(target);
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.TL_messages_translateResult && !((TLRPC.TL_messages_translateResult) res).result.isEmpty()
                && ((TLRPC.TL_messages_translateResult) res).result.get(0) != null && ((TLRPC.TL_messages_translateResult) res).result.get(0).text != null) {
                onTranslated(account, msg, TranslateAlert2.preprocess(source, ((TLRPC.TL_messages_translateResult) res).result.get(0)), target);
            } else if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {
                if (ZgTranslate.windowMayUseGoogle() && isLoading(msg)) {
                    // Telegram (default): Telegram's own fallback, as its Translate window does.
                    requestGoogle(account, msg, source, target);
                } else {
                    // "Telegram only": no silent Google (iOS #60); say where Google can be chosen.
                    onFailed(account, msg, 0, ZgTranslate.getTelegramUnavailableText());
                }
            } else {
                final boolean limit = err != null && err.text != null && (err.text.startsWith("FLOOD_WAIT") || "QUOTA_EXCEEDED".equals(err.text));
                onFailed(account, msg, limit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2, null);
            }
        }));
    }

    private static void onTranslated(int account, MessageObject msg, TLRPC.TL_textWithEntities translated, String target) {
        final long dialogId = msg.getDialogId();
        final int messageId = msg.getId();
        final boolean wasLoading = setLoading(account, dialogId, messageId, false);
        msg.messageOwner.translatedText = translated;
        msg.messageOwner.translatedToLanguage = target;
        MessagesStorage.getInstance(account).updateMessageCustomParams(dialogId, msg.messageOwner);
        if (wasLoading || isShown(account, dialogId, messageId)) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslated, msg);
        }
    }

    private static void onFailed(int account, MessageObject msg, int errorRes, String errorText) {
        final long dialogId = msg.getDialogId();
        final int messageId = msg.getId();
        final boolean wasLoading = setLoading(account, dialogId, messageId, false);
        if (!wasLoading) {
            return; // switched back by the user meanwhile
        }
        setShown(account, dialogId, messageId, false);
        final NotificationCenter center = NotificationCenter.getInstance(account);
        center.postNotificationName(NotificationCenter.messageTranslated, msg);
        center.postNotificationName(NotificationCenter.messageTranslating, msg);
        ZgTranslate.showError(errorText != null ? errorText : LocaleController.getString(errorRes));
    }

    /** A queued Google request was dropped (Google is no longer used): back to the original, without an error. */
    private static void onCancelled(int account, MessageObject msg) {
        final long dialogId = msg.getDialogId();
        final int messageId = msg.getId();
        if (!setLoading(account, dialogId, messageId, false)) {
            return;
        }
        setShown(account, dialogId, messageId, false);
        final NotificationCenter center = NotificationCenter.getInstance(account);
        center.postNotificationName(NotificationCenter.messageTranslated, msg);
        center.postNotificationName(NotificationCenter.messageTranslating, msg);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Language detection (Telegram's bundled ML Kit language id, stored like TranslateController.checkLanguage)
    // ---------------------------------------------------------------------------------------------------------

    private static void detect(int account, MessageObject msg) {
        final String text = msg.messageOwner.message;
        if (TextUtils.isEmpty(text) || !LanguageDetector.hasSupport()) {
            return;
        }
        final long dialogId = msg.getDialogId();
        final int messageId = msg.getId();
        synchronized (lock) {
            final AccountState state = getLocked(account);
            if (state == null || contains(state.detecting, dialogId, messageId)) {
                return;
            }
            put(state.detecting, dialogId, messageId, true);
        }
        final TLRPC.Message owner = msg.messageOwner;
        Utilities.stageQueue.postRunnable(() -> LanguageDetector.detectLanguage(text,
            language -> AndroidUtilities.runOnUIThread(() -> onDetected(account, msg, owner, language)),
            error -> AndroidUtilities.runOnUIThread(() -> onDetected(account, msg, owner, null))));
    }

    private static void onDetected(int account, MessageObject msg, TLRPC.Message owner, String language) {
        final long dialogId = msg.getDialogId();
        synchronized (lock) {
            final AccountState state = getLocked(account);
            if (state != null) {
                put(state.detecting, dialogId, msg.getId(), false);
            }
        }
        if (owner.originalLanguage == null) {
            owner.originalLanguage = language != null ? language : TranslateController.UNKNOWN_LANGUAGE;
            MessagesStorage.getInstance(account).updateMessageCustomParams(dialogId, owner);
        }
        // Redraws the open chat's cells (ChatActivity invalidates them), so the button appears.
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageTranslating, msg);
    }

    // ---------------------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------------------

    /** TranslateController.cleanup() via ZgTranslate.clearAccount (logout, app language change). */
    static void clear(int account) {
        if (account < 0 || account >= states.length) {
            return;
        }
        synchronized (lock) {
            states[account] = new AccountState();
        }
        try {
            MessagesController.getMainSettings(account).edit().remove(KEY_SHOWN).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void setShown(int account, long dialogId, int messageId, boolean shown) {
        synchronized (lock) {
            final AccountState state = getLocked(account);
            if (state == null || contains(state.shown, dialogId, messageId) == shown) {
                return;
            }
            final String key = dialogId + ":" + messageId;
            if (shown) {
                put(state.shown, dialogId, messageId, true);
                state.order.add(key);
                while (state.order.size() > MAX_REMEMBERED) {
                    final String oldest = state.order.remove(0);
                    final long[] parsed = parseKey(oldest);
                    if (parsed != null) {
                        put(state.shown, parsed[0], (int) parsed[1], false);
                    }
                }
            } else {
                put(state.shown, dialogId, messageId, false);
                state.order.remove(key);
            }
            MessagesController.getMainSettings(account).edit().putString(KEY_SHOWN, TextUtils.join(";", state.order)).apply();
        }
    }

    /** Returns whether the message was loading before. */
    private static boolean setLoading(int account, long dialogId, int messageId, boolean loading) {
        synchronized (lock) {
            final AccountState state = getLocked(account);
            if (state == null) {
                return false;
            }
            final boolean was = contains(state.loading, dialogId, messageId);
            put(state.loading, dialogId, messageId, loading);
            return was;
        }
    }

    private static AccountState peekLocked(int account) {
        return account >= 0 && account < states.length ? states[account] : null;
    }

    private static AccountState getLocked(int account) {
        if (account < 0 || account >= states.length) {
            return null;
        }
        AccountState state = states[account];
        if (state == null) {
            state = new AccountState();
            try {
                final String stored = MessagesController.getMainSettings(account).getString(KEY_SHOWN, null);
                if (!TextUtils.isEmpty(stored)) {
                    for (String key : stored.split(";")) {
                        final long[] parsed = parseKey(key);
                        if (parsed != null && !contains(state.shown, parsed[0], (int) parsed[1])) {
                            put(state.shown, parsed[0], (int) parsed[1], true);
                            state.order.add(key);
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            states[account] = state;
        }
        return state;
    }

    private static long[] parseKey(String key) {
        if (key == null) {
            return null;
        }
        final int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            return null;
        }
        try {
            return new long[] { Long.parseLong(key.substring(0, colon)), Integer.parseInt(key.substring(colon + 1)) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean contains(LongSparseArray<SparseBooleanArray> map, long dialogId, int messageId) {
        final SparseBooleanArray ids = map.get(dialogId);
        return ids != null && ids.get(messageId, false);
    }

    private static void put(LongSparseArray<SparseBooleanArray> map, long dialogId, int messageId, boolean value) {
        SparseBooleanArray ids = map.get(dialogId);
        if (value) {
            if (ids == null) {
                map.put(dialogId, ids = new SparseBooleanArray());
            }
            ids.put(messageId, true);
        } else if (ids != null) {
            ids.delete(messageId);
            if (ids.size() == 0) {
                map.remove(dialogId);
            }
        }
    }

    /** "pt-br" and "pt", "nb" and "no" are the same language for the button. Draw time: no allocation. */
    private static boolean sameLanguage(String a, String b) {
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        final int la = baseLength(a);
        final int lb = baseLength(b);
        if (la == lb && a.regionMatches(true, 0, b, 0, la)) {
            return true;
        }
        return isNorwegian(a, la) && isNorwegian(b, lb);
    }

    private static int baseLength(String language) {
        for (int i = 0; i < language.length(); i++) {
            final char c = language.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return language.length();
    }

    private static boolean isNorwegian(String language, int length) {
        return length == 2 && (language.regionMatches(true, 0, "nb", 0, 2) || language.regionMatches(true, 0, "no", 0, 2));
    }
}

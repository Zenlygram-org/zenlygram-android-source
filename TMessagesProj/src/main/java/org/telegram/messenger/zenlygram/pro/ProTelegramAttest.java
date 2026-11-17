package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Obtains Telegram-signed Mini App initData for a bind-attest offer, silently, as exactly one
 * signed-in Telegram account: resolve @bot_username afresh (it must be the offered bot id, a bot,
 * and have a Main Mini App), then messages.requestMainWebView(peer = bot, bot, start_param,
 * platform = "android") with no other flags. Only the Main Mini App echoes start_param into
 * initData (simple web views carry no initData; requestWebView omits start_param). The returned
 * URL is never opened, no message is sent to the bot and nothing is shown: the username is
 * resolved with contacts.resolveUsername directly, because UserNameResolver puts a flood-wait
 * bulletin on whatever screen is open. initData is never logged.
 */
public final class ProTelegramAttest {
    public interface Result {
        /** UI thread. {@code initData} is null on any failure: the caller links by code instead. */
        void done(String initData);
    }

    /**
     * UI thread. initData on success (errorCode null); otherwise initData is null and errorCode is
     * attest_unavailable when a prerequisite failed before the RPC (the bot is not resolvable, resolves to
     * another id, is not a bot or has no Main Mini App), telegram_<error> for an RPC error
     * (ProAutoLinkMachine.telegramErrorCode), or null when Telegram answered without usable initData.
     */
    public interface Outcome {
        void done(String initData, String errorCode);
    }

    /** The Pro screen's attest: the same requests; only whether initData arrived is passed on. */
    public static void obtain(int account, ProBindAttest.Offer offer, Result result) {
        obtain(account, offer, (initData, errorCode) -> result.done(initData));
    }

    public static void obtain(int account, ProBindAttest.Offer offer, Outcome outcome) {
        // Forced: a cached user may predate the bot's Main Mini App being enabled (or removed).
        TLRPC.TL_contacts_resolveUsername resolve = new TLRPC.TL_contacts_resolveUsername();
        resolve.username = offer.botUsername;
        ConnectionsManager.getInstance(account).sendRequest(resolve, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            MessagesController messages = MessagesController.getInstance(account);
            TLRPC.User bot = null;
            if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                messages.putUsers(resolved.users, false);
                messages.putChats(resolved.chats, false);
                MessagesStorage.getInstance(account).putUsersAndChats(resolved.users, resolved.chats, false, true);
                long peerId = MessageObject.getPeerId(resolved.peer);
                bot = peerId > 0 ? messages.getUser(peerId) : null;
            }
            if (bot == null || !ProBindAttest.botMatches(offer, bot.id, bot.bot, bot.bot_has_main_app)) {
                outcome.done(null, ProAutoLinkMachine.CODE_ATTEST_UNAVAILABLE);
                return;
            }
            TLRPC.TL_messages_requestMainWebView request = new TLRPC.TL_messages_requestMainWebView();
            request.peer = messages.getInputPeer(bot.id);
            request.bot = messages.getInputUser(bot);
            request.start_param = offer.startParam;
            request.flags |= 2; // start_param
            request.platform = "android";
            ConnectionsManager.getInstance(account).sendRequest(request, (webView, failure) -> AndroidUtilities.runOnUIThread(() -> {
                if (failure != null) {
                    outcome.done(null, ProAutoLinkMachine.telegramErrorCode(failure.text));
                } else {
                    outcome.done(webView instanceof TLRPC.TL_webViewResultUrl
                            ? ProBindAttest.initData(((TLRPC.TL_webViewResultUrl) webView).url) : null, null);
                }
            }));
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private ProTelegramAttest() { }
}

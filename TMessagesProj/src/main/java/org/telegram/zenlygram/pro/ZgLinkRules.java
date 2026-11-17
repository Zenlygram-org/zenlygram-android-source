/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.ProErrors;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The decisions of the "Link Telegram Account" screen ({@link ZgLinkAccountActivity}) that need no Android type, so
 * they are tested on the JVM (ZgLinkRulesTest). They are the iOS rules, fed with the Android client's values:
 *
 * <ul>
 *   <li>{@link #permission}: whether a manual link may start at all and whether it may use the silent Telegram
 *       confirmation (iOS SGCustomerSync.bindPermission + SGEntitlement.permitsStart);</li>
 *   <li>{@link #failure}: the message of a failed bind/start or bind/poll, from the client's error code and HTTP
 *       status (iOS SGEntitlementFailureMapping and SGLinkAccountModel.message(for:));</li>
 *   <li>{@link #afterPoll} / {@link #afterPollError}: what one bind/poll answer means for the screen (iOS
 *       SGLinkAccountKeyRestartBudget.reaction); which errors end the code is the client's own rule
 *       (ProErrors.endsLink) plus the paused switch, which iOS ends on too;</li>
 *   <li>{@link #botDeepLink}: the only link "Open Telegram" may open.</li>
 * </ul>
 *
 * Nothing here sends, stores or logs.
 */
final class ZgLinkRules {

    private ZgLinkRules() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // May a link start
    // ---------------------------------------------------------------------------------------------------------

    enum Permission {
        /** Any link, the Telegram-attested one included; bind/start carries the account's grant. */
        ALLOWED,
        /** The code flow only: no attestation, no consent attached (an optional-consent build without a grant). */
        CODE_ONLY,
        /** No bind/start at all: nothing is sent for this account. */
        REFUSED
    }

    /**
     * An account whose deletion is pending never links. An account with an effective grant links any way. Without
     * one a required-consent build sends nothing (the consent wall is in front of this screen anyway; this is the
     * defence behind it) and an optional-consent build offers the code only.
     */
    static Permission permission(boolean backendEnabled, boolean deletionPending, boolean effectiveGrant, boolean consentRequired) {
        if (!backendEnabled || deletionPending) {
            return Permission.REFUSED;
        }
        if (effectiveGrant) {
            return Permission.ALLOWED;
        }
        return consentRequired ? Permission.REFUSED : Permission.CODE_ONLY;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Failure messages
    // ---------------------------------------------------------------------------------------------------------

    /** What the failed state says. The first seven are the iOS link screen's; the rest are Android client errors. */
    enum Message {
        DECLINED,
        CODE_EXPIRED,
        DID_NOT_GO_THROUGH,
        /** 503 bind_paused: the operator paused linking. */
        LINKING_UNAVAILABLE,
        TOO_MANY_ATTEMPTS,
        NO_CONNECTION,
        TEMPORARILY_UNAVAILABLE,
        CHECK_DATE_AND_TIME,
        /** The code was confirmed by another Telegram account than the one this screen links. */
        WRONG_ACCOUNT,
        /** The session is signed with a key this version does not know. */
        UPDATE_REQUIRED,
        /** The device's secure storage could not keep the session. */
        STORAGE
    }

    /**
     * The message for a ZenlyPro.Error of bind/start or bind/poll. The stable code decides before the status (bind_paused
     * is a 503 that is a switch, not an outage); a 5xx without a code of its own and a lost connection are both "no
     * connection", as on iOS; whatever is left is "temporarily unavailable", which never promises anything.
     */
    static Message failure(String code, int httpStatus) {
        if (httpStatus == 429 || "rate_limited".equals(code)) {
            return Message.TOO_MANY_ATTEMPTS;
        }
        if (code != null) {
            switch (code) {
                case "connection_failed":
                    return Message.NO_CONNECTION;
                case "bind_paused":
                    return Message.LINKING_UNAVAILABLE;
                case "bind_not_found":
                // Superseded or bound to another install key: this code is finished, a new one works.
                case "link_changed":
                case "session_changed":
                case "device_key_changed":
                    return Message.DID_NOT_GO_THROUGH;
                case "clock_skew":
                    return Message.CHECK_DATE_AND_TIME;
                case "account_mismatch":
                    return Message.WRONG_ACCOUNT;
                case "unknown_signing_key":
                    return Message.UPDATE_REQUIRED;
                case "storage_unavailable":
                    return Message.STORAGE;
                default:
                    break;
            }
        }
        return httpStatus >= 500 ? Message.NO_CONNECTION : Message.TEMPORARILY_UNAVAILABLE;
    }

    // ---------------------------------------------------------------------------------------------------------
    // One bind/poll answer
    // ---------------------------------------------------------------------------------------------------------

    /** What the screen does after one bind/poll answer. */
    static final class PollStep {
        enum Next {
            /** Keep the code and poll again. */
            POLL_AGAIN,
            /** The session was adopted: linked. */
            LINKED,
            /** The code is finished: show {@link #message} and offer a new code. */
            FAILED
        }

        /** The "No connection." hint under the waiting line. */
        enum Hint {
            CLEAR,
            KEEP,
            NO_CONNECTION
        }

        final Next next;
        /** Only for {@link Next#FAILED}. */
        final Message message;
        /** Only for {@link Next#POLL_AGAIN}. */
        final Hint hint;

        private PollStep(Next next, Message message, Hint hint) {
            this.next = next;
            this.message = message;
            this.hint = hint;
        }

        private static PollStep again(Hint hint) {
            return new PollStep(Next.POLL_AGAIN, null, hint);
        }

        private static PollStep failed(Message message) {
            return new PollStep(Next.FAILED, message, null);
        }
    }

    /** {@code state} is the ZenlyPro.LinkState of a bind/poll that answered (its name, any case). */
    static PollStep afterPoll(String state) {
        final String value = state == null ? "" : state.toLowerCase(Locale.US);
        switch (value) {
            case "pending":
                return PollStep.again(PollStep.Hint.CLEAR);
            case "confirmed":
                return new PollStep(PollStep.Next.LINKED, null, null);
            case "denied":
                return PollStep.failed(Message.DECLINED);
            case "expired":
                return PollStep.failed(Message.CODE_EXPIRED);
            case "consumed":
                return PollStep.failed(Message.DID_NOT_GO_THROUGH);
            default:
                return PollStep.failed(Message.TEMPORARILY_UNAVAILABLE);
        }
    }

    /**
     * A bind/poll that failed. Throttling keeps the code and whatever hint is up (the Retry-After is the schedule's);
     * an error that ends the code shows its message; anything else is an outage or a lost connection: the code lives
     * for five minutes, so the screen keeps polling and says "No connection." meanwhile.
     */
    static PollStep afterPollError(String code, int httpStatus) {
        if (httpStatus == 429 || "rate_limited".equals(code)) {
            return PollStep.again(PollStep.Hint.KEEP);
        }
        if (ProErrors.endsLink(code, httpStatus) || "bind_paused".equals(code)) {
            return PollStep.failed(failure(code, httpStatus));
        }
        return PollStep.again(PollStep.Hint.NO_CONNECTION);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The code
    // ---------------------------------------------------------------------------------------------------------

    /** The server's code lifetime (contract: 300 s), used when its expires_at cannot be trusted on this clock. */
    static final long DEFAULT_LIFETIME_MS = 300_000;
    private static final long MAX_LIFETIME_MS = 900_000;

    /**
     * How long the code just issued lives, from its {@code expires_at} and the trusted clock (both epoch seconds).
     * A code the server issued a moment ago is neither expired already nor valid for much longer than its five
     * minutes: such a value means this device's clock is off, and the default lifetime is used instead (the server's
     * own "expired" answer still ends the code).
     */
    static long lifetimeMs(long expiresAtEpochSeconds, long nowEpochSeconds) {
        final long left = (expiresAtEpochSeconds - nowEpochSeconds) * 1000L;
        return left <= 0 || left > MAX_LIFETIME_MS ? DEFAULT_LIFETIME_MS : left;
    }

    /** "4:05" for the "Expires in" line. */
    static String remaining(long seconds) {
        final long value = Math.max(0, seconds);
        return String.format(Locale.US, "%d:%02d", value / 60, value % 60);
    }

    /** The code as TalkBack reads it, character by character: that is how it is compared with the bot's. */
    static String spelled(String code) {
        if (code == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(code.length() * 2);
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(code.charAt(i));
        }
        return out.toString();
    }

    private static final Pattern BOT_PATH = Pattern.compile("/[A-Za-z][A-Za-z0-9_]{3,31}");
    private static final Pattern START_QUERY = Pattern.compile("start=[A-Za-z0-9_-]{1,64}");

    /**
     * {@code deepLink} when it is exactly a bot's start link, {@code https://t.me/<bot>?start=<param>}, else null. The
     * button says "Open Telegram" and opens the link inside this app, so it opens that bot's chat or nothing: never
     * another host, an invoice, an invite, a Mini App or any other t.me action a wrong answer could name.
     */
    static String botDeepLink(String deepLink) {
        if (deepLink == null) {
            return null;
        }
        try {
            final URI uri = new URI(deepLink);
            if (!"https".equals(uri.getScheme()) || !"t.me".equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo() != null
                || uri.getPort() != -1 || uri.getRawFragment() != null) {
                return null;
            }
            final String path = uri.getRawPath();
            final String query = uri.getRawQuery();
            if (path == null || query == null || !BOT_PATH.matcher(path).matches() || !START_QUERY.matcher(query).matches()) {
                return null;
            }
            return deepLink;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}

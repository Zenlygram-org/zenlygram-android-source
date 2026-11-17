package org.telegram.messenger.zenlygram.pro;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One automatic (headless) link attempt as a pure state machine (design 50 §8.3), host-tested.
 * ProAutoLink performs each Action and feeds its result back; the machine decides the next step,
 * the outcome and the link.attempt error code. Steps: bind/start (with the consent) → no attest
 * offer: ATTEST_UNAVAILABLE (the code UI is never shown; the server row expires) → Telegram Mini App
 * initData under a 30 s watchdog → bind/attest → bind/poll at once, then 1 s apart, at most 3 polls.
 * A manual link, a slot change or a superseded link ends the attempt as STALE (no penalty, no event).
 * Inputs that do not belong to the current step are ignored. Main thread only; nothing here logs.
 */
public final class ProAutoLinkMachine {
    public enum Outcome {
        SUCCESS, ATTEST_UNAVAILABLE, RATE_LIMITED, BIND_PAUSED, BIND_CONFLICT, CONSENT_STALE, ATTEST_FAILED, POLL_TIMEOUT,
        OFFLINE, FAILED, STALE
    }

    /** What ProAutoLink does next. */
    public enum Action {
        /** ZenlyPro.beginLinkHeadless. */
        BEGIN,
        /** ProTelegramAttest.obtain(account, offer, Outcome) and arm the WATCHDOG_MS timer. */
        OBTAIN,
        /** ZenlyPro.attestLinkHeadless with the initData just obtained. */
        ATTEST,
        /** ZenlyPro.pollLinkHeadless. */
        POLL,
        /** Poll again after POLL_INTERVAL_MS. */
        WAIT,
        /** The attempt ended: read outcome(), errorCode() and retryAfterSeconds(). */
        FINISH,
        /** A late or misplaced input (e.g. Telegram answering after the watchdog): nothing to do. */
        IGNORE
    }

    public static final long WATCHDOG_MS = 30_000, POLL_INTERVAL_MS = 1_000;
    public static final int MAX_POLLS = 3;
    /** link.attempt error codes the machine names itself (contract §3.4); the others are the backend's. */
    public static final String CODE_ATTEST_UNAVAILABLE = "attest_unavailable", CODE_TIMEOUT = "timeout",
            CODE_OFFLINE = "offline", CODE_UNKNOWN = "unknown";

    /** Contract §3.4 error_code. */
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    /** An MTProto RPC error name (TL_error.text), e.g. FLOOD_WAIT_17. */
    private static final Pattern RPC_ERROR = Pattern.compile("[A-Z0-9][A-Z0-9_]*");
    private static final String TELEGRAM_PREFIX = "telegram_";

    private enum Step { NEW, BEGIN, OBTAIN, ATTEST, POLL, WAIT, DONE }

    private Step step = Step.NEW;
    private int polls;
    private Outcome outcome;
    private String errorCode;
    private int retryAfterSeconds;

    public Action start() {
        if (step != Step.NEW) return Action.IGNORE;
        step = Step.BEGIN;
        return Action.BEGIN;
    }

    /** bind/start answered; offered = its answer carried a valid attest offer (ZenlyPro.LinkRequest.attest). */
    public Action begun(boolean offered) {
        if (step != Step.BEGIN) return Action.IGNORE;
        if (!offered) return finish(Outcome.ATTEST_UNAVAILABLE, CODE_ATTEST_UNAVAILABLE, 0);
        step = Step.OBTAIN;
        return Action.OBTAIN;
    }

    /** Telegram answered (ProTelegramAttest.Outcome): the initData, or the code of why there is none. */
    public Action obtained(String initData, String telegramCode) {
        if (step != Step.OBTAIN) return Action.IGNORE;
        if (initData == null || initData.isEmpty()) return finish(Outcome.ATTEST_FAILED, validCode(telegramCode), 0);
        step = Step.ATTEST;
        return Action.ATTEST;
    }

    /** WATCHDOG_MS passed since OBTAIN without an answer from Telegram. */
    public Action watchdog() {
        if (step != Step.OBTAIN) return Action.IGNORE;
        return finish(Outcome.ATTEST_FAILED, CODE_TIMEOUT, 0);
    }

    /** bind/attest confirmed the bind: the first poll runs at once. */
    public Action attested() {
        if (step != Step.ATTEST) return Action.IGNORE;
        step = Step.POLL;
        polls = 1;
        return Action.POLL;
    }

    /**
     * bind/poll answered with state (ZenlyPro.LinkState, lower case). confirmed: ZenlyPro already adopted the
     * session (Telegram-id check, signature, push registration). pending: poll again after POLL_INTERVAL_MS,
     * at most MAX_POLLS in all.
     */
    public Action polled(String state) {
        if (step != Step.POLL) return Action.IGNORE;
        if ("confirmed".equals(state)) return finish(Outcome.SUCCESS, null, 0);
        if (!"pending".equals(state)) return finish(Outcome.FAILED, CODE_UNKNOWN, 0);
        if (polls >= MAX_POLLS) return finish(Outcome.POLL_TIMEOUT, CODE_TIMEOUT, 0);
        step = Step.WAIT;
        return Action.WAIT;
    }

    /** POLL_INTERVAL_MS passed after a pending poll. */
    public Action waited() {
        if (step != Step.WAIT) return Action.IGNORE;
        step = Step.POLL;
        polls++;
        return Action.POLL;
    }

    /** A ZenlyPro step (bind/start, bind/attest or bind/poll) failed with ZenlyPro.Error code, status and Retry-After. */
    public Action failed(String code, int httpStatus, int retryAfter) {
        if (step != Step.BEGIN && step != Step.ATTEST && step != Step.POLL) return Action.IGNORE;
        Outcome classified = classify(code, httpStatus);
        String reported;
        switch (classified) {
            case STALE: reported = null; break;
            case OFFLINE: reported = CODE_OFFLINE; break;
            case RATE_LIMITED: reported = "rate_limited"; break;
            default: reported = validCode(code);
        }
        return finish(classified, reported, Math.max(0, retryAfter));
    }

    /** A manual Link tap, a change of the slot's Telegram user or ProAutoLink.forget: no penalty, no event. */
    public Action interrupted() {
        if (step == Step.DONE) return Action.IGNORE;
        return finish(Outcome.STALE, null, 0);
    }

    public boolean finished() { return step == Step.DONE; }

    /** Null until finished. */
    public Outcome outcome() { return outcome; }

    /** The link.attempt error_code (contract §3.4) of a finished attempt; null for SUCCESS and STALE. */
    public String errorCode() { return errorCode; }

    public int retryAfterSeconds() { return retryAfterSeconds; }

    /** Polls started so far (diagnostics and tests). */
    public int polls() { return polls; }

    private Action finish(Outcome result, String code, int retryAfter) {
        step = Step.DONE;
        outcome = result;
        errorCode = code;
        retryAfterSeconds = retryAfter;
        return Action.FINISH;
    }

    /**
     * ZenlyPro error codes of the headless steps → outcome (design 50 §8.3, §8.4). Superseded or refused
     * locally (a manual link, the slot's user or session changed, linking not configured) → STALE.
     */
    static Outcome classify(String code, int httpStatus) {
        if (code != null) {
            switch (code) {
                case "link_busy": case "link_changed": case "session_changed": case "not_configured": case "login_required":
                    return Outcome.STALE;
                case "consent_stale": return Outcome.CONSENT_STALE;
                case "bind_conflict": return Outcome.BIND_CONFLICT;
                case "bind_paused": return Outcome.BIND_PAUSED;
                case "rate_limited": return Outcome.RATE_LIMITED;
                case "attest_unavailable": return Outcome.ATTEST_UNAVAILABLE;
                case "connection_failed": return Outcome.OFFLINE;
                default: break;
            }
        }
        return httpStatus == 429 ? Outcome.RATE_LIMITED : Outcome.FAILED;
    }

    /**
     * telegram_ + the RPC error name lower-cased with one trailing _<digits> removed (FLOOD_WAIT_17 →
     * telegram_flood_wait, PHONE_MIGRATE_2 → telegram_phone_migrate: the digits can reveal the account's data
     * center or a wait); anything that is not an RPC error name, or would exceed the 64-character error_code, is
     * unknown.
     */
    public static String telegramErrorCode(String rpcError) {
        if (rpcError == null || !RPC_ERROR.matcher(rpcError).matches()) return CODE_UNKNOWN;
        String name = rpcError.toLowerCase(Locale.US);
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) >= '0' && name.charAt(end - 1) <= '9') end--;
        if (end > 1 && end < name.length() && name.charAt(end - 1) == '_') name = name.substring(0, end - 1);
        String code = TELEGRAM_PREFIX + name;
        return ERROR_CODE.matcher(code).matches() ? code : CODE_UNKNOWN;
    }

    /** code when it is a contract error_code, else unknown (never free text). */
    static String validCode(String code) {
        return code != null && ERROR_CODE.matcher(code).matches() ? code : CODE_UNKNOWN;
    }
}

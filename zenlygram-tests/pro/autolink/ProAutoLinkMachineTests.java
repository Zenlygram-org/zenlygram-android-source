package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentManifest;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.messenger.zenlygram.pro.ProAutoLinkMachine.Action;
import org.telegram.messenger.zenlygram.pro.ProAutoLinkMachine.Outcome;

/**
 * One automatic link attempt (ProAutoLinkMachine): the step order, polls (at once, then 1 s apart, at
 * most 3), the 30 s Telegram watchdog, STALE on a manual link / slot change / superseded link, outcome
 * classes and link.attempt error codes, the Telegram RPC error mapping, and that a stale grant of an
 * account that is not the selected one never starts an attempt or rides on bind/start. Run with run-tests.sh.
 */
public final class ProAutoLinkMachineTests {
    private static int checks;
    private static final long TG = 555000111L, OTHER_TG = 555000222L, T0 = 1793268000L;
    private static final String AUTH = "zenlygram.org";
    private static final String SHA = "229fd61b5ac4c2711e1b9635b6e5b24ab44ab5564baac8a2c1067a5b127f64d9";
    private static final String SHA2 = "996d5f8db11e5e06d2f23ccefc4a691cca16019b4e77456c26fb243a856d5ecd";

    public static void main(String[] args) {
        happyPath();
        polling();
        noOffer();
        telegram();
        watchdog();
        stale();
        backendErrors();
        lateInputs();
        telegramCodes();
        staleGrants();
        System.out.println("Pro auto-link machine: " + checks + " checks passed");
    }

    private static ProAutoLinkMachine obtaining() {
        ProAutoLinkMachine machine = new ProAutoLinkMachine();
        check(machine.start() == Action.BEGIN, "an attempt starts with bind/start");
        check(machine.begun(true) == Action.OBTAIN, "an attest offer: ask Telegram for initData (watchdog armed)");
        return machine;
    }

    private static ProAutoLinkMachine polling(ProAutoLinkMachine machine) {
        check(machine.obtained("query_id=AAH&hash=ff", null) == Action.ATTEST, "initData goes to bind/attest");
        check(machine.attested() == Action.POLL, "a confirmed attest polls at once");
        return machine;
    }

    private static void happyPath() {
        ProAutoLinkMachine machine = polling(obtaining());
        check(machine.polled("confirmed") == Action.FINISH, "confirmed ends the attempt");
        check(machine.outcome() == Outcome.SUCCESS && machine.errorCode() == null && machine.finished(), "SUCCESS carries no error code");
        check(machine.polls() == 1, "one poll was enough");
        check(machine.start() == Action.IGNORE, "a finished attempt never starts again");
    }

    private static void polling() {
        ProAutoLinkMachine machine = polling(obtaining());
        check(machine.polled("pending") == Action.WAIT, "pending after the first poll: wait 1 s");
        check(machine.polled("pending") == Action.IGNORE, "no poll answer is taken while waiting");
        check(machine.waited() == Action.POLL, "the second poll runs after the wait");
        check(machine.polled("pending") == Action.WAIT, "pending again: one more wait");
        check(machine.waited() == Action.POLL, "the third poll");
        check(machine.polled("pending") == Action.FINISH && machine.outcome() == Outcome.POLL_TIMEOUT, "after 3 pending polls: POLL_TIMEOUT");
        check(machine.polls() == ProAutoLinkMachine.MAX_POLLS && machine.errorCode().equals("timeout"), "at most 3 polls, code timeout");
        check(ProAutoLinkMachine.POLL_INTERVAL_MS == 1000 && ProAutoLinkMachine.WATCHDOG_MS == 30000, "1 s poll interval, 30 s watchdog");

        ProAutoLinkMachine late = polling(obtaining());
        late.polled("pending");
        late.waited();
        check(late.polled("confirmed") == Action.FINISH && late.outcome() == Outcome.SUCCESS, "a later confirmed poll succeeds");

        for (String state : new String[]{"denied", "expired", "consumed", "", null}) {
            ProAutoLinkMachine ended = polling(obtaining());
            check(ended.polled(state) == Action.FINISH && ended.outcome() == Outcome.FAILED && "unknown".equals(ended.errorCode()),
                    "poll state " + state + " fails on the ladder with code unknown");
        }
    }

    private static void noOffer() {
        ProAutoLinkMachine machine = new ProAutoLinkMachine();
        machine.start();
        check(machine.begun(false) == Action.FINISH, "attest == null ends at once: the code UI is never shown");
        check(machine.outcome() == Outcome.ATTEST_UNAVAILABLE && "attest_unavailable".equals(machine.errorCode()),
                "ATTEST_UNAVAILABLE with code attest_unavailable");
        check(machine.obtained("x", null) == Action.IGNORE && machine.attested() == Action.IGNORE, "nothing runs after it");
    }

    private static void telegram() {
        ProAutoLinkMachine prerequisite = obtaining();
        check(prerequisite.obtained(null, "attest_unavailable") == Action.FINISH && prerequisite.outcome() == Outcome.ATTEST_FAILED
                && "attest_unavailable".equals(prerequisite.errorCode()), "a failed prerequisite before the RPC: ATTEST_FAILED attest_unavailable");
        ProAutoLinkMachine rpc = obtaining();
        rpc.obtained(null, ProAutoLinkMachine.telegramErrorCode("FLOOD_WAIT_17"));
        check(rpc.outcome() == Outcome.ATTEST_FAILED && "telegram_flood_wait".equals(rpc.errorCode()), "an RPC error keeps its telegram_ code");
        ProAutoLinkMachine empty = obtaining();
        empty.obtained(null, null);
        check(empty.outcome() == Outcome.ATTEST_FAILED && "unknown".equals(empty.errorCode()), "Telegram answered without initData: unknown");
        ProAutoLinkMachine blank = obtaining();
        blank.obtained("", null);
        check(blank.outcome() == Outcome.ATTEST_FAILED, "empty initData is no initData");
        ProAutoLinkMachine junk = obtaining();
        junk.obtained(null, "Some Text, with spaces");
        check("unknown".equals(junk.errorCode()), "free text is never an error code");
    }

    private static void watchdog() {
        ProAutoLinkMachine machine = obtaining();
        check(machine.watchdog() == Action.FINISH && machine.outcome() == Outcome.ATTEST_FAILED && "timeout".equals(machine.errorCode()),
                "no Telegram answer within 30 s: ATTEST_FAILED timeout");
        check(machine.obtained("query_id=late", null) == Action.IGNORE, "a late Telegram answer is ignored (nothing is attested)");
        ProAutoLinkMachine answered = polling(obtaining());
        check(answered.watchdog() == Action.IGNORE, "the watchdog only guards the Telegram step");
        check(new ProAutoLinkMachine().watchdog() == Action.IGNORE, "a watchdog before the attempt does nothing");
    }

    private static void stale() {
        for (String code : new String[]{"link_busy", "link_changed", "session_changed", "not_configured", "login_required"}) {
            ProAutoLinkMachine begin = new ProAutoLinkMachine();
            begin.start();
            check(begin.failed(code, 0, 0) == Action.FINISH && begin.outcome() == Outcome.STALE && begin.errorCode() == null,
                    code + " on bind/start: STALE, no event code");
        }
        ProAutoLinkMachine manualTap = obtaining();
        manualTap.obtained("init", null);
        check(manualTap.failed("link_changed", 0, 0) == Action.FINISH && manualTap.outcome() == Outcome.STALE,
                "a manual Link tap supersedes the headless link: bind/attest reports link_changed → STALE");
        ProAutoLinkMachine slot = obtaining();
        check(slot.interrupted() == Action.FINISH && slot.outcome() == Outcome.STALE, "a slot user change while Telegram answers: STALE");
        ProAutoLinkMachine waiting = polling(obtaining());
        waiting.polled("pending");
        check(waiting.interrupted() == Action.FINISH && waiting.outcome() == Outcome.STALE, "interrupted while waiting to poll: STALE");
        check(waiting.waited() == Action.IGNORE, "the poll timer of an interrupted attempt does nothing");
        check(waiting.interrupted() == Action.IGNORE, "a second interruption is ignored");
        ProAutoLinkMachine polled = polling(obtaining());
        check(polled.failed("session_changed", 0, 0) == Action.FINISH && polled.outcome() == Outcome.STALE,
                "the slot changed under the poll: STALE");
    }

    private static Outcome beginFails(String code, int status, int retryAfter) {
        ProAutoLinkMachine machine = new ProAutoLinkMachine();
        machine.start();
        check(machine.failed(code, status, retryAfter) == Action.FINISH, "a failed bind/start ends the attempt: " + code);
        lastError = machine.errorCode();
        lastRetryAfter = machine.retryAfterSeconds();
        return machine.outcome();
    }

    private static String lastError;
    private static int lastRetryAfter;

    private static void backendErrors() {
        check(beginFails("consent_stale", 409, 0) == Outcome.CONSENT_STALE && "consent_stale".equals(lastError),
                "409 consent_stale: CONSENT_STALE (no retry until a new grant)");
        check(beginFails("bind_conflict", 409, 0) == Outcome.BIND_CONFLICT && "bind_conflict".equals(lastError), "409 bind_conflict");
        check(beginFails("bind_paused", 503, 120) == Outcome.BIND_PAUSED && "bind_paused".equals(lastError) && lastRetryAfter == 120,
                "503 bind_paused keeps its Retry-After");
        check(beginFails("rate_limited", 429, 30) == Outcome.RATE_LIMITED && "rate_limited".equals(lastError) && lastRetryAfter == 30,
                "429 rate_limited keeps its Retry-After");
        check(beginFails("too_many", 429, 7) == Outcome.RATE_LIMITED && "rate_limited".equals(lastError), "any 429 is RATE_LIMITED");
        check(beginFails("connection_failed", 0, 0) == Outcome.OFFLINE && "offline".equals(lastError), "no connection: OFFLINE offline");
        check(beginFails("invalid_request", 422, 0) == Outcome.FAILED && "invalid_request".equals(lastError), "other backend codes are kept");
        check(beginFails("service_unavailable", 503, 0) == Outcome.FAILED && "service_unavailable".equals(lastError), "5xx: the ladder");
        check(beginFails("Bad Thing!", 500, 0) == Outcome.FAILED && "unknown".equals(lastError), "a code outside the pattern is unknown");
        check(beginFails(null, 500, 0) == Outcome.FAILED && "unknown".equals(lastError), "no code is unknown");
        check(beginFails("rate_limited", 429, -5) == Outcome.RATE_LIMITED && lastRetryAfter == 0, "a negative Retry-After is none");

        String[] attestCodes = {"attest_invalid", "attest_stale", "attest_mismatch", "bind_not_found", "bind_expired", "clock_skew",
                "unauthenticated", "device_key_changed"};
        for (String code : attestCodes) {
            ProAutoLinkMachine machine = obtaining();
            machine.obtained("init", null);
            check(machine.failed(code, 422, 0) == Action.FINISH && machine.outcome() == Outcome.FAILED && code.equals(machine.errorCode()),
                    "bind/attest " + code + " is kept in the event and goes on the ladder");
        }
        ProAutoLinkMachine inert = obtaining();
        inert.obtained("init", null);
        inert.failed("attest_unavailable", 503, 0);
        check(inert.outcome() == Outcome.ATTEST_UNAVAILABLE && "attest_unavailable".equals(inert.errorCode()),
                "bind/attest 503 attest_unavailable: the attest-unavailable streak");
        ProAutoLinkMachine conflict = obtaining();
        conflict.obtained("init", null);
        conflict.failed("bind_conflict", 409, 0);
        check(conflict.outcome() == Outcome.BIND_CONFLICT, "bind/attest 409 bind_conflict pauses");
        ProAutoLinkMachine mismatch = polling(obtaining());
        mismatch.failed("account_mismatch", 0, 0);
        check(mismatch.outcome() == Outcome.FAILED && "account_mismatch".equals(mismatch.errorCode()),
                "the poll's Telegram-id check failing: account_mismatch on the ladder");
        ProAutoLinkMachine offline = polling(obtaining());
        offline.failed("connection_failed", 0, 0);
        check(offline.outcome() == Outcome.OFFLINE, "a poll without connection: OFFLINE");
    }

    private static void lateInputs() {
        ProAutoLinkMachine machine = new ProAutoLinkMachine();
        check(machine.begun(true) == Action.IGNORE && machine.attested() == Action.IGNORE && machine.polled("confirmed") == Action.IGNORE
                && machine.waited() == Action.IGNORE && machine.failed("rate_limited", 429, 1) == Action.IGNORE,
                "nothing happens before start()");
        check(machine.outcome() == null && !machine.finished(), "no outcome before the end");
        ProAutoLinkMachine obtaining = obtaining();
        check(obtaining.start() == Action.IGNORE, "one attempt runs once: a running attempt never starts a second bind/start");
        check(obtaining.failed("rate_limited", 429, 1) == Action.IGNORE, "no ZenlyPro failure belongs to the Telegram step");
        check(obtaining.attested() == Action.IGNORE && obtaining.polled("confirmed") == Action.IGNORE, "steps never skip ahead");
        ProAutoLinkMachine done = polling(obtaining());
        done.polled("confirmed");
        check(done.failed("connection_failed", 0, 0) == Action.IGNORE && done.outcome() == Outcome.SUCCESS,
                "a finished attempt keeps its outcome");
    }

    private static void telegramCodes() {
        check("telegram_flood_wait".equals(ProAutoLinkMachine.telegramErrorCode("FLOOD_WAIT_17")), "FLOOD_WAIT_17 → telegram_flood_wait");
        check("telegram_phone_migrate".equals(ProAutoLinkMachine.telegramErrorCode("PHONE_MIGRATE_2")),
                "PHONE_MIGRATE_2 → telegram_phone_migrate (the data center is not revealed)");
        check("telegram_bot_invalid".equals(ProAutoLinkMachine.telegramErrorCode("BOT_INVALID")), "BOT_INVALID → telegram_bot_invalid");
        check("telegram_flood_premium_wait".equals(ProAutoLinkMachine.telegramErrorCode("FLOOD_PREMIUM_WAIT_300")),
                "only the trailing number goes");
        check("telegram_2fa_confirm_wait".equals(ProAutoLinkMachine.telegramErrorCode("2FA_CONFIRM_WAIT_604800")),
                "an RPC name may start with a digit");
        check("telegram_file_part_0_missing".equals(ProAutoLinkMachine.telegramErrorCode("FILE_PART_0_MISSING")),
                "digits inside the name stay");
        check("telegram_peer_id_invalid".equals(ProAutoLinkMachine.telegramErrorCode("PEER_ID_INVALID")), "a plain name");
        check("telegram_500".equals(ProAutoLinkMachine.telegramErrorCode("500")), "a bare number keeps its digits");
        String[] junk = {null, "", "flood_wait_17", "Flood Wait", "FLOOD-WAIT", "_FLOOD", "FLOOD WAIT", "INTERNAL\n", "ÉRROR"};
        for (String text : junk) {
            check("unknown".equals(ProAutoLinkMachine.telegramErrorCode(text)), "junk → unknown: " + text);
        }
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 56; i++) longName.append('A');
        check("unknown".equals(ProAutoLinkMachine.telegramErrorCode(longName.toString())), "a name beyond 64 code characters is unknown");
        check(("telegram_" + longName.substring(0, 55).toLowerCase()).equals(ProAutoLinkMachine.telegramErrorCode(longName.substring(0, 55))),
                "55 name characters still fit the 64-character code");
    }

    /**
     * A stale grant (iOS parity, design 40 §4.6 rows 5–6) of a signed-in account that is not the selected one: a v1
     * grant under a bundled min_valid_version of 2, and a grant whose text this build renders with another hash once
     * the launch pass (ConsentMachine.operatorChanged) stored it as needs_reconsent. ZenlyCustomer hands ProAutoLink a
     * grant time only while the grant may link (effectiveGrantAt: ConsentSnapshot.recordingAllowed) and ZenlyPro the
     * grant only then (linkGrantJson: grantJsonIfGranted): for these that is 0, so the schedule stops at NO_GRANT (no
     * timer; only a new grant lifts it), no attempt starts, and no bind/start body carries the grant, automatic or
     * manual, nor does a manual link attest silently. The selected account's current grant still links with it.
     */
    private static void staleGrants() {
        ConsentText.Shown v1 = new ConsentText.Shown(1, ConsentManifest.SCOPES, "uz", "v1", SHA);
        ConsentText.Shown v2 = new ConsentText.Shown(2, ConsentManifest.SCOPES, "uz", "v2", SHA2);
        ConsentSnapshot selected = grant(ConsentSnapshot.empty(TG, 2), v2, T0 + 100, 1);
        ConsentSnapshot retired = grant(ConsentSnapshot.empty(OTHER_TG, 1), v1, T0, 2).withMinValidVersion(2);
        ConsentSnapshot unchecked = grant(ConsentSnapshot.empty(OTHER_TG, 1), v1, T0, 3);
        ConsentSnapshot changed = ConsentMachine.operatorChanged(unchecked, SHA2, T0 + 50).snapshot;
        long now = T0 + 3600;

        check(blocker(selected, now) == ProAutoLinkSchedule.Blocker.NONE && new ProAutoLinkMachine().start() == Action.BEGIN
                && bindStart(selected).contains(",\"consent\":" + selected.latestGrantJson() + "}"),
                "the selected account's current grant: an attempt starts and bind/start carries the grant");
        check(blocker(unchecked, now) == ProAutoLinkSchedule.Blocker.NONE && bindStart(unchecked).contains("\"consent\":"),
                "before the launch pass a grant of a changed text would link with the grant attached (the gap)");
        ConsentSnapshot[] stale = {retired, changed};
        String[] names = {"a retired text version", "a changed text, normalised at launch"};
        for (int i = 0; i < stale.length; i++) {
            check(stale[i].tgUserId == OTHER_TG && grantAt(stale[i]) == 0, names[i] + ": the gate holds no grant time");
            check(blocker(stale[i], now) == ProAutoLinkSchedule.Blocker.NO_GRANT && waitMs(stale[i], now) == -1,
                    names[i] + ": NO_GRANT, no timer, so no attempt starts until a new grant");
            check(!bindStart(stale[i]).contains("\"consent\""), names[i] + ": no bind/start body carries it, automatic or manual");
            check(!ProLinkRequests.useAttest(true, stale[i].recordingAllowed()), names[i] + ": a manual link does not attest silently");
        }
    }

    private static ConsentSnapshot grant(ConsentSnapshot empty, ConsentText.Shown text, long at, int n) {
        String consentId = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", 7000 + n);
        return ConsentMachine.grant(empty, text, ConsentMachine.Mode.REQUIRED, consentId, at, "1.0.0", AUTH).snapshot;
    }

    /** ZenlyCustomer.effectiveGrantAt for an account without a pending deletion. */
    private static long grantAt(ConsentSnapshot s) {
        return s.recordingAllowed() && s.latestGrant() != null ? Math.max(1, s.latestGrant().decidedAt) : 0;
    }

    /** An initial schedule, foreground long after the first idle, unlinked and configured. */
    private static ProAutoLinkSchedule.Facts schedule(ConsentSnapshot s) {
        return ProAutoLinkSchedule.Facts.of(grantAt(s), ProAutoLinkSchedule.FIRST_IDLE_MS + ProAutoLinkSchedule.MAX_JITTER_MS, 0);
    }

    private static ProAutoLinkSchedule.Blocker blocker(ConsentSnapshot s, long now) {
        return ProAutoLinkSchedule.blocker(ProAutoLinkSchedule.State.INITIAL, schedule(s), now);
    }

    private static long waitMs(ConsentSnapshot s, long now) {
        return ProAutoLinkSchedule.waitMs(ProAutoLinkSchedule.State.INITIAL, schedule(s), now);
    }

    /** The bind/start body with what ZenlyCustomer.linkGrantJson hands ZenlyPro (no deletion pending). */
    private static String bindStart(ConsentSnapshot s) {
        return new String(ProLinkRequests.bindStartBody("device-1", "pubkey-1", "14", "1.1.0", "Test Phone", s.grantJsonIfGranted(AUTH)),
                StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

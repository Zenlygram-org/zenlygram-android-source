package org.telegram.zenlygram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.telegram.zenlygram.pro.ZgLinkRules.Message;
import org.telegram.zenlygram.pro.ZgLinkRules.Permission;
import org.telegram.zenlygram.pro.ZgLinkRules.PollStep;

/** JVM tests of the link screen's decisions (iOS SGLinkAccountFunnel / SGLinkAccountKeyRestartBudget.reaction tests). */
public class ZgLinkRulesTest {

    @Test
    public void permissionFollowsConsentAndDeletion() {
        // An effective grant links any way.
        assertEquals(Permission.ALLOWED, ZgLinkRules.permission(true, false, true, true));
        assertEquals(Permission.ALLOWED, ZgLinkRules.permission(true, false, true, false));
        // Without one: nothing is sent in a required-consent build, the code only in an optional one.
        assertEquals(Permission.REFUSED, ZgLinkRules.permission(true, false, false, true));
        assertEquals(Permission.CODE_ONLY, ZgLinkRules.permission(true, false, false, false));
        // A pending deletion refuses in both modes, whatever the grant says.
        assertEquals(Permission.REFUSED, ZgLinkRules.permission(true, true, true, true));
        assertEquals(Permission.REFUSED, ZgLinkRules.permission(true, true, false, false));
        // No authenticated backend in the build: nothing to link to.
        assertEquals(Permission.REFUSED, ZgLinkRules.permission(false, false, true, false));
    }

    @Test
    public void failureMessages() {
        // Throttling, by status or by the client's own cooldown code.
        assertEquals(Message.TOO_MANY_ATTEMPTS, ZgLinkRules.failure("rate_limited", 429));
        assertEquals(Message.TOO_MANY_ATTEMPTS, ZgLinkRules.failure("anything", 429));
        assertEquals(Message.TOO_MANY_ATTEMPTS, ZgLinkRules.failure("rate_limited", 0));
        // The stable code decides before the status: bind_paused is a 503 that is a switch, not an outage.
        assertEquals(Message.LINKING_UNAVAILABLE, ZgLinkRules.failure("bind_paused", 503));
        // No answer, or a 5xx without a code of its own.
        assertEquals(Message.NO_CONNECTION, ZgLinkRules.failure("connection_failed", 0));
        assertEquals(Message.NO_CONNECTION, ZgLinkRules.failure("service_unavailable", 503));
        assertEquals(Message.NO_CONNECTION, ZgLinkRules.failure(null, 500));
        // This code is finished; a new one works.
        assertEquals(Message.DID_NOT_GO_THROUGH, ZgLinkRules.failure("bind_not_found", 404));
        assertEquals(Message.DID_NOT_GO_THROUGH, ZgLinkRules.failure("link_changed", 0));
        assertEquals(Message.DID_NOT_GO_THROUGH, ZgLinkRules.failure("session_changed", 0));
        assertEquals(Message.DID_NOT_GO_THROUGH, ZgLinkRules.failure("device_key_changed", 0));
        assertEquals(Message.CHECK_DATE_AND_TIME, ZgLinkRules.failure("clock_skew", 401));
        assertEquals(Message.WRONG_ACCOUNT, ZgLinkRules.failure("account_mismatch", 0));
        assertEquals(Message.UPDATE_REQUIRED, ZgLinkRules.failure("unknown_signing_key", 0));
        assertEquals(Message.STORAGE, ZgLinkRules.failure("storage_unavailable", 0));
        // Everything else never promises anything.
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure("invalid_request", 400));
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure("invalid_pubkey", 422));
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure("invalid_response", 0));
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure("not_configured", 0));
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure("login_required", 0));
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure(null, 0));
        assertEquals(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.failure("something_new", 409));
    }

    @Test
    public void pollAnswers() {
        assertAgain(PollStep.Hint.CLEAR, ZgLinkRules.afterPoll("PENDING"));
        assertAgain(PollStep.Hint.CLEAR, ZgLinkRules.afterPoll("pending"));
        final PollStep confirmed = ZgLinkRules.afterPoll("CONFIRMED");
        assertEquals(PollStep.Next.LINKED, confirmed.next);
        assertNull(confirmed.message);
        assertFailed(Message.DECLINED, ZgLinkRules.afterPoll("DENIED"));
        assertFailed(Message.CODE_EXPIRED, ZgLinkRules.afterPoll("EXPIRED"));
        // Somebody else took this confirmation: the code is finished.
        assertFailed(Message.DID_NOT_GO_THROUGH, ZgLinkRules.afterPoll("CONSUMED"));
        assertFailed(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.afterPoll("something_new"));
        assertFailed(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.afterPoll(null));
    }

    @Test
    public void pollErrorsThatKeepTheCode() {
        // Throttled: the code stays and so does whatever hint is up.
        assertAgain(PollStep.Hint.KEEP, ZgLinkRules.afterPollError("rate_limited", 429));
        assertAgain(PollStep.Hint.KEEP, ZgLinkRules.afterPollError("rate_limited", 0));
        assertAgain(PollStep.Hint.KEEP, ZgLinkRules.afterPollError(null, 429));
        // Offline or an outage: a lift ride does not cost the user the code.
        assertAgain(PollStep.Hint.NO_CONNECTION, ZgLinkRules.afterPollError("connection_failed", 0));
        assertAgain(PollStep.Hint.NO_CONNECTION, ZgLinkRules.afterPollError("service_unavailable", 503));
        assertAgain(PollStep.Hint.NO_CONNECTION, ZgLinkRules.afterPollError(null, 502));
    }

    @Test
    public void pollErrorsThatEndTheCode() {
        assertFailed(Message.DID_NOT_GO_THROUGH, ZgLinkRules.afterPollError("bind_not_found", 404));
        assertFailed(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.afterPollError("invalid_request", 400));
        assertFailed(Message.CHECK_DATE_AND_TIME, ZgLinkRules.afterPollError("clock_skew", 401));
        // The operator's switch ends the code although it is a 503.
        assertFailed(Message.LINKING_UNAVAILABLE, ZgLinkRules.afterPollError("bind_paused", 503));
        // Results that can never verify (the client's own codes, no HTTP status).
        assertFailed(Message.WRONG_ACCOUNT, ZgLinkRules.afterPollError("account_mismatch", 0));
        assertFailed(Message.DID_NOT_GO_THROUGH, ZgLinkRules.afterPollError("link_changed", 0));
        assertFailed(Message.DID_NOT_GO_THROUGH, ZgLinkRules.afterPollError("session_changed", 0));
        assertFailed(Message.DID_NOT_GO_THROUGH, ZgLinkRules.afterPollError("device_key_changed", 0));
        assertFailed(Message.UPDATE_REQUIRED, ZgLinkRules.afterPollError("unknown_signing_key", 0));
        assertFailed(Message.STORAGE, ZgLinkRules.afterPollError("storage_unavailable", 0));
        assertFailed(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.afterPollError("invalid_response", 0));
        assertFailed(Message.TEMPORARILY_UNAVAILABLE, ZgLinkRules.afterPollError("not_configured", 0));
    }

    @Test
    public void codeLifetime() {
        final long now = 1_793_000_000L;
        assertEquals(300_000, ZgLinkRules.lifetimeMs(now + 300, now));
        assertEquals(120_000, ZgLinkRules.lifetimeMs(now + 120, now));
        assertEquals(900_000, ZgLinkRules.lifetimeMs(now + 900, now));
        // A clock that is off: a code issued a moment ago is neither expired nor valid for an hour.
        assertEquals(ZgLinkRules.DEFAULT_LIFETIME_MS, ZgLinkRules.lifetimeMs(now, now));
        assertEquals(ZgLinkRules.DEFAULT_LIFETIME_MS, ZgLinkRules.lifetimeMs(now - 600, now));
        assertEquals(ZgLinkRules.DEFAULT_LIFETIME_MS, ZgLinkRules.lifetimeMs(now + 3600, now));
        assertEquals(ZgLinkRules.DEFAULT_LIFETIME_MS, ZgLinkRules.lifetimeMs(0, now));
    }

    @Test
    public void countdownText() {
        assertEquals("5:00", ZgLinkRules.remaining(300));
        assertEquals("4:59", ZgLinkRules.remaining(299));
        assertEquals("1:05", ZgLinkRules.remaining(65));
        assertEquals("0:09", ZgLinkRules.remaining(9));
        assertEquals("0:00", ZgLinkRules.remaining(0));
        assertEquals("0:00", ZgLinkRules.remaining(-3));
        assertEquals("15:00", ZgLinkRules.remaining(900));
    }

    @Test
    public void spelledCode() {
        assertEquals("A B 1 2 C D 3 4", ZgLinkRules.spelled("AB12CD34"));
        assertEquals("X", ZgLinkRules.spelled("X"));
        assertEquals("", ZgLinkRules.spelled(""));
        assertEquals("", ZgLinkRules.spelled(null));
    }

    @Test
    public void onlyABotStartLinkOpens() {
        final String link = "https://t.me/ZenlygramBot?start=bAB12CD34";
        assertEquals(link, ZgLinkRules.botDeepLink(link));
        assertEquals("https://T.ME/Some_Bot1?start=b-_x", ZgLinkRules.botDeepLink("https://T.ME/Some_Bot1?start=b-_x"));

        assertNull(ZgLinkRules.botDeepLink(null));
        assertNull(ZgLinkRules.botDeepLink(""));
        assertNull(ZgLinkRules.botDeepLink("not a link"));
        // Another scheme, host, port, user or fragment.
        assertNull(ZgLinkRules.botDeepLink("http://t.me/ZenlygramBot?start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("tg://resolve?domain=ZenlygramBot&start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me.evil.example/ZenlygramBot?start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://evil.example/ZenlygramBot?start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://telegram.me/ZenlygramBot?start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me:8443/ZenlygramBot?start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://user@t.me/ZenlygramBot?start=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot?start=bAB12CD34#x"));
        // Any other t.me action: no start parameter, a Mini App, a group start, an invoice, an invite, a second path
        // segment, a second parameter, characters a start parameter cannot have.
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot?startapp=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot?startgroup=bAB12CD34"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/$invoice?start=b"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/+AbCdEf?start=b"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/joinchat/AbCdEf?start=b"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot/app?start=b"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot?start=b&attach=wallet"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot?start=b%20c"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/ZenlygramBot?start="));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/abc?start=b"));
        assertNull(ZgLinkRules.botDeepLink("https://t.me/1bot?start=b"));
    }

    private static void assertAgain(PollStep.Hint hint, PollStep step) {
        assertEquals(PollStep.Next.POLL_AGAIN, step.next);
        assertEquals(hint, step.hint);
        assertNull(step.message);
    }

    private static void assertFailed(Message message, PollStep step) {
        assertEquals(PollStep.Next.FAILED, step.next);
        assertEquals(message, step.message);
        assertNull(step.hint);
    }
}

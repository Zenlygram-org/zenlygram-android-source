/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Decision;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Inputs;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Notice;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.Presentation;
import org.telegram.zenlygram.customer.ZgConsentWallPolicy.StoredNotice;

/** Mirrors iOS SGConsentWallPolicyTests (rows 1–10) and SGConsentCanonicalTextTests' host list. */
public class ZgConsentWallPolicyTest {

    private static Inputs inputs(boolean required, String state) {
        final Inputs in = new Inputs();
        in.required = required;
        in.state = state;
        in.bundledCurrentVersion = 1;
        in.bundledMinValidVersion = 1;
        in.recordTextVersion = 1;
        in.recordTextSha256 = "aa";
        in.renderedSha256ForRecord = "aa";
        return in;
    }

    private static void expect(Decision decision, Presentation presentation, Notice notice) {
        assertEquals(decision.toString(), presentation, decision.presentation);
        assertEquals(decision.toString(), notice, decision.notice);
    }

    @Test
    public void unreadableDefers() {
        final Inputs in = inputs(true, "none");
        in.readable = false;
        expect(ZgConsentWallPolicy.decide(in), Presentation.DEFERRED, Notice.NONE);
        in.required = false;
        expect(ZgConsentWallPolicy.decide(in), Presentation.DEFERRED, Notice.NONE);
    }

    @Test
    public void firstAskBlocksInRequiredModeAndPromptsInOptional() {
        expect(ZgConsentWallPolicy.decide(inputs(true, "none")), Presentation.WALL, Notice.NONE);
        assertTrue(ZgConsentWallPolicy.decide(inputs(true, "none")).blocks());
        expect(ZgConsentWallPolicy.decide(inputs(false, "none")), Presentation.PROMPT, Notice.NONE);
        assertFalse(ZgConsentWallPolicy.decide(inputs(false, "none")).blocks());
        // a legacy decline asks again in a required build, never in an optional one
        expect(ZgConsentWallPolicy.decide(inputs(true, "declined")), Presentation.WALL, Notice.NONE);
        expect(ZgConsentWallPolicy.decide(inputs(false, "declined")), Presentation.NONE, Notice.NONE);
    }

    @Test
    public void grantedPassesUnlessTheTextChanged() {
        expect(ZgConsentWallPolicy.decide(inputs(true, "granted")), Presentation.NONE, Notice.NONE);
        final Inputs old = inputs(true, "granted");
        old.bundledMinValidVersion = 2;
        old.bundledCurrentVersion = 2;
        final Decision reconsent = ZgConsentWallPolicy.decide(old);
        expect(reconsent, Presentation.WALL, Notice.RECONSENT);
        assertEquals(StoredNotice.RECONSENT, reconsent.normalise);
        final Inputs operator = inputs(false, "granted");
        operator.renderedSha256ForRecord = "bb";
        final Decision changed = ZgConsentWallPolicy.decide(operator);
        expect(changed, Presentation.PROMPT, Notice.OPERATOR_CHANGED);
        assertEquals(StoredNotice.OPERATOR_CHANGED, changed.normalise);
        // the version is not bundled any more: no hash to compare, the grant stands
        final Inputs unbundled = inputs(true, "granted");
        unbundled.renderedSha256ForRecord = null;
        expect(ZgConsentWallPolicy.decide(unbundled), Presentation.NONE, Notice.NONE);
    }

    @Test
    public void retiredAndLinkEnded() {
        final Inputs retiredCurrent = inputs(true, "granted");
        retiredCurrent.latestGrantRejection = ZgConsentWallPolicy.VERSION_RETIRED_CODE;
        expect(ZgConsentWallPolicy.decide(retiredCurrent), Presentation.TEXT_UNAVAILABLE, Notice.NONE);
        retiredCurrent.required = false;
        expect(ZgConsentWallPolicy.decide(retiredCurrent), Presentation.NONE, Notice.NONE);
        final Inputs retiredOld = inputs(true, "granted");
        retiredOld.bundledCurrentVersion = 2;
        retiredOld.latestGrantRejection = ZgConsentWallPolicy.VERSION_RETIRED_CODE;
        expect(ZgConsentWallPolicy.decide(retiredOld), Presentation.WALL, Notice.RECONSENT);
        final Inputs ended = inputs(true, "granted");
        ended.linkEnded = true;
        final Decision decision = ZgConsentWallPolicy.decide(ended);
        expect(decision, Presentation.WALL, Notice.LINK_ENDED);
        assertEquals(StoredNotice.LINK_ENDED, decision.normalise);
    }

    @Test
    public void withdrawnDeletedAndStoredNotices() {
        expect(ZgConsentWallPolicy.decide(inputs(true, "withdrawn")), Presentation.WALL, Notice.WITHDRAWN);
        expect(ZgConsentWallPolicy.decide(inputs(true, "withdrawn_remote")), Presentation.WALL, Notice.WITHDRAWN_REMOTE);
        expect(ZgConsentWallPolicy.decide(inputs(false, "withdrawn")), Presentation.NONE, Notice.NONE);
        final Inputs deleted = inputs(true, "granted");
        deleted.deletedNotice = true;
        expect(ZgConsentWallPolicy.decide(deleted), Presentation.WALL, Notice.DELETED);
        deleted.required = false;
        expect(ZgConsentWallPolicy.decide(deleted), Presentation.NONE, Notice.NONE);
        final Inputs needs = inputs(true, "needs_reconsent");
        expect(ZgConsentWallPolicy.decide(needs), Presentation.WALL, Notice.RECONSENT);
        needs.storedNotice = StoredNotice.OPERATOR_CHANGED;
        expect(ZgConsentWallPolicy.decide(needs), Presentation.WALL, Notice.OPERATOR_CHANGED);
        needs.storedNotice = StoredNotice.LINK_ENDED;
        needs.required = false;
        final Decision prompt = ZgConsentWallPolicy.decide(needs);
        expect(prompt, Presentation.PROMPT, Notice.LINK_ENDED);
        assertNull(prompt.normalise);
    }

    @Test
    public void missingOperatorValuesAreUsableOnlyForALocalHost() {
        for (String host : new String[]{"127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "::1", "[::1]", "10.0.0.2",
            "172.16.0.1", "172.31.255.255", "192.168.1.2", "zenly.local", "Mac-Studio.local."}) {
            assertTrue(host, ZgConsentWallPolicy.acceptsMissingValues(host));
        }
        for (String host : new String[]{"zenlygram.org", "www.zenlygram.org", "zenlygram.com", "172.15.0.1", "172.32.0.1",
            "192.169.0.1", "11.0.0.1", "127.0.0", "127.0.0.256", "1270.0.0.1", ".local", "local", "example.localhost.org",
            "::2", "", "8.8.8.8"}) {
            assertFalse(host, ZgConsentWallPolicy.acceptsMissingValues(host));
        }
        assertFalse(ZgConsentWallPolicy.acceptsMissingValues(null));
        assertTrue(ZgConsentWallPolicy.operatorUsable(true, false, "zenlygram.org"));
        assertFalse(ZgConsentWallPolicy.operatorUsable(false, false, "zenlygram.org"));
        assertTrue(ZgConsentWallPolicy.operatorUsable(false, false, "192.168.1.19"));
        assertFalse(ZgConsentWallPolicy.operatorUsable(true, true, "localhost"));
    }
}

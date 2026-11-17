package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Run with run-tests.sh. No Android runtime, network, test server, or third-party library needed. */
public final class ProCoreTests {
    private static int checks;
    /** The local grace of a policy without local_grace_days (ver 1, ver 2 or none): seven days, as before. */
    private static final int WEEK = ProPolicy.DEFAULT_LOCAL_GRACE_DAYS;
    private static final long LOCAL_GRACE = WEEK * ProPolicy.DAY;
    // Public fixture already checked into iOS SGEntitlementServerInteropTests. Its test account
    // and binding were deleted after capture; this is not a live session or refresh credential.
    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaEXLVGbgr1nvSprYzys1YeaC3Zv0RceCityCCsZqJ0pkHwcPsgHZj1XoSYq7Bkr6/uvJKf1D8OaRjMtCdGWwdA==";
    private static final String TOKEN = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2YSJ9.eyJpc3MiOiJ6ZW5seWdyYW0iLCJzdWIiOiIwMW0zODBhM2R4M2JxMWZleDU4Nnc5MzBzZCIsInRnaWQiOjEyMzQ1Njc4OSwiZGV2IjoiMDFtMzgwYTNmYWhlMmt0cGQ3czNlcnFiOHkiLCJ0aWVyIjoicHJvIiwiZW50IjpbInZhdWx0IiwiY2hhdGxvY2siLCJ0YXNrcyIsImVkaXRoaXN0b3J5Il0sInVudGlsIjoiMjAzNi0wOS0yMFQyMDo0NjoyN1oiLCJncmFjZV9kYXlzIjoxNCwiaWF0IjoxNzkwMTk2Mzg3LCJleHAiOjE3OTA0NTU1ODcsImp0aSI6ImVkMDczNTIxLTVkZmYtNDI4Ni1iMDMxLTY1ZTIwMzc1YTJlOSIsInZlciI6MX0.kylVNlQnBeYIRSO6T0udUKfk99-Ddxib8pBTktTfnCd9kl6QrKhjvi5K6swlwcYQVuW27FigpofPg3ixmHYH1w";

    public static void main(String[] args) throws Exception {
        cryptography();
        accountIsolation();
        policyBoundaries();
        localGraceLengths();
        clock();
        linkLifecycle();
        System.out.println("Pro core: " + checks + " checks passed");
    }

    private static void cryptography() throws Exception {
        PublicKey key = ProCrypto.publicKey(Base64.getDecoder().decode(PUBLIC_KEY));
        String[] parts = TOKEN.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        check(ProCrypto.verify(key, parts[0], parts[1], signature), "real backend ES256 token verifies");
        check(!ProCrypto.verify(key, parts[0], parts[1] + "A", signature), "changed payload rejected");
        String changedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        check(!ProCrypto.verify(key, changedHeader, parts[1], signature), "changed algorithm header invalidates signature");
        signature[0] ^= 1;
        check(!ProCrypto.verify(key, parts[0], parts[1], signature), "changed signature rejected");
        check(!ProCrypto.verify(key, parts[0], parts[1], new byte[63]), "truncated JOSE signature rejected");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        check(!ProCrypto.verify(generator.generateKeyPair().getPublic(), parts[0], parts[1],
                Base64.getUrlDecoder().decode(parts[2])), "unrelated public key rejected");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        boolean wrongCurveRejected = false;
        try { ProCrypto.publicKey(generator.generateKeyPair().getPublic().getEncoded()); }
        catch (Exception expected) { wrongCurveRejected = true; }
        check(wrongCurveRejected, "non-P256 trust key rejected");
        check(Arrays.equals("device\nnonce\n123456".getBytes(StandardCharsets.UTF_8),
                ProCrypto.deviceMessage("device", "nonce", "123456")), "device signing bytes match backend exactly");
        byte[] highIntegers = new byte[64];
        Arrays.fill(highIntegers, (byte) 0x80);
        byte[] der = ProCrypto.joseToDer(highIntegers);
        check(der.length == 72 && der[4] == 0 && der[39] == 0, "unsigned JOSE integers receive DER sign padding");
        byte[] leadingZeros = new byte[64];
        leadingZeros[31] = 1;
        leadingZeros[63] = 2;
        check(Arrays.equals(ProCrypto.joseToDer(leadingZeros), new byte[]{0x30, 6, 2, 1, 1, 2, 1, 2}), "DER removes noncanonical leading zeroes");
    }

    /** A genuine account grant (account_ent/account_until) that mirrors the legacy ent/until it is minted with. */
    private static ProPolicy.Claims claims(long until, long expiry, long grace, String... capabilities) {
        Set<String> values = new HashSet<>(Arrays.asList(capabilities));
        return new ProPolicy.Claims("backend-account", "binding", 12345L, 1000, expiry, until, grace, values,
                ProPolicy.AccountGrant.of(values, until));
    }

    private static ProPolicy.Decision paid(ProPolicy.Claims claims, long now, long firstLaunch, boolean configured) {
        return ProPolicy.evaluate(claims, now, firstLaunch, configured, ProPolicy.Mode.PAID, WEEK);
    }

    private static void accountIsolation() {
        ProPolicy.Claims claims = claims(10000, 5000, 14, "vault");
        check(claims.belongsTo(12345, "backend-account", "binding"), "own account and binding accepted");
        check(!claims.belongsTo(67890, "backend-account", "binding"), "another Telegram account rejected");
        check(!claims.belongsTo(12345, "other-account", "binding"), "another backend account rejected");
        check(!claims.belongsTo(12345, "backend-account", "other-device"), "another binding rejected");
        check(!claims.belongsTo(0, "backend-account", "binding"), "logged-out account rejected");
    }

    /** Owner-enabled paid mode; free mode is covered by ProFreeFirstTests. */
    private static void policyBoundaries() {
        long first = 1000000;
        long end = first + LOCAL_GRACE;
        check(paid(null, first, first, true).grants("vault"), "local grace begins at first launch");
        check(paid(null, end - 1, first, true).grants("tasks"), "local grace includes final second");
        check(!paid(null, end, first, true).grants("tasks"), "local grace closes at exact boundary");
        check(!paid(null, end, first, false).grants("vault"), "missing trust configuration is never paid access");
        check(paid(null, end, first, false).state == ProPolicy.State.UNCONFIGURED, "missing configuration has explicit state");
        check(paid(null, first + 10, first, false).grants("chatlock"), "unconfigured builds can use time-limited local grace");
        check(!paid(null, 100, 0, true).grants("vault"), "missing secure first-launch record grants nothing");
        check(!paid(null, first - 1, first, true).grants("vault"), "invalid pre-install date grants nothing");
        check(!paid(null, first, first, true).grants("ai"), "local grace cannot remotely enable AI");
        ProPolicy.Claims limited = claims(end + 10000, end + 1000, 14, "vault");
        check(paid(limited, end, first, true).grants("vault"), "verified capability granted");
        check(!paid(limited, end, first, true).grants("tasks"), "missing capability stays unavailable");
        ProPolicy.Claims legacyWildcard = new ProPolicy.Claims("backend-account", "binding", 12345L, 1000, end + 1000, end + 10000, 14,
                new HashSet<>(Arrays.asList("*")), ProPolicy.AccountGrant.ABSENT);
        check(!paid(legacyWildcard, end, first, true).grants("tasks"), "legacy signed wildcard is never paid access");
        check(paid(claims(end, end + 1000, 14, "vault"), end, first, true).state == ProPolicy.State.EXPIRED, "subscription boundary is final");
        long expiry = end + 500;
        ProPolicy.Claims offline = claims(end + 30 * ProPolicy.DAY, expiry, 14, "edithistory");
        check(paid(offline, expiry + 14 * ProPolicy.DAY - 1, first, true).grants("edithistory"), "token offline grace preserves paid access");
        check(paid(offline, expiry + 14 * ProPolicy.DAY, first, true).state == ProPolicy.State.RECONNECT, "offline limit asks reconnect rather than claiming subscription ended");
        check(claims(Long.MAX_VALUE, 100, Long.MAX_VALUE, "vault").hardStop() == Long.MAX_VALUE, "grace multiplication cannot overflow into expiry");
        check(claims(5000, 1000, -10, "vault").hardStop() == 1000, "negative grace is never subtracted");
    }

    /**
     * The local grace at the length the accepted signed policy sets (local_grace_days 0..30; 7 without one): the end
     * is start + days x 86 400 s, exclusive, and 0 days never grants.
     */
    private static void localGraceLengths() {
        check(ProPolicy.DEFAULT_LOCAL_GRACE_DAYS == 7 && ProPolicy.MAX_LOCAL_GRACE_DAYS == 30, "default 7 days, contract ceiling 30");
        long first = 1000000;
        for (int days : new int[] {1, 7, 30}) {
            long end = first + days * ProPolicy.DAY;
            String name = days + "-day grace: ";
            check(ProPolicy.localGraceEnd(first, days) == end, name + "ends at start + days x 86400 s");
            ProPolicy.Decision open = ProPolicy.evaluate(null, first, first, true, ProPolicy.Mode.PAID, days);
            check(open.state == ProPolicy.State.LOCAL_TRIAL && open.until == end && open.displayedUntil() == end, name + "begins at its start and names its end");
            check(open.grants("vault") && open.grants("chatlock") && open.grants("tasks") && open.grants("edithistory") && !open.grants("ai"),
                    name + "grants the shipped local capabilities, never AI");
            check(!ProPolicy.serverGrants(open, "tasks"), name + "is still no grant the server knows");
            check(ProPolicy.evaluate(null, end - 1, first, true, ProPolicy.Mode.PAID, days).grants("tasks"), name + "includes its final second");
            ProPolicy.Decision closed = ProPolicy.evaluate(null, end, first, true, ProPolicy.Mode.PAID, days);
            check(closed.state == ProPolicy.State.LINK_REQUIRED && !closed.grants("tasks") && closed.until == 0 && closed.displayedUntil() == 0,
                    name + "closes at the exact boundary and names no date after it");
            check(!ProPolicy.evaluate(null, first - 1, first, true, ProPolicy.Mode.PAID, days).grants("vault"), name + "nothing before its start");
            check(!ProPolicy.evaluate(null, 100, 0, true, ProPolicy.Mode.PAID, days).grants("vault"), name + "no start record, no grace");
        }
        for (long now : new long[] {first - 1, first, first + 1, first + 3 * ProPolicy.DAY, first + 7 * ProPolicy.DAY, first + 30 * ProPolicy.DAY}) {
            ProPolicy.Decision none = ProPolicy.evaluate(null, now, first, true, ProPolicy.Mode.PAID, 0);
            check(none.state == ProPolicy.State.LINK_REQUIRED && none.until == 0 && none.displayedUntil() == 0,
                    "0 days: not active at once, no free-until date (now " + now + ")");
            check(!none.grants("vault") && !none.grants("chatlock") && !none.grants("tasks") && !none.grants("edithistory"),
                    "0 days: the local grace never grants (now " + now + ")");
        }
        check(ProPolicy.evaluate(null, first, first, false, ProPolicy.Mode.PAID, 0).state == ProPolicy.State.UNCONFIGURED,
                "0 days in an unconfigured build: its explicit state, no grace");
        ProPolicy.Claims ended = claims(first - 10, first + 1000, 14, "vault");
        check(ProPolicy.evaluate(ended, first, first, true, ProPolicy.Mode.PAID, 0).state == ProPolicy.State.EXPIRED,
                "0 days: an ended grant reads as ended at once, as after a grace that is over");
        check(ProPolicy.evaluate(ended, first, first, true, ProPolicy.Mode.PAID, 7).state == ProPolicy.State.LOCAL_TRIAL,
                "the same account inside a 7-day grace is still free");
        ProPolicy.Claims limited = claims(first + 10000, first + 1000, 14, "vault");
        ProPolicy.Decision granted = ProPolicy.evaluate(limited, first, first, true, ProPolicy.Mode.PAID, 0);
        check(granted.state == ProPolicy.State.ACTIVE && granted.grants("vault") && !granted.grants("tasks"), "0 days: a genuine grant still grants what it lists");
        ProPolicy.Decision free = ProPolicy.evaluate(null, first, first, true, ProPolicy.Mode.FREE, 0);
        check(free.state == ProPolicy.State.FREE && free.grants("vault") && free.until == 0, "free mode never reads the length: 0 days changes nothing there");
        check(ProPolicy.evaluate(null, first, first, true, ProPolicy.Mode.PAID, -3).state == ProPolicy.State.LINK_REQUIRED
                && ProPolicy.localGraceEnd(first, -3) == first && ProPolicy.localGraceEnd(first, 0) == first,
                "a length below 0 is no grace, never a window before the start");
        check(ProPolicy.localGraceEnd(Long.MAX_VALUE - 5, 30) == Long.MAX_VALUE, "the end saturates instead of overflowing");
    }

    private static void clock() {
        check(ProPolicy.effectiveNow(1000, 2000, 10000, 70000, true) == 2060, "clock rollback cannot extend a grant");
        check(ProPolicy.effectiveNow(2100, 2000, 10000, 70000, true) == 2100, "ordinary wall clock can advance");
        check(ProPolicy.effectiveNow(99999999, 2000, 10000, 70000, true) == 2060 + ProPolicy.CLOCK_TOLERANCE, "fast wall clock is bounded in same boot");
        check(ProPolicy.effectiveNow(1000, 2000, 10000, 5000, false) == 2000, "reboot retains persisted time floor");
        check(ProPolicy.effectiveNow(10000, 2000, 10000, 5000, false) == 10000, "time genuinely passed while powered off is counted");
        check(ProPolicy.effectiveNow(1234, 0, 0, 0, false) == 1234, "initial clock uses wall time");
    }

    private static void linkLifecycle() {
        ProLinkLifecycle flow = new ProLinkLifecycle(12345);
        flow.resume();
        long pendingBegin = flow.begin(12345);
        check(flow.ownsLink(), "pending begin without a code is owned by the UI");
        check(flow.destroy(), "destroy must cancel a pending begin request");
        check(!flow.accepts(pendingBegin, 12345), "begin callback after destroy is discarded");
        check(flow.begin(12345) < 0, "destroyed UI cannot initiate another link");

        flow = new ProLinkLifecycle(12345);
        flow.resume();
        long first = flow.begin(12345);
        long second = flow.begin(12345);
        check(!flow.accepts(first, 12345) && flow.accepts(second, 12345), "new begin supersedes old callbacks");
        check(!flow.accepts(second, 99999), "account-slot replacement rejects old link callbacks");
        check(flow.begin(99999) < 0, "link UI cannot silently switch Telegram identity");
        flow.pending(second, 1000);
        check(!flow.tryPoll(3999, 12345), "poll respects three-second interval");
        check(flow.tryPoll(4000, 12345), "poll starts after the interval");
        check(!flow.tryPoll(4000, 12345), "duplicate scheduled timer cannot overlap polling");
        flow.pause();
        flow.pending(second, 5000);
        check(flow.accepts(second, 12345), "pausing to confirm in bot preserves the link");
        check(!flow.tryPoll(9000, 12345), "paused UI never polls");
        flow.resume();
        check(flow.tryPoll(9000, 12345), "return from bot resumes pending link");
        flow.retry(second, 9000, 900);
        check(flow.nextPollDelay(9000) == 900000, "long server Retry-After is honored without clamping");
        flow.pause();
        flow.resume();
        check(!flow.tryPoll(10000, 12345), "resume cannot bypass rate-limit backoff");
        check(flow.tryPoll(909000, 12345), "poll resumes exactly after server backoff");
        flow.retry(second, 909000, 0);
        check(flow.nextPollDelay(909000) == 10000, "network failures back off exponentially");
        flow.pending(second, 920000);
        check(flow.nextPollDelay(920000) == 3000, "successful pending response resets backoff");
        flow.expedite(second - 1);
        check(flow.nextPollDelay(920000) == 3000, "a stale attestation cannot expedite polling");
        flow.expedite(second);
        check(flow.nextPollDelay(920000) == 0 && flow.tryPoll(920000, 12345), "server-confirmed attestation polls at once");
        flow.pending(second, 920000);
        check(flow.cancel(), "explicit cancellation releases owned flow");
        check(!flow.accepts(second, 12345), "poll callback after cancellation is discarded");
        check(!flow.destroy(), "destroying idle UI does not cancel another UI's link");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

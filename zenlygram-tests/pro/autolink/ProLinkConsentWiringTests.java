package org.telegram.messenger.zenlygram.pro;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Plain-JVM checks of the source wiring that reads a link's consent again once its bind/start runs on its lane (design 50
 * §8.1; iOS beginBind reads its permission again after the wait for a logout's revoke). beginLink reads the grant before
 * it queues the start on WORKER, where it can wait behind a logout's or an Unlink's final send and bind/revoke;
 * startLink's operation reads it again after its first LOCK block, outside LOCK and before any bind/start is built, and
 * sends only ProLinkRequests.consentAfterWait of the two reads (that rule itself is checked by ProLinkRequestsTests). The
 * grant read before the wait never travels by itself: the body, the consent_stale report and the consent-free retry all
 * use the second read. Run with run-tests.sh, which passes the Android repository root as the first argument.
 */
public final class ProLinkConsentWiringTests {
    private static int checks;
    private static final String PRO = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/ZenlyPro.java";
    private static final String START = "private void startLink(ExecutorService lane, Callback<LinkRequest> callback, Stamp stamp, long attempt, ";
    private static final String GUARD = "synchronized (LOCK)";
    private static final String READ_AGAIN = "final String consent = ProLinkRequests.consentAfterWait(queuedConsent, linkConsent(stamp.telegramId));";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ProLinkConsentWiringTests <android repository root>");
        File file = new File(args[0], PRO);
        check(file.isFile(), "SETUP: ZenlyPro.java found under " + args[0]);
        String source = squeeze(code(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        bothStartsReadBeforeTheyQueue(source);
        startReadsAgainAfterTheWait(source);
        System.out.println("Pro link consent after the lane's wait: " + checks + " checks passed");
    }

    /** The first read: on the calling thread, before the start is queued (manual on WORKER, automatic on LINK_WORKER). */
    private static void bothStartsReadBeforeTheyQueue(String source) {
        String manual = body(source, "public void beginLink(Callback<LinkRequest> callback)");
        check(manual.contains("startLink(WORKER, callback, stamp, attempt, linkConsent(stamp.telegramId), false);"),
                "beginLink reads the grant before it queues its start on WORKER");
        String automatic = body(source, "void beginLinkHeadless(Callback<LinkRequest> callback)");
        check(automatic.contains("startLink(LINK_WORKER, callback, stamp, attempt, linkConsent(stamp.telegramId), true);"),
                "beginLinkHeadless reads the grant before it queues its start on LINK_WORKER");
        check(count(source, "startLink(") == 3, "SETUP: startLink is declared once and called by the two starts only");
    }

    /** The second read: inside the queued operation, after its first LOCK block, outside LOCK, before any bind/start. */
    private static void startReadsAgainAfterTheWait(String source) {
        String start = body(source, START);
        int operation = start.indexOf("async(lane, callback, starting(attempt, () -> {");
        check(operation >= 0, "SETUP: startLink queues one operation on its lane");
        String queued = start.substring(operation);
        int firstLock = queued.indexOf(GUARD);
        String checked = block(queued, firstLock);
        check(firstLock >= 0 && checked.contains("requireCurrentLocked(stamp, false);") && checked.contains("requireLinkAttemptLocked(attempt);"),
                "SETUP: the operation first checks the slot and the attempt under LOCK");
        int afterLock = firstLock + GUARD.length() + 1 + checked.length();
        int read = queued.indexOf(READ_AGAIN);
        check(read >= 0, "the operation reads the grant again and keeps only consentAfterWait of the two reads");
        check(count(queued, "linkConsent(") == 1, "exactly one read inside the operation");
        check(read >= afterLock, "the second read comes after the wait and the attempt's first check (never at queue time)");
        int secondLock = queued.indexOf(GUARD, afterLock);
        check(secondLock < 0 || read < secondLock, "the second read runs outside LOCK, before the next locked step");
        int send = queued.indexOf("requestBytes(");
        check(send > read, "the second read comes before any bind/start is built or sent");
        check(source.contains(START + "String queuedConsent, boolean headless)") && count(start, "queuedConsent") == 1,
                "the grant read before the wait (queuedConsent) is only handed to consentAfterWait: it never travels by itself");
        check(queued.contains("ProLinkRequests.bindStartBody(device, key, Build.VERSION.RELEASE, appVersionName(), name, consent)"),
                "the first bind/start carries the grant of the second read");
        check(queued.contains("if (consent != null && response.status == 409") && queued.contains("ProAutoLink.consentStaleReported(account, "
                + "stamp.telegramId, configuration.authority, consent);"), "a consent_stale answer reports exactly the grant that was sent");
        check(queued.contains("if (consent != null && ProLinkRequests.retryWithoutConsent(response.status, refused, !headless))"),
                "the consent-free retry follows only a start that carried the second read's grant");
        check(queued.contains("ProLinkRequests.bindStartBody(device, key, Build.VERSION.RELEASE, appVersionName(), name, null)"),
                "... and that retry carries no consent");
    }

    /** Java source with every comment and the contents of every string and char literal blanked (same length). */
    private static String code(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i) || source.startsWith("/*", i)) {
                boolean line = source.startsWith("//", i);
                int end = line ? source.indexOf('\n', i) : source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : line ? end : end + 2;
                for (; i < end; i++) out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            } else if (c == '"' || c == '\'') {
                out.append(c);
                for (i++; i < source.length() && source.charAt(i) != c && source.charAt(i) != '\n'; i++) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                }
                if (i < source.length()) out.append(source.charAt(i++));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The braces-included body of the one declaration {@code header} in {@code code}. */
    private static String body(String code, String header) {
        int at = code.indexOf(header);
        check(at >= 0 && code.indexOf(header, at + 1) < 0, "SETUP: exactly one declaration: " + header);
        return block(code, at);
    }

    /** The braces-included block that opens at the first brace at or after {@code from}. */
    private static String block(String code, int from) {
        int open = from < 0 ? -1 : code.indexOf('{', from);
        int depth = 0;
        for (int i = open; open >= 0 && i < code.length(); i++) {
            if (code.charAt(i) == '{') {
                depth++;
            } else if (code.charAt(i) == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("SETUP: no balanced block after offset " + from);
    }

    private static String squeeze(String code) { return code.replaceAll("\\s+", " ").trim(); }

    private static int count(String source, String text) {
        int found = 0;
        for (int at = source.indexOf(text); at >= 0; at = source.indexOf(text, at + text.length())) found++;
        return found;
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

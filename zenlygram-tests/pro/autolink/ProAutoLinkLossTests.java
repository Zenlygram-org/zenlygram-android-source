package org.telegram.messenger.zenlygram.pro;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Plain-JVM checks of the source wiring that applies a server 401 before an automatic link can start (design 50
 * §8.2): ZenlyPro marks the binding loss in the LOCK section of clearRefreshLocked, after the session was saved
 * without its refresh token; the posted delivery lowers the mark in its own LOCK block before the binding listener
 * runs outside LOCK; bindingLossPending() reads it under LOCK; and ProAutoLink.pump reads the slot's identity before
 * the mark and hands both to ProAutoLinkSchedule. ProAutoLinkSchedule's LOSS_PENDING rule itself is checked by
 * ProAutoLinkScheduleTests. Run with run-tests.sh, which passes the Android repository root as the first argument.
 */
public final class ProAutoLinkLossTests {
    private static int checks;
    private static final String PRO = "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/";
    private static final String LOST = "private void bindingLostLocked(String code, String reason)";
    private static final String DELIVERY = "AndroidUtilities.runOnUIThread(";
    private static final String GUARD = "synchronized (LOCK)";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ProAutoLinkLossTests <android repository root>");
        File android = new File(args[0]);
        String zenlyPro = read(android, "ZenlyPro.java"), autoLink = read(android, "ProAutoLink.java");
        lossIsMarkedWithTheTokenDrop(zenlyPro);
        deliveryLowersTheMarkBeforeTheListener(zenlyPro);
        markIsReadUnderLock(zenlyPro);
        pumpReadsTheIdentityBeforeTheMark(autoLink);
        System.out.println("Pro auto-link binding-loss hold: " + checks + " checks passed");
    }

    /** bindingLostLocked counts the report itself, inside its caller's LOCK section, which saved the session first. */
    private static void lossIsMarkedWithTheTokenDrop(String source) {
        check(source.contains("private int lossesUndelivered;"), "ZenlyPro keeps the mark per slot (an instance field)");
        String lost = body(source, LOST, "ZenlyPro.java");
        String delivery = body(lost, DELIVERY, "bindingLostLocked");
        String report = lost.replace(delivery, "");
        check(report.contains("lossesUndelivered++") && !delivery.contains("lossesUndelivered++"),
                "bindingLostLocked raises the mark itself (under its caller's LOCK), not in the posted delivery");
        check(report.indexOf("return;") < report.indexOf("lossesUndelivered++"),
                "only a report that is posted raises the mark (after the no-listener return)");
        check(count(source, "lossesUndelivered++") == 1 && count(source, "lossesUndelivered--") == 1,
                "the loss report is the only place that raises the mark and its delivery the only one that lowers it");
        // Regression guard (true before this hold existed): the token drop is saved before the loss is reported.
        String clear = body(source, "private void clearRefreshLocked(String serverCode, String reason)", "ZenlyPro.java");
        int save = clear.indexOf("saveLocked(updated)"), reported = clear.indexOf("bindingLostLocked(");
        check(save >= 0 && reported > save, "clearRefreshLocked saves the session without its refresh token before it reports the loss");
    }

    /** The posted runnable lowers the mark in its own LOCK block, then calls the listener outside it. */
    private static void deliveryLowersTheMarkBeforeTheListener(String source) {
        String delivery = body(body(source, LOST, "ZenlyPro.java"), DELIVERY, "bindingLostLocked");
        check(delivery.contains(GUARD), "the delivery takes LOCK to lower the mark");
        String guarded = body(delivery, GUARD, "the delivery");
        check(guarded.contains("lossesUndelivered--"), "the delivery lowers the mark inside its synchronized (LOCK) block");
        int listener = delivery.indexOf("listener.onBindingLost(");
        check(listener >= 0, "SETUP: the delivery calls listener.onBindingLost");
        check(delivery.indexOf(guarded) + guarded.length() <= listener,
                "the mark is lowered before listener.onBindingLost runs, and the listener runs outside LOCK");
    }

    private static void markIsReadUnderLock(String source) {
        String pending = body(source, "public boolean bindingLossPending()", "ZenlyPro.java");
        check(pending.contains(GUARD) && body(pending, GUARD, "bindingLossPending").contains("lossesUndelivered"),
                "bindingLossPending() reads the mark under LOCK");
    }

    /** pump: one identity read, then the mark behind !linked, both into the schedule's Facts. */
    private static void pumpReadsTheIdentityBeforeTheMark(String source) {
        String pump = body(source, "private static void pump()", "ProAutoLink.java");
        int identity = pump.indexOf("identity().isLinked()"), mark = pump.indexOf("bindingLossPending()");
        check(identity >= 0 && mark >= 0, "pump reads the slot's identity and its binding-loss mark");
        check(identity < mark, "pump reads the identity before the mark: a slot a 401 unlinked already carries its mark");
        check(count(pump, "identity().isLinked()") == 1 && pump.contains(".linked(linked)"),
                "the schedule gets that one identity read (.linked(linked))");
        check(pump.contains("!linked && ZenlyPro.get(account).bindingLossPending()"), "the mark is read only for a slot read unlinked");
        check(pump.contains(".lossPending(lossPending)"), "pump hands the mark to the schedule (.lossPending(lossPending))");
    }

    private static String read(File android, String name) throws IOException {
        File file = new File(android, PRO + name);
        check(file.isFile(), "SETUP: " + name + " found under " + android);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The brace-balanced body following the one occurrence of signature in source (named where). */
    private static String body(String source, String signature, String where) {
        int start = source.indexOf(signature);
        check(start >= 0 && source.indexOf(signature, start + 1) < 0, "SETUP: " + where + " has exactly one " + signature);
        int open = source.indexOf('{', start), depth = 0;
        for (int i = open; i >= 0 && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("SETUP: no balanced body for " + signature + " in " + where);
    }

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

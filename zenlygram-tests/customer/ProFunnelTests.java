package org.telegram.messenger.zenlygram.customer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-JVM checks of the Pro funnel mappings (design 50 §11.4): the source of {@code pro.screen_view}, its mode, the
 * {@code pro.purchase_fail} reason of every {@code GooglePlayModel.Problem} (the constant list is read from the app
 * source, so a new Problem fails here until it is classified), the pending purchase, the restore and trial outcomes and
 * the server code passthrough. Run with run-tests.sh, which passes the Android repository root.
 */
public final class ProFunnelTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ProFunnelTests <android repository root>");
        Path root = Paths.get(args[0]);
        sources();
        reasons(root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/GooglePlayModel.java"));
        restoreAndTrial();
        System.out.println("Pro funnel: " + checks + " checks passed");
    }

    private static void sources() {
        check(ProFunnel.sourceOf(ProFunnel.Origin.SETTINGS) == CustomerEvents.ProSource.SETTINGS, "settings (Zenlygram hub or Telegram settings)");
        check(ProFunnel.sourceOf(ProFunnel.Origin.TASKS) == CustomerEvents.ProSource.TASKS, "tasks");
        check(ProFunnel.sourceOf(ProFunnel.Origin.FEATURE_GATE) == CustomerEvents.ProSource.FEATURE_GATE, "feature gate");
        check(ProFunnel.sourceOf(ProFunnel.Origin.OTHER) == CustomerEvents.ProSource.OTHER && ProFunnel.sourceOf(null) == CustomerEvents.ProSource.OTHER,
                "anything else");
        check(ProFunnel.mode(true) == CustomerEvents.ProMode.PAID && ProFunnel.mode(false) == CustomerEvents.ProMode.FREE, "paid when purchases are offered");
    }

    private static void reasons(Path model) throws Exception {
        Map<String, CustomerEvents.FailReason> expected = new LinkedHashMap<>();
        expected.put("USER_CANCELED", CustomerEvents.FailReason.CANCELLED);
        for (String name : new String[]{"BILLING_UNSUPPORTED", "NOT_CONFIGURED", "STORE_UNAVAILABLE", "NO_OFFERS", "UPDATE_REQUIRED"}) {
            expected.put(name, CustomerEvents.FailReason.UNAVAILABLE);
        }
        for (String name : new String[]{"ACCOUNT_MISMATCH", "PURCHASE_REJECTED", "MONETIZATION_DISABLED"}) {
            expected.put(name, CustomerEvents.FailReason.NOT_ALLOWED);
        }
        for (String name : new String[]{"NONE", "OFFLINE", "ALREADY_OWNED", "NOTHING_TO_RESTORE", "SESSION_CHANGED", "RATE_LIMITED",
                "STORAGE_UNAVAILABLE", "FAILED"}) {
            expected.put(name, CustomerEvents.FailReason.FAILED);
        }
        // The real enum: every constant has a documented reason.
        String text = new String(Files.readAllBytes(model), StandardCharsets.UTF_8);
        Matcher declaration = Pattern.compile("public enum Problem \\{\\s*([A-Z_,\\s]+);").matcher(text);
        check(declaration.find(), "GooglePlayModel.Problem was found");
        List<String> problems = new ArrayList<>();
        for (String name : declaration.group(1).split(",")) {
            if (!name.trim().isEmpty()) problems.add(name.trim());
        }
        check(problems.size() == expected.size() && expected.keySet().containsAll(problems),
                "every Problem is classified (new ones fail here): " + problems);
        for (Map.Entry<String, CustomerEvents.FailReason> entry : expected.entrySet()) {
            check(ProFunnel.reason(entry.getKey()) == entry.getValue(), entry.getKey() + " → " + entry.getValue().wire);
        }
        check(ProFunnel.reason(ProFunnel.PENDING) == CustomerEvents.FailReason.PENDING && ProFunnel.PENDING.equals("PENDING"),
                "a pending Play purchase → pending");
        for (String junk : new String[]{null, "", "user_canceled", "SOMETHING_NEW"}) {
            check(ProFunnel.reason(junk) == CustomerEvents.FailReason.FAILED, "'" + junk + "' → failed");
        }
        check(!problems.contains(ProFunnel.PENDING), "PENDING is not a Problem constant (it cannot shadow one)");
    }

    private static void restoreAndTrial() {
        check(ProFunnel.restoreOutcome("restored") == CustomerEvents.RestoreOutcome.RESTORED, "restored");
        check(ProFunnel.restoreOutcome("nothing") == CustomerEvents.RestoreOutcome.NOTHING, "nothing to restore");
        for (String other : new String[]{null, "failed", "NOTHING_TO_RESTORE", "RESTORED", "x"}) {
            check(ProFunnel.restoreOutcome(other) == CustomerEvents.RestoreOutcome.FAILED, "'" + other + "' → failed");
        }
        check(ProFunnel.trialOutcome(true, null) == CustomerEvents.TrialOutcome.STARTED, "success → started");
        check(ProFunnel.trialOutcome(true, "trial_paused") == CustomerEvents.TrialOutcome.STARTED, "success wins");
        for (String code : new String[]{"trial_already_used", "trial_device_cap", "trial_device_blocked", "trial_paused"}) {
            check(ProFunnel.trialOutcome(false, code) == CustomerEvents.TrialOutcome.REFUSED, code + " → refused");
        }
        for (String code : new String[]{null, "rate_limited", "connection_failed", "trial_used"}) {
            check(ProFunnel.trialOutcome(false, code) == CustomerEvents.TrialOutcome.FAILED, code + " → failed");
        }
        check("purchase_account_mismatch".equals(ProFunnel.serverCode("purchase_account_mismatch")), "a server code passes");
        check(ProFunnel.serverCode(null) == null && ProFunnel.serverCode("Purchase failed: x") == null, "else omitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

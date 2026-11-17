package org.telegram.messenger.zenlygram.pro;

/**
 * Plain-JVM tests for Firebase token registration rules (run with run-tests.sh). Token strings are
 * synthetic fixtures shaped like FCM tokens; no real token or credential is used.
 */
public final class ProPushTokenTests {
    private static int checks;
    private static final String FCM_SHAPED = "cX9k2Lq:APA91bH_r-Zq0~x.y" + repeat("A", 120);

    public static void main(String[] args) {
        tokenValidation();
        registrationState();
        retrySchedule();
        System.out.println("Pro push token: " + checks + " checks passed");
    }

    private static void tokenValidation() {
        check(ProPushToken.valid(FCM_SHAPED), "FCM punctuation and length accepted exactly");
        check(ProPushToken.valid("!") && ProPushToken.valid("~"), "printable ASCII bounds accepted; no minimum length");
        check(ProPushToken.valid(repeat("a", 4096)), "4096 chars accepted");
        check(!ProPushToken.valid(repeat("a", 4097)), "over 4096 rejected");
        check(!ProPushToken.valid("") && !ProPushToken.valid(null), "empty/null never registered (client never clears)");
        check(!ProPushToken.valid(" token") && !ProPushToken.valid("tok en") && !ProPushToken.valid("token\n")
                && !ProPushToken.valid("tok\ten"), "whitespace rejected, never trimmed");
        check(!ProPushToken.valid("tok\u007fen") && !ProPushToken.valid("tok\u0000en"), "control characters rejected");
        check(!ProPushToken.valid("токен"), "non-ASCII rejected");
    }

    private static void registrationState() {
        String registered = ProPushToken.fingerprint(FCM_SHAPED);
        check(registered.matches("[a-f0-9]{64}") && !registered.contains("APA91b"), "only a fingerprint is persisted");
        check(registered.equals(ProPushToken.fingerprint(FCM_SHAPED)), "fingerprint stable for the same token");
        check(ProPushToken.needsRegistration(FCM_SHAPED, null), "unregistered binding registers");
        check(!ProPushToken.needsRegistration(FCM_SHAPED, registered), "same token is not re-sent");
        check(ProPushToken.needsRegistration(FCM_SHAPED + "2", registered), "Firebase token change re-registers");
        check(!ProPushToken.needsRegistration("bad token", null), "invalid token is never sent");
    }

    private static void retrySchedule() {
        String token = ProPushToken.fingerprint(FCM_SHAPED);
        ProPushToken.Schedule idle = ProPushToken.Schedule.IDLE;
        check(idle.allows(token, 0), "first attempt immediate");
        ProPushToken.Schedule once = idle.failed(1000, 0);
        check(!once.allows(token, 1000 + 59999) && once.allows(token, 1000 + 60000), "first failure waits exactly one minute");
        ProPushToken.Schedule twice = once.failed(70000, 0);
        check(twice.nextAttemptElapsed == 70000 + 120000, "backoff doubles");
        ProPushToken.Schedule many = twice;
        for (int i = 0; i < 20; i++) many = many.failed(0, 0);
        check(many.nextAttemptElapsed == ProPushToken.MAX_BACKOFF_MS, "backoff capped at one hour");
        check(idle.failed(0, 900).nextAttemptElapsed == 900000, "longer server Retry-After honored");
        check(idle.failed(0, -5).nextAttemptElapsed == 60000, "negative Retry-After ignored");
        ProPushToken.Schedule rejected = many.rejectedToken(token);
        check(!rejected.allows(token, Long.MAX_VALUE), "422-rejected token is never retried");
        check(rejected.allows(ProPushToken.fingerprint(FCM_SHAPED + "new"), 0), "a new Firebase token is tried immediately");
        check(rejected.succeeded() == ProPushToken.Schedule.IDLE && rejected.succeeded().allows(token, 0), "success resets schedule");
        check(once.failures == 1 && idle.failures == 0, "transitions never mutate earlier schedules");
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

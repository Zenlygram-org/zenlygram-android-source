package org.telegram.messenger.zenlygram.pro;

/**
 * Plain-JVM checks of the client-side 429 cooldown (AC-P5). Routes are limited by trust domain, route
 * and installation (device id), so one route's Retry-After is shared by every Telegram account on this
 * installation while other routes keep working. The unauthenticated linking routes are scoped the same
 * way: the backend counts bind/start and bind/poll separately, so a 429 on one never holds the other.
 * Server limits stay authoritative: the client only waits, never retries by itself, never shortens a
 * cooldown. The cooldown lives in process memory (a restart may send once more and receive 429 again).
 */
public final class ProCooldownsTests {
    private static int checks;
    private static final String API = "https://pro.example.test/v1";

    public static void main(String[] args) {
        routeScope();
        retryAfter();
        bounds();
        System.out.println("Pro cooldowns: " + checks + " checks passed");
    }

    private static void routeScope() {
        String prepare = ProCooldowns.scope(API, "google/prepare");
        String entitlement = ProCooldowns.scope(API, "entitlement");
        ProCooldowns limited = ProCooldowns.NONE.throttled(prepare, 1000, 30);
        check(limited.remainingMs(prepare, 1000) == 30000, "a 429 on google/prepare waits for its Retry-After");
        check(limited.remainingMs(entitlement, 1000) == 0, "google/prepare throttling never blocks the entitlement refresh");
        check(limited.remainingMs(ProCooldowns.scope(API, "entitlement/refresh"), 1000) == 0
                && limited.remainingMs(ProCooldowns.scope(API, "google/attach"), 1000) == 0
                && limited.remainingMs(ProCooldowns.scope(API, "bind/poll"), 1000) == 0, "other routes keep working");
        ProCooldowns polling = ProCooldowns.NONE.throttled(ProCooldowns.scope(API, "bind/poll"), 1000, 20);
        check(polling.remainingMs(ProCooldowns.scope(API, "bind/poll"), 1000) == 20000, "a 429 on link polling waits for its Retry-After");
        check(polling.remainingMs(ProCooldowns.scope(API, "bind/start"), 1000) == 0,
                "a 429 on bind/poll never holds a new linking code: the server counts start and poll separately");
        ProCooldowns starting = ProCooldowns.NONE.throttled(ProCooldowns.scope(API, "bind/start"), 1000, 60);
        check(starting.remainingMs(ProCooldowns.scope(API, "bind/start"), 1000) == 60000, "a 429 on bind/start waits for its Retry-After");
        check(starting.remainingMs(ProCooldowns.scope(API, "bind/poll"), 1000) == 0
                && starting.remainingMs(ProCooldowns.scope(API, "bind/attest"), 1000) == 0,
                "a 429 on bind/start never holds polling or attesting a code already issued");
        check(polling.remainingMs(ProCooldowns.scope(API, "google/prepare"), 1000) == 0
                && polling.remainingMs(ProCooldowns.scope(API, "entitlement"), 1000) == 0
                && starting.remainingMs(ProCooldowns.scope(API, "entitlement/refresh"), 1000) == 0, "linking throttles never block Pro refresh or Play");
        check(ProCooldowns.scope(API, "bind/start").equals(API + "\nbind/start") && ProCooldowns.scope(API, "bind/poll").equals(API + "\nbind/poll")
                && ProCooldowns.scope(API, "profile/sync").equals(API + "\nprofile/sync"),
                "every route's scope is its trust domain plus the route itself (customer routes by their names)");
        ProCooldowns syncing = ProCooldowns.NONE.throttled(ProCooldowns.scope(API, "profile/sync"), 1000, 30);
        check(syncing.remainingMs(ProCooldowns.scope(API, "profile/events"), 1000) == 0
                && syncing.remainingMs(ProCooldowns.scope(API, "profile/consents"), 1000) == 0, "one customer route's 429 never holds another");
        check(limited.remainingMs(ProCooldowns.scope("https://other.example.test/v1", "google/prepare"), 1000) == 0,
                "another backend trust domain is a different limit");
        check(ProCooldowns.NONE.remainingMs(prepare, 1000) == 0, "the original value is unchanged");
    }

    private static void retryAfter() {
        String route = ProCooldowns.scope(API, "trial/start");
        ProCooldowns missing = ProCooldowns.NONE.throttled(route, 5000, 0);
        check(missing.remainingMs(route, 5000) == 1000, "a 429 without Retry-After still waits one second");
        check(missing.remainingMs(route, 6000) == 0, "the cooldown ends exactly at its deadline");
        ProCooldowns longer = ProCooldowns.NONE.throttled(route, 5000, 60);
        check(longer.throttled(route, 6000, 2).remainingMs(route, 6000) == 59000, "a shorter later 429 never shortens the server cooldown");
        check(longer.throttled(route, 6000, 120).remainingMs(route, 6000) == 120000, "a longer later 429 extends it");
        check(ProCooldowns.NONE.throttled(route, Long.MAX_VALUE - 10, Integer.MAX_VALUE).remainingMs(route, Long.MAX_VALUE - 10) > 0,
                "an extreme Retry-After cannot overflow into no cooldown");
        check(ProCooldowns.NONE.throttled(route, 5000, -3).remainingMs(route, 5000) == 1000, "a negative Retry-After is the minimum wait");
    }

    private static void bounds() {
        ProCooldowns many = ProCooldowns.NONE;
        for (int i = 0; i < 200; i++) many = many.throttled(ProCooldowns.scope(API, "route" + i), i * 1000L, 1);
        check(many.size() <= 2, "expired cooldowns are dropped, so the table stays bounded");
        check(many.remainingMs(ProCooldowns.scope(API, "route199"), 199000) == 1000, "the newest cooldown is kept");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}

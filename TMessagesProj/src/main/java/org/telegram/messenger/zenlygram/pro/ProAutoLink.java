package org.telegram.messenger.zenlygram.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Automatic link (M3, contract §6.6, design 50 §8): links an account that consented, silently, by
 * Telegram attestation. ProAutoLinkSchedule decides eligibility and backoff, ProAutoLinkMachine one
 * attempt's steps; this adapter performs them (ZenlyPro's headless link on LINK_WORKER,
 * ProTelegramAttest under a 30 s watchdog, the 1 s poll timer), keeps at most one attempt in flight
 * per installation and persists each account's schedule in SharedPreferences("zenlygram_customer")
 * as autolink.v1.<tg>@<authority>. It never shows UI, opens a WebView, messages the bot, shares
 * contacts, accepts a login prompt or falls back to the code. Nothing runs until ZenlyCustomer asks
 * (consider) while the main app is in the foreground. State lives on the main thread; every entry
 * point called elsewhere re-posts itself there.
 */
public final class ProAutoLink {
    /** ZenlyCustomer's consent gate, asked on the main thread. */
    public interface Gate {
        /**
         * Trusted epoch seconds (> 0) at which the latest grant of the slot's signed-in Telegram user was
         * decided (its decided_at), when that grant allows an automatic link (ZenlyCustomer.hasEffectiveGrant:
         * granted including the binding-loss derivation and not stale in this build, not rejected, no deletion
         * pending); 0 otherwise, for a selected slot or any other.
         */
        long effectiveGrantAt(int account);
    }

    /** Main thread, outside any Pro lock. */
    public interface Listener {
        /** An automatic attempt ended (never for STALE): link.attempt{trigger:auto, method:attest}. */
        void onAttempt(int account, boolean success, String errorCode, long durationSeconds);

        /**
         * bind/start answered 409 consent_stale to the grant sentGrantJson (exactly the ZenlyPro.ConsentProvider's
         * string), in an automatic attempt (which then ends CONSENT_STALE) or in a manual link (which then links
         * without it): that grant becomes needs_reconsent (ZenlyConsent.markConsentStale) and never links again.
         */
        void onConsentStale(int account, long telegramUserId, String authority, String sentGrantJson);
    }

    static final String PREFERENCES = "zenlygram_customer", KEY_PREFIX = "autolink.v1.", JITTER_KEY = "autolink.v1.jitter_ms";
    /** An account whose attempt ended STALE rests this long (in memory) before it is considered again. */
    static final long STALE_REST_MS = 60_000;
    /** The wake timer re-checks at least this often: Handler delays stop in deep sleep and clocks move. */
    static final long MAX_WAKE_MS = 3_600_000;

    private static volatile Gate gate;
    private static volatile Listener listener;
    private static final Random RANDOM = new SecureRandom();
    /** Accounts ZenlyCustomer asked to link: slot → its Telegram user when asked. */
    private static final Map<Integer, Long> wanted = new HashMap<>();
    private static final int[] proScreens = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] restUntilElapsed = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static boolean foreground;
    private static long firstForegroundElapsed = -1, jitterMs = -1;
    private static String appVersion;
    private static Attempt current;
    private static Runnable wake;

    private static final class Attempt {
        final int account;
        final long telegramId, grantDecidedAt, startedElapsed;
        final String authority;
        final ProAutoLinkMachine machine = new ProAutoLinkMachine();
        ZenlyPro.LinkRequest link;
        /** Held only between Telegram's answer and bind/attest. */
        String initData;
        Runnable watchdog, pollTimer;

        Attempt(int account, long telegramId, String authority, long grantDecidedAt, long startedElapsed) {
            this.account = account;
            this.telegramId = telegramId;
            this.authority = authority;
            this.grantDecidedAt = grantDecidedAt;
            this.startedElapsed = startedElapsed;
        }
    }

    private ProAutoLink() { }

    public static void setGate(Gate value) { gate = value; }

    public static void setListener(Listener value) { listener = value; }

    /** ZenlyCustomer's pipeline wants this account linked (design 50 §10 rule 3); it links only when §8.2 allows. */
    public static void consider(int account) {
        if (!onMain(() -> consider(account)) || !validSlot(account)) return;
        long telegramId = UserConfig.getInstance(account).getClientUserId();
        if (telegramId <= 0) {
            wanted.remove(account);
            return;
        }
        wanted.put(account, telegramId);
        pump();
    }

    /** ZenlyProActivity.onFragmentCreate (true) and onFragmentDestroy (false): no automatic link while it is alive. */
    public static void setProScreenAlive(int account, boolean alive) {
        if (!onMain(() -> setProScreenAlive(account, alive)) || !validSlot(account)) return;
        proScreens[account] = Math.max(0, proScreens[account] + (alive ? 1 : -1));
        if (!alive) pump();
    }

    /** After a successful Unlink (compliance C-13): no automatic link until a newer grant or a manual link. */
    public static void noteUserUnlinked(int account) {
        if (!onMain(() -> noteUserUnlinked(account)) || !validSlot(account)) return;
        long telegramId = UserConfig.getInstance(account).getClientUserId();
        String authority = ZenlyPro.authority();
        if (telegramId <= 0 || authority == null) return;
        update(telegramId, authority, ProAutoLinkSchedule.userUnlinked(load(telegramId, authority), ZenlyPro.trustedNowSeconds()));
    }

    public static void onForeground() {
        if (!onMain(ProAutoLink::onForeground)) return;
        foreground = true;
        if (firstForegroundElapsed < 0) firstForegroundElapsed = SystemClock.elapsedRealtime();
        pump();
    }

    /** The wake timer stops; an attempt already in flight finishes. */
    public static void onBackground() {
        if (!onMain(ProAutoLink::onBackground)) return;
        foreground = false;
        cancelWake();
    }

    /** Logout, erasure or withdrawal of telegramUserId: its schedules at every origin go and its attempt ends silently. */
    public static void forget(long telegramUserId) {
        if (!onMain(() -> forget(telegramUserId)) || telegramUserId <= 0) return;
        if (current != null && current.telegramId == telegramUserId) abandon(current);
        for (Iterator<Map.Entry<Integer, Long>> entries = wanted.entrySet().iterator(); entries.hasNext(); ) {
            if (entries.next().getValue() == telegramUserId) entries.remove();
        }
        SharedPreferences preferences = preferences();
        SharedPreferences.Editor editor = preferences.edit();
        String prefix = KEY_PREFIX + telegramUserId + "@";
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.commit();
        pump();
    }

    // ---- ZenlyPro's links (any thread) ---------------------------------------------------------

    /**
     * bind/start (automatic or manual) answered 409 consent_stale to the grant sentGrantJson: that grant, decided at
     * its recorded_at, and every older one never link automatically again, and the listener hears it once.
     */
    static void consentStaleReported(int account, long telegramUserId, String authority, String sentGrantJson) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!validSlot(account) || telegramUserId <= 0 || authority == null) return;
            long refused = recordedAt(sentGrantJson);
            if (refused <= 0 && UserConfig.getInstance(account).getClientUserId() == telegramUserId) refused = grantAt(account);
            update(telegramUserId, authority, ProAutoLinkSchedule.staleGrant(load(telegramUserId, authority),
                    refused > 0 ? refused : ZenlyPro.trustedNowSeconds()));
            Listener events = listener;
            if (events != null) events.onConsentStale(account, telegramUserId, authority, sentGrantJson);
        });
    }

    /** A manual link of the slot was adopted: its Unlink suppression and attest streak end. */
    static void manualLinkAdopted(int account, long telegramUserId, String authority) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!validSlot(account) || telegramUserId <= 0 || authority == null) return;
            update(telegramUserId, authority, ProAutoLinkSchedule.manualLinked(load(telegramUserId, authority)));
            Long asked = wanted.get(account);
            if (asked != null && asked == telegramUserId) wanted.remove(account);
        });
    }

    // ---- Scheduling ------------------------------------------------------------------------

    /** Starts the first eligible wanted account (the selected one first, then slot order), else arms the wake timer. */
    private static void pump() {
        cancelWake();
        if (current != null || !foreground) return;
        long now = ZenlyPro.trustedNowSeconds();
        long elapsed = SystemClock.elapsedRealtime();
        long earliest = -1;
        for (int account : order()) {
            Long asked = wanted.get(account);
            if (asked == null) continue;
            UserConfig user = UserConfig.getInstance(account);
            long telegramId = user.getClientUserId();
            if (telegramId != asked) {
                wanted.remove(account);
                continue;
            }
            String authority = ZenlyPro.authority();
            boolean configured = authority != null && ZenlyPro.linkingConfigured();
            ProAutoLinkSchedule.State state = configured ? loadCurrent(telegramId, authority, now) : ProAutoLinkSchedule.State.INITIAL;
            // The identity before the binding-loss mark: ZenlyPro sets both in the LOCK section of a server 401, so a slot read
            // unlinked by that 401 already carries the mark, and its delivery (on this thread) cannot lower it in between.
            boolean linked = configured && CustomerApi.forAccount(account).identity().isLinked();
            long grantAt = grantAt(account);
            boolean lossPending = !linked && ZenlyPro.get(account).bindingLossPending();
            ProAutoLinkSchedule.Facts facts = ProAutoLinkSchedule.Facts
                    .of(grantAt, firstForegroundElapsed < 0 ? -1 : elapsed - firstForegroundElapsed, jitter())
                    .foreground(foreground)
                    .activated(user.isClientActivated())
                    .linked(linked)
                    .lossPending(lossPending)
                    .configured(configured)
                    .proScreenAlive(proScreens[account] > 0)
                    .manualLinkPending(ZenlyPro.get(account).manualLinkPending())
                    .inFlight(current != null);
            ProAutoLinkSchedule.Blocker blocker = ProAutoLinkSchedule.blocker(state, facts, now);
            if (blocker == ProAutoLinkSchedule.Blocker.LINKED) {
                wanted.remove(account);
                continue;
            }
            long wait = ProAutoLinkSchedule.waitMs(state, facts, now);
            if (wait == 0 && restUntilElapsed[account] > elapsed) wait = restUntilElapsed[account] - elapsed;
            if (wait == 0) {
                start(account, telegramId, authority, state, grantAt);
                return;
            }
            if (wait > 0 && (earliest < 0 || wait < earliest)) earliest = wait;
        }
        if (earliest > 0) armWake(Math.min(earliest, MAX_WAKE_MS));
    }

    private static void start(int account, long telegramId, String authority, ProAutoLinkSchedule.State state, long grantAt) {
        // Counted before the request: the daily cap holds even if the process dies mid-attempt.
        update(telegramId, authority, ProAutoLinkSchedule.started(state, ZenlyPro.trustedNowSeconds()));
        Attempt attempt = new Attempt(account, telegramId, authority, grantAt, SystemClock.elapsedRealtime());
        current = attempt;
        perform(attempt, attempt.machine.start());
    }

    private static void perform(Attempt attempt, ProAutoLinkMachine.Action action) {
        if (current != attempt) return;
        ZenlyPro pro = ZenlyPro.get(attempt.account);
        switch (action) {
            case BEGIN:
                pro.beginLinkHeadless(new ZenlyPro.Callback<ZenlyPro.LinkRequest>() {
                    @Override public void onSuccess(ZenlyPro.LinkRequest link) {
                        if (!live(attempt)) return;
                        attempt.link = link;
                        perform(attempt, attempt.machine.begun(link.attest != null));
                    }
                    @Override public void onError(ZenlyPro.Error error) { failed(attempt, error); }
                });
                break;
            case OBTAIN:
                attempt.watchdog = () -> {
                    attempt.watchdog = null;
                    if (live(attempt)) perform(attempt, attempt.machine.watchdog());
                };
                AndroidUtilities.runOnUIThread(attempt.watchdog, ProAutoLinkMachine.WATCHDOG_MS);
                ProTelegramAttest.obtain(attempt.account, attempt.link.attest, (initData, errorCode) -> {
                    if (!live(attempt)) return;
                    cancelTimer(attempt.watchdog);
                    attempt.watchdog = null;
                    attempt.initData = initData;
                    perform(attempt, attempt.machine.obtained(initData, errorCode));
                });
                break;
            case ATTEST: {
                String initData = attempt.initData;
                attempt.initData = null;
                pro.attestLinkHeadless(attempt.link, initData, new ZenlyPro.Callback<ZenlyPro.LinkRequest>() {
                    @Override public void onSuccess(ZenlyPro.LinkRequest value) {
                        if (live(attempt)) perform(attempt, attempt.machine.attested());
                    }
                    @Override public void onError(ZenlyPro.Error error) { failed(attempt, error); }
                });
                break;
            }
            case POLL:
                pro.pollLinkHeadless(attempt.link, new ZenlyPro.Callback<ZenlyPro.LinkResult>() {
                    @Override public void onSuccess(ZenlyPro.LinkResult result) {
                        if (live(attempt)) perform(attempt, attempt.machine.polled(result.state.name().toLowerCase(Locale.US)));
                    }
                    @Override public void onError(ZenlyPro.Error error) { failed(attempt, error); }
                });
                break;
            case WAIT:
                attempt.pollTimer = () -> {
                    attempt.pollTimer = null;
                    if (live(attempt)) perform(attempt, attempt.machine.waited());
                };
                AndroidUtilities.runOnUIThread(attempt.pollTimer, ProAutoLinkMachine.POLL_INTERVAL_MS);
                break;
            case FINISH:
                finish(attempt);
                break;
            default: // IGNORE: a late input of this attempt.
                break;
        }
    }

    /** Still the current attempt and the slot's user; a changed user ends it as STALE. */
    private static boolean live(Attempt attempt) {
        if (current != attempt) return false;
        if (UserConfig.getInstance(attempt.account).getClientUserId() == attempt.telegramId) return true;
        perform(attempt, attempt.machine.interrupted());
        return false;
    }

    private static void failed(Attempt attempt, ZenlyPro.Error error) {
        if (live(attempt)) perform(attempt, attempt.machine.failed(error.code, error.httpStatus, error.retryAfterSeconds));
    }

    private static void finish(Attempt attempt) {
        if (current != attempt) return;
        current = null;
        clear(attempt);
        ProAutoLinkMachine.Outcome outcome = attempt.machine.outcome();
        long elapsed = SystemClock.elapsedRealtime();
        if (outcome != ProAutoLinkMachine.Outcome.SUCCESS) ZenlyPro.get(attempt.account).cancelHeadlessLink(); // Drops the poll token.
        if (outcome == ProAutoLinkMachine.Outcome.STALE) {
            restUntilElapsed[attempt.account] = elapsed + STALE_REST_MS; // No penalty and no event.
        } else {
            ProAutoLinkSchedule.State state = ProAutoLinkSchedule.after(load(attempt.telegramId, attempt.authority), outcome,
                    ZenlyPro.trustedNowSeconds(), attempt.machine.retryAfterSeconds(), RANDOM);
            // CONSENT_STALE: ZenlyPro reported the refused grant before this outcome (consentStaleReported, which also told
            // the listener); recording it again from this attempt's grant time changes nothing or only adds certainty.
            if (outcome == ProAutoLinkMachine.Outcome.CONSENT_STALE) state = ProAutoLinkSchedule.staleGrant(state, attempt.grantDecidedAt);
            update(attempt.telegramId, attempt.authority, state);
            if (outcome == ProAutoLinkMachine.Outcome.SUCCESS) wanted.remove(attempt.account);
            Listener events = listener;
            if (events != null) {
                events.onAttempt(attempt.account, outcome == ProAutoLinkMachine.Outcome.SUCCESS, attempt.machine.errorCode(),
                        Math.max(0, elapsed - attempt.startedElapsed) / 1000);
            }
        }
        pump();
    }

    /** forget() during an attempt: it ends without a state write or an event, and its headless link is fenced. */
    private static void abandon(Attempt attempt) {
        current = null;
        clear(attempt);
        ZenlyPro.get(attempt.account).cancelHeadlessLink();
    }

    private static void clear(Attempt attempt) {
        cancelTimer(attempt.watchdog);
        cancelTimer(attempt.pollTimer);
        attempt.watchdog = null;
        attempt.pollTimer = null;
        attempt.initData = null;
    }

    private static long grantAt(int account) {
        Gate consent = gate;
        return consent == null ? 0 : Math.max(0, consent.effectiveGrantAt(account));
    }

    /** A grant ConsentEvent's recorded_at (its record's decided_at) in epoch seconds; 0 when it has none. */
    private static long recordedAt(String grantJson) {
        if (grantJson == null) return 0;
        try {
            return Math.max(0, ProPolicy.utc(new JSONObject(grantJson).optString("recorded_at")));
        } catch (Exception unreadable) {
            return 0;
        }
    }

    /** Per-installation U(0, 30 s) added to the first idle; drawn once and kept. */
    private static long jitter() {
        if (jitterMs >= 0) return jitterMs;
        SharedPreferences preferences = preferences();
        long stored = preferences.getLong(JITTER_KEY, -1);
        if (stored < 0 || stored > ProAutoLinkSchedule.MAX_JITTER_MS) {
            stored = (long) (RANDOM.nextDouble() * ProAutoLinkSchedule.MAX_JITTER_MS);
            preferences.edit().putLong(JITTER_KEY, stored).apply();
        }
        jitterMs = stored;
        return stored;
    }

    /** The selected account first, then slot order. */
    private static int[] order() {
        int selected = UserConfig.selectedAccount;
        int[] order = new int[UserConfig.MAX_ACCOUNT_COUNT];
        int count = 0;
        if (validSlot(selected)) order[count++] = selected;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (account != selected) order[count++] = account;
        }
        return Arrays.copyOf(order, count);
    }

    private static void armWake(long delayMs) {
        cancelWake();
        Runnable timer = new Runnable() {
            @Override public void run() {
                if (wake != this) return;
                wake = null;
                pump();
            }
        };
        wake = timer;
        AndroidUtilities.runOnUIThread(timer, delayMs);
    }

    private static void cancelWake() {
        cancelTimer(wake);
        wake = null;
    }

    private static void cancelTimer(Runnable timer) {
        if (timer != null) AndroidUtilities.cancelRunOnUIThread(timer);
    }

    private static boolean validSlot(int account) { return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT; }

    /** True on the main thread; elsewhere it posts again there and returns false. */
    private static boolean onMain(Runnable again) {
        if (Looper.myLooper() == Looper.getMainLooper()) return true;
        AndroidUtilities.runOnUIThread(again);
        return false;
    }

    // ---- autolink.v1.<tg>@<authority> ---------------------------------------------------------

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static String key(long telegramUserId, String authority) {
        return KEY_PREFIX + telegramUserId + "@" + authority;
    }

    /** The stored schedule normalized at nowS (app version, clock clamp, old starts); written back when that changed it. */
    private static ProAutoLinkSchedule.State loadCurrent(long telegramUserId, String authority, long nowS) {
        if (appVersion == null) appVersion = ZenlyPro.appVersionName();
        ProAutoLinkSchedule.State stored = load(telegramUserId, authority);
        ProAutoLinkSchedule.State state = ProAutoLinkSchedule.loaded(stored, appVersion, nowS);
        if (state != stored) save(telegramUserId, authority, state);
        return state;
    }

    /**
     * The stored schedule; INITIAL when there is none. An unreadable record fails closed: it reads as an
     * Unlink now, so only a newer grant or a manual link links this account again.
     */
    static ProAutoLinkSchedule.State load(long telegramUserId, String authority) {
        String stored = preferences().getString(key(telegramUserId, authority), null);
        if (stored == null) return ProAutoLinkSchedule.State.INITIAL;
        ProAutoLinkSchedule.State state = decode(stored);
        return state != null ? state
                : ProAutoLinkSchedule.userUnlinked(ProAutoLinkSchedule.State.INITIAL, ZenlyPro.trustedNowSeconds());
    }

    /** Synchronous: the schedule is on disk before the next request is sent. */
    static void save(long telegramUserId, String authority, ProAutoLinkSchedule.State state) {
        String encoded = encode(state);
        if (encoded != null) preferences().edit().putString(key(telegramUserId, authority), encoded).commit();
    }

    private static void update(long telegramUserId, String authority, ProAutoLinkSchedule.State state) {
        if (!state.equals(load(telegramUserId, authority))) save(telegramUserId, authority, state);
    }

    /**
     * {"v":1,"failures":2,"attest_streak":0,"next_at":1793000000,"paused_until":0,"starts":[1792990000],
     * "user_unlinked_at":0,"stale_grant_at":0,"app_version":"1.0.0"} (trusted epoch seconds).
     */
    static String encode(ProAutoLinkSchedule.State state) {
        try {
            JSONArray starts = new JSONArray();
            for (long start : state.starts()) starts.put(start);
            return new JSONObject().put("v", 1).put("failures", state.failures).put("attest_streak", state.attestStreak)
                    .put("next_at", state.nextAt).put("paused_until", state.pausedUntil).put("starts", starts)
                    .put("user_unlinked_at", state.userUnlinkedAt).put("stale_grant_at", state.staleGrantAt)
                    .put("app_version", state.appVersion == null ? JSONObject.NULL : state.appVersion).toString();
        } catch (Exception unencodable) {
            return null;
        }
    }

    /** The state of an encode() record; null when it is not one (other version, malformed). */
    static ProAutoLinkSchedule.State decode(String json) {
        try {
            JSONObject value = new JSONObject(json);
            if (value.optInt("v") != 1) return null;
            JSONArray array = value.optJSONArray("starts");
            long[] starts = new long[array == null ? 0 : array.length()];
            for (int i = 0; i < starts.length; i++) starts[i] = array.getLong(i);
            String version = value.isNull("app_version") ? null : value.getString("app_version");
            return ProAutoLinkSchedule.State.of(value.getInt("failures"), value.getInt("attest_streak"), value.getLong("next_at"),
                    value.getLong("paused_until"), starts, value.getLong("user_unlinked_at"), value.optLong("stale_grant_at"), version);
        } catch (Exception malformed) {
            return null;
        }
    }
}

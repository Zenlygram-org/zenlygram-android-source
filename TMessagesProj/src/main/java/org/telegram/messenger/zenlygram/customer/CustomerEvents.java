package org.telegram.messenger.zenlygram.customer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Analytics taxonomy v1 (contract §3.1–§3.4; design 50 §11.1, §11.3), pure and host-tested: the registry
 * ({@code B/resources/contracts/analytics-events.json}, vendored as {@code fixtures/analytics-events.json}) as Java
 * enums, the typed builders of every event Android emits, the event and batch envelope JSON and the device context.
 * <p>
 * An event can carry no free text (contract §6.9): a string prop is a registry enum or a value checked against its
 * registry pattern — an invalid {@code product_id} drops the event (null), an invalid {@code base_plan_id} is omitted,
 * an invalid {@code error_code} becomes {@code unknown} — and every integer is clamped to its registry type
 * ({@code count} 1..1000, {@code amount} 0..100000, {@code duration_s} 0..86400, {@code priority} 0..4). The Java
 * enums are a subset of the registry's: Android never produces the {@code pin} method (neither the Vault nor chat
 * locks know whether a secret is a PIN, and it is never derived from the secret), the iOS-only screens
 * {@code zg.paywall} / {@code zg.link_account} or the {@code message_filter} feature. Adding anything is a taxonomy
 * bump, deployed to the server before an app emits it. Nothing here stores, sends or logs.
 */
public final class CustomerEvents {
    public static final int TAXONOMY_VERSION = 1;
    /** {@code schema_version} of the batch envelope (contract §0.2). */
    public static final int SCHEMA_VERSION = 1;
    /** An event's compact JSON is at most 2 KiB (contract §3.1; the server measures the UTF-8 bytes). */
    public static final int MAX_EVENT_BYTES = 2048;
    /** {@code seq}: 0..1 000 000, strictly increasing per (account, session); {@code app.session_start} is 0. */
    public static final int MAX_SEQ = 1000000;
    public static final String PLATFORM = "android";

    public static final int COUNT_MIN = 1, COUNT_MAX = 1000, AMOUNT_MAX = 100000, DURATION_MAX = 86400, PRIORITY_MAX = 4;

    // Registry event names.
    public static final String SESSION_START = "app.session_start", SESSION_END = "app.session_end", SCREEN_VIEW = "screen.view",
            FEATURE_TOGGLE = "feature.toggle", FEATURE_USED = "feature.used", TASK_CREATED = "tasks.task_created",
            TASK_COMPLETED = "tasks.task_completed", TASK_DELETED = "tasks.task_deleted", LIST_CREATED = "tasks.list_created",
            LIST_DELETED = "tasks.list_deleted", TASKS_SHARED = "tasks.shared", TASKS_MIGRATION = "tasks.migration",
            TASKS_SYNC_ISSUE = "tasks.sync_issue", VAULT_UNLOCKED = "vault.unlocked", VAULT_CHAT_ADDED = "vault.chat_added",
            VAULT_CHAT_REMOVED = "vault.chat_removed", CHAT_LOCK_SET = "chat_lock.lock_set", CHAT_LOCK_UNLOCKED = "chat_lock.unlocked",
            CHAT_LOCK_REMOVED = "chat_lock.lock_removed", EDIT_HISTORY_VIEWED = "edit_history.viewed", PRO_SCREEN_VIEW = "pro.screen_view",
            PRO_PLAN_SELECT = "pro.plan_select", PRO_PURCHASE_START = "pro.purchase_start", PRO_PURCHASE_SUCCESS = "pro.purchase_success",
            PRO_PURCHASE_FAIL = "pro.purchase_fail", PRO_RESTORE = "pro.restore", PRO_TRIAL_START = "pro.trial_start",
            LINK_ATTEMPT = "link.attempt", ERROR_OCCURRED = "error.occurred";

    private static final Pattern PRODUCT_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern BASE_PLAN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    /** The client's {@code ui_language} form (design 50 §9.1; iOS: base language code lower-cased, '_' → '-'). */
    private static final Pattern UI_LANGUAGE = Pattern.compile("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,2}");
    /** contract §0.3 {@code iana_tz}. */
    private static final Pattern IANA_TZ = Pattern.compile("[A-Za-z][A-Za-z0-9_+/-]{0,63}");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,20}");
    /** A uuid4 only used to size the envelope (every batch_id is 36 characters). */
    private static final String SIZING_ID = "00000000-0000-4000-8000-000000000000";
    /** The envelope's bytes without its context object and events: {@link #envelope} with "{}" and no events, minus 2. */
    private static final int ENVELOPE_FIXED_BYTES;

    static {
        ENVELOPE_FIXED_BYTES = utf8Length(envelope(SIZING_ID, 0, "{}", Collections.<String>emptyList())) - 2;
    }

    private CustomerEvents() {}

    // ---- Registry enums (enums.<name>) ----

    /** {@code enums.screen} as Android reports it (no iOS-only zg.paywall / zg.link_account). */
    public enum Screen {
        ZG_HUB("zg.hub"), ZG_PRO("zg.pro"), ZG_DATA_CONSENT("zg.data_consent"), ZG_MY_DATA("zg.my_data"),
        ZG_ACCOUNT_DELETE("zg.account_delete"), ZG_PREFERENCES("zg.preferences"), TASKS_BOARD("tasks.board"),
        TASKS_LISTS("tasks.lists"), TASKS_TASK_EDITOR("tasks.task_editor"), TASKS_CALENDAR("tasks.calendar"),
        TASKS_UNSENT_CHANGES("tasks.unsent_changes"), TASKS_INVITATIONS("tasks.invitations"), TASKS_ARCHIVE("tasks.archive"),
        VAULT_UNLOCK("vault.unlock"), VAULT_CHATS("vault.chats"), VAULT_SETTINGS("vault.settings"),
        CHAT_LOCK_UNLOCK("chat_lock.unlock"), CHAT_LOCK_SETTINGS("chat_lock.settings"),
        EDIT_HISTORY_MESSAGE("edit_history.message"), EDIT_HISTORY_SETTINGS("edit_history.settings"),
        CALLS_RECORDINGS("calls.recordings"), TG_CHAT_LIST("tg.chat_list"), TG_CONTACTS("tg.contacts"), TG_CALLS("tg.calls"),
        TG_SETTINGS("tg.settings");

        public final String wire;

        Screen(String wire) { this.wire = wire; }

        /** The screen of its wire name, or null (also for anything Android never reports). */
        public static Screen of(String wire) {
            for (Screen screen : values()) {
                if (screen.wire.equals(wire)) return screen;
            }
            return null;
        }

        /** A Telegram-native surface ({@code tg.*}), reported only as a tab or a stack root. */
        public boolean telegram() { return wire.startsWith("tg."); }
    }

    /** {@code enums.feature} as Android reports it (Android has no message filter). */
    public enum Feature {
        TASKS("tasks"), TASKS_CLOUD("tasks_cloud"), TASKS_CALENDAR("tasks_calendar"), VAULT("vault"), CHAT_LOCK("chat_lock"),
        EDIT_HISTORY("edit_history"), DELETED_MESSAGES("deleted_messages"), CALL_RECORDING("call_recording"),
        TRANSLATION("translation"), UNREAD_REMINDER("unread_reminder"), STRANGER_INBOX("stranger_inbox"),
        FOREIGN_USER_BLOCK("foreign_user_block"), OCR("ocr"), DICTATION("dictation"), APK_PROTECTION("apk_protection"),
        CHAT_TOOLS("chat_tools"), FOLDERS("folders"), ROUND_VIDEO_FROM_GALLERY("round_video_from_gallery"),
        STORY_CONFIRMATION("story_confirmation"), SEND_CONFIRMATION("send_confirmation"), APP_ICON("app_icon");

        public final String wire;

        Feature(String wire) { this.wire = wire; }
    }

    /** {@code enums.error_domain} ({@code error.occurred}). */
    public enum ErrorDomain {
        NETWORK("network"), BACKEND("backend"), LINK("link"), TASKS_SYNC("tasks_sync"), TASKS_MIGRATION("tasks_migration"),
        PURCHASE("purchase"), CONSENT_UPLOAD("consent_upload"), PROFILE_SYNC("profile_sync"), EVENTS_UPLOAD("events_upload"),
        STORAGE("storage"), VAULT("vault"), CHAT_LOCK("chat_lock"), OTHER("other");

        public final String wire;

        ErrorDomain(String wire) { this.wire = wire; }
    }

    /** {@code enums.rail}: where a purchase runs (Android's Play flows are {@link #GOOGLE}). */
    public enum Rail {
        APPLE("apple"), GOOGLE("google"), WEB("web");

        public final String wire;

        Rail(String wire) { this.wire = wire; }
    }

    /** {@code enums.tasks_scope}. */
    public enum TasksScope {
        CLOUD("cloud"), LOCAL("local");

        public final String wire;

        TasksScope(String wire) { this.wire = wire; }
    }

    // ---- Inline enums (an event's own list) ----

    /** {@code app.session_start.trigger} (sessions only, design 50 §11.2). */
    public enum SessionTrigger {
        LAUNCH("launch"), FOREGROUND("foreground"), ACCOUNT_SWITCH("account_switch");

        public final String wire;

        SessionTrigger(String wire) { this.wire = wire; }
    }

    /** {@code app.session_end.reason}; Android never emits {@code terminate} (it cannot detect it). */
    public enum SessionEndReason {
        BACKGROUND("background"), ACCOUNT_SWITCH("account_switch"), LOGOUT("logout");

        public final String wire;

        SessionEndReason(String wire) { this.wire = wire; }
    }

    /** {@code tasks.shared.action}. */
    public enum SharedAction {
        INVITE_SENT("invite_sent"), INVITE_ACCEPTED("invite_accepted"), INVITE_DECLINED("invite_declined"), LEFT("left"),
        REVOKED("revoked"), BLOCKED("blocked");

        public final String wire;

        SharedAction(String wire) { this.wire = wire; }
    }

    /** {@code tasks.migration.phase}. */
    public enum MigrationPhase {
        STARTED("started"), COMPLETED("completed"), FAILED("failed");

        public final String wire;

        MigrationPhase(String wire) { this.wire = wire; }
    }

    /** {@code tasks.sync_issue.kind} (aggregated per session per kind). */
    public enum SyncIssueKind {
        CONFLICT_AUTO_RESOLVED("conflict_auto_resolved"), CONFLICT_MANUAL("conflict_manual"), STALLED("stalled"),
        BLOCKED_CREATION("blocked_creation"), INVALID("invalid");

        public final String wire;

        SyncIssueKind(String wire) { this.wire = wire; }
    }

    /** {@code vault.unlocked.method} / {@code chat_lock.unlocked.method} as Android sends them: never {@code pin}. */
    public enum UnlockMethod {
        PASSWORD("password"), BIOMETRIC("biometric");

        public final String wire;

        UnlockMethod(String wire) { this.wire = wire; }
    }

    /** {@code chat_lock.lock_set.method} as Android sends it: always {@code password}. */
    public enum LockMethod {
        PASSWORD("password");

        public final String wire;

        LockMethod(String wire) { this.wire = wire; }
    }

    /** {@code edit_history.viewed.kind}. */
    public enum EditHistoryKind {
        EDITED("edited"), DELETED("deleted");

        public final String wire;

        EditHistoryKind(String wire) { this.wire = wire; }
    }

    /** {@code pro.screen_view.source}: how the Pro screen was reached ({@link ProFunnel#sourceOf}). */
    public enum ProSource {
        SETTINGS("settings"), PAYWALL("paywall"), FEATURE_GATE("feature_gate"), TASKS("tasks"), LINK("link"), DEEPLINK("deeplink"),
        OTHER("other");

        public final String wire;

        ProSource(String wire) { this.wire = wire; }
    }

    /** {@code pro.screen_view.mode}: the monetization mode the screen showed. */
    public enum ProMode {
        FREE("free"), PAID("paid");

        public final String wire;

        ProMode(String wire) { this.wire = wire; }
    }

    /** {@code pro.purchase_fail.reason} ({@link ProFunnel#reason}). */
    public enum FailReason {
        CANCELLED("cancelled"), PENDING("pending"), FAILED("failed"), NOT_ALLOWED("not_allowed"), UNAVAILABLE("unavailable");

        public final String wire;

        FailReason(String wire) { this.wire = wire; }
    }

    /** {@code pro.restore.outcome}. */
    public enum RestoreOutcome {
        RESTORED("restored"), NOTHING("nothing"), FAILED("failed");

        public final String wire;

        RestoreOutcome(String wire) { this.wire = wire; }
    }

    /** {@code pro.trial_start.outcome}. */
    public enum TrialOutcome {
        STARTED("started"), REFUSED("refused"), FAILED("failed");

        public final String wire;

        TrialOutcome(String wire) { this.wire = wire; }
    }

    /** {@code link.attempt.trigger}. */
    public enum LinkTrigger {
        AUTO("auto"), MANUAL("manual");

        public final String wire;

        LinkTrigger(String wire) { this.wire = wire; }
    }

    /** {@code link.attempt.method}. */
    public enum LinkMethod {
        ATTEST("attest"), CODE("code");

        public final String wire;

        LinkMethod(String wire) { this.wire = wire; }
    }

    /** {@code link.attempt.outcome}. */
    public enum LinkOutcome {
        SUCCESS("success"), FAILED("failed");

        public final String wire;

        LinkOutcome(String wire) { this.wire = wire; }
    }

    // ---- Events ----

    /**
     * One event's {@code name} and {@code props} (contract §3.1), built only by the typed builders below; immutable.
     * {@link #json} gives the exact event object a batch carries.
     */
    public static final class Event {
        public final String name;
        /** screen.view: its screen; else null (the session's same-screen suppression key). */
        public final Screen screen;
        /** feature.used: its feature; else null (the session's once-per-session key). */
        public final Feature usedFeature;
        private final String propsJson;

        private Event(String name, CustomerJson.Obj props, Screen screen, Feature usedFeature) {
            this.name = name;
            this.propsJson = props.toString();
            this.screen = screen;
            this.usedFeature = usedFeature;
        }

        /** The props object, compact, keys in the registry's order ({@code {}} when there are none). */
        public String propsJson() { return propsJson; }

        /**
         * The event object {@code {"id","name","ts","session_id","seq","props"}} (compact; '/' and non-ASCII
         * unescaped, as the server measures the 2 KiB limit) for the given envelope fields.
         */
        public String json(String id, long ts, String sessionId, int seq) {
            return new CustomerJson.Obj().put("id", id).put("name", name).put("ts", CustomerJson.instant(ts))
                    .put("session_id", sessionId).put("seq", seq).put("props", new CustomerJson.Raw(propsJson)).toString();
        }

        @Override public String toString() { return name + propsJson; }
    }

    private static Event event(String name, CustomerJson.Obj props) { return new Event(name, props, null, null); }

    private static CustomerJson.Obj props() { return new CustomerJson.Obj(); }

    /** {@code screen.view}. */
    public static Event screenView(Screen screen) {
        if (screen == null) throw new IllegalArgumentException("screen");
        return new Event(SCREEN_VIEW, props().put("screen", screen.wire), screen, null);
    }

    /** {@code feature.toggle}: a Zenlygram setting switch changed by the user. */
    public static Event featureToggle(Feature feature, boolean enabled) {
        return event(FEATURE_TOGGLE, props().put("feature", feature.wire).put("enabled", enabled));
    }

    /** {@code feature.used} (the session keeps it once per feature). */
    public static Event featureUsed(Feature feature) {
        return new Event(FEATURE_USED, props().put("feature", feature.wire), null, feature);
    }

    /** {@code tasks.task_created}; {@code priority} clamped to 0..4. */
    public static Event taskCreated(TasksScope scope, boolean hasDue, int priority, boolean inList) {
        return event(TASK_CREATED, props().put("scope", scope.wire).put("has_due", hasDue)
                .put("priority", clamp(priority, 0, PRIORITY_MAX)).put("in_list", inList));
    }

    /** {@code tasks.task_completed}. */
    public static Event taskCompleted(TasksScope scope, boolean reopened) {
        return event(TASK_COMPLETED, props().put("scope", scope.wire).put("reopened", reopened));
    }

    /** {@code tasks.task_deleted}. */
    public static Event taskDeleted(TasksScope scope) { return event(TASK_DELETED, props().put("scope", scope.wire)); }

    /** {@code tasks.list_created}. */
    public static Event listCreated(TasksScope scope) { return event(LIST_CREATED, props().put("scope", scope.wire)); }

    /** {@code tasks.list_deleted}; {@code tasks} clamped to 0..100000. */
    public static Event listDeleted(TasksScope scope, int tasks) {
        return event(LIST_DELETED, props().put("scope", scope.wire).put("tasks", clamp(tasks, 0, AMOUNT_MAX)));
    }

    /** {@code tasks.shared}. */
    public static Event tasksShared(SharedAction action) { return event(TASKS_SHARED, props().put("action", action.wire)); }

    /**
     * {@code tasks.migration}: the optional counts (0..100000) and duration (0..86400 s) are omitted when null; an
     * {@code errorCode} that is not a stable code becomes {@code unknown}.
     */
    public static Event tasksMigration(MigrationPhase phase, Integer lists, Integer tasks, Integer skipped, Long durationS, String errorCode) {
        CustomerJson.Obj props = props().put("phase", phase.wire);
        if (lists != null) props.put("lists", clamp(lists, 0, AMOUNT_MAX));
        if (tasks != null) props.put("tasks", clamp(tasks, 0, AMOUNT_MAX));
        if (skipped != null) props.put("skipped", clamp(skipped, 0, AMOUNT_MAX));
        if (durationS != null) props.put("duration_s", clamp(durationS, 0, DURATION_MAX));
        if (errorCode != null) props.put("error_code", ErrorCodes.normalize(errorCode));
        return event(TASKS_MIGRATION, props);
    }

    /** {@code vault.unlocked}: method {@code biometric}, else {@code password} (never {@code pin}). */
    public static Event vaultUnlocked(boolean biometric) {
        return event(VAULT_UNLOCKED, props().put("method", (biometric ? UnlockMethod.BIOMETRIC : UnlockMethod.PASSWORD).wire));
    }

    /** {@code vault.chat_added}. */
    public static Event vaultChatAdded() { return event(VAULT_CHAT_ADDED, props()); }

    /** {@code vault.chat_removed}. */
    public static Event vaultChatRemoved() { return event(VAULT_CHAT_REMOVED, props()); }

    /** {@code chat_lock.lock_set}: method {@code password}; {@code biometric} = biometric unlock enabled. */
    public static Event chatLockSet(boolean biometricEnabled) {
        return event(CHAT_LOCK_SET, props().put("method", LockMethod.PASSWORD.wire).put("biometric", biometricEnabled));
    }

    /** {@code chat_lock.unlocked}: method {@code biometric}, else {@code password}. */
    public static Event chatLockUnlocked(boolean biometric) {
        return event(CHAT_LOCK_UNLOCKED, props().put("method", (biometric ? UnlockMethod.BIOMETRIC : UnlockMethod.PASSWORD).wire));
    }

    /** {@code chat_lock.lock_removed}. */
    public static Event chatLockRemoved() { return event(CHAT_LOCK_REMOVED, props()); }

    /** {@code edit_history.viewed}: kind {@code deleted} for a kept deleted message, else {@code edited}. */
    public static Event editHistoryViewed(boolean deleted) {
        return event(EDIT_HISTORY_VIEWED, props().put("kind", (deleted ? EditHistoryKind.DELETED : EditHistoryKind.EDITED).wire));
    }

    /** {@code pro.screen_view}. */
    public static Event proScreenView(ProSource source, ProMode mode) {
        return event(PRO_SCREEN_VIEW, props().put("source", source.wire).put("mode", mode.wire));
    }

    /** {@code pro.plan_select}; null (dropped) when {@code productId} is not a {@code product_id}. */
    public static Event proPlanSelect(String productId, Rail rail, String basePlanId) {
        CustomerJson.Obj props = purchase(productId, rail, basePlanId);
        return props == null ? null : event(PRO_PLAN_SELECT, props);
    }

    /** {@code pro.purchase_start}; null when {@code productId} is not a {@code product_id}. */
    public static Event proPurchaseStart(String productId, Rail rail, String basePlanId) {
        CustomerJson.Obj props = purchase(productId, rail, basePlanId);
        return props == null ? null : event(PRO_PURCHASE_START, props);
    }

    /** {@code pro.purchase_success}; null when {@code productId} is not a {@code product_id}. */
    public static Event proPurchaseSuccess(String productId, Rail rail, String basePlanId, boolean trial) {
        CustomerJson.Obj props = purchase(productId, rail, basePlanId);
        return props == null ? null : event(PRO_PURCHASE_SUCCESS, props.put("trial", trial));
    }

    /**
     * {@code pro.purchase_fail}; null when {@code productId} is not a {@code product_id}; {@code errorCode} omitted when
     * null, {@code unknown} when not a stable code.
     */
    public static Event proPurchaseFail(String productId, Rail rail, String basePlanId, FailReason reason, String errorCode) {
        CustomerJson.Obj props = purchase(productId, rail, basePlanId);
        if (props == null) return null;
        props.put("reason", reason.wire);
        if (errorCode != null) props.put("error_code", ErrorCodes.normalize(errorCode));
        return event(PRO_PURCHASE_FAIL, props);
    }

    /** {@code pro.restore}; {@code errorCode} as in {@link #proPurchaseFail}. */
    public static Event proRestore(Rail rail, RestoreOutcome outcome, String errorCode) {
        CustomerJson.Obj props = props().put("rail", rail.wire).put("outcome", outcome.wire);
        if (errorCode != null) props.put("error_code", ErrorCodes.normalize(errorCode));
        return event(PRO_RESTORE, props);
    }

    /** {@code pro.trial_start}; {@code errorCode} as in {@link #proPurchaseFail}. */
    public static Event proTrialStart(TrialOutcome outcome, String errorCode) {
        CustomerJson.Obj props = props().put("outcome", outcome.wire);
        if (errorCode != null) props.put("error_code", ErrorCodes.normalize(errorCode));
        return event(PRO_TRIAL_START, props);
    }

    /**
     * {@code link.attempt} (end of one bind attempt): trigger {@code auto}/{@code manual}, method
     * {@code attest}/{@code code}; {@code errorCode} as in {@link #proPurchaseFail}; {@code durationS} clamped to
     * 0..86400 and omitted when negative (unknown).
     */
    public static Event linkAttempt(boolean auto, boolean attest, boolean success, String errorCode, long durationS) {
        CustomerJson.Obj props = props().put("trigger", (auto ? LinkTrigger.AUTO : LinkTrigger.MANUAL).wire)
                .put("method", (attest ? LinkMethod.ATTEST : LinkMethod.CODE).wire)
                .put("outcome", (success ? LinkOutcome.SUCCESS : LinkOutcome.FAILED).wire);
        if (errorCode != null) props.put("error_code", ErrorCodes.normalize(errorCode));
        if (durationS >= 0) props.put("duration_s", clamp(durationS, 0, DURATION_MAX));
        return event(LINK_ATTEMPT, props);
    }

    // The session's own events (SessionTracker only).

    /** {@code app.session_start}. */
    static Event sessionStart(SessionTrigger trigger) { return event(SESSION_START, props().put("trigger", trigger.wire)); }

    /** {@code app.session_end}; {@code durationS} clamped to 0..86400. */
    static Event sessionEnd(long durationS, SessionEndReason reason) {
        return event(SESSION_END, props().put("duration_s", clamp(durationS, 0, DURATION_MAX)).put("reason", reason.wire));
    }

    /** {@code error.occurred} (a session's aggregate per domain and code); count clamped to 1..1000. */
    static Event errorOccurred(ErrorDomain domain, String code, int count) {
        return event(ERROR_OCCURRED, props().put("domain", domain.wire).put("code", ErrorCodes.normalize(code))
                .put("count", clamp(count, COUNT_MIN, COUNT_MAX)));
    }

    /** {@code tasks.sync_issue} (a session's aggregate per kind); count clamped to 1..1000. */
    static Event syncIssue(SyncIssueKind kind, int count) {
        return event(TASKS_SYNC_ISSUE, props().put("kind", kind.wire).put("count", clamp(count, COUNT_MIN, COUNT_MAX)));
    }

    private static CustomerJson.Obj purchase(String productId, Rail rail, String basePlanId) {
        if (!isProductId(productId)) return null;
        CustomerJson.Obj props = props().put("product_id", productId).put("rail", rail.wire);
        if (isBasePlanId(basePlanId)) props.put("base_plan_id", basePlanId);
        return props;
    }

    /** contract §0.3 {@code product_id}. */
    public static boolean isProductId(String value) { return value != null && PRODUCT_ID.matcher(value).matches(); }

    /** contract §0.3 {@code base_plan_id}. */
    public static boolean isBasePlanId(String value) { return value != null && BASE_PLAN_ID.matcher(value).matches(); }

    static int clamp(long value, int min, int max) { return (int) Math.max(min, Math.min(max, value)); }

    // ---- Recording rules (design 50 §11.1, §11.3) ----

    /**
     * Statistics may be recorded for an account: it is activated, its consent is effectively granted with a latest
     * grant that was not rejected — so no withdrawal, decline, binding loss or {@code consent_stale} since the grant
     * ({@link ConsentSnapshot#recordingAllowed}) — and no account deletion is pending.
     */
    public static boolean recordable(boolean activated, ConsentSnapshot consent, boolean deletionPending) {
        return activated && consent != null && consent.recordingAllowed() && !deletionPending;
    }

    /**
     * The screen a fragment-stack top reports: a Zenlygram screen wherever it sits; a Telegram-native one
     * ({@code tg.*}) only as the root of its stack and never as a picker (forward/share/contact selection), as the
     * consent text promises; null = nothing is reported.
     */
    public static Screen stackScreen(Screen screen, boolean stackRoot, boolean picker) {
        if (screen == null || picker) return null;
        return screen.telegram() && !stackRoot ? null : screen;
    }

    // ---- Envelope (contract §3.1) ----

    /**
     * The batch body: {@code schema_version}, {@code taxonomy_version}, {@code batch_id}, {@code sent_at} (device clock at
     * send), {@code context} (the context the events were recorded under) and the exact event objects, compact.
     */
    public static String envelope(String batchId, long sentAtS, String contextJson, List<String> eventJsons) {
        ArrayList<Object> events = new ArrayList<>(eventJsons.size());
        for (String json : eventJsons) events.add(new CustomerJson.Raw(json));
        return new CustomerJson.Obj().put("schema_version", SCHEMA_VERSION).put("taxonomy_version", TAXONOMY_VERSION)
                .put("batch_id", batchId).put("sent_at", CustomerJson.instant(sentAtS)).put("context", new CustomerJson.Raw(contextJson))
                .put("events", events).toString();
    }

    /** The UTF-8 bytes of an envelope with a context of {@code contextBytes} bytes and no events. */
    public static int envelopeBytes(int contextBytes) { return ENVELOPE_FIXED_BYTES + contextBytes; }

    /** UTF-8 byte length of {@code text}. */
    public static int utf8Length(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }

    // ---- Device context (contract §3.1 context; design 50 §9.1 rules) ----

    /**
     * The context events are recorded under: the nine contract keys in order. Strict values ({@code platform},
     * {@code device_class}, {@code os_version}, {@code app_version}, {@code distribution}) are always valid; a
     * descriptive value that fails its pattern is null (the server would store null anyway, contract §0.2).
     */
    public static final class Context {
        /** contract §2.4.3 {@code distribution} values. */
        public static final List<String> DISTRIBUTIONS = Collections.unmodifiableList(Arrays.asList("appstore", "testflight", "play",
                "direct", "debug"));
        public static final String PHONE = "phone", TABLET = "tablet";

        public final String deviceClass, osVersion, appVersion, appBuild, deviceModel, uiLanguage, timezone, distribution;
        private final String json;
        private final String key;

        private Context(String deviceClass, String osVersion, String appVersion, String appBuild, String deviceModel, String uiLanguage,
                String timezone, String distribution) {
            this.deviceClass = deviceClass;
            this.osVersion = osVersion;
            this.appVersion = appVersion;
            this.appBuild = appBuild;
            this.deviceModel = deviceModel;
            this.uiLanguage = uiLanguage;
            this.timezone = timezone;
            this.distribution = distribution;
            this.json = new CustomerJson.Obj().put("platform", PLATFORM).put("device_class", deviceClass).put("os_version", osVersion)
                    .put("app_version", appVersion).put("app_build", appBuild).put("device_model", deviceModel)
                    .put("ui_language", uiLanguage).put("timezone", timezone).put("distribution", distribution).toString();
            this.key = ConsentText.sha256Hex(json);
        }

        /**
         * The context of raw device facts: {@code os_version} and {@code app_version} in the {@code ver} form
         * ({@link UnicodeText#ver}); {@code app_build} a decimal string; {@code device_model} printable ASCII
         * (whitespace collapsed, at most 64); {@code ui_language} lower-cased with '_' read as '-', kept only in the
         * client form {@code ^[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,2}$}; {@code timezone} an {@code iana_tz};
         * {@code distribution} one of the contract values ({@code direct} for anything else).
         */
        public static Context of(boolean tablet, String osVersion, String appVersion, String appBuild, String deviceModel, String uiLanguage,
                String timezone, String distribution) {
            return new Context(tablet ? TABLET : PHONE, UnicodeText.ver(osVersion), UnicodeText.ver(appVersion), appBuild(appBuild),
                    deviceModel(deviceModel), uiLanguage(uiLanguage), timezone(timezone), distribution(distribution));
        }

        /** The {@code context} object: compact, keys in contract order, null values written as JSON null. */
        public String json() { return json; }

        /** {@code context_key}: lowercase hex SHA-256 of {@link #json}. */
        public String key() { return key; }

        /** A decimal build number, else null. */
        public static String appBuild(String raw) { return raw != null && DECIMAL.matcher(raw).matches() ? raw : null; }

        /** contract §0.3 {@code model}: printable ASCII, whitespace collapsed, 1..64; else null. */
        public static String deviceModel(String raw) { return UnicodeText.printableAscii(raw, 64); }

        /** The client {@code ui_language} form of a language tag ({@code pt_BR} → {@code pt-br}), else null. */
        public static String uiLanguage(String raw) {
            if (raw == null) return null;
            String value = raw.trim().replace('_', '-').toLowerCase(Locale.ROOT);
            return UI_LANGUAGE.matcher(value).matches() ? value : null;
        }

        /** {@code raw} when it is an {@code iana_tz}, else null. */
        public static String timezone(String raw) { return raw != null && IANA_TZ.matcher(raw).matches() ? raw : null; }

        /** {@code raw} when it is a contract distribution value, else {@code direct}. */
        public static String distribution(String raw) { return DISTRIBUTIONS.contains(raw) ? raw : "direct"; }

        @Override public boolean equals(Object other) { return other instanceof Context && ((Context) other).json.equals(json); }

        @Override public int hashCode() { return json.hashCode(); }

        @Override public String toString() { return json; }
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.GooglePlayCheckout;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.zenlygram.cloud.ZgBackend;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Zenlygram Pro: who may use the Pro features right now (iOS SGEntitlement). The server's signed monetization policy
 * decides the mode. FREE (every install's first mode): Pro is free for everyone, every gate answers "allowed" and
 * nothing is sold. PAID (the owner turned it on in the admin panel): a create needs a grant, see below.
 *
 * <p>Pro covers four capabilities ({@link Capability}): Secret Vault, Chat Lock, Tasks and Edit History capture. The
 * AI assistant is refused by this build in every mode. Telegram Premium is a different product: never use
 * UserConfig.isPremium(), PremiumPreviewFragment or the Premium star for Zenlygram Pro.
 *
 * <h3>How a feature checks Pro</h3>
 * Only CREATING new material is ever gated ({@link GatedAction}). Reading, opening, unlocking, unhiding, removing a
 * pincode, editing, completing, deleting and undoing are never gated ({@link UnrestrictedAction}): those paths do not
 * call this class at all. A gate on a read path would lock people out of their own data, the worst bug this module
 * can have.
 * <ul>
 *   <li>A create in the UI (a button, a menu item, a composer): on the UI thread, right before writing,
 *       {@code if (!ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_TASK)) return;}. On false, stop and keep
 *       what the user typed (do not create a lazy default list either). Close an ItemOptions or popup first.</li>
 *   <li>Edit history and kept-deleted capture: {@link #canCaptureRetainedHistory()}, once per update batch, on any
 *       thread, together with the user's own switches (Show Deleted Messages, Save Edit History) and the privacy
 *       exclusions. It is also mirrored to ZgSettings.editHistoryCaptureBlocked (this class is its only writer).</li>
 *   <li>Other code off the UI thread: {@link #can(int, GatedAction)} or {@link #gate(int, GatedAction)}.
 *       Synchronous, thread-safe, never touches the network.</li>
 *   <li>UI that only describes Pro (a status line, a settings value): {@link #getAccess()} and
 *       {@link #isProActive()}. Never use them to gate data the user already has.</li>
 *   <li>The AI assistant: {@link #isAIAssistantAvailable()} is false, so it has no entry point.</li>
 * </ul>
 * The account to pass is the one whose data the create writes, normally {@code fragment.getCurrentAccount()}.
 *
 * <h3>The decision in paid mode</h3>
 * The cloud client ({@code org.telegram.messenger.zenlygram.pro.ZenlyPro}) holds each account's signed entitlement
 * and decides per account (the table is {@link ZgProRules}, JVM-tested): a genuine running grant of the account
 * (bought on Google Play, on iOS or on the web, a trial, a manual grant) allows; otherwise the local grace ("the local
 * week") allows: it runs from the later of the first launch and the first adopted paid policy for the number of days
 * the signed policy of the Android line sets ({@code local_grace_days}, 0 to 30; 0 = no local
 * grace, the create is blocked at once); otherwise the create is blocked. The Vault
 * is per account. Chat Lock, Tasks and Edit History are device-wide in the product (iOS keeps one store per install
 * for them): any signed-in account's grant allows them ({@link Capability#isDeviceGlobal()}). The one exception is a
 * Tasks board that syncs with the server. There the server decides, by the account's own genuine grant: it knows
 * neither the local grace nor the other accounts of this device (Android sends no sponsor proof), so that board asks
 * what the server would accept ({@code ZenlyPro.serverGrants}) and a create it would refuse opens the Pro screen
 * instead of failing later.
 * A build without the authenticated backend cannot verify a grant and stays open ({@link Access#UNLOCKED_BUILD}).
 *
 * <h3>What to show when Pro is not active</h3>
 * {@link GateResult#BLOCKED} opens the Zenlygram Pro screen through {@link ZgProGate}, never an error dialog, a retry
 * loop or "contact support". The Play build sells there through Google Play Billing only; the side-load build sells
 * nothing and says so. {@link GateResult#UNAVAILABLE} shows nothing: there is nothing to sell. A lapse never deletes,
 * hides, unhides or unlocks anything; capture only stops keeping NEW messages, and what was kept stays readable and
 * removable.
 *
 * <h3>Policy and storage</h3>
 * The app's copy of the signed policy is persisted in the private preferences file {@value #PREFS_NAME} (key
 * {@value #KEY_POLICY}, "free" or "paid"); it decides only in a build that is not enforcing and is what the policy
 * line of the Pro screen shows. An enforcing build follows the client's own verified policy (its mode and its local
 * grace length), grants and install record (first launch, first paid policy), which live in the client's encrypted
 * store. No tokens are kept here, and the local grace length is kept nowhere but inside the client's signed token.
 *
 * <h3>Changes</h3>
 * When the decision of any account may have changed (the policy, a grant, the end of the local grace, a link),
 * {@link #didChange} is posted on {@code NotificationCenter.getGlobalInstance()} on the UI thread, without arguments.
 * Observe it with {@code NotificationCenter.getGlobalInstance().listen(view, ZgPro.didChange, args -> refresh())} or
 * addObserver / removeObserver.
 */
public final class ZgPro {

    /**
     * NotificationCenter event of the global instance: the Pro decision changed. Zenlygram's own id, far outside
     * Telegram's sequential ids (observers are kept in a SparseArray keyed by id), so NotificationCenter needs no edit.
     */
    public static final int didChange = 0x5A470001;

    static final String PREFS_NAME = "zenlygram_pro";
    static final String KEY_POLICY = "policy";
    static final String KEY_FIRST_LAUNCH_AT = "first_launch_at";

    /** What the server grants (iOS SGEntitlementCapability). {@link #wire} is the server's capability string. */
    public enum Capability {
        VAULT("vault", false),
        CHAT_LOCK("chatlock", true),
        TASKS("tasks", true),
        /** Capturing NEW kept-deleted copies and edit revisions. What was kept is never gated. */
        EDIT_HISTORY("edithistory", true),
        /** Refused by this build in every mode: it would change what leaves the device (iOS refusedByThisBuild). */
        AI("ai", false);

        public final String wire;
        private final boolean deviceGlobal;

        Capability(String wire, boolean deviceGlobal) {
            this.wire = wire;
            this.deviceGlobal = deviceGlobal;
        }

        /** Device-wide in the product: any signed-in account's grant allows it while Pro is a subscription. */
        public boolean isDeviceGlobal() {
            return deviceGlobal;
        }

        public boolean isRefusedByThisBuild() {
            return this == AI;
        }

        /** The cloud client's name for this capability; null for the refused one. */
        ZenlyPro.Capability client() {
            switch (this) {
                case VAULT:
                    return ZenlyPro.Capability.VAULT;
                case CHAT_LOCK:
                    return ZenlyPro.Capability.CHAT_LOCK;
                case TASKS:
                    return ZenlyPro.Capability.TASKS;
                case EDIT_HISTORY:
                    return ZenlyPro.Capability.EDIT_HISTORY;
                default:
                    return null;
            }
        }
    }

    /** The ONLY gated actions; every one creates new material (iOS SGEntitlementGatedAction). */
    public enum GatedAction {
        /** Adding a NEW chat to the Secret Vault, including the first vault setup. Not restore or recovery. */
        CREATE_VAULT_ENTRY(Capability.VAULT),
        /** Putting a pincode on a chat that has none, and arming the Chat Lock pincode for the first time. */
        CREATE_CHAT_LOCK_PINCODE(Capability.CHAT_LOCK),
        /** A new task or a new list (quick-add composer, New List, Lists +). */
        CREATE_TASK(Capability.TASKS),
        /** Recording a NEW kept-deleted copy or edit revision (use {@link #canCaptureRetainedHistory()}). */
        CREATE_RETAINED_HISTORY_RECORD(Capability.EDIT_HISTORY),
        /** Anything in the AI assistant: always {@link GateResult#UNAVAILABLE} in this build. */
        USE_AI_ASSISTANT(Capability.AI);

        public final Capability capability;

        GatedAction(Capability capability) {
            this.capability = capability;
        }
    }

    /**
     * Actions that must never consult Pro (iOS SGEntitlementUnrestrictedAction). {@link #can(UnrestrictedAction)} is
     * always true. They exist so that a read path finds a name that documents why there is nothing to check. Hiding the
     * previews of locked chats is a privacy setting: it must not read Pro at all. Reading and removing kept history
     * needs no call either.
     */
    public enum UnrestrictedAction {
        OPEN_VAULT,
        LIST_VAULT_ENTRIES,
        READ_VAULTED_CHAT,
        /** Unhiding a chat: a lapse never unhides anything, and the user may always do it. */
        REMOVE_VAULT_ENTRY,
        UNLOCK_CHAT_WITH_PINCODE,
        /** Removing a pincode: a lapse never unlocks anything, and the user may always do it. */
        REMOVE_CHAT_LOCK_PINCODE,
        HIDE_LOCKED_CHAT_PREVIEW,
        READ_TASK,
        EDIT_TASK,
        COMPLETE_TASK,
        DELETE_TASK,
        UNDO_TASK_DELETE
    }

    public enum GateResult {
        /** Go ahead. */
        ALLOWED,
        /** Pro is not active: show the Zenlygram Pro screen (ZgProGate does) and keep the user's input. */
        BLOCKED,
        /** The capability is not offered at all (the AI assistant): no entry point, no paywall. */
        UNAVAILABLE
    }

    /** The monetization policy (iOS SGMonetizationMode). Every install starts FREE. */
    public enum Policy {
        FREE("free"),
        PAID("paid");

        /** Value stored under {@value #KEY_POLICY}. */
        public final String wire;

        Policy(String wire) {
            this.wire = wire;
        }

        /** Unknown or missing values are FREE: an unreadable flag never takes rights away. */
        static Policy fromWire(String value) {
            return PAID.wire.equals(value) ? PAID : FREE;
        }
    }

    /**
     * What Pro looks like for one account (iOS SGProStatusAccess). Every value names access, never a payment.
     */
    public enum Access {
        /** Policy FREE: the Pro features are open for everyone and nothing is sold. */
        FREE_ACCESS(true),
        /** A build that cannot verify grants (or has nothing configured): everything stays open, nothing is sold. */
        UNLOCKED_BUILD(true),
        /** Policy PAID, a genuine running grant of this account. */
        ACTIVE(true),
        /** Policy PAID, nothing granting yet, inside the local grace: "Free until ...". Never with a grace of 0 days. */
        GRACE(true),
        /** Policy PAID, this account never had a grant (or is not linked): the offer, never "ended". */
        INACTIVE(false),
        /** Policy PAID, this account's grant ran out: "Subscription ended". */
        LAPSED(false),
        /** Policy PAID, the saved grant cannot be trusted until the device checks in: "reconnect". */
        RECONNECT(false);

        /** Whether the Pro features may be used. */
        public final boolean active;

        Access(boolean active) {
            this.active = active;
        }
    }

    /** How long one capture decision is reused; a change of the client's decision drops it at once. */
    private static final long CAPTURE_MEMO_MS = 15_000;

    private static final Object lock = new Object();
    /** The app's copy of the signed policy; null until read from {@value #PREFS_NAME}. */
    private static volatile Policy policy;
    private static volatile SharedPreferences preferences;
    /** elapsedRealtime of the last capture decision (0: none) and the decision. */
    private static volatile long captureCheckedAt;
    private static volatile boolean captureAllowed = true;
    private static boolean attached;

    private ZgPro() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Gates
    // ---------------------------------------------------------------------------------------------------------

    /** Is this create allowed right now? Thread-safe and synchronous. On the UI thread use ZgProGate.check. */
    public static boolean can(int account, GatedAction action) {
        return gate(account, action) == GateResult.ALLOWED;
    }

    /** Always true, by contract: reads, unlocks, unhides, edits and deletes of existing data are never gated. */
    public static boolean can(UnrestrictedAction action) {
        return true;
    }

    /** {@link #can(int, GatedAction)} with the reason, for the UI that answers a "no". */
    public static GateResult gate(int account, GatedAction action) {
        if (action.capability.isRefusedByThisBuild()) {
            return GateResult.UNAVAILABLE;
        }
        final Access preview = previewAccess();
        if (preview != null) {
            return preview.active ? GateResult.ALLOWED : GateResult.BLOCKED;
        }
        if (!isEnforcing()) {
            return GateResult.ALLOWED; // a build that cannot verify a grant never blocks
        }
        if (action == GatedAction.CREATE_TASK && tasksSyncWithServer(account)) {
            // The server accepts this create or refuses it, by this account's own genuine grant: neither the local
            // grace nor another signed-in account counts there.
            return serverAccepts(account, action.capability) ? GateResult.ALLOWED : GateResult.BLOCKED;
        }
        return allows(account, action.capability, action.capability.isDeviceGlobal()) ? GateResult.ALLOWED : GateResult.BLOCKED;
    }

    /**
     * Whether {@code account}'s creates of a device-wide capability are open only because ANOTHER signed-in account
     * has a grant (the Pro screen says so: the account's own line reads "not active" while those features work).
     */
    public static boolean isCoveredByAnotherAccount(int account) {
        if (previewAccess() != null || !isEnforcing()) {
            return false;
        }
        final int own = slot(account);
        return !slotGrants(own, Capability.CHAT_LOCK) && allows(own, Capability.CHAT_LOCK, true);
    }

    /** Whether new Tasks of {@code account} are created on the server (its board syncs), where the server's rule applies. */
    public static boolean tasksAskTheServer(int account) {
        return previewAccess() == null && isEnforcing() && tasksSyncWithServer(account);
    }

    /**
     * Whether NEW deleted messages may be kept and NEW edit revisions recorded. Read it once per update batch, on any
     * thread; the user's own switches and the privacy exclusions still decide first. True while Pro is free. Already
     * kept history is never affected.
     */
    public static boolean canCaptureRetainedHistory() {
        final Access preview = previewAccess();
        if (preview != null) {
            return preview.active;
        }
        if (!isEnforcing()) {
            return true;
        }
        final long now = SystemClock.elapsedRealtime();
        final long checkedAt = captureCheckedAt;
        if (checkedAt != 0 && now >= checkedAt && now - checkedAt < CAPTURE_MEMO_MS) {
            return captureAllowed;
        }
        final boolean allowed = allows(UserConfig.selectedAccount, Capability.EDIT_HISTORY, true);
        captureAllowed = allowed;
        captureCheckedAt = now == 0 ? 1 : now;
        mirrorCaptureFlag(allowed);
        return allowed;
    }

    /** False in this build (AI is refused in every mode): the assistant has no entry point and no paywall. */
    public static boolean isAIAssistantAvailable() {
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // State, for UI that describes Pro
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether this build verifies signed grants and therefore enforces a paid policy (iOS SGEntitlementEnforcement):
     * the authenticated backend with its pinned keys is on. False keeps everything open whatever the policy says.
     */
    public static boolean isEnforcing() {
        return ZgBackend.enabled();
    }

    /** The access of the selected account. Synchronous, thread-safe, no network. */
    public static Access getAccess() {
        return getAccess(UserConfig.selectedAccount);
    }

    /** The access of {@code account} (a slot index). An unreadable decision is open: free-first, it never blocks. */
    public static Access getAccess(int account) {
        final Access preview = previewAccess();
        if (preview != null) {
            return preview;
        }
        if (!isEnforcing()) {
            return ZgProRules.access(false, getPolicy() == Policy.PAID, null, false);
        }
        try {
            final ZenlyPro.Snapshot snapshot = ZenlyPro.get(slot(account)).snapshot();
            return ZgProRules.access(true, false, ZgProRules.Entitlement.of(snapshot.status.name()), snapshot.lapsed);
        } catch (Throwable e) {
            FileLog.e(e);
            return Access.UNLOCKED_BUILD;
        }
    }

    /**
     * The date the selected account's access line names, in Unix seconds: the end of the local grace
     * ({@link Access#GRACE}; the start plus the signed policy's length) or of the account's genuine paid period; 0
     * when there is none to show.
     */
    public static long getAccessUntil(int account) {
        final Access preview = previewAccess();
        if (preview != null) {
            return preview.active ? System.currentTimeMillis() / 1000L + 5 * 86400L : 0;
        }
        if (!isEnforcing()) {
            return 0;
        }
        try {
            return ZenlyPro.get(slot(account)).snapshot().untilEpochSeconds;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Whether the Pro features are usable by the selected account right now. */
    public static boolean isProActive() {
        return getAccess().active;
    }

    /** The app's copy of the signed monetization policy (FREE until a verified PAID one was stored). */
    public static Policy getPolicy() {
        Policy current = policy;
        if (current != null) {
            return current;
        }
        final SharedPreferences prefs = getPreferences();
        if (prefs == null) {
            return Policy.FREE; // no Context yet (cannot happen after Application.onCreate): stay open
        }
        synchronized (lock) {
            current = policy;
            if (current == null) {
                current = readPolicy(prefs);
                policy = current;
            }
        }
        return current;
    }

    /** Pro is free for everyone right now (policy FREE): no purchase UI, no countdown. */
    public static boolean isFreeForEveryone() {
        return getAccess() == Access.FREE_ACCESS;
    }

    /** The verified policy is paid and this build enforces it: Pro is a subscription right now. */
    public static boolean isPaidMode() {
        if (previewAccess() != null) {
            return true;
        }
        if (!isEnforcing()) {
            return false;
        }
        try {
            return ZenlyPro.get(slot(UserConfig.selectedAccount)).snapshot().purchasesAvailable;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Whether this build may sell (prices, plans, Subscribe): only the Google Play build, through Play Billing, and
     * only while Pro is a subscription. The side-load build sells nothing; it uses a grant the account already has.
     */
    public static boolean offersPurchases() {
        return isPaidMode() && isPlayBuild();
    }

    /**
     * Whether the Pro screen sends a buyer to the website's checkout ({@link ZgProRules#sellsOnWebsite}): the
     * side-load build while Pro is a subscription. Never the Google Play build.
     */
    public static boolean offersWebPurchase() {
        return ZgProRules.sellsOnWebsite(isPaidMode(), isPlayBuild());
    }

    /** This APK is the Google Play build (ZgBilling): the only one with Play Billing controls. */
    public static boolean isPlayBuild() {
        try {
            return GooglePlayCheckout.playBuild();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * A second "Telegram Account" row among the Pro rows of the hub. Never: the link is an identity step and has its
     * one row in the Account &amp; data block ({@link ZgLinkAccountActivity}); the Pro screen has the same row.
     */
    public static boolean offersAccountLinking() {
        return false;
    }

    /** The hub's "Restore Purchases" row: the Play build while Pro is a subscription. It opens the Pro screen's restore. */
    public static boolean offersRestorePurchases() {
        return offersPurchases();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Preview (debug builds)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Debug builds only (Settings &gt; Zenlygram &gt; Developer &gt; Preview Pro state): the access to act on instead of the
     * real one, as if the signed policy were paid, so the paid screens and the create gate can be seen before the owner
     * turns paid mode on. Null in a release build and while the preview is off; nothing here reaches the client or
     * the server.
     */
    private static Access previewAccess() {
        if (!BuildVars.DEBUG_VERSION) {
            return null;
        }
        try {
            switch (ZgSettings.proPreview()) {
                case ZgSettings.ProPreview.GRACE:
                    return Access.GRACE;
                case ZgSettings.ProPreview.INACTIVE:
                    return Access.INACTIVE;
                case ZgSettings.ProPreview.LAPSED:
                    return Access.LAPSED;
                case ZgSettings.ProPreview.RECONNECT:
                    return Access.RECONNECT;
                case ZgSettings.ProPreview.ACTIVE:
                    return Access.ACTIVE;
                default:
                    return null;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean previewNoticeOwed;

    /** The Developer row changed the preview: every observer re-reads Pro, and the one-time notice is owed again. */
    public static void onPreviewChanged() {
        previewNoticeOwed = previewAccess() != null;
        announceChange();
    }

    /**
     * The one-time "Zenlygram Pro is now a subscription" notice for a create that was just blocked (ZgProGate): once
     * per free to paid change, and only for an account that never had a grant (ZgProRules.announcesPaidMode).
     */
    static boolean takePaidModeNotice(int account) {
        try {
            final Access preview = previewAccess();
            if (preview != null) {
                final boolean owed = previewNoticeOwed && ZgProRules.announcesPaidMode(preview);
                previewNoticeOwed = previewNoticeOwed && !owed;
                return owed;
            }
            return isEnforcing() && ZgProRules.announcesPaidMode(getAccess(account)) && ZenlyPro.takePaidModeNotice();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // The cloud client's decision
    // ---------------------------------------------------------------------------------------------------------

    /**
     * ZgBackend.onPostInit, UI thread: follows the client's per-account access and status events (a grant adopted
     * or ended, the policy changed, the local grace ended or got another length, a link made or lost) and announces
     * {@link #didChange}.
     */
    public static void attachToClient() {
        if (attached || !isEnforcing()) {
            return;
        }
        attached = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final NotificationCenter center = NotificationCenter.getInstance(a);
            center.addObserver(CLIENT_OBSERVER, NotificationCenter.zenlygramAccessChanged);
            center.addObserver(CLIENT_OBSERVER, NotificationCenter.zenlygramProStatusChanged);
        }
        // The device Tasks board is one per install: its store asks this gate, the same one the UI asked.
        org.telegram.messenger.zenlygram.tasks.ZenlyTasksStore.setCreateGate(account -> can(account, GatedAction.CREATE_TASK));
        canCaptureRetainedHistory(); // the capture flag of this launch
    }

    private static final NotificationCenter.NotificationCenterDelegate CLIENT_OBSERVER = (id, account, args) -> announceChange();

    /** One announcement per burst (the client posts its access and status events together). */
    private static final Runnable ANNOUNCE = () -> {
        captureCheckedAt = 0;
        canCaptureRetainedHistory();
        NotificationCenter.getGlobalInstance().postNotificationName(didChange);
    };

    /** Any thread. */
    private static void announceChange() {
        captureCheckedAt = 0;
        AndroidUtilities.cancelRunOnUIThread(ANNOUNCE);
        AndroidUtilities.runOnUIThread(ANNOUNCE);
    }

    private static int slot(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT ? account : UserConfig.selectedAccount;
    }

    /** {@link ZgProRules#allowed} over the signed-in slots; the asking account is read first (the common "yes"). */
    private static boolean allows(int account, Capability capability, boolean deviceGlobal) {
        final int own = slot(account);
        if (slotGrants(own, capability)) {
            return true;
        }
        if (!deviceGlobal) {
            return false;
        }
        final boolean[] grants = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
        for (int a = 0; a < grants.length; a++) {
            grants[a] = a != own && slotGrants(a, capability);
        }
        return ZgProRules.allowed(grants, own, true);
    }

    /** A signed-in slot whose decision grants the capability. An unreadable decision grants: it never blocks. */
    private static boolean slotGrants(int account, Capability capability) {
        try {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return false;
            }
            final ZenlyPro pro = ZenlyPro.get(account);
            return pro.canCreate(capability.client()) || pro.status() == ZenlyPro.Status.UNCONFIGURED;
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    /**
     * What the server would accept from this account (the client's ProPolicy.serverGrants): a free policy or the
     * account's own genuine grant. An unreadable decision accepts: it never blocks.
     */
    private static boolean serverAccepts(int account, Capability capability) {
        try {
            final ZenlyPro pro = ZenlyPro.get(slot(account));
            return pro.serverGrants(capability.client()) || pro.status() == ZenlyPro.Status.UNCONFIGURED;
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    /** This account's Tasks may sync with the server (data consent and link in place): its creates are the server's to accept. */
    private static boolean tasksSyncWithServer(int account) {
        try {
            return TasksCloudGate.permission(slot(account)) == TasksCloudGate.Permission.ALLOWED;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Policy
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The verified signed monetization policy ({@code GET /v1/monetization}, ES256 against the pinned ring) of
     * org.telegram.zenlygram.cloud.ZgCloudPolicies. The only caller; never a timer, build flag or network failure. A
     * missing or unverifiable policy is FREE. An enforcing build decides by the client's own verified copy of the same
     * policy, so a change here also asks the client to fetch it (behind its own one-a-minute gate).
     */
    public static void applySignedPolicy(boolean paid) {
        final Policy wanted = paid ? Policy.PAID : Policy.FREE;
        if (getPolicy() != wanted) {
            setPolicy(wanted);
        }
    }

    /**
     * ZgCloudPolicies adopted another REVISION of the signed policy: the owner changed something, and it may be
     * only the local grace length or the trial, which {@link #applySignedPolicy} does not see. The client's own
     * verified copy is what decides (mode and local grace length), so it is asked to fetch the policy too, behind its
     * own one-a-minute gate; what it accepts applies at once and reaches this class through the client's events.
     * Nothing of the app's copy is handed over: the client takes the length from its own signed token only.
     */
    public static void onSignedPolicyRevised() {
        if (!isEnforcing()) {
            return;
        }
        try {
            ZenlyPro.refreshPolicy();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Stores the app's copy of the monetization policy and announces the change. */
    static void setPolicy(Policy newPolicy) {
        final SharedPreferences prefs = getPreferences();
        if (newPolicy == null || prefs == null) {
            return;
        }
        final Policy before = getPolicy();
        synchronized (lock) {
            prefs.edit().putString(KEY_POLICY, newPolicy.wire).apply();
            policy = newPolicy;
        }
        if (newPolicy != before) {
            if (isEnforcing()) {
                try {
                    ZenlyPro.refreshPolicy();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            announceChange();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------------------------

    private static SharedPreferences getPreferences() {
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            preferences = prefs;
        }
        return prefs;
    }

    /**
     * Reads the stored policy; on the very first run stores FREE and the first-launch time (kept from the free phase;
     * the paid week is counted from the client's own install record, not from this value).
     */
    private static Policy readPolicy(SharedPreferences prefs) {
        String stored;
        try {
            stored = prefs.getString(KEY_POLICY, null);
        } catch (ClassCastException e) {
            stored = null;
        }
        SharedPreferences.Editor editor = null;
        if (stored == null) {
            editor = prefs.edit().putString(KEY_POLICY, Policy.FREE.wire);
        }
        if (!prefs.contains(KEY_FIRST_LAUNCH_AT)) {
            editor = (editor != null ? editor : prefs.edit()).putLong(KEY_FIRST_LAUNCH_AT, System.currentTimeMillis());
        }
        if (editor != null) {
            editor.apply();
        }
        return Policy.fromWire(stored);
    }

    /**
     * Keeps ZgSettings.editHistoryCaptureBlocked equal to the capture decision (iOS SGSimpleSettings flag of the same
     * name). This class is its only writer; it writes only when the value differs.
     */
    private static void mirrorCaptureFlag(boolean captureAllowed) {
        final boolean blocked = !captureAllowed;
        try {
            if (ZgSettings.editHistoryCaptureBlocked.get() != blocked) {
                ZgSettings.editHistoryCaptureBlocked.set(blocked);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}

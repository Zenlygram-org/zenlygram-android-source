/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.messenger.zenlygram.pro.ProAutoLink;
import org.telegram.messenger.zenlygram.pro.ProTelegramAttest;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.cloud.ZgBackend;
import org.telegram.zenlygram.settings.ZgSettingRowCell;
import org.telegram.zenlygram.settings.ZgSettingsIcons.Tile;

import java.util.ArrayList;
import java.util.Random;

/**
 * "Link Telegram Account" (iOS SGPayWallLinkAccount; Settings &gt; Zenlygram &gt; Account &amp; data &gt; Telegram Account,
 * the Zenlygram Pro screen, the Cloud Tasks board): the link between this device and the Telegram account the screen was
 * opened for, which is what Cloud Tasks and the data consent follow from one device to the next. It is an identity step
 * and never a purchase: nothing here carries a price, a plan or a call to buy.
 *
 * <p>The app knows the account's Telegram id but cannot prove it, so the proof comes from Telegram. First silently: the
 * app, signed in as this account, asks Telegram for a Mini App launch of the bind bot and hands the data Telegram signed
 * to the server (no code, no bot chat; only for an account whose data consent allows it, and the screen says what is
 * asked while it is). When that is not possible it falls back to the code: the server issues an 8-character code, the
 * user confirms it in the bot, opened inside this app as this account, and the screen polls for the session.
 *
 * <p>What the screen shows, as on iOS:
 * <ul>
 *   <li>linking: "Linking…", with the disclosure while the silent confirmation may run;</li>
 *   <li>the code: the code (a tap copies it), "Expires in m:ss", "Open Telegram" (or the bot to send the code to),
 *       "Waiting for confirmation…", "No connection." while offline, and the warning;</li>
 *   <li>failed: why, and "Get a New Code";</li>
 *   <li>linked: "Telegram account linked", "Unlink This Device" and what unlinking does;</li>
 *   <li>not linked: after Unlink nothing starts by itself, not even here; the link is the user's own tap.</li>
 * </ul>
 * Opening the screen for an account that is not linked starts linking at once (iOS: the idle phase begins).
 *
 * <p>Three parts: {@link ZgLinkFlow} decides (which phase, which attempt is current, when to poll; no Android type,
 * JVM-tested); the client ({@link ZenlyPro}) does every request and keeps the session; this class connects the two
 * ({@link #host}) and draws the phase. An answer is dropped once the screen is gone, the account is no longer the
 * selected one, or a newer attempt started. While the screen is hidden or the app is in the background it does not
 * poll (a start or a confirmation already on its way finishes); the code stays and the poll that was due goes out when
 * the screen is back. The automatic link of this account does not start while the screen is alive. Main thread only.
 * Nothing here logs.
 */
public class ZgLinkAccountActivity extends UniversalFragment implements ZenlyStats.NamedScreen, NotificationCenter.NotificationCenterDelegate {

    private static final int ID_INTRO = 1;
    private static final int ID_LINKING = 2;
    private static final int ID_DISCLOSURE = 3;
    private static final int ID_CODE_HEADER = 4;
    private static final int ID_CODE = 5;
    private static final int ID_OPEN_TELEGRAM = 6;
    private static final int ID_CODE_HINT = 7;
    private static final int ID_WAITING = 8;
    private static final int ID_WARNING = 9;
    private static final int ID_FAILURE = 10;
    private static final int ID_NEW_CODE = 11;
    private static final int ID_LINKED = 12;
    private static final int ID_LINKED_NOTICE = 13;
    private static final int ID_UNLINK = 14;
    private static final int ID_UNLINK_TEXT = 15;
    private static final int ID_NOT_LINKED = 16;
    private static final int ID_LINK = 17;

    /**
     * The @username of the bot the last bind offered for the silent confirmation (any account, this process); before
     * any bind answered, the configured one. It is what the disclosure and the "send this code to" line name until the
     * current bind brings its own.
     */
    private static volatile String lastBot = org.telegram.zenlygram.cloud.ZgCloudConfig.BIND_BOT_USERNAME;

    private ZenlyPro pro;
    /** The Telegram user the screen was opened for; the slot may be reused by another one. */
    private long accountUserId;
    private ZgLinkFlow<ZenlyPro.LinkRequest> flow;
    /** Opened by {@link #forTelegramProof}. */
    private boolean freshProof;
    private boolean destroyed;
    /** The phase the list last showed: a change into linked or failed is announced once. */
    private ZgLinkFlow.Phase shownPhase;
    private final Random random = new Random();
    private final Runnable pollTimer = () -> flow.timer(ZgLinkFlow.Timer.POLL);
    private final Runnable tickTimer = () -> flow.timer(ZgLinkFlow.Timer.TICK);
    private final Runnable attestTimer = () -> flow.timer(ZgLinkFlow.Timer.ATTEST_TIMEOUT);

    public ZgLinkAccountActivity() {
        super();
    }

    /**
     * The screen in proof mode (iOS requiresTelegramProof): it starts a fresh link even when a session is stored, and
     * keeps that session until the new confirmation replaces it. For a caller to whom a stored session is not enough,
     * i.e. Cloud Tasks after the server answered that the account is not linked (403 tasks_account_not_linked). Every
     * other caller uses the plain constructor, which opens a linked account as linked.
     */
    public static ZgLinkAccountActivity forTelegramProof() {
        final ZgLinkAccountActivity screen = new ZgLinkAccountActivity();
        screen.freshProof = true;
        return screen;
    }

    /** Whether {@code account} is linked on this device: the client's stored session, read locally, never the network. */
    public static boolean isLinked(int account) {
        if (!ZgBackend.enabled() || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        try {
            return ZenlyPro.get(account).snapshot().linked;
        } catch (RuntimeException e) {
            // A settings row never fails over the link state (and nothing about the link is logged).
            return false;
        }
    }

    /** "Linked" / "Not linked", the value of the Telegram Account rows. */
    public static CharSequence stateLabel(int account) {
        return getString(isLinked(account) ? R.string.ZG_Settings_Pro_LinkAccount_Linked : R.string.ZG_Settings_Pro_LinkAccount_NotLinked);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public boolean onFragmentCreate() {
        pro = ZenlyPro.get(currentAccount);
        accountUserId = getUserConfig().getClientUserId();
        // A synchronous local read: opening this screen never waits for the network.
        flow = new ZgLinkFlow<>(host, pro.snapshot().linked, freshProof);
        shownPhase = flow.phase();
        getNotificationCenter().addObserver(this, NotificationCenter.zenlygramProStatusChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        // No automatic link of this account while the user links (or unlinks) here.
        ProAutoLink.setProScreenAlive(currentAccount, true);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        getNotificationCenter().removeObserver(this, NotificationCenter.zenlygramProStatusChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        // The attempt ends with the screen: the client forgets its bind, and the server's code expires by itself.
        flow.destroyed();
        ProAutoLink.setProScreenAlive(currentAccount, false);
        super.onFragmentDestroy();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return fragmentView;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Telegram's notice cells keep the width they were bound with: rebuild and rebind once the new screen size is
        // known (LaunchActivity updates it in its own onConfigurationChanged, after this one may run).
        AndroidUtilities.runOnUIThread(() -> {
            if (listView != null && !destroyed) {
                listView.adapter.update(false);
            }
        });
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_LinkAccount_Title);
    }

    /** Statistics: Android's taxonomy has no screen of its own for the link; it is part of the Pro screen there. */
    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.ZG_PRO;
    }

    @Override
    public void onResume() {
        super.onResume();
        flow.shown();
    }

    @Override
    public void onPause() {
        flow.hidden();
        super.onPause();
    }

    /**
     * zenlygramProStatusChanged (this account's instance): anything the Pro status shows changed, e.g. an automatic
     * link finished or the server ended the session. activeAccountChanged (global): another account was selected.
     */
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            flow.accountChanged();
        } else if (id == NotificationCenter.zenlygramProStatusChanged && account == currentAccount) {
            flow.statusChanged();
        }
    }

    /** The screen acts only for the account it was opened for, while that account is the selected one. */
    private boolean alive() {
        return !destroyed && pro != null && accountUserId != 0 && UserConfig.selectedAccount == currentAccount
            && accountUserId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    // ---------------------------------------------------------------------------------------------------------
    // The flow's host: the client, the timers and the list
    // ---------------------------------------------------------------------------------------------------------

    private final ZgLinkFlow.Host<ZenlyPro.LinkRequest> host = new ZgLinkFlow.Host<ZenlyPro.LinkRequest>() {
        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public double random() {
            return random.nextDouble();
        }

        @Override
        public boolean alive() {
            return ZgLinkAccountActivity.this.alive();
        }

        @Override
        public boolean linked() {
            return pro.snapshot().linked;
        }

        /** Whether anything may be sent for this account, and how (the iOS bind permission). */
        @Override
        public ZgLinkRules.Permission permission() {
            return ZgLinkRules.permission(ZgBackend.enabled(), ZenlyCustomer.deletionPending(accountUserId),
                ZenlyCustomer.hasEffectiveGrant(accountUserId), ZenlyConsent.mode() == ConsentMachine.Mode.REQUIRED);
        }

        @Override
        public boolean mayAttest() {
            return ZenlyCustomer.hasEffectiveGrant(accountUserId);
        }

        @Override
        public void start(final int attempt) {
            pro.beginLink(new ZenlyPro.Callback<ZenlyPro.LinkRequest>() {
                @Override
                public void onSuccess(ZenlyPro.LinkRequest bind) {
                    if (bind.attest != null) {
                        lastBot = bind.attest.botUsername;
                    }
                    flow.started(attempt, bind, bind.attest != null,
                        ZgLinkRules.lifetimeMs(bind.expiresAtEpochSeconds, ZenlyPro.trustedNowSeconds()));
                }

                @Override
                public void onError(ZenlyPro.Error error) {
                    flow.startFailed(attempt, error.code, error.httpStatus);
                }
            });
        }

        /**
         * The silent confirmation (bind-attest): this account asks Telegram for the Mini App data of the offered bot,
         * signed by Telegram (no web view is opened and nothing is sent to the bot), and the server attaches it to
         * this bind. The data goes from Telegram's answer straight to the client and is kept nowhere.
         */
        @Override
        public void obtainAttestation(final int attempt, final ZenlyPro.LinkRequest bind) {
            ProTelegramAttest.obtain(currentAccount, bind.attest, initData -> {
                if (!flow.attestationObtained(attempt, bind, initData != null)) {
                    return;
                }
                pro.attestLink(bind, initData, new ZenlyPro.Callback<ZenlyPro.LinkRequest>() {
                    @Override
                    public void onSuccess(ZenlyPro.LinkRequest confirmed) {
                        flow.attested(attempt, bind);
                    }

                    @Override
                    public void onError(ZenlyPro.Error error) {
                        flow.attestFailed(attempt, bind);
                    }
                });
            });
        }

        @Override
        public void poll(final int attempt, final ZenlyPro.LinkRequest bind) {
            pro.pollLink(bind, new ZenlyPro.Callback<ZenlyPro.LinkResult>() {
                @Override
                public void onSuccess(ZenlyPro.LinkResult result) {
                    flow.polled(attempt, bind, result.state.name(), result.mergeConflict);
                }

                @Override
                public void onError(ZenlyPro.Error error) {
                    flow.pollFailed(attempt, bind, error.code, error.httpStatus, error.retryAfterSeconds);
                }
            });
        }

        @Override
        public void cancel() {
            pro.cancelLink();
        }

        @Override
        public void setTimer(ZgLinkFlow.Timer timer, long delayMs) {
            final Runnable runnable = runnable(timer);
            AndroidUtilities.cancelRunOnUIThread(runnable);
            AndroidUtilities.runOnUIThread(runnable, delayMs);
        }

        @Override
        public void clearTimer(ZgLinkFlow.Timer timer) {
            AndroidUtilities.cancelRunOnUIThread(runnable(timer));
        }

        /** link.attempt{trigger: manual}, recorded where the import's Pro screen records it. */
        @Override
        public void attemptEnded(boolean attested, boolean success, String errorCode, long durationSeconds) {
            ZenlyStats.linkAttempt(currentAccount, false, attested, success, errorCode, durationSeconds);
        }

        @Override
        public void changed() {
            render();
        }

        @Override
        public void countdown() {
            updateCountdown();
        }

        /** Another account is selected (or this slot holds another user): the screen closes (iOS dismisses). */
        @Override
        public void close() {
            // Not from inside onResume or a timer of the layout that is being walked: on the next turn.
            AndroidUtilities.runOnUIThread(() -> {
                if (!isFinished) {
                    finishFragment();
                }
            });
        }
    };

    private Runnable runnable(ZgLinkFlow.Timer timer) {
        switch (timer) {
            case POLL:
                return pollTimer;
            case TICK:
                return tickTimer;
            default:
                return attestTimer;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------------------------------------------

    private void render() {
        if (listView != null) {
            listView.adapter.update(true);
        }
        final ZgLinkFlow.Phase phase = flow.phase();
        if (phase != shownPhase) {
            shownPhase = phase;
            if (phase == ZgLinkFlow.Phase.LINKED) {
                AndroidUtilities.makeAccessibilityAnnouncement(getString(R.string.ZG_LinkAccount_Success_Title));
            } else if (phase == ZgLinkFlow.Phase.FAILED) {
                AndroidUtilities.makeAccessibilityAnnouncement(messageText(flow.failure()));
            }
        }
    }

    /** "Expires in 4:32", or null once the time is up. */
    private CharSequence expiresText() {
        final long seconds = flow.remainingSeconds();
        return seconds > 0 ? formatString(R.string.ZG_LinkAccount_Expires, ZgLinkRules.remaining(seconds)) : null;
    }

    /**
     * Written into the row itself: a list update every second would rebind the code row. The row's list item gets the
     * text too, so a row that is bound again later (scrolled back in) starts from the current time.
     */
    private void updateCountdown() {
        if (listView == null || flow.phase() != ZgLinkFlow.Phase.WAITING) {
            return;
        }
        final CharSequence text = expiresText();
        final UItem item = listView.adapter.findItem(ID_CODE);
        if (item != null) {
            item.subtext = text;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof ZgLinkCells.CodeCell) {
                ((ZgLinkCells.CodeCell) child).setExpires(text);
            }
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (flow == null) {
            return;
        }
        final ZgLinkFlow.Phase phase = flow.phase();
        final ZenlyPro.LinkRequest bind = flow.bind();
        if (phase == ZgLinkFlow.Phase.LINKED) {
            items.add(ZgSettingRowCell.Factory.asInfo(ID_LINKED, R.drawable.msg_select, Tile.GREEN.top, Tile.GREEN.bottom,
                getString(R.string.ZG_LinkAccount_Success_Title), getString(R.string.ZGL_LinkAccount_Success_Text), null, false));
            // The link succeeded; this notice never stands in its way.
            items.add(UItem.asShadow(ID_LINKED_NOTICE, flow.mergeConflict() ? getString(R.string.ZG_Entitlement_Message_LinkingMergeConflict) : null));
            items.add(UItem.asButton(ID_UNLINK, R.drawable.msg_leave, getString(R.string.ZG_LinkAccount_Unlink)).red());
            items.add(UItem.asShadow(ID_UNLINK_TEXT, getString(R.string.ZGL_LinkAccount_Unlink_Text)));
            return;
        }
        items.add(UItem.asShadow(ID_INTRO, getString(R.string.ZGL_LinkAccount_Intro)));
        if (phase == ZgLinkFlow.Phase.WAITING && bind != null) {
            items.add(UItem.asHeader(ID_CODE_HEADER, getString(R.string.ZG_Header_LinkAccount_Step_Code)));
            items.add(ZgLinkCells.CodeCell.Factory.of(ID_CODE, bind.code, flow.copied(), expiresText()));
            if (ZgLinkRules.botDeepLink(bind.deepLink) != null) {
                items.add(ZgLinkCells.ButtonCell.Factory.of(ID_OPEN_TELEGRAM, getString(R.string.ZG_Common_OpenTelegram), v -> openTelegram()));
            } else {
                // No link to open: the code and the bot's name are all it takes.
                final String bot = botName(bind);
                items.add(UItem.asShadow(ID_CODE_HINT, bot != null ? formatString(R.string.ZG_LinkAccount_Code_Hint, bot)
                    : getString(R.string.ZGL_LinkAccount_Code_Hint_NoBot)));
            }
            // Still waiting and, when the connection drops, why: the code stays valid, so the hint is added under it.
            items.add(ZgLinkCells.ProgressCell.Factory.of(ID_WAITING, getString(R.string.ZG_LinkAccount_Waiting)));
            final String warning = getString(R.string.ZG_LinkAccount_Warning);
            items.add(UItem.asShadow(ID_WARNING, flow.offline() ? getString(R.string.ZG_Entitlement_Message_NoConnection) + "\n\n" + warning : warning));
        } else if (phase == ZgLinkFlow.Phase.FAILED) {
            items.add(ZgSettingRowCell.Factory.asInfo(ID_FAILURE, R.drawable.msg_warning, Tile.ORANGE.top, Tile.ORANGE.bottom,
                messageText(flow.failure()), null, null, false));
            items.add(ZgLinkCells.ButtonCell.Factory.of(ID_NEW_CODE, getString(R.string.ZG_LinkAccount_GetNewCode), v -> flow.retry()));
        } else if (phase == ZgLinkFlow.Phase.UNLINKED) {
            items.add(ZgSettingRowCell.Factory.asInfo(ID_NOT_LINKED, R.drawable.msg_contact, Tile.BLUE.top, Tile.BLUE.bottom,
                getString(R.string.ZG_Settings_Pro_LinkAccount), null, getString(R.string.ZG_Settings_Pro_LinkAccount_NotLinked), false));
            items.add(ZgLinkCells.ButtonCell.Factory.of(ID_LINK, getString(R.string.ZG_LinkAccount_Title), v -> flow.retry()));
        } else {
            items.add(ZgLinkCells.ProgressCell.Factory.of(ID_LINKING, getString(R.string.ZGL_LinkAccount_Linking)));
            // What the silent confirmation asks Telegram for, as this account, while it may run (the account's consent
            // allows it) and as soon as the bot can be named.
            final String bot = flow.discloses() ? botName(bind) : null;
            items.add(UItem.asShadow(ID_DISCLOSURE, bot == null ? null : formatString(R.string.ZenlyProLinkAttestDisclosure, "@" + bot)));
        }
    }

    /** The bot of the current bind, else the one an earlier bind of this process named; null when none is known. */
    private static String botName(ZenlyPro.LinkRequest bind) {
        return bind != null && bind.attest != null ? bind.attest.botUsername : lastBot;
    }

    private static String messageText(ZgLinkRules.Message message) {
        if (message == null) {
            return getString(R.string.ZG_Entitlement_Message_TemporarilyUnavailable);
        }
        switch (message) {
            case DECLINED:
                return getString(R.string.ZG_Entitlement_Message_LinkingDeclined);
            case CODE_EXPIRED:
                return getString(R.string.ZG_Entitlement_Message_LinkingCodeExpired);
            case DID_NOT_GO_THROUGH:
                return getString(R.string.ZG_Entitlement_Message_LinkingDidNotGoThrough);
            case LINKING_UNAVAILABLE:
                return getString(R.string.ZG_Entitlement_Message_LinkingUnavailable);
            case TOO_MANY_ATTEMPTS:
                return getString(R.string.ZG_Entitlement_Message_TooManyAttempts);
            case NO_CONNECTION:
                return getString(R.string.ZG_Entitlement_Message_NoConnection);
            case CHECK_DATE_AND_TIME:
                return getString(R.string.ZG_Entitlement_Message_CheckDateAndTime);
            case WRONG_ACCOUNT:
                return getString(R.string.ZenlyProWrongAccount);
            case UPDATE_REQUIRED:
                return getString(R.string.ZGL_LinkAccount_Error_Update);
            case STORAGE:
                return getString(R.string.ZGL_LinkAccount_Error_Storage);
            case TEMPORARILY_UNAVAILABLE:
            default:
                return getString(R.string.ZG_Entitlement_Message_TemporarilyUnavailable);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Taps
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CODE) {
            copyCode();
        } else if (item.id == ID_UNLINK) {
            askUnlink();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void copyCode() {
        final ZenlyPro.LinkRequest bind = flow.bind();
        if (!alive() || flow.phase() != ZgLinkFlow.Phase.WAITING || bind == null || !AndroidUtilities.addToClipboard(bind.code)) {
            return;
        }
        flow.codeCopied();
        final String text = getString(R.string.ZG_LinkAccount_Code_Copied);
        // Shown only where the system does not confirm a copy itself.
        BulletinFactory.of(this).createCopyBulletin(text).show();
        AndroidUtilities.makeAccessibilityAnnouncement(text);
    }

    /**
     * "Open Telegram": the bind's bot chat inside this app, as this account (it is the selected one, or the screen
     * would not act), through the app's own t.me handling. Never an intent another app could take: that would hand the
     * code to another Telegram client and another account.
     */
    private void openTelegram() {
        final ZenlyPro.LinkRequest bind = flow.bind();
        if (!alive() || flow.phase() != ZgLinkFlow.Phase.WAITING || bind == null) {
            return;
        }
        final String url = ZgLinkRules.botDeepLink(bind.deepLink);
        final Activity activity = getParentActivity();
        if (url != null && activity != null) {
            Browser.openAsInternalIntent(activity, url);
        }
    }

    private void askUnlink() {
        final Activity activity = getParentActivity();
        if (!flow.mayUnlink() || activity == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(activity, getResourceProvider())
            .setTitle(getString(R.string.ZG_LinkAccount_Unlink))
            .setMessage(getString(R.string.ZGL_LinkAccount_Unlink_Text))
            .setNegativeButton(getString(R.string.Cancel), null)
            .setPositiveButton(getString(R.string.ZG_LinkAccount_Unlink_Action), (d, which) -> confirmUnlink())
            .create();
        showDialog(dialog);
        // Unlinking is destructive (the row is red): so is the button that confirms it.
        dialog.redPositive();
    }

    /**
     * The confirmed Unlink. Order matters: the account's pending consent withdrawals are queued first, on the lane the
     * client queues its revoke on, so they reach the server while the session still exists (as at a logout); then the
     * client drops the session at once and revokes it in the background. Unlinking never cancels a subscription and
     * never touches the vault, locked chats, tasks or kept versions.
     */
    private void confirmUnlink() {
        if (!alive()) return; // the account or the user of this slot may have changed while the dialog was up
        if (!flow.mayUnlink()) {
            return;
        }
        ZenlyCustomer.onUserUnlink(currentAccount);
        pro.unlink(new ZenlyPro.Callback<ZenlyPro.Snapshot>() {
            @Override
            public void onSuccess(ZenlyPro.Snapshot value) {
                // The screen showed "not linked" the moment the session was dropped; the revoke changes nothing here.
            }

            @Override
            public void onError(ZenlyPro.Error error) {
                // The saved session could not be removed from this device.
                if (alive()) {
                    BulletinFactory.of(ZgLinkAccountActivity.this).createErrorBulletin(getString(R.string.ZGL_LinkAccount_Error_Unlink)).show();
                    flow.statusChanged();
                }
            }
        });
        // The user unlinked: no automatic link of this account until a manual link or a newer grant. Written now, as iOS
        // writes it, not when the revoke answers: that answer is dropped once the screen is closed, and the automatic
        // link would then bring the account straight back.
        ProAutoLink.noteUserUnlinked(currentAccount);
        flow.unlinked();
    }
}

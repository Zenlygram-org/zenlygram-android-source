/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;
import org.telegram.zenlygram.chatlock.ZgProtectionLayer;
import org.telegram.zenlygram.lock.ZgLockBiometrics;
import org.telegram.zenlygram.lock.ZgLockCode;
import org.telegram.zenlygram.lock.ZgLockForeground;
import org.telegram.zenlygram.lock.ZgLockThrottle;
import org.telegram.zenlygram.lock.ZgMasterPin;
import org.telegram.zenlygram.lock.ZgPinDialog;
import org.telegram.zenlygram.lock.ZgPinPage;
import org.telegram.zenlygram.pro.ZgPro;
import org.telegram.zenlygram.pro.ZgProGate;

import java.util.ArrayList;

/**
 * Every Secret Vault PIN flow, built from the shared {@link ZgPinDialog} pages (iOS SecretVaultPinController + the
 * settings, reveal and open-chat callers). Main thread.
 *
 * <ul>
 *   <li><b>Reveal</b> (long-press on the chat-list title): the vault PIN (biometrics once automatically when on),
 *       "Forgot pincode?" after 4 wrong entries or during a lockout; success opens the vault list.</li>
 *   <li><b>Open a hidden chat</b> from outside the vault: the generic "Enter PIN / Chat is locked" prompt without
 *       "Forgot"; it waits for Telegram's own passcode screen first.</li>
 *   <li><b>Settings</b>: turn on (Pro-gated PIN setup, PIN by default with "Switch to password", biometrics offered),
 *       turn off (PIN; every hidden chat comes back), change the PIN, recovery.</li>
 *   <li><b>Forgot pincode?</b>: unchanged strong biometrics (the enrollment the PIN was set with), then the Chat Lock
 *       master that already existed when the vault PIN was set, else "Recovery unavailable". The device screen lock is
 *       never accepted for the vault; it only guards recovery when the PIN can never be checked again.</li>
 * </ul>
 * Only the first setup and adding a chat are Pro-gated; nothing that reveals, opens, unhides, turns off or recovers.
 */
public final class ZgVaultFlows {

    static final int PURPOSE_REVEAL = 0;
    static final int PURPOSE_OPEN_CHAT = 1;
    static final int PURPOSE_SETTINGS = 2;

    private static final long WAIT_POLL_MS = 250;
    private static final long WAIT_GIVE_UP_MS = 30_000;
    private static boolean zgProvenByBiometrics; // ZENLYGRAM: statistics, how the vault PIN prompt was passed (the method of vault.unlocked)

    interface Proven {
        void run(ZgPinDialog dialog);
    }

    interface BooleanCallback {
        void run(boolean value);
    }

    private static final SparseArray<ArrayList<ZgProtectionLayer.OpenCallback>> pendingOpens = new SparseArray<>();

    private ZgVaultFlows() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reveal
    // ---------------------------------------------------------------------------------------------------------

    /** The title long-press: PIN, then the vault list of {@code host}'s account. */
    @MainThread
    public static void reveal(@Nullable BaseFragment host) {
        if (host == null || host.getParentActivity() == null || !ZgVault.isActive()) {
            return;
        }
        final Activity activity = host.getParentActivity();
        final int account = host.getCurrentAccount();
        if (ZgVault.isTemporarilyUnavailable()) {
            showTryAgain(activity);
            return;
        }
        if (ZgVault.needsRecovery()) {
            ZgPinDialog.show(activity, recoveryPage(host, null));
            return;
        }
        if (ZgVaultSession.isOpen(account)) {
            presentVault(host, account);
            return;
        }
        ZgPinDialog.show(activity, verifyPage(PURPOSE_REVEAL, host, null, dialog -> {
            ZgVaultSession.getInstance().open(account);
            org.telegram.messenger.zenlygram.customer.ZenlyStats.vaultUnlocked(account, zgProvenByBiometrics); // ZENLYGRAM: statistics, vault.unlocked (recorded only with the data consent)
            dialog.finish();
            presentVault(host, account);
        }));
    }

    /** The secret code typed into chat search matched the vault PIN (ZgVaultSecretCode): open and show the vault. */
    static void revealByCode(@Nullable BaseFragment host) {
        if (host == null || host.getParentActivity() == null || !ZgVault.isActive()) {
            return;
        }
        final int account = host.getCurrentAccount();
        ZgVaultSession.getInstance().open(account);
        presentVault(host, account);
    }

    private static void presentVault(BaseFragment host, int account) {
        if (host.isFinished || host.getParentActivity() == null || !ZgVaultSession.isOpen(account)) {
            return;
        }
        host.presentFragment(new ZgVaultActivity(account));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Open a hidden chat from outside the vault (ZgVaultLayer.requireOpen)
    // ---------------------------------------------------------------------------------------------------------

    @MainThread
    static void requestOpenForChat(int account, @Nullable BaseFragment host, ZgProtectionLayer.OpenCallback callback) {
        if (!ZgVault.isActive()) {
            callback.onResult(true);
            return;
        }
        final ArrayList<ZgProtectionLayer.OpenCallback> waiting = pendingOpens.get(account);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        if (!ZgVault.isOperable()) {
            // Nothing can open it right now (recovery, busy Keystore): fail closed, never name the vault here.
            callback.onResult(false);
            return;
        }
        final ArrayList<ZgProtectionLayer.OpenCallback> callbacks = new ArrayList<>();
        callbacks.add(callback);
        pendingOpens.put(account, callbacks);
        final BooleanCallback finish = opened -> {
            final ArrayList<ZgProtectionLayer.OpenCallback> list = pendingOpens.get(account);
            if (list != callbacks) {
                return;
            }
            pendingOpens.remove(account);
            for (ZgProtectionLayer.OpenCallback cb : list) {
                try {
                    cb.onResult(opened);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        };
        final long startedAt = SystemClock.elapsedRealtime();
        final Runnable[] attempt = new Runnable[1];
        attempt[0] = () -> {
            if (pendingOpens.get(account) != callbacks) {
                return;
            }
            if (ZgVaultSession.isOpen(account)) {
                finish.run(true);
                return;
            }
            final Activity activity = host != null && host.getParentActivity() != null ? host.getParentActivity() : LaunchActivity.instance;
            final boolean hostGone = host != null && host.isFinished;
            if (hostGone || activity == null || activity.isFinishing() || SystemClock.elapsedRealtime() - startedAt > WAIT_GIVE_UP_MS) {
                finish.run(false);
                return;
            }
            // Telegram's own passcode comes first; nothing is shown in the background or over a paused screen.
            if (SharedConfig.isWaitingForPasscodeEnter || !ZgLockForeground.isForeground() || host != null && host.isPaused()) {
                AndroidUtilities.runOnUIThread(attempt[0], WAIT_POLL_MS);
                return;
            }
            final ZgPinPage page = verifyPage(PURPOSE_OPEN_CHAT, host, null, dialog -> {
                ZgVaultSession.getInstance().open(account);
                org.telegram.messenger.zenlygram.customer.ZenlyStats.vaultUnlocked(account, zgProvenByBiometrics); // ZENLYGRAM: statistics, vault.unlocked (recorded only with the data consent)
                dialog.finish();
                finish.run(true);
            }).onCancel(dialog -> finish.run(false));
            final ZgPinDialog dialog = ZgPinDialog.show(activity, page);
            if (dialog == null) {
                finish.run(false);
            } else {
                dialog.setOnInterruptedListener(() -> finish.run(false));
            }
        };
        attempt[0].run();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------------------

    /** The "Secret Vault" switch was tapped. {@code refresh} redraws the settings list after a change. */
    @MainThread
    public static void onSwitchClick(BaseFragment fragment, @Nullable Runnable refresh) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (ZgVault.isActive() && ZgVault.isTemporarilyUnavailable()) {
            // The list of hidden chats can't be read yet (busy Keystore): turning off now would lose what it restores.
            showTryAgain(fragment.getParentActivity());
            return;
        }
        if (ZgVault.needsRecovery()) {
            startRecovery(fragment, refresh);
        } else if (ZgVault.isActive()) {
            startDisable(fragment, refresh);
        } else {
            startEnable(fragment, refresh);
        }
    }

    private static void startEnable(BaseFragment fragment, @Nullable Runnable refresh) {
        final Activity activity = fragment.getParentActivity();
        if (ZgVault.hasCredential() && !ZgVault.isCredentialUnverifiable()) {
            // The switch is off but a PIN is still stored: turning on again needs that PIN (never gated).
            ZgPinDialog.show(activity, verifyPage(PURPOSE_SETTINGS, fragment, getString(R.string.ZG_Vault_EnableSubtitle), dialog -> {
                if (ZgVault.reEnable()) {
                    org.telegram.zenlygram.customer.ZgStats.settingToggled(fragment.getCurrentAccount(), "secretVaultEnabled", true); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
                    dialog.finish();
                    run(refresh);
                    showRevealHint(fragment);
                } else {
                    dialog.showError(getString(R.string.ZG_Vault_SaveFailed));
                }
            }));
            return;
        }
        if (!ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_VAULT_ENTRY)) {
            return;
        }
        ZgPinDialog.show(activity, setupPage(fragment, () -> {
            org.telegram.zenlygram.customer.ZgStats.settingToggled(fragment.getCurrentAccount(), "secretVaultEnabled", true); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
            run(refresh);
            showRevealHint(fragment);
        }));
    }

    private static void startDisable(BaseFragment fragment, @Nullable Runnable refresh) {
        if (ZgVault.isCredentialUnverifiable()) {
            startRecovery(fragment, refresh);
            return;
        }
        ZgPinDialog.show(fragment.getParentActivity(), verifyPage(PURPOSE_SETTINGS, fragment, getString(R.string.ZG_Vault_DisableSubtitle), dialog -> {
            if (ZgVaultEffects.turnOff()) {
                dialog.finish();
                run(refresh);
                showBulletin(fragment, R.raw.chats_unhide, getString(R.string.ZG_Vault_Bulletin_Off));
            } else {
                dialog.showError(getString(R.string.ZG_Vault_SaveFailed));
            }
        }));
    }

    /** "Change Vault PIN": the current PIN (or "Forgot pincode?"), then a new one. Never gated. */
    @MainThread
    public static void startChangePin(BaseFragment fragment, @Nullable Runnable refresh) {
        if (fragment == null || fragment.getParentActivity() == null || !ZgVault.isActive()) {
            return;
        }
        if (ZgVault.isTemporarilyUnavailable()) {
            showTryAgain(fragment.getParentActivity());
            return;
        }
        if (ZgVault.needsRecovery()) {
            startRecovery(fragment, refresh);
            return;
        }
        ZgPinDialog.show(fragment.getParentActivity(), verifyPage(PURPOSE_SETTINGS, fragment, getString(R.string.ZG_Vault_ChangePinSubtitle), dialog ->
            dialog.push(setupPage(fragment, () -> {
                run(refresh);
                showBulletin(fragment, R.raw.done, getString(R.string.ZG_Vault_Bulletin_PinChanged));
            }))));
    }

    @MainThread
    public static void startRecovery(BaseFragment fragment, @Nullable Runnable refresh) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        ZgPinDialog.show(fragment.getParentActivity(), recoveryPage(fragment, refresh));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pages
    // ---------------------------------------------------------------------------------------------------------

    /** The vault PIN. Throttled in ZgLockThrottle VAULT (5 free, then 30 s ... 15 min). */
    static ZgPinPage verifyPage(int purpose, @Nullable BaseFragment host, @Nullable CharSequence subtitle, Proven onProven) {
        org.telegram.messenger.zenlygram.customer.ZenlyStats.screen(org.telegram.messenger.UserConfig.selectedAccount, org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.VAULT_UNLOCK); // ZENLYGRAM: statistics, the vault PIN prompt is a screen of the registry (recorded only with the data consent)
        final int type = ZgVault.getCredentialType();
        final ZgPinPage page = ZgPinPage.verify(type, ZgLockThrottle.Scope.VAULT, ZgVault::verifyCredential,
                (dialog, code, viaBiometric, enrollmentChanged) -> {
                    zgProvenByBiometrics = viaBiometric; // ZENLYGRAM: statistics, the method of vault.unlocked
                    if (enrollmentChanged && !viaBiometric && code != null && ZgVault.isBiometricEnabled()) {
                        offerKeepBiometrics(dialog, () -> onProven.run(dialog));
                    } else {
                        onProven.run(dialog);
                    }
                })
            .textPolicy(ZgLockCode.TEXT_POLICY_NO_WHITESPACE)
            .biometric(ZgVault.isBiometricEnabled() ? ZgVault.BIO_SLOT : null, purpose != PURPOSE_SETTINGS,
                getString(R.string.ZG_Vault_BiometricReason), null)
            .onUnverifiable(dialog -> {
                if (purpose == PURPOSE_OPEN_CHAT) {
                    dialog.showError(getString(R.string.ZG_Lock_CheckFailed));
                } else {
                    dialog.push(recoveryPage(host, null));
                }
            });
        if (purpose == PURPOSE_OPEN_CHAT) {
            page.subtitle(getString(R.string.ZG_ChatLock_ChatIsLocked));
        } else if (subtitle != null) {
            page.subtitle(subtitle);
        }
        if (purpose != PURPOSE_OPEN_CHAT) {
            page.recovery(getString(R.string.ZG_ChatLock_ForgotPincode), ZgPinPage.RECOVERY_AFTER_STRUGGLE, dialog -> forgot(dialog, onProven));
        }
        return page;
    }

    /** Choose a new vault PIN (4 digits by default, "Switch to password"; passwords without spaces). */
    private static ZgPinPage setupPage(@Nullable BaseFragment fragment, Runnable onSaved) {
        org.telegram.messenger.zenlygram.customer.ZenlyStats.screen(org.telegram.messenger.UserConfig.selectedAccount, org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.VAULT_SETTINGS); // ZENLYGRAM: statistics, choosing the vault PIN is a screen of the registry (recorded only with the data consent)
        return ZgPinPage.setup(ZgLockCode.TYPE_PIN, (dialog, code, type) -> warnIfUnrecoverable(dialog, () -> offerBiometrics(dialog, useBiometrics -> {
                dialog.setBusy(true);
                ZgPinDialog.runOnWorker(() -> {
                    int result;
                    try {
                        result = ZgVault.saveCredential(code, type, useBiometrics, false);
                    } catch (Throwable e) {
                        FileLog.e("ZgVaultFlows: save failed: " + e.getClass().getSimpleName());
                        result = ZgVault.SAVE_FAILED;
                    }
                    final int saved = result;
                    AndroidUtilities.runOnUIThread(() -> {
                        dialog.setBusy(false);
                        if (saved == ZgVault.SAVE_OK) {
                            dialog.finish();
                            onSaved.run();
                        } else {
                            dialog.showError(getString(saved == ZgVault.SAVE_NO_KEYSTORE ? R.string.ZG_Vault_NoKeystore : R.string.ZG_Vault_SaveFailed));
                        }
                    });
                });
            })))
            .textPolicy(ZgLockCode.TEXT_POLICY_NO_WHITESPACE);
    }

    /**
     * "Forgot pincode?" will only work with a strong biometric (its slot is made with the PIN) or a Chat Lock master
     * that exists when the PIN is set. With neither, the owner is told before the PIN is saved that a forgotten PIN
     * cannot be recovered in the app (the device screen lock is never accepted for the vault): continue, or go back.
     */
    private static void warnIfUnrecoverable(ZgPinDialog dialog, Runnable then) {
        final boolean recoverable = ZgLockBiometrics.isStrongAvailable() || ZgMasterPin.isSet() && !ZgMasterPin.isUnverifiable();
        if (recoverable) {
            then.run();
            return;
        }
        final boolean[] answered = new boolean[1];
        try {
            final AlertDialog alert = new AlertDialog.Builder(dialog.getActivity())
                .setTitle(getString(R.string.ZG_Vault_NoRecoveryTitle))
                .setMessage(getString(R.string.ZG_Vault_NoRecoveryText))
                .setNegativeButton(getString(R.string.Cancel), (a, which) -> {
                    answered[0] = true;
                    dialog.pop();
                })
                .setPositiveButton(getString(R.string.ZG_Vault_NoRecoveryContinue), (a, which) -> {
                    answered[0] = true;
                    then.run();
                })
                .create();
            alert.setOnDismissListener(d -> {
                if (!answered[0]) {
                    answered[0] = true;
                    dialog.pop();
                }
            });
            alert.show();
        } catch (Throwable e) {
            FileLog.e(e);
            then.run();
        }
    }

    /**
     * The PIN can never be checked here again, or the list of hidden chats is gone (Keystore key lost): a new PIN or
     * turning the vault off, both after the device's screen lock (only a confirmation on a device without any).
     */
    static ZgPinPage recoveryPage(@Nullable BaseFragment fragment, @Nullable Runnable onResolved) {
        final boolean lost = ZgVault.isMembershipLost();
        return ZgPinPage.info(getString(lost ? R.string.ZG_Vault_LostTitle : R.string.ZG_Vault_UnverifiableTitle),
                getString(lost ? R.string.ZG_Vault_LostText : R.string.ZG_Vault_UnverifiableText))
            .primary(getString(R.string.ZG_Vault_SetNewPin), dialog -> proveOwnerForRecovery(dialog, () ->
                dialog.push(setupPage(fragment, () -> {
                    run(onResolved);
                    showBulletin(fragment, R.raw.done, getString(R.string.ZG_Vault_Bulletin_PinChanged));
                }))))
            .secondary(getString(R.string.ZG_Vault_TurnOff), true, dialog -> proveOwnerForRecovery(dialog, () -> {
                if (ZgVaultEffects.turnOff()) {
                    dialog.finish();
                    run(onResolved);
                    showBulletin(fragment, R.raw.chats_unhide, getString(R.string.ZG_Vault_Bulletin_Off));
                } else {
                    dialog.showError(getString(R.string.ZG_Vault_SaveFailed));
                }
            }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Forgot pincode?
    // ---------------------------------------------------------------------------------------------------------

    private static void forgot(ZgPinDialog dialog, Proven onProven) {
        final boolean master = ZgVault.isMasterRungAvailable();
        if (ZgLockBiometrics.enrollment(ZgVault.BIO_SLOT) == ZgLockBiometrics.Enrollment.UNCHANGED) {
            ZgLockBiometrics.authenticate(dialog.getActivity(), ZgVault.BIO_SLOT, getString(R.string.ZG_Vault_ForgotBiometricReason), null,
                getString(R.string.Cancel), result -> {
                    if (result == ZgLockBiometrics.Result.SUCCESS) {
                        zgProvenByBiometrics = true; // ZENLYGRAM: statistics, Forgot pincode: the biometric rung
                        ZgLockThrottle.reset(ZgLockThrottle.Scope.VAULT);
                        onProven.run(dialog);
                    } else if (master) {
                        dialog.push(masterPage(onProven));
                    } else if (result != ZgLockBiometrics.Result.CANCELLED) {
                        showRecoveryUnavailable(dialog);
                    }
                });
        } else if (master) {
            dialog.push(masterPage(onProven));
        } else {
            showRecoveryUnavailable(dialog);
        }
    }

    /** Rung 2: the Chat Lock master pincode (its own MASTER throttle); success resets the vault throttle. */
    private static ZgPinPage masterPage(Proven onProven) {
        final int type = ZgMasterPin.getType();
        return ZgPinPage.verify(type, ZgLockThrottle.Scope.MASTER, ZgMasterPin::verify, (dialog, code, viaBiometric, enrollmentChanged) -> {
                zgProvenByBiometrics = false; // ZENLYGRAM: statistics, Forgot pincode: the master rung
                ZgLockThrottle.reset(ZgLockThrottle.Scope.VAULT);
                onProven.run(dialog);
            })
            .title(getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_ChatLock_EnterMasterPin : R.string.ZG_ChatLock_EnterMasterPassword))
            .subtitle(getString(R.string.ZG_Vault_MasterSubtitle))
            .onUnverifiable(dialog -> {
                dialog.pop();
                showRecoveryUnavailable(dialog);
            });
    }

    private static void showRecoveryUnavailable(ZgPinDialog dialog) {
        showAlert(dialog.getActivity(), getString(R.string.ZG_Vault_RecoveryUnavailableTitle), getString(R.string.ZG_Vault_RecoveryUnavailableText));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Device owner (recovery only)
    // ---------------------------------------------------------------------------------------------------------

    private static void proveOwnerForRecovery(ZgPinDialog dialog, Runnable proven) {
        final Activity activity = dialog.getActivity();
        if (!ZgLockBiometrics.canProveDeviceOwner()) {
            // Nothing on this device can prove anything: an explicit confirmation, so the owner is never locked out.
            new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.ZG_Vault_UnverifiableTitle))
                .setMessage(getString(R.string.ZG_Vault_NoScreenLockText))
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.ZG_Vault_Continue), (alert, which) -> proven.run())
                .show();
            return;
        }
        ZgLockBiometrics.authenticateDeviceOwner(activity, getString(R.string.ZG_Vault_RecoveryReason), null, false, result -> {
            if (result == ZgLockBiometrics.Result.SUCCESS) {
                proven.run();
            } else if (result == ZgLockBiometrics.Result.UNAVAILABLE) {
                showAlert(activity, getString(R.string.ZG_Vault_TryAgainTitle), getString(R.string.ZG_Vault_TryAgainText));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Biometrics at set-up and after an enrollment change
    // ---------------------------------------------------------------------------------------------------------

    /** Offered at set-up when a strong biometric exists (owner decision); otherwise answers false at once. */
    private static void offerBiometrics(ZgPinDialog dialog, BooleanCallback callback) {
        if (!ZgLockBiometrics.isStrongAvailable()) {
            callback.run(false);
            return;
        }
        final boolean[] answered = new boolean[1];
        final BooleanCallback once = value -> {
            if (!answered[0]) {
                answered[0] = true;
                callback.run(value);
            }
        };
        try {
            final AlertDialog alert = new AlertDialog.Builder(dialog.getActivity())
                .setTitle(getString(R.string.ZG_Lock_BiometricOffer_Title))
                .setMessage(getString(R.string.ZG_Lock_BiometricOffer_Text))
                .setNegativeButton(getString(R.string.ZG_Lock_BiometricOffer_Skip), (a, which) -> once.run(false))
                .setPositiveButton(getString(R.string.ZG_Lock_BiometricOffer_Use), (a, which) -> once.run(true))
                .create();
            alert.setOnDismissListener(d -> once.run(false));
            alert.show();
        } catch (Throwable e) {
            FileLog.e(e);
            once.run(false);
        }
    }

    /** The right PIN after an enrollment change: keep biometrics (new baseline) or the PIN only. */
    private static void offerKeepBiometrics(ZgPinDialog dialog, Runnable then) {
        final boolean[] answered = new boolean[1];
        final BooleanCallback once = keep -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            ZgPinDialog.runOnWorker(() -> {
                // Either way the proven owner accepts today's enrollment as the new "Forgot PIN" baseline.
                final boolean slot = ZgLockBiometrics.createSlot(ZgVault.BIO_SLOT);
                ZgVault.setBiometricEnabled(keep && slot);
                AndroidUtilities.runOnUIThread(then);
            });
        };
        try {
            final AlertDialog alert = new AlertDialog.Builder(dialog.getActivity())
                .setTitle(getString(R.string.ZG_Lock_BiometricKeepTitle))
                .setMessage(getString(R.string.ZG_Lock_BiometricKeepText))
                .setNegativeButton(getString(R.string.ZG_Lock_BiometricPinOnly), (a, which) -> once.run(false))
                .setPositiveButton(getString(R.string.ZG_Lock_BiometricKeep), (a, which) -> once.run(true))
                .create();
            alert.setOnDismissListener(d -> {
                if (!answered[0]) {
                    answered[0] = true;
                    then.run();
                }
            });
            alert.show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (!answered[0]) {
                answered[0] = true;
                then.run();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void showRevealHint(@Nullable BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        showAlert(fragment.getParentActivity(), getString(R.string.ZG_Settings_SecretVault_RevealHint_Title),
            getString(ZgVaultSecretCode.allowsTitleReveal() ? R.string.ZG_Vault_RevealHint_LongPress : R.string.ZG_Settings_SecretVault_RevealHint_SecretCode));
    }

    static void showTryAgain(@Nullable Activity activity) {
        showAlert(activity, getString(R.string.ZG_Vault_TryAgainTitle), getString(R.string.ZG_Vault_TryAgainText));
    }

    private static void showAlert(@Nullable Activity activity, CharSequence title, CharSequence text) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(getString(R.string.OK), null)
                .show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    static void showBulletin(@Nullable BaseFragment fragment, int rawRes, CharSequence text) {
        if (fragment == null || fragment.getParentActivity() == null || fragment.isFinished) {
            return;
        }
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(rawRes, text).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void run(@Nullable Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.app.Activity;
import android.content.DialogInterface;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.lock.ZgLockBiometrics;
import org.telegram.zenlygram.lock.ZgLockCode;
import org.telegram.zenlygram.lock.ZgLockThrottle;
import org.telegram.zenlygram.lock.ZgMasterPin;
import org.telegram.zenlygram.lock.ZgPinDialog;
import org.telegram.zenlygram.lock.ZgPinPage;
import org.telegram.zenlygram.pro.ZgPro;
import org.telegram.zenlygram.pro.ZgProGate;

/**
 * Every Chat Lock PIN flow, built from {@link ZgPinDialog} pages (iOS ChatPincodeViewController + the settings and
 * context-menu callers). Main thread.
 *
 * <ul>
 *   <li>Unlock a chat: its pincode (biometrics once automatically if on), "Forgot pincode?" pushes the master page,
 *       whose success removes that chat's lock; "Reset Chat Lock" there (after 4 wrong entries or during a lockout)
 *       turns Chat Lock off after device-owner proof.</li>
 *   <li>Set / remove a chat's pincode (chat list), enable / disable Chat Lock (settings).</li>
 *   <li>Recovery when the credentials can never be checked again (Keystore key lost): one new pincode for the master
 *       and every locked chat, or Chat Lock off; both after device-owner proof (only a confirmation on a device
 *       without any screen lock, where nothing else can be proven).</li>
 * </ul>
 * Only creating Chat Lock and locking a new chat are Pro-gated; nothing that unlocks, removes or recovers is.
 */
public final class ZgChatLockFlows {

    interface PromptResult {
        void onResult(int result);
    }

    interface BooleanCallback {
        void run(boolean value);
    }

    private ZgChatLockFlows() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Unlock prompt (ZgChatLockPresenter)
    // ---------------------------------------------------------------------------------------------------------

    /** The PIN screen for a locked chat. {@code result} gets exactly one value. */
    @Nullable
    static ZgPinDialog showUnlockPrompt(Activity activity, BaseFragment host, int account, long dialogId, PromptResult result) {
        final PromptResult once = once(result);
        final ZgPinPage first;
        // After a recovery the chat may still be locked (with the new code the owner just chose): it opens now.
        final Runnable recovered = () -> {
            ZgChatLockSession.getInstance().grant(ZgChatLockSession.Key.of(account, dialogId));
            once.onResult(ZgChatLockSession.RESULT_UNLOCKED);
        };
        if (ZgChatLockStore.isUnverifiable(dialogId)) {
            first = recoveryPage(host, recovered)
                .onCancel(d -> once.onResult(ZgChatLockSession.RESULT_CANCELLED));
        } else {
            final int type = ZgChatLockStore.getType(dialogId);
            final CharSequence name = displayName(account, dialogId);
            final String slot = ZgChatLockStore.bioSlot(dialogId);
            first = ZgPinPage.verify(type, ZgLockThrottle.Scope.chat(dialogId), code -> ZgChatLockStore.verify(dialogId, code),
                    (dialog, code, viaBiometric, enrollmentChanged) -> {
                        final Runnable unlock = () -> {
                            ZgChatLockSession.getInstance().grant(ZgChatLockSession.Key.of(account, dialogId));
                            org.telegram.messenger.zenlygram.customer.ZenlyStats.chatLockUnlocked(account, viaBiometric); // ZENLYGRAM: statistics, chat_lock.unlocked (recorded only with the data consent)
                            dialog.finish();
                            once.onResult(ZgChatLockSession.RESULT_UNLOCKED);
                        };
                        if (enrollmentChanged && !viaBiometric && ZgChatLockStore.isBiometricEnabled(dialogId)) {
                            offerKeepBiometrics(dialog, false, dialogId, unlock);
                        } else {
                            unlock.run();
                        }
                    })
                .subtitle(name != null ? name : LocaleController.getString(R.string.ZG_ChatLock_ChatIsLocked))
                .biometric(ZgChatLockStore.isBiometricEnabled(dialogId) ? slot : null, true,
                    LocaleController.getString(R.string.ZG_ChatLock_BiometricReason), name)
                .onUnverifiable(d -> d.push(recoveryPage(host, () -> {
                    d.finish();
                    recovered.run();
                })))
                .onCancel(d -> once.onResult(ZgChatLockSession.RESULT_CANCELLED));
            if (ZgMasterPin.isSet()) {
                first.recovery(LocaleController.getString(R.string.ZG_ChatLock_ForgotPincode), ZgPinPage.RECOVERY_ALWAYS,
                    d -> d.push(masterPageForChat(host, dialogId, () -> {
                        d.finish();
                        recovered.run();
                    })));
            }
        }
        final ZgPinDialog dialog = ZgPinDialog.show(activity, first);
        if (dialog != null) {
            dialog.setOnInterruptedListener(() -> once.onResult(ZgChatLockSession.RESULT_INTERRUPTED));
            org.telegram.messenger.zenlygram.customer.ZenlyStats.screen(account, org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.CHAT_LOCK_UNLOCK); // ZENLYGRAM: statistics, the PIN prompt is a screen of the registry (recorded only with the data consent)
        }
        return dialog;
    }

    /** "Forgot pincode?": the master removes this chat's lock; "Reset Chat Lock" turns Chat Lock off. */
    private static ZgPinPage masterPageForChat(BaseFragment host, long dialogId, Runnable unlocked) {
        final int type = ZgMasterPin.getType();
        return ZgPinPage.verify(type, ZgLockThrottle.Scope.MASTER, ZgMasterPin::verify, (dialog, code, viaBiometric, enrollmentChanged) -> {
                final Runnable done = () -> {
                    if (ZgChatLockStore.removePincode(dialogId)) {
                        org.telegram.messenger.zenlygram.customer.ZenlyStats.chatLockRemoved(host != null ? host.getCurrentAccount() : UserConfig.selectedAccount); // ZENLYGRAM: statistics, chat_lock.lock_removed, the master removed this chat's lock (recorded only with the data consent)
                        unlocked.run();
                    } else {
                        dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                    }
                };
                if (enrollmentChanged && !viaBiometric && code != null && ZgMasterPin.isBiometricEnabled()) {
                    offerKeepBiometrics(dialog, true, 0, done);
                } else {
                    done.run();
                }
            })
            .title(LocaleController.getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_ChatLock_EnterMasterPin : R.string.ZG_ChatLock_EnterMasterPassword))
            .subtitle(LocaleController.getString(R.string.ZG_ChatLock_MasterSubtitle))
            .biometric(ZgMasterPin.isBiometricEnabled() ? ZgMasterPin.BIO_SLOT : null, false, LocaleController.getString(R.string.ZG_ChatLock_BiometricReason), null)
            .recovery(LocaleController.getString(R.string.ZG_ChatLock_ResetButton), ZgPinPage.RECOVERY_AFTER_STRUGGLE,
                d -> confirmReset(d, () -> {
                    unlocked.run();
                    showBulletin(host, R.raw.done, LocaleController.getString(R.string.ZG_ChatLock_Bulletin_Off));
                }))
            .onUnverifiable(d -> d.push(recoveryPage(host, unlocked)));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Chat list: set / remove a chat's pincode
    // ---------------------------------------------------------------------------------------------------------

    /** "Set Pincode" on a chat without one. Pro-gated (creating a lock). */
    public static void startSetPincode(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (!ZgChatLockStore.isEnabled() || ZgChatLockStore.isLocked(dialogId) || dialogId == 0
            || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        if (ZgChatLockStore.needsRecovery()) {
            // A new pincode now would be made with a new Keystore key: recover the existing locks first.
            ZgPinDialog.show(fragment.getParentActivity(), recoveryPage(fragment, null));
            return;
        }
        if (!ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_CHAT_LOCK_PINCODE)) {
            return;
        }
        final CharSequence name = displayName(account, dialogId);
        final ZgPinPage page = ZgPinPage.setup(ZgLockCode.TYPE_PIN, (dialog, code, type) -> offerBiometrics(dialog, useBiometrics -> {
            dialog.setBusy(true);
            ZgPinDialog.runOnWorker(() -> {
                final String slot = ZgChatLockStore.bioSlot(dialogId);
                final boolean biometric = useBiometrics && ZgLockBiometrics.createSlot(slot);
                final boolean ok = ZgChatLockStore.setPincode(dialogId, code, type, biometric);
                if (!ok && biometric) {
                    ZgLockBiometrics.deleteSlot(slot);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    dialog.setBusy(false);
                    if (ok) {
                        dialog.finish();
                        org.telegram.messenger.zenlygram.customer.ZenlyStats.chatLockSet(account, biometric); // ZENLYGRAM: statistics, chat_lock.lock_set (recorded only with the data consent)
                        showBulletin(fragment, R.raw.passcode_lock_close, LocaleController.getString(R.string.ZG_ChatLock_Bulletin_Locked));
                    } else {
                        dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                    }
                });
            });
        }));
        if (name != null) {
            page.subtitle(LocaleController.formatString(R.string.ZG_ChatLock_SetChatSubtitle, name));
        }
        ZgPinDialog.show(fragment.getParentActivity(), page);
    }

    /** "Remove Pincode": the chat's current code (throttled; no "Forgot", no biometrics). Never gated. */
    public static void startRemovePincode(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || !ZgChatLockStore.isLocked(dialogId)) {
            return;
        }
        final Runnable removed = () -> showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_ChatLock_Bulletin_Unlocked));
        if (ZgChatLockStore.isUnverifiable(dialogId)) {
            ZgPinDialog.show(fragment.getParentActivity(), recoveryPage(fragment, null));
            return;
        }
        final int type = ZgChatLockStore.getType(dialogId);
        final ZgPinPage page = ZgPinPage.verify(type, ZgLockThrottle.Scope.chat(dialogId), code -> ZgChatLockStore.verify(dialogId, code),
                (dialog, code, viaBiometric, enrollmentChanged) -> {
                    if (ZgChatLockStore.removePincode(dialogId)) {
                        dialog.finish();
                        removed.run();
                    } else {
                        dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                    }
                })
            .title(LocaleController.getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_Lock_ConfirmPin : R.string.ZG_Lock_ConfirmPassword))
            .subtitle(LocaleController.getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_ChatLock_RemovePinSubtitle : R.string.ZG_ChatLock_RemovePasswordSubtitle))
            .onUnverifiable(d -> d.push(recoveryPage(fragment, d::finish)));
        ZgPinDialog.show(fragment.getParentActivity(), page);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Settings: enable / disable / recover
    // ---------------------------------------------------------------------------------------------------------

    /** Arms Chat Lock: the master pincode (Pro-gated). {@code onChanged} runs after a successful change. */
    public static void startEnable(BaseFragment fragment, @Nullable Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || ZgChatLockStore.isEnabled()) {
            return;
        }
        if (!ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_CHAT_LOCK_PINCODE)) {
            return;
        }
        final ZgPinPage page = ZgPinPage.setup(ZgLockCode.TYPE_PIN, (dialog, code, type) -> offerBiometrics(dialog, useBiometrics -> {
                dialog.setBusy(true);
                ZgPinDialog.runOnWorker(() -> {
                    final boolean ok = saveMaster(code, type, useBiometrics);
                    AndroidUtilities.runOnUIThread(() -> {
                        dialog.setBusy(false);
                        if (ok) {
                            dialog.finish();
                            if (onChanged != null) {
                                onChanged.run();
                            }
                            org.telegram.messenger.zenlygram.customer.ZenlyStats.chatLockSet(fragment.getCurrentAccount(), ZgMasterPin.isBiometricEnabled()); // ZENLYGRAM: statistics, chat_lock.lock_set for the first master (recorded only with the data consent)
                            showBulletin(fragment, R.raw.passcode_lock_close, LocaleController.getString(R.string.ZG_ChatLock_Bulletin_On));
                        } else {
                            dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                        }
                    });
                });
            }))
            .setupTitles(LocaleController.getString(R.string.ZG_ChatLock_SetMasterPin), LocaleController.getString(R.string.ZG_ChatLock_SetMasterPassword))
            .subtitle(LocaleController.getString(R.string.ZG_ChatLock_SetMasterSubtitle));
        ZgPinDialog.show(fragment.getParentActivity(), page);
    }

    /** "Disable Chat Lock?" then the master (or Reset). Never gated. */
    public static void startDisable(BaseFragment fragment, @Nullable Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || !ZgChatLockStore.isEnabled()) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        new AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.ZG_Settings_ChatLock_Disable_Title))
            .setMessage(LocaleController.getString(R.string.ZG_Settings_ChatLock_Disable_Text))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.ZG_Settings_ChatLock_Disable_Action), (alert, which) -> showDisablePage(fragment, onChanged))
            .makeRed(DialogInterface.BUTTON_POSITIVE)
            .show();
    }

    private static void showDisablePage(BaseFragment fragment, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Runnable turnedOff = () -> {
            if (onChanged != null) {
                onChanged.run();
            }
            showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_ChatLock_Bulletin_Off));
        };
        if (ZgMasterPin.isUnverifiable()) {
            ZgPinDialog.show(activity, recoveryPage(fragment, onChanged));
            return;
        }
        final int type = ZgMasterPin.getType();
        final ZgPinPage page = ZgPinPage.verify(type, ZgLockThrottle.Scope.MASTER, ZgMasterPin::verify, (dialog, code, viaBiometric, enrollmentChanged) -> {
                if (ZgChatLockStore.disableChatLock()) {
                    dialog.finish();
                    turnedOff.run();
                } else {
                    dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                }
            })
            .title(LocaleController.getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_ChatLock_EnterMasterPin : R.string.ZG_ChatLock_EnterMasterPassword))
            .subtitle(LocaleController.getString(R.string.ZG_ChatLock_DisableSubtitle))
            .biometric(ZgMasterPin.isBiometricEnabled() ? ZgMasterPin.BIO_SLOT : null, false, LocaleController.getString(R.string.ZG_ChatLock_DisableBiometricReason), null)
            .recovery(LocaleController.getString(R.string.ZG_ChatLock_ResetButton), ZgPinPage.RECOVERY_AFTER_STRUGGLE,
                d -> confirmReset(d, () -> {
                    d.finish();
                    turnedOff.run();
                }))
            .onUnverifiable(d -> d.push(recoveryPage(fragment, () -> {
                d.finish();
                if (onChanged != null) {
                    onChanged.run();
                }
            })));
        ZgPinDialog.show(activity, page);
    }

    /** Settings row while Chat Lock needs recovery. */
    public static void startRecovery(BaseFragment fragment, @Nullable Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        ZgPinDialog.show(fragment.getParentActivity(), recoveryPage(fragment, onChanged));
    }

    /**
     * "Chat Lock needs a new pincode": one new code for the master and every locked chat, or Chat Lock off. Runs
     * {@code onResolved} after either (the dialog is closed by then unless the page was pushed: then the caller's
     * runnable closes it).
     */
    static ZgPinPage recoveryPage(@Nullable BaseFragment fragment, @Nullable Runnable onResolved) {
        return ZgPinPage.info(LocaleController.getString(R.string.ZG_ChatLock_LostTitle), LocaleController.getString(R.string.ZG_ChatLock_LostText))
            .primary(LocaleController.getString(R.string.ZG_ChatLock_SetNewPinForLocked), d -> proveOwnerForRecovery(d, () ->
                d.push(ZgPinPage.setup(ZgLockCode.TYPE_PIN, (dialog, code, type) -> offerBiometrics(dialog, useBiometrics -> {
                        dialog.setBusy(true);
                        ZgPinDialog.runOnWorker(() -> {
                            final boolean ok = ZgChatLockStore.replaceAllWithNewCode(code, type, false) && finishMasterBiometrics(useBiometrics);
                            AndroidUtilities.runOnUIThread(() -> {
                                dialog.setBusy(false);
                                if (ok) {
                                    dialog.finish();
                                    if (onResolved != null) {
                                        onResolved.run();
                                    }
                                } else {
                                    dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                                }
                            });
                        });
                    }))
                    .setupTitles(LocaleController.getString(R.string.ZG_ChatLock_SetMasterPin), LocaleController.getString(R.string.ZG_ChatLock_SetMasterPassword))
                    .subtitle(LocaleController.getString(R.string.ZG_ChatLock_SetMasterSubtitle)))))
            .secondary(LocaleController.getString(R.string.ZG_ChatLock_TurnOff), true, d -> proveOwnerForRecovery(d, () -> {
                if (ZgChatLockStore.disableChatLock()) {
                    d.finish();
                    if (onResolved != null) {
                        onResolved.run();
                    }
                    showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_ChatLock_Bulletin_Off));
                } else {
                    d.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                }
            }));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reset ladder and device-owner proof
    // ---------------------------------------------------------------------------------------------------------

    /** "Reset Chat Lock?" then device-owner proof, then Chat Lock is turned off and {@code afterReset} runs. */
    static void confirmReset(ZgPinDialog dialog, Runnable afterReset) {
        new AlertDialog.Builder(dialog.getActivity())
            .setTitle(LocaleController.getString(R.string.ZG_ChatLock_ResetConfirmTitle))
            .setMessage(LocaleController.getString(R.string.ZG_ChatLock_ResetConfirmText))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.ZG_ChatLock_ResetAction), (alert, which) -> proveOwnerForReset(dialog, () -> {
                if (ZgChatLockStore.disableChatLock()) {
                    ZgLockThrottle.resetChatLockAll();
                    afterReset.run();
                } else {
                    dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                }
            }))
            .makeRed(DialogInterface.BUTTON_POSITIVE)
            .show();
    }

    /**
     * iOS audit #40 ladder: the enrollment the master was set with proves the owner by biometrics; a changed enrollment
     * needs an explicit confirmation and then the screen lock; no biometrics means the screen lock; a device that can
     * prove nothing is refused ("Reset unavailable").
     */
    private static void proveOwnerForReset(ZgPinDialog dialog, Runnable proven) {
        final Activity activity = dialog.getActivity();
        final ZgLockBiometrics.Enrollment enrollment = ZgLockBiometrics.enrollment(ZgMasterPin.BIO_SLOT);
        if (enrollment == ZgLockBiometrics.Enrollment.UNCHANGED) {
            ZgLockBiometrics.authenticate(activity, ZgMasterPin.BIO_SLOT, LocaleController.getString(R.string.ZG_ChatLock_ResetReason), null,
                LocaleController.getString(R.string.Cancel), result -> {
                    if (result == ZgLockBiometrics.Result.SUCCESS) {
                        proven.run();
                    } else if (result == ZgLockBiometrics.Result.ENROLLMENT_CHANGED || result == ZgLockBiometrics.Result.UNAVAILABLE) {
                        deviceCredential(dialog, false, proven, false);
                    }
                });
        } else if (enrollment == ZgLockBiometrics.Enrollment.CHANGED) {
            new AlertDialog.Builder(dialog.getActivity())
                .setTitle(LocaleController.getString(R.string.ZG_Lock_BiometricKeepTitle))
                .setMessage(LocaleController.getString(R.string.ZG_ChatLock_ResetChangedText))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Confirm), (alert, which) -> deviceCredential(dialog, true, proven, false))
                .show();
        } else {
            deviceCredential(dialog, false, proven, false);
        }
    }

    /** Recovery (credentials unverifiable): the screen lock, or only an explicit confirmation without one. */
    static void proveOwnerForRecovery(ZgPinDialog dialog, Runnable proven) {
        if (!ZgLockBiometrics.canProveDeviceOwner()) {
            new AlertDialog.Builder(dialog.getActivity())
                .setTitle(LocaleController.getString(R.string.ZG_ChatLock_LostTitle))
                .setMessage(LocaleController.getString(R.string.ZG_ChatLock_TurnOffNoProofText))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Confirm), (alert, which) -> proven.run())
                .show();
            return;
        }
        deviceCredential(dialog, false, proven, true);
    }

    private static void deviceCredential(ZgPinDialog dialog, boolean credentialOnly, Runnable proven, boolean recovery) {
        if (!ZgLockBiometrics.canProveDeviceOwner()) {
            showResetUnavailable(dialog);
            return;
        }
        final CharSequence title = LocaleController.getString(recovery ? R.string.ZG_ChatLock_LostReason : R.string.ZG_ChatLock_ResetReason);
        ZgLockBiometrics.authenticateDeviceOwner(dialog.getActivity(), title, null, credentialOnly, result -> {
            if (result == ZgLockBiometrics.Result.SUCCESS) {
                proven.run();
            } else if (result == ZgLockBiometrics.Result.UNAVAILABLE) {
                showResetUnavailable(dialog);
            }
        });
    }

    private static void showResetUnavailable(ZgPinDialog dialog) {
        new AlertDialog.Builder(dialog.getActivity())
            .setTitle(LocaleController.getString(R.string.ZG_ChatLock_ResetUnavailableTitle))
            .setMessage(LocaleController.getString(R.string.ZG_ChatLock_ResetUnavailableText))
            .setPositiveButton(LocaleController.getString(R.string.OK), null)
            .show();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Biometrics at set-up and after an enrollment change
    // ---------------------------------------------------------------------------------------------------------

    /** Offered at set-up when a strong biometric exists (owner decision); otherwise answers false at once. */
    static void offerBiometrics(ZgPinDialog dialog, BooleanCallback callback) {
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
        final AlertDialog alert = new AlertDialog.Builder(dialog.getActivity())
            .setTitle(LocaleController.getString(R.string.ZG_Lock_BiometricOffer_Title))
            .setMessage(LocaleController.getString(R.string.ZG_Lock_BiometricOffer_Text))
            .setNegativeButton(LocaleController.getString(R.string.ZG_Lock_BiometricOffer_Skip), (a, which) -> once.run(false))
            .setPositiveButton(LocaleController.getString(R.string.ZG_Lock_BiometricOffer_Use), (a, which) -> once.run(true))
            .create();
        alert.setOnDismissListener(d -> once.run(false));
        try {
            alert.show();
        } catch (Throwable e) {
            FileLog.e(e);
            once.run(false);
        }
    }

    /** After the right code on a page that saw an enrollment change: keep biometrics (new baseline) or turn them off. */
    private static void offerKeepBiometrics(ZgPinDialog dialog, boolean master, long dialogId, Runnable then) {
        final boolean[] answered = new boolean[1];
        final BooleanCallback once = keep -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            final String slot = master ? ZgMasterPin.BIO_SLOT : ZgChatLockStore.bioSlot(dialogId);
            ZgPinDialog.runOnWorker(() -> {
                if (keep) {
                    if (!ZgLockBiometrics.createSlot(slot)) {
                        if (master) {
                            ZgMasterPin.setBiometricEnabled(false);
                        } else {
                            ZgChatLockStore.setBiometricEnabled(dialogId, false);
                        }
                    }
                } else if (master) {
                    ZgMasterPin.setBiometricEnabled(false);
                    // The master slot stays the reset baseline: the proven owner accepts today's enrollment.
                    ZgLockBiometrics.createSlot(slot);
                } else {
                    ZgChatLockStore.setBiometricEnabled(dialogId, false);
                    ZgLockBiometrics.deleteSlot(slot);
                }
                AndroidUtilities.runOnUIThread(then);
            });
        };
        final AlertDialog alert = new AlertDialog.Builder(dialog.getActivity())
            .setTitle(LocaleController.getString(R.string.ZG_Lock_BiometricKeepTitle))
            .setMessage(LocaleController.getString(R.string.ZG_Lock_BiometricKeepText))
            .setNegativeButton(LocaleController.getString(R.string.ZG_Lock_BiometricPinOnly), (a, which) -> once.run(false))
            .setPositiveButton(LocaleController.getString(R.string.ZG_Lock_BiometricKeep), (a, which) -> once.run(true))
            .create();
        alert.setOnDismissListener(d -> {
            if (!answered[0]) {
                answered[0] = true;
                then.run();
            }
        });
        try {
            alert.show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (!answered[0]) {
                answered[0] = true;
                then.run();
            }
        }
    }

    /** Worker thread: stores the master; its slot is created whenever strong biometrics exist (reset baseline). */
    private static boolean saveMaster(String code, int type, boolean useBiometrics) {
        if (!ZgMasterPin.set(code, type, false)) {
            return false;
        }
        return finishMasterBiometrics(useBiometrics);
    }

    private static boolean finishMasterBiometrics(boolean useBiometrics) {
        final boolean slot = ZgLockBiometrics.isStrongAvailable() && ZgLockBiometrics.createSlot(ZgMasterPin.BIO_SLOT);
        if (useBiometrics && slot) {
            ZgMasterPin.setBiometricEnabled(true);
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** The chat's title, or null when it is unknown or hidden in the Secret Vault (never named). */
    @Nullable
    static CharSequence displayName(int account, long dialogId) {
        if (ZgChatLockSession.isVaultHidden(account, dialogId)) {
            return null;
        }
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            if (DialogObject.isEncryptedDialog(dialogId)) {
                final TLRPC.EncryptedChat encryptedChat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                final TLRPC.User user = encryptedChat != null ? controller.getUser(encryptedChat.user_id) : null;
                return user != null ? UserObject.getUserName(user) : null;
            }
            if (DialogObject.isUserDialog(dialogId)) {
                final TLRPC.User user = controller.getUser(dialogId);
                return user != null ? UserObject.getUserName(user) : null;
            }
            final TLRPC.Chat chat = controller.getChat(-dialogId);
            return chat != null ? chat.title : null;
        } catch (Throwable e) {
            return null;
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

    private static PromptResult once(PromptResult result) {
        final boolean[] done = new boolean[1];
        return value -> {
            if (!done[0]) {
                done[0] = true;
                result.onResult(value);
            }
        };
    }
}

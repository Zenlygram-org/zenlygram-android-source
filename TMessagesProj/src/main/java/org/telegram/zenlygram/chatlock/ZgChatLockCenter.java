/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;
import org.telegram.zenlygram.lock.ZgLockBiometrics;
import org.telegram.zenlygram.lock.ZgLockCode;
import org.telegram.zenlygram.lock.ZgLockThrottle;
import org.telegram.zenlygram.lock.ZgMasterPin;
import org.telegram.zenlygram.lock.ZgPinDialog;
import org.telegram.zenlygram.lock.ZgPinPage;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Chat Lock Center glue (iOS SGChatLockFlows + SGChatLockController): reads the screen's input from the stores, opens
 * the screens, and runs the Center's flows that the older entry points did not have (change the master, master
 * biometrics, change a chat's pincode, a chat's biometrics, the master-first chain, LEGACY turn-off). PIN pages are
 * {@link ZgPinDialog}s; every write runs on the worker thread like the rest of Chat Lock. Main thread.
 */
public final class ZgChatLockCenter {

    private ZgChatLockCenter() {
    }

    /** The Center's input for {@code account}. */
    public static ZgChatLockCenterSpec.Input input(int account) {
        final ZgChatLockCenterSpec.Input input = new ZgChatLockCenterSpec.Input();
        input.masterState = ZgMasterPin.isSet() ? ZgChatLockCenterSpec.MasterState.PRESENT : ZgChatLockCenterSpec.MasterState.ABSENT;
        input.masterBiometric = ZgMasterPin.isBiometricEnabled();
        input.lockedIds = ZgChatLockStore.getLockedIds();
        input.accountPeerId = UserConfig.getInstance(account).getClientUserId();
        final HashSet<Long> hidden = new HashSet<>();
        final HashMap<Long, Long> resolved = new HashMap<>();
        final MessagesController controller = MessagesController.getInstance(account);
        for (long id : input.lockedIds) {
            if (ZgChatLockSession.isVaultHidden(account, id)) {
                hidden.add(id); // fail-closed answer comes from the session
            }
            final Long target = resolve(controller, id);
            if (target != null) {
                resolved.put(id, target);
            }
        }
        input.vaultHidden = hidden;
        input.resolved = resolved;
        input.needsRecovery = ZgChatLockStore.needsRecovery();
        input.pendingCount = input.lockedIds.length;
        input.canAuthDeviceOwner = ZgLockBiometrics.canProveDeviceOwner();
        input.hasBiometrics = ZgLockBiometrics.isStrongAvailable();
        int accounts = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts++;
            }
        }
        input.accountCount = Math.max(1, accounts);
        return input;
    }

    /** null: this account does not know the chat; 0: known, no migration; else the supergroup's dialog id. */
    @Nullable
    private static Long resolve(MessagesController controller, long id) {
        try {
            if (DialogObject.isEncryptedDialog(id)) {
                return controller.getEncryptedChat(DialogObject.getEncryptedChatId(id)) != null ? 0L : null;
            }
            if (DialogObject.isUserDialog(id)) {
                return controller.getUser(id) != null ? 0L : null;
            }
            final TLRPC.Chat chat = controller.getChat(-id);
            if (chat == null) {
                return null;
            }
            if (chat.migrated_to != null && chat.migrated_to.channel_id != 0) {
                return -chat.migrated_to.channel_id;
            }
            return 0L;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The hub row's value (iOS): On while armed or LEGACY, "Needs setup" while recovery is pending, else Off. */
    public static CharSequence hubValue() {
        if (ZgChatLockStore.needsRecovery()) {
            return LocaleController.getString(R.string.ZG_Hub_NeedsSetup);
        }
        final boolean on = ZgMasterPin.isSet() || ZgChatLockStore.getLockedCount() > 0;
        return LocaleController.getString(on ? R.string.ZG_Hub_On : R.string.ZG_Hub_Off);
    }

    public static void open(BaseFragment fragment) {
        fragment.presentFragment(new ZgChatLockCenterActivity());
    }

    /** The per-chat screen of a locked chat (profile "Chat Lock…", a row of the Center). */
    public static void openChat(BaseFragment fragment, long dialogId) {
        fragment.presentFragment(new ZgChatLockChatActivity(dialogId));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Master-first chain, picker
    // ---------------------------------------------------------------------------------------------------------

    /** The set-up explainer, then {@code then}. */
    static void explainer(BaseFragment fragment, ZgChatLockCenterSpec.Explainer kind, Runnable then) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int text = kind == ZgChatLockCenterSpec.Explainer.HUB ? R.string.ZG_CLC_SetupTextFromHub
            : kind == ZgChatLockCenterSpec.Explainer.CHAT ? R.string.ZG_CLC_SetupTextFromChat : R.string.ZG_CLC_SetupTextLegacy;
        new AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.ZG_CLC_SetupTitle))
            .setMessage(LocaleController.getString(text))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.ZG_CLC_Continue), (d, w) -> then.run())
            .show();
    }

    /** "Turn On Chat Lock" (OFF) / "Set Master Pincode" (LEGACY): explainer, master; from OFF the picker follows. */
    public static void armMaster(BaseFragment fragment, boolean legacy, @Nullable Runnable onChanged) {
        explainer(fragment, legacy ? ZgChatLockCenterSpec.Explainer.LEGACY : ZgChatLockCenterSpec.Explainer.HUB,
            () -> ZgChatLockFlows.startEnable(fragment, () -> {
                if (onChanged != null) {
                    onChanged.run();
                }
                if (!legacy) {
                    AndroidUtilities.runOnUIThread(() -> openPicker(fragment), 350);
                }
            }));
    }

    /**
     * "Lock Chat" (chat list, profile, picker): with a master the chat's own pincode; without one the master-first
     * chain (explainer, master, then this chat's pincode), so a chat is never locked without a recovery key.
     */
    public static void lockChat(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || ZgChatLockStore.isLocked(dialogId)) {
            return;
        }
        if (ZgChatLockStore.needsRecovery()) {
            ZgChatLockFlows.startRecovery(fragment, null);
            return;
        }
        if (ZgChatLockStore.isEnabled()) {
            ZgChatLockFlows.startSetPincode(fragment, dialogId);
            return;
        }
        explainer(fragment, ZgChatLockCenterSpec.Explainer.CHAT,
            () -> ZgChatLockFlows.startEnable(fragment, () -> AndroidUtilities.runOnUIThread(() -> ZgChatLockFlows.startSetPincode(fragment, dialogId), 350)));
    }

    /** "Add a Chat…": Telegram's chat picker; the chosen chat gets its own pincode (a locked one opens its screen). */
    public static void openPicker(BaseFragment fragment) {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("allowSwitchAccount", false);
        args.putBoolean("checkCanWrite", false);
        args.putString("selectAlertString", null);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            final long dialogId = dids.get(0).dialogId;
            f.finishFragment();
            final int account = fragment.getCurrentAccount();
            if (!ZgChatLockUi.isLockable(account, dialogId) || ZgChatLockSession.isVaultHidden(account, dialogId)) {
                return true;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (ZgChatLockStore.isLocked(dialogId)) {
                    openChat(fragment, dialogId);
                } else {
                    lockChat(fragment, dialogId);
                }
            }, 300);
            return true;
        });
        fragment.presentFragment(picker);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Master management
    // ---------------------------------------------------------------------------------------------------------

    /** The master's own verify page for a management action (manage purpose: no biometric auto-attempt). */
    private static ZgPinPage masterVerify(BaseFragment fragment, int subtitle, Runnable proven) {
        final int type = ZgMasterPin.getType();
        return ZgPinPage.verify(type, ZgLockThrottle.Scope.MASTER, ZgMasterPin::verify, (dialog, code, viaBiometric, changed) -> {
                dialog.finish();
                proven.run();
            })
            .title(LocaleController.getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_ChatLock_EnterMasterPin : R.string.ZG_ChatLock_EnterMasterPassword))
            .subtitle(LocaleController.getString(subtitle))
            .recovery(LocaleController.getString(R.string.ZG_ChatLock_ResetButton), ZgPinPage.RECOVERY_AFTER_STRUGGLE,
                d -> ZgChatLockFlows.confirmReset(d, () -> {
                    d.finish();
                    ZgChatLockFlows.showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_CLC_ToastOff));
                }))
            .onUnverifiable(d -> d.push(ZgChatLockFlows.recoveryPage(fragment, d::finish)));
    }

    /** F7: the current master, then a new one (same generation: the vault's recovery rung keeps working). */
    public static void changeMaster(BaseFragment fragment, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || !ZgMasterPin.isSet()) {
            return;
        }
        ZgPinDialog.show(activity, masterVerify(fragment, R.string.ZG_CLC_MasterToChange, () -> {
            final ZgPinPage setup = ZgPinPage.setup(ZgMasterPin.getType(), (dialog, code, type) -> {
                    dialog.setBusy(true);
                    final boolean biometric = ZgMasterPin.isBiometricEnabled();
                    ZgPinDialog.runOnWorker(() -> {
                        final boolean ok = ZgMasterPin.set(code, type, biometric);
                        AndroidUtilities.runOnUIThread(() -> {
                            dialog.setBusy(false);
                            if (ok) {
                                dialog.finish();
                                if (onChanged != null) {
                                    onChanged.run();
                                }
                                ZgChatLockFlows.showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_CLC_ToastMasterChanged));
                            } else {
                                dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                            }
                        });
                    });
                })
                .setupTitles(LocaleController.getString(R.string.ZG_ChatLock_SetMasterPin), LocaleController.getString(R.string.ZG_ChatLock_SetMasterPassword))
                .subtitle(LocaleController.getString(R.string.ZG_CLC_CaptionNewMaster));
            AndroidUtilities.runOnUIThread(() -> ZgPinDialog.show(fragment.getParentActivity(), setup), 250);
        }));
    }

    /** F8: off needs no proof (capability only shrinks); on needs the master first. */
    public static void setMasterBiometrics(BaseFragment fragment, boolean enabled, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || !ZgMasterPin.isSet()) {
            return;
        }
        if (!enabled) {
            ZgMasterPin.setBiometricEnabled(false);
            if (onChanged != null) {
                onChanged.run();
            }
            return;
        }
        ZgPinDialog.show(activity, masterVerify(fragment, R.string.ZG_CLC_MasterToEnableBiometrics, () -> ZgPinDialog.runOnWorker(() -> {
            final boolean ok = ZgLockBiometrics.createSlot(ZgMasterPin.BIO_SLOT) && ZgMasterPin.setBiometricEnabled(true);
            AndroidUtilities.runOnUIThread(() -> {
                if (!ok) {
                    ZgChatLockFlows.showBulletin(fragment, R.raw.error, LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                }
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        })));
    }

    /** F9: Telegram-style turn off with the master (the existing flow: confirmation, master or Reset). */
    public static void turnOff(BaseFragment fragment, @Nullable Runnable onChanged) {
        ZgChatLockFlows.startDisable(fragment, onChanged);
    }

    /** F10 (LEGACY): no master to ask for, so device-owner proof, then Chat Lock is turned off. */
    public static void turnOffWithoutMaster(BaseFragment fragment, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || !ZgLockBiometrics.canProveDeviceOwner()) {
            return;
        }
        ZgLockBiometrics.authenticateDeviceOwner(activity, LocaleController.getString(R.string.ZG_CLC_TurnOffWithoutMasterReason), null, false, result -> {
            if (result != ZgLockBiometrics.Result.SUCCESS) {
                return;
            }
            if (ZgChatLockStore.disableChatLock()) {
                if (onChanged != null) {
                    onChanged.run();
                }
                ZgChatLockFlows.showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_CLC_ToastOff));
            } else {
                ZgChatLockFlows.showBulletin(fragment, R.raw.error, LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Per-chat management
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A chat's own verify page for a management action: the chat's code is the proof; "Forgot pincode?" takes the
     * master instead (which keeps the lock for change / biometrics and removes it for removal: {@code viaMaster}).
     */
    private static ZgPinPage chatVerify(BaseFragment fragment, long dialogId, int subtitle, int ladderSubtitle, ProofListener proven) {
        final int type = ZgChatLockStore.getType(dialogId);
        final ZgPinPage page = ZgPinPage.verify(type, ZgLockThrottle.Scope.chat(dialogId), code -> ZgChatLockStore.verify(dialogId, code),
                (dialog, code, viaBiometric, changed) -> proven.onProven(dialog, false))
            .title(LocaleController.getString(type == ZgLockCode.TYPE_PIN ? R.string.ZG_Lock_ConfirmPin : R.string.ZG_Lock_ConfirmPassword))
            .subtitle(LocaleController.getString(subtitle))
            .onUnverifiable(d -> d.push(ZgChatLockFlows.recoveryPage(fragment, d::finish)));
        if (ZgMasterPin.isSet()) {
            page.recovery(LocaleController.getString(R.string.ZG_ChatLock_ForgotPincode), ZgPinPage.RECOVERY_ALWAYS, d -> {
                final int masterType = ZgMasterPin.getType();
                d.push(ZgPinPage.verify(masterType, ZgLockThrottle.Scope.MASTER, ZgMasterPin::verify,
                        (dialog, code, viaBiometric, changed) -> proven.onProven(dialog, true))
                    .title(LocaleController.getString(masterType == ZgLockCode.TYPE_PIN ? R.string.ZG_ChatLock_EnterMasterPin : R.string.ZG_ChatLock_EnterMasterPassword))
                    .subtitle(LocaleController.getString(ladderSubtitle))
                    .recovery(LocaleController.getString(R.string.ZG_ChatLock_ResetButton), ZgPinPage.RECOVERY_AFTER_STRUGGLE,
                        dd -> ZgChatLockFlows.confirmReset(dd, dd::finish)));
            });
        }
        return page;
    }

    interface ProofListener {
        void onProven(ZgPinDialog dialog, boolean viaMaster);
    }

    /** F4: the current code (or the master), then a new code for the chat; the lock is kept. */
    public static void changePincode(BaseFragment fragment, long dialogId, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || !ZgChatLockStore.isLocked(dialogId)) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        ZgPinDialog.show(activity, chatVerify(fragment, dialogId, R.string.ZG_CLC_VerifyToChange, R.string.ZG_CLC_MasterLadderForChange, (dialog, viaMaster) -> {
            final CharSequence name = ZgChatLockFlows.displayName(account, dialogId);
            dialog.push(ZgPinPage.setup(ZgChatLockStore.getType(dialogId), (d, code, type) -> {
                    d.setBusy(true);
                    final boolean biometric = ZgChatLockStore.isBiometricEnabled(dialogId);
                    ZgPinDialog.runOnWorker(() -> {
                        final boolean ok = ZgChatLockStore.setPincode(dialogId, code, type, biometric);
                        AndroidUtilities.runOnUIThread(() -> {
                            d.setBusy(false);
                            if (ok) {
                                d.finish();
                                if (onChanged != null) {
                                    onChanged.run();
                                }
                                ZgChatLockFlows.showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_CLC_ToastPincodeChanged));
                            } else {
                                d.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                            }
                        });
                    });
                })
                .subtitle(name != null ? LocaleController.formatString(R.string.ZG_CLC_CaptionNewChat, name)
                    : LocaleController.getString(R.string.ZG_CLC_CaptionNewChatGeneric)));
        }));
    }

    /** F5: the chat's code (or the master); the lock goes, with every locked legacy group that maps to this chat. */
    public static void removeLock(BaseFragment fragment, long dialogId, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || !ZgChatLockStore.isLocked(dialogId)) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        ZgPinDialog.show(activity, chatVerify(fragment, dialogId, R.string.ZG_CLC_VerifyToRemove, R.string.ZG_CLC_MasterLadderForRemove, (dialog, viaMaster) -> {
            final ZgChatLockCenterSpec.Input input = input(account);
            boolean ok = true;
            for (long id : ZgChatLockCenterSpec.removalSet(dialogId, input.lockedIds, input.resolved)) {
                ok &= ZgChatLockStore.removePincode(id);
            }
            if (ok) {
                dialog.finish();
                if (onChanged != null) {
                    onChanged.run();
                }
                org.telegram.messenger.zenlygram.customer.ZenlyStats.chatLockRemoved(account); // ZENLYGRAM: statistics, chat_lock.lock_removed, one per removal (recorded only with the data consent)
                ZgChatLockFlows.showBulletin(fragment, R.raw.done, LocaleController.getString(R.string.ZG_CLC_ToastLockRemoved));
            } else {
                dialog.showError(LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
            }
        }));
    }

    /** F6: off without proof; on after the chat's code (a master proof sets a new chat code first). */
    public static void setChatBiometrics(BaseFragment fragment, long dialogId, boolean enabled, @Nullable Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null || !ZgChatLockStore.isLocked(dialogId)) {
            return;
        }
        final String slot = ZgChatLockStore.bioSlot(dialogId);
        if (!enabled) {
            ZgChatLockStore.setBiometricEnabled(dialogId, false);
            ZgLockBiometrics.deleteSlot(slot);
            if (onChanged != null) {
                onChanged.run();
            }
            return;
        }
        ZgPinDialog.show(activity, chatVerify(fragment, dialogId, R.string.ZG_CLC_VerifyToEnableBiometrics, R.string.ZG_CLC_MasterLadderForChange, (dialog, viaMaster) -> {
            if (viaMaster) {
                // Biometrics are never enabled on a code the owner cannot enter: a new chat code comes first.
                dialog.finish();
                AndroidUtilities.runOnUIThread(() -> changePincode(fragment, dialogId, () -> enableChatBiometricsNow(fragment, dialogId, onChanged)), 250);
                return;
            }
            dialog.setBusy(true);
            ZgPinDialog.runOnWorker(() -> {
                final boolean ok = ZgLockBiometrics.createSlot(slot) && ZgChatLockStore.setBiometricEnabled(dialogId, true);
                AndroidUtilities.runOnUIThread(() -> {
                    dialog.setBusy(false);
                    dialog.finish();
                    if (!ok) {
                        ZgChatLockFlows.showBulletin(fragment, R.raw.error, LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                });
            });
        }));
    }

    /** After a proof (a fresh chat code): the chat's biometric slot, without asking again. */
    private static void enableChatBiometricsNow(BaseFragment fragment, long dialogId, @Nullable Runnable onChanged) {
        final String slot = ZgChatLockStore.bioSlot(dialogId);
        ZgPinDialog.runOnWorker(() -> {
            final boolean ok = ZgLockBiometrics.createSlot(slot) && ZgChatLockStore.setBiometricEnabled(dialogId, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (!ok) {
                    ZgChatLockFlows.showBulletin(fragment, R.raw.error, LocaleController.getString(R.string.ZG_ChatLock_SaveFailed));
                }
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        });
    }
}

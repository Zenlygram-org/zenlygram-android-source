/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.DialogsActivity;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.lock.ZgLockCode;
import org.telegram.zenlygram.lock.ZgLockCrypto;
import org.telegram.zenlygram.lock.ZgLockThrottle;
import org.telegram.zenlygram.lock.ZgPinDialog;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Reveal with a secret code" (Settings &gt; Zenlygram &gt; Secret Vault): typing the full vault PIN into the main chat
 * list's search opens the vault (iOS SecretVaultCodeGate wiring). Hooks (marked ZENLYGRAM): DialogsActivity search
 * expand / collapse / text / keyboard Search, DialogsSearchAdapter and SearchViewPager ({@link #holdsRemote}: while the
 * mode is on, nothing typed is sent to Telegram's servers until it is proven not to be the PIN). While the mode is on,
 * the title long-press does not reveal the vault (iOS: no other entry). Checks run on one worker thread; the PIN
 * screens' VAULT budget is never spent from here, only VAULT_SEARCH.
 */
public final class ZgVaultSecretCode {

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "zg-vault-code");
        t.setDaemon(true);
        return t;
    });

    @Nullable
    private static ZgVaultCodeGate gate;
    @Nullable
    private static DialogsActivity host;

    private ZgVaultSecretCode() {
    }

    /** The switch is on and the vault is on: the mode the gate sees. */
    static ZgVaultCodeGate.Mode mode() {
        if (!ZgSettings.secretVaultUseSecretCode.get() || !ZgVault.isEnabled()) {
            return ZgVaultCodeGate.Mode.OFF;
        }
        if (ZgVault.needsRecovery() || ZgVault.isCredentialUnverifiable() || ZgVault.isTemporarilyUnavailable() || !ZgVault.hasCredential()) {
            return ZgVaultCodeGate.Mode.HOLD_ONLY;
        }
        return ZgVaultCodeGate.Mode.ARMED;
    }

    /** Whether the title long-press may still reveal the vault. */
    public static boolean allowsTitleReveal() {
        return !ZgSettings.secretVaultUseSecretCode.get();
    }

    public static void begin(DialogsActivity fragment) {
        if (fragment == null || !fragment.zgIsMainChatList()) {
            return;
        }
        host = fragment;
        gate = new ZgVaultCodeGate(new AndroidEnv(fragment.getCurrentAccount()));
    }

    public static void end(DialogsActivity fragment) {
        if (host == fragment) {
            host = null;
            gate = null;
        }
    }

    public static void onTextChanged(DialogsActivity fragment, String text) {
        if (gate == null && fragment != null && fragment.zgIsMainChatList()) {
            begin(fragment);
        }
        final ZgVaultCodeGate g = gate;
        if (g != null && host == fragment) {
            g.textChanged(text);
        }
    }

    /** Main thread, any thread for the adapter: whether the remote part of a search for {@code text} must wait. */
    public static boolean holdsRemote(String text) {
        final ZgVaultCodeGate g = gate;
        if (g == null || TextUtils.isEmpty(text) || mode() == ZgVaultCodeGate.Mode.OFF) {
            return false;
        }
        return !g.isReleased(text);
    }

    /** Keyboard Search: the only path that may release held text. */
    public static void onSubmitted(DialogsActivity fragment, String text) {
        final ZgVaultCodeGate g = gate;
        if (g == null || host != fragment || TextUtils.isEmpty(text)) {
            return;
        }
        worker.execute(() -> {
            final ZgVaultCodeGate.Outcome outcome = g.textSubmitted(text);
            AndroidUtilities.runOnUIThread(() -> {
                if (host != fragment) {
                    return;
                }
                switch (outcome) {
                    case SEARCH:
                        fragment.zgRerunSearch(text);
                        break;
                    case HOLD_EXPLAINED:
                        try {
                            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.ZG_Vault_SearchHeld)).show();
                        } catch (Throwable ignore) {
                        }
                        break;
                    default:
                        break;
                }
            });
        });
    }

    /** The PIN matched (worker thread): the search closes and the vault opens. */
    private static void revealed() {
        AndroidUtilities.runOnUIThread(() -> {
            final DialogsActivity fragment = host;
            if (fragment == null || fragment.isFinished || fragment.getParentActivity() == null) {
                return;
            }
            fragment.zgCloseSearch();
            ZgVaultFlows.revealByCode(fragment);
        });
    }

    /** Settings row "Reveal with a secret code": both directions prove the vault PIN (iOS audit #27). */
    public static void onSwitchClick(BaseFragment fragment, @Nullable Runnable refresh) {
        if (fragment == null || fragment.getParentActivity() == null || !ZgVault.isUsable()) {
            return;
        }
        final boolean target = !ZgSettings.secretVaultUseSecretCode.get();
        ZgPinDialog.show(fragment.getParentActivity(), ZgVaultFlows.verifyPage(ZgVaultFlows.PURPOSE_SETTINGS, fragment,
            LocaleController.getString(R.string.ZG_Vault_SecretCodeSubtitle), dialog -> {
                ZgSettings.secretVaultUseSecretCode.set(target);
                dialog.finish();
                if (refresh != null) {
                    refresh.run();
                }
            }));
    }

    private static final class AndroidEnv implements ZgVaultCodeGate.Env {
        private final HashMap<Runnable, Runnable> scheduled = new HashMap<>();

        AndroidEnv(int account) {
        }

        @Override
        public ZgVaultCodeGate.Mode mode() {
            return ZgVaultSecretCode.mode();
        }

        @Override
        public boolean isPin4() {
            return ZgVault.getCredentialType() == ZgLockCode.TYPE_PIN;
        }

        @Override
        public boolean isThrottled() {
            return ZgLockThrottle.remainingSeconds(ZgLockThrottle.Scope.VAULT_SEARCH) > 0
                || ZgLockThrottle.remainingSeconds(ZgLockThrottle.Scope.VAULT) > 0;
        }

        @Override
        public ZgVaultCodeGate.Verdict check(String text) {
            final ZgLockCrypto.Verdict verdict = ZgVault.verifyCredential(text);
            if (verdict == ZgLockCrypto.Verdict.MATCH) {
                return ZgVaultCodeGate.Verdict.MATCH;
            }
            if (verdict == ZgLockCrypto.Verdict.MISMATCH) {
                return ZgVaultCodeGate.Verdict.MISMATCH;
            }
            return ZgVaultCodeGate.Verdict.UNAVAILABLE;
        }

        @Override
        public void registerFailure() {
            ZgLockThrottle.registerFailure(ZgLockThrottle.Scope.VAULT_SEARCH);
        }

        @Override
        public synchronized void schedule(Runnable runnable, long delayMs) {
            final Runnable onMain = () -> worker.execute(runnable);
            scheduled.put(runnable, onMain);
            AndroidUtilities.runOnUIThread(onMain, delayMs);
        }

        @Override
        public synchronized void cancel(Runnable runnable) {
            final Runnable onMain = scheduled.remove(runnable);
            if (onMain != null) {
                AndroidUtilities.cancelRunOnUIThread(onMain);
            }
        }

        @Override
        public void onReveal() {
            revealed();
        }
    }
}

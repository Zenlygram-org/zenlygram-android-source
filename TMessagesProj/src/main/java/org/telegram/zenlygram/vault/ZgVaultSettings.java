/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * The Secret Vault rows of Settings > Zenlygram > Hidden &amp; locked chats (iOS SGSettingsController vault rows):
 *
 * <ul>
 *   <li>"Secret Vault" (a controlled switch: it moves only after its flow succeeded). Off: Pro gate, then a new PIN
 *       (4 digits by default, "Switch to password"), the biometrics offer and the reveal hint. On: the PIN (or "Forgot
 *       pincode?"), then every hidden chat comes back. Needs recovery: the recovery page.</li>
 *   <li>"Change Vault PIN" while the vault works.</li>
 *   <li>"Reveal with a secret code": typing the full PIN into the chat-list search opens the vault instead of the title
 *       long-press (ZgVaultSecretCode); both directions of the switch ask for the vault PIN.</li>
 * </ul>
 */
public final class ZgVaultSettings {

    private ZgVaultSettings() {
    }

    /** The switch reads "on" and the PIN rows show only while the vault really works. */
    public static boolean isUsable() {
        return ZgVault.isUsable();
    }

    public static boolean isEnabled() {
        return ZgVault.isEnabled();
    }

    /** On, but the PIN or the list of hidden chats can't be used on this device any more. */
    public static boolean needsRecovery() {
        return ZgVault.needsRecovery();
    }

    /** "Reveal with a secret code" (typing the PIN into chat search, ZgVaultSecretCode). */
    public static boolean isSecretCodeAvailable() {
        return ZgVault.isPorted();
    }

    public static void onSwitchClick(BaseFragment fragment, @Nullable Runnable refresh) {
        ZgVaultFlows.onSwitchClick(fragment, refresh);
    }

    public static void onChangePinClick(BaseFragment fragment, @Nullable Runnable refresh) {
        ZgVaultFlows.startChangePin(fragment, refresh);
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.zenlygram.chatlock.ZgProtectionLayer;

/**
 * The Secret Vault as Chat Lock's second protection layer (iOS ChatLockSession+Vault). Registered at start-up by
 * {@link ZgVault#init()}. Through it every Chat Lock surface treats a hidden chat like a locked one: chat-list rows,
 * notifications and search are redacted, and a hidden chat opened from anywhere outside the vault (link, mention,
 * profile "Message", shortcut, widget, a screen restored after process death) is covered by Chat Lock's gate until the
 * vault PIN is entered ({@link #requireOpen}). No hidden chat is ever named on a PIN screen or a cover.
 */
final class ZgVaultLayer implements ZgProtectionLayer {

    static final ZgVaultLayer INSTANCE = new ZgVaultLayer();

    private ZgVaultLayer() {
    }

    @Override
    public boolean isHidden(int account, long dialogId) {
        return ZgVault.isHidden(account, dialogId);
    }

    @Override
    public boolean isOpen(int account) {
        return ZgVaultSession.isOpen(account);
    }

    /** Rows of hidden chats appear only in the vault list, which exists only while the vault is open. */
    @Override
    public boolean showsRows(int account) {
        return ZgVaultSession.isOpen(account);
    }

    @Override
    public boolean failsClosed() {
        return ZgVault.isFailingClosed();
    }

    @Override
    public void requireOpen(int account, @Nullable BaseFragment host, OpenCallback callback) {
        if (ZgVaultSession.isOpen(account)) {
            callback.onResult(true);
            return;
        }
        ZgVaultFlows.requestOpenForChat(account, host, callback);
    }
}

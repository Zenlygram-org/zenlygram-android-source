/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.BaseFragment;

/**
 * The second protection layer that Chat Lock consults, implemented by the Secret Vault and registered with
 * {@link ZgChatLockSession#setProtectionLayer} (iOS ChatLockSession+Vault).
 *
 * <ul>
 *   <li>Content of a chat is visible only when the Chat Lock grant exists (if it is locked) AND, if the chat is hidden
 *       in the vault, that account's vault is open.</li>
 *   <li>A vaulted and locked chat asks for the vault PIN first (generic prompt, never naming the chat), then for the
 *       chat's own pincode. A closed vault never prompts by itself on resume.</li>
 *   <li>Chat Lock never names a vaulted chat (PIN screen subtitle, cover title) and redacts its notifications and
 *       chat-list rows like a locked chat's ({@link ZgChatLockSession#hidesContentGlobally}).</li>
 * </ul>
 */
public interface ZgProtectionLayer {

    interface OpenCallback {
        void onResult(boolean opened);
    }

    /**
     * Any thread (DialogCell, notificationsQueue): whether {@code dialogId} is hidden in the vault of
     * {@code account} (an account index), whatever the vault's open state. Must be cheap and never throw.
     */
    boolean isHidden(int account, long dialogId);

    /** Main thread: whether the vault of {@code account} is open now. */
    @MainThread
    boolean isOpen(int account);

    /**
     * Main thread: asks for the vault PIN (generic copy, no "Forgot" needed) because a hidden chat is being opened
     * from {@code host}; calls back once, with true when the vault of {@code account} is open afterwards.
     */
    @MainThread
    void requireOpen(int account, @Nullable BaseFragment host, OpenCallback callback);

    /**
     * Any thread: whether chat-list rows of chats hidden in the vault of {@code account} may show their text right now
     * (the vault list of an open vault). A chat that is also Chat-Locked stays redacted regardless.
     */
    default boolean showsRows(int account) {
        return false;
    }

    /**
     * Any thread: the layer hides chats but cannot tell which ones right now (the vault's key store was busy at
     * start-up): every notification is redacted until it can (like Telegram's passcode does).
     */
    default boolean failsClosed() {
        return false;
    }
}

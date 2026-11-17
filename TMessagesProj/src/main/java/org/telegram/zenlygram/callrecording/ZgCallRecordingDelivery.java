/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.chatlock.ZgChatLockStore;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.vault.ZgVault;
import org.telegram.zenlygram.vault.ZgVaultHooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;

/**
 * Sending a finished recording to the recording account's own Saved Messages (port of iOS SGCallRecordingDelivery,
 * reduced to its user-visible rules):
 * <ul>
 *   <li>Automatic only with "Send to Saved Messages" on, only for an open (unprotected) recording with audio; never
 *   for a protected or unverifiable one.</li>
 *   <li>The message is an audio file with the caption "Call recording · name · date"; the original stays in Call
 *   Recordings until the server confirms the message, then it is removed (Telegram may keep cached copies).</li>
 *   <li>A send that is not confirmed while the app runs is shown as "not sent", with "Try sending again".</li>
 * </ul>
 */
public final class ZgCallRecordingDelivery {

    private static final String SEND_DIR = "zg_call_send";
    /** Recording ids with a send in flight in this process. */
    private static final HashSet<String> inFlight = new HashSet<>();

    private ZgCallRecordingDelivery() {
    }

    /** ZgCallRecorder: a recording was saved. */
    static void recordingFinished(String id) {
        final ZgCallRecordingMetadata m = ZgCallRecordingStore.readMetadata(id);
        if (m == null) {
            return;
        }
        if (ZgCallRecordingPolicy.shouldAutoSend(ZgSettings.callRecordingSendToSavedMessages.get(), privacy(m), true)) {
            send(m);
        }
    }

    public static boolean isSending(String id) {
        return inFlight.contains(id);
    }

    /** A "sending" sidecar with nothing in flight in this process: the send was never confirmed. */
    public static boolean isFailed(ZgCallRecordingMetadata m) {
        return ZgCallRecordingMetadata.DELIVERY_FAILED.equals(m.delivery)
            || ZgCallRecordingMetadata.DELIVERY_SENDING.equals(m.delivery) && !inFlight.contains(m.id);
    }

    public static ZgCallRecordingPolicy.Privacy privacy(ZgCallRecordingMetadata m) {
        final boolean configured = ZgVault.isEnabled() || ZgChatLockStore.isEnabled();
        return ZgCallRecordingPolicy.privacy(m.accountUserId, m.peerId, configured, (peerId, accountUserId) -> {
            if (!configured) {
                return ZgCallRecordingPolicy.PeerProtection.NONE;
            }
            if (ZgChatLockStore.isLocked(peerId)) {
                return ZgCallRecordingPolicy.PeerProtection.PROTECTED;
            }
            final int slot = accountSlot(accountUserId);
            if (slot < 0) {
                return ZgCallRecordingPolicy.PeerProtection.UNKNOWN;
            }
            return ZgVaultHooks.isHidden(slot, peerId) ? ZgCallRecordingPolicy.PeerProtection.PROTECTED : ZgCallRecordingPolicy.PeerProtection.NONE;
        });
    }

    /** The account slot signed in as {@code userId}, -1 if none. */
    static int accountSlot(Long userId) {
        if (userId == null) {
            return -1;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated() && config.getClientUserId() == userId) {
                return a;
            }
        }
        return -1;
    }

    /** Result of a manual or automatic send request. */
    public enum SendResult { QUEUED, ACCOUNT_UNAVAILABLE, REFUSED, NOT_QUEUED }

    public static SendResult send(ZgCallRecordingMetadata m) {
        if (m == null || inFlight.contains(m.id) || privacy(m) != ZgCallRecordingPolicy.Privacy.OPEN) {
            return SendResult.REFUSED;
        }
        final int account = accountSlot(m.accountUserId);
        if (account < 0) {
            return SendResult.ACCOUNT_UNAVAILABLE;
        }
        final File source = ZgCallRecordingStore.audioFile(m.id);
        if (source == null || !source.isFile() || source.length() == 0) {
            return SendResult.NOT_QUEUED;
        }
        final String date = LocaleController.formatDateTime((long) m.startTimestamp, false);
        final String title = m.peerTitle.isEmpty() ? getString(R.string.ZG_CallRecording_UnknownPeer) : m.peerTitle;
        final String caption = formatString(R.string.ZG_CallRecording_SendToSaved_Caption, title, date);
        // Telegram refuses to send app-private files, so the upload reads a copy in its own cache directory.
        final File copy = copyForSending(source, ZgCallRecordingFormat.sanitizedFileName(title + " " + date + ZgCallRecordingFormat.EXTENSION));
        if (copy == null) {
            return SendResult.NOT_QUEUED;
        }
        final long self = UserConfig.getInstance(account).getClientUserId();
        try {
            SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), copy.getAbsolutePath(), copy.getAbsolutePath(), null,
                caption, ZgCallRecordingFormat.MIME, self, null, null, null, null, null, true, 0, null, null, false);
        } catch (Throwable e) {
            FileLog.e(e);
            copy.delete();
            return SendResult.NOT_QUEUED;
        }
        inFlight.add(m.id);
        ZgCallRecordingStore.writeMetadata(m.withDelivery(ZgCallRecordingMetadata.DELIVERY_SENDING));
        observe(account, self, m.id, copy, caption);
        return SendResult.QUEUED;
    }

    private static void observe(int account, long self, String id, File copy, String caption) {
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        holder[0] = (event, acc, args) -> {
            if (args.length < 3 || !(args[2] instanceof TLRPC.Message)) {
                return;
            }
            final TLRPC.Message message = (TLRPC.Message) args[2];
            final boolean mine = message.dialog_id == self || message.peer_id != null && message.peer_id.user_id == self;
            final boolean same = copy.getAbsolutePath().equals(message.attachPath) || caption.equals(message.message);
            if (!mine || !same) {
                return;
            }
            center.removeObserver(holder[0], NotificationCenter.messageReceivedByServer);
            inFlight.remove(id);
            // Confirmed by the server: the original leaves Call Recordings, as the setting's description says.
            ZgCallRecordingStore.delete(id);
            AndroidUtilities.runOnUIThread(copy::delete, 60_000);
            ZgCallRecordingsActivity.notifyChanged();
        };
        center.addObserver(holder[0], NotificationCenter.messageReceivedByServer);
    }

    private static File copyForSending(File source, String name) {
        try {
            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (dir == null) {
                return null;
            }
            dir = new File(dir, SEND_DIR + "/" + System.currentTimeMillis());
            if (!dir.mkdirs()) {
                return null;
            }
            final File out = new File(dir, name);
            try (FileInputStream in = new FileInputStream(source); FileOutputStream os = new FileOutputStream(out)) {
                final byte[] buffer = new byte[64 * 1024];
                int r;
                while ((r = in.read(buffer)) > 0) {
                    os.write(buffer, 0, r);
                }
            }
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}

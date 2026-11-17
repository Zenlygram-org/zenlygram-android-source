/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which recordings belong to a protected chat, and what one row of the recordings list may show and offer (port of
 * iOS SGCallRecordingPrivacy + SGCallRecordingListPolicy). Free of UI and of the protection stores: the caller passes
 * the verdicts in, so the rules are testable.
 *
 * <p>FAIL-CLOSED: protection is evaluated for the ACTUAL recorded pair (account + peer). A recording whose identity is
 * unknown is open only when no protection is configured at all; otherwise it is unverifiable. Android difference: iOS
 * lets a protected recording be played after the chat's own unlock; Android has no per-recording unlock flow yet, so
 * a protected recording is handled like an unverifiable one (listed redacted, Delete only).
 */
public final class ZgCallRecordingPolicy {

    public enum Privacy { OPEN, PROTECTED, UNVERIFIABLE }

    public enum PeerProtection { NONE, PROTECTED, UNKNOWN }

    public enum Badge {
        LIVE, PLAYING, SEND_FAILED, INCOMPLETE, SENDING;

        public boolean isWarning() {
            return this == SEND_FAILED || this == INCOMPLETE;
        }
    }

    public interface ProtectionLookup {
        PeerProtection protection(long peerId, long accountUserId);
    }

    private ZgCallRecordingPolicy() {
    }

    public static Privacy privacy(Long accountUserId, Long peerId, boolean isProtectionConfigured, ProtectionLookup lookup) {
        if (accountUserId == null || peerId == null) {
            return isProtectionConfigured ? Privacy.UNVERIFIABLE : Privacy.OPEN;
        }
        switch (lookup.protection(peerId, accountUserId)) {
            case NONE:
                return Privacy.OPEN;
            case PROTECTED:
                return Privacy.PROTECTED;
            default:
                return Privacy.UNVERIFIABLE;
        }
    }

    /** What one row shows and offers. */
    public static final class Row {
        public final boolean isRedacted;
        public final List<Badge> badges;
        public final boolean showsDate;
        public final boolean showsDuration;
        public final boolean canPlay;
        public final boolean canShare;
        public final boolean canSend;

        public Row(Privacy privacy, boolean isLive, boolean isPlaying, boolean isSendWaiting, boolean isSendFailed, boolean isComplete, boolean isExportAvailable) {
            final ArrayList<Badge> list = new ArrayList<>();
            if (isLive) {
                list.add(Badge.LIVE);
            } else {
                if (isPlaying) {
                    list.add(Badge.PLAYING);
                }
                if (isSendFailed) {
                    list.add(Badge.SEND_FAILED);
                }
                if (!isComplete) {
                    list.add(Badge.INCOMPLETE);
                }
                if (isSendWaiting) {
                    list.add(Badge.SENDING);
                }
            }
            badges = Collections.unmodifiableList(list);
            isRedacted = privacy != Privacy.OPEN;
            showsDate = !isRedacted;
            showsDuration = !isRedacted && !isLive;
            canPlay = privacy == Privacy.OPEN && !isLive;
            canShare = canPlay && isExportAvailable;
            canSend = canPlay && isExportAvailable && !isSendWaiting;
        }

        public Badge primaryBadge() {
            return badges.isEmpty() ? null : badges.get(0);
        }
    }

    /** Automatic delivery to Saved Messages: only switched on, only open, only finished recordings with audio. */
    public static boolean shouldAutoSend(boolean sendToSavedEnabled, Privacy privacy, boolean hasAudio) {
        return sendToSavedEnabled && privacy == Privacy.OPEN && hasAudio;
    }
}

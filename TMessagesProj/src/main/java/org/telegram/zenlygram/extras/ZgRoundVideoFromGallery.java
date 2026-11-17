/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.extras;

import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedFileNative;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.ChatAttachAlertPhotoLayout;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.voip.AnimatedFileInfo;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Round Video from Gallery (iOS sendRoundVideoFromGallery, on by default): one video picked in the attach menu is
 * sent as a round video message.
 *
 * <p>Entry point: long-press the Send button of the attach menu with exactly one video selected, then
 * "Send as Round Video". Like iOS, the video is centre-cropped to a square and only its first 60 seconds are used.
 * A round video has no caption, price, spoiler, cover or edits, so the option is not offered for a video that has a
 * caption (its own or the attach menu's), a paid-media price, a spoiler, a cover or edits from the photo editor (trim,
 * crop, mute, filters, drawing): sending would silently drop what the user set. The send menu stays open while a price
 * is set in it ("Paid media"), so the tap checks again and only explains instead of sending. A self-destruct timer
 * (view once) and the message effect chosen in the send preview are kept: round videos support both.
 * The work is Telegram's own: a VideoEditedInfo with roundVideo = true, a centred square CropState and the round
 * video size and bitrate the server configures, sent through ChatActivity.sendMedia, so MediaCodecVideoConvertor
 * transcodes it and the reply, topic, slow mode and paid-message checks of a normal send apply. The document gets
 * the round_message attribute in SendMessagesHelper.prepareSendingVideo.
 */
public final class ZgRoundVideoFromGallery {

    /** Telegram's limit for a round video message. */
    private static final long MAX_DURATION_MS = 60_000L;

    private ZgRoundVideoFromGallery() {
    }

    /**
     * Adds "Send as Round Video" to the send-button options of the attach menu when it applies.
     *
     * @param selectedEffect the message effect chosen in the long-press send preview (0 = none); read once, on the tap
     * @param dismissPreview closes the long-press send preview (it owns the options sheet); true = the media was sent
     */
    public static void addSendOption(ChatAttachAlert alert, ItemOptions options, ChatActivity chatActivity,
                                     ChatAttachAlertPhotoLayout photoLayout, Utilities.Callback0Return<Long> selectedEffect,
                                     Utilities.Callback<Boolean> dismissPreview) {
        if (!ZgFeatures.ROUND_VIDEO_FROM_GALLERY.isPorted() || !ZgSettings.sendRoundVideoFromGallery.get()
            || alert == null || options == null || chatActivity == null || photoLayout == null) {
            return;
        }
        final MediaController.PhotoEntry entry = getSingleSelectedVideo(photoLayout);
        if (entry == null || !canSendRoundVideo(chatActivity) || hasSomethingRoundVideosDrop(alert, entry)) {
            return;
        }
        options.add(R.drawable.msg_video, getString(R.string.ZG_ExtrasB_SendAsRoundVideo), () -> {
            // The menu stays open while "Paid media" below sets a price (and a spoiler) on the entry: check again.
            if (hasSomethingRoundVideosDrop(alert, entry)) {
                if (dismissPreview != null) {
                    dismissPreview.run(false);
                }
                BulletinFactory.of(alert.sizeNotifierFrameLayout, alert.getResourcesProvider())
                    .createErrorBulletin(getString(R.string.ZG_ExtrasB_RoundVideoDrops)).show();
                return;
            }
            final Long effect = selectedEffect != null ? selectedEffect.run() : null; // before the preview closes
            if (dismissPreview != null) {
                dismissPreview.run(true);
            }
            alert.setButtonPressed(true);
            alert.dismiss(true);
            send(chatActivity, entry, effect != null ? effect : 0);
        });
    }

    private static MediaController.PhotoEntry getSingleSelectedVideo(ChatAttachAlertPhotoLayout photoLayout) {
        final ArrayList<Object> order = photoLayout.getSelectedPhotosOrder();
        final HashMap<Object, Object> selected = photoLayout.getSelectedPhotos();
        if (order == null || selected == null || order.size() != 1 || selected.size() != 1) {
            return null;
        }
        final Object object = selected.get(order.get(0));
        if (!(object instanceof MediaController.PhotoEntry)) {
            return null;
        }
        final MediaController.PhotoEntry entry = (MediaController.PhotoEntry) object;
        if (!entry.isVideo || entry.path == null || entry.isLivePhoto) {
            return null;
        }
        return entry;
    }

    /**
     * A caption (the video's own or the attach menu's), a paid-media price, a spoiler, a cover or photo-editor edits:
     * a round video would drop them.
     */
    static boolean hasSomethingRoundVideosDrop(ChatAttachAlert alert, MediaController.PhotoEntry entry) {
        if (isEdited(entry.editedInfo) || entry.hasSpoiler || entry.starsAmount > 0 || !TextUtils.isEmpty(entry.caption)
            || entry.coverPath != null || entry.coverPhoto != null
            || entry.stickers != null && !entry.stickers.isEmpty() || entry.isCropped || entry.isPainted || entry.isFiltered) {
            return true;
        }
        final EditTextEmoji comment = alert != null ? alert.getCommentView() : null;
        return comment != null && comment.getText() != null && !TextUtils.isEmpty(comment.getText().toString().trim());
    }

    /**
     * Whether the photo editor changed the video: trimmed, muted, cropped or rotated, filtered or drawn on. Only opening
     * the video in the viewer also fills editedInfo (quality, untrimmed times -1), which is not an edit.
     */
    static boolean isEdited(VideoEditedInfo info) {
        return info != null && (info.startTime > 0 || info.endTime > 0 || info.muted || info.cropState != null
            || info.filterState != null || info.paintPath != null || info.mediaEntities != null && !info.mediaEntities.isEmpty());
    }

    /** The chat accepts round videos: ChatObject.canSendRoundVideo for groups and channels, the user's privacy setting for users. */
    private static boolean canSendRoundVideo(ChatActivity chatActivity) {
        final TLRPC.Chat chat = chatActivity.getCurrentChat();
        if (chat != null) {
            return ChatObject.canSendRoundVideo(chat);
        }
        final TLRPC.UserFull userFull = chatActivity.getCurrentUserInfo();
        return userFull == null || !userFull.voice_messages_forbidden;
    }

    /** Reads the video on a background thread, then sends it from the UI thread. */
    public static void send(ChatActivity chatActivity, MediaController.PhotoEntry source, long effectId) {
        final String path = source.path;
        final int account = chatActivity.getCurrentAccount();
        final MediaController.PhotoEntry clean = new MediaController.PhotoEntry(source.bucketId, source.imageId,
            source.dateTaken, path, source.orientation, source.duration, true, source.width, source.height, source.size);
        clean.ttl = source.ttl; // view once / self-destruct: round videos support it, like the round camera's
        clean.effectId = effectId; // the round camera keeps its effect the same way (InstantCameraView: entry.effectId)
        Utilities.globalQueue.postRunnable(() -> {
            VideoEditedInfo info = null;
            try {
                info = createRoundVideoInfo(account, path);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final VideoEditedInfo result = info;
            AndroidUtilities.runOnUIThread(() -> {
                if (chatActivity.isFinished || chatActivity.getParentActivity() == null) {
                    return;
                }
                if (result == null) {
                    BulletinFactory.of(chatActivity).createErrorBulletin(getString(R.string.ZG_ExtrasB_RoundVideoUnsupported)).show();
                    return;
                }
                org.telegram.messenger.zenlygram.customer.ZenlyStats.featureUsed(account, org.telegram.messenger.zenlygram.customer.CustomerEvents.Feature.ROUND_VIDEO_FROM_GALLERY); // ZENLYGRAM: statistics, feature.used (recorded only with the data consent)
                chatActivity.zgSendGalleryRoundVideo(clean, result); // sendMedia without the round camera's close / transition
            });
        });
    }

    /**
     * The conversion settings: a centred square crop scaled to the round video size, Telegram's round video bitrate,
     * the first 60 s. Null when the device cannot transcode the file.
     *
     * <p>MediaCodec decodes into a surface with the track rotation applied, so the crop is expressed in display
     * coordinates (TextureRenderer draws the frame (1 / cropPw) x (1 / cropPh) times the output size, centred), the
     * same convention PhotoViewer uses for cropped videos.
     */
    static VideoEditedInfo createRoundVideoInfo(int account, String path) {
        if (path == null || !new File(path).exists()) {
            return null;
        }
        final int[] params = new int[AnimatedFileInfo.PARAM_NUM_COUNT];
        AnimatedFileNative.getVideoInfo(path, params, 0);
        final boolean hasAudio = params[AnimatedFileInfo.PARAM_NUM_HAS_AUDIO] != 0;
        if (params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_VIDEO_CODEC] == 0
            || hasAudio && params[AnimatedFileInfo.PARAM_NUM_SUPPORTED_AUDIO_CODEC] == 0) {
            return null;
        }
        final int width = params[AnimatedFileInfo.PARAM_NUM_WIDTH];
        final int height = params[AnimatedFileInfo.PARAM_NUM_HEIGHT];
        final int rotation = params[AnimatedFileInfo.PARAM_NUM_ROTATION];
        final long durationMs = params[AnimatedFileInfo.PARAM_NUM_DURATION];
        if (width <= 0 || height <= 0 || durationMs <= 0) {
            return null;
        }
        final boolean sideways = rotation == 90 || rotation == 270;
        final int displayWidth = sideways ? height : width;
        final int displayHeight = sideways ? width : height;
        final int side = Math.min(displayWidth, displayHeight);

        final MessagesController controller = MessagesController.getInstance(account);
        int size = controller.roundVideoSize > 0 ? controller.roundVideoSize : 384;
        size -= size % 16; // encoders want multiples of 16
        if (size < 16) {
            size = 384;
        }
        final int videoBitrate = (controller.roundVideoBitrate > 0 ? controller.roundVideoBitrate : 1000) * 1024;
        final int audioBitrate = (controller.roundAudioBitrate > 0 ? controller.roundAudioBitrate : 64) * 1024;
        final long sendMs = Math.min(durationMs, MAX_DURATION_MS);

        final VideoEditedInfo info = new VideoEditedInfo();
        info.originalPath = path;
        info.roundVideo = true;
        info.muted = false;
        info.rotationValue = rotation;
        info.originalWidth = width;
        info.originalHeight = height;
        info.resultWidth = size;
        info.resultHeight = size;
        final int framerate = params[AnimatedFileInfo.PARAM_NUM_FRAMERATE];
        info.framerate = framerate > 0 ? framerate : 30;
        int originalBitrate = MediaController.getVideoBitrate(path);
        info.originalBitrate = originalBitrate > 0 ? originalBitrate : params[AnimatedFileInfo.PARAM_NUM_BITRATE];
        info.bitrate = videoBitrate;
        info.startTime = -1;
        info.endTime = durationMs > MAX_DURATION_MS ? MAX_DURATION_MS * 1000L : -1;
        info.originalDuration = durationMs * 1000L;
        info.estimatedDuration = sendMs;
        info.estimatedSize = Math.max(1L, (long) ((videoBitrate + (hasAudio ? audioBitrate : 0)) / 8.0 * sendMs / 1000.0));

        final MediaController.CropState crop = new MediaController.CropState();
        crop.cropPx = 0;
        crop.cropPy = 0;
        crop.cropScale = 1f;
        crop.cropRotate = 0;
        crop.cropPw = side / (float) displayWidth;
        crop.cropPh = side / (float) displayHeight;
        crop.transformWidth = size;
        crop.transformHeight = size;
        crop.transformRotation = 0;
        info.cropState = crop;
        return info;
    }
}

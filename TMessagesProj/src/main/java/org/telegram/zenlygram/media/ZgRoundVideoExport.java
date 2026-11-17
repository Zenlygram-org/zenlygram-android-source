/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.core.ZgFeatures;

import java.io.File;
import java.util.ArrayList;

/**
 * «Save to Gallery» on a round video message (iOS: «Save Video», SaveToCameraRoll + SGRoundVideoBranding).
 *
 * <p>Telegram Android offers no gallery save for round videos at all, so the option is added to the message menu
 * (next to Telegram's own entries, only where saving is allowed: not in chats or messages that restrict saving, not
 * for a view-once round video, only once the file is downloaded). The saved file is the branded 1080×1080 render
 * ({@link ZgRoundVideoTranscoder}); if rendering fails the original square file is saved, exactly as iOS does.
 */
public final class ZgRoundVideoExport {

    /** Option id, outside Telegram's ChatActivity.OPTION_* and the other Zenlygram menus (9001+, 9101). */
    public static final int OPTION_ZG_SAVE_ROUND_VIDEO = 9201;

    private static final String EXPORT_DIR = "zg_round_export";

    private ZgRoundVideoExport() {
    }

    /** End of ChatActivity.fillMessageMenu: "Save to Gallery" for a saveable, downloaded round video. */
    public static void adjust(ChatActivity chat, MessageObject message, ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        try {
            if (!ZgFeatures.ROUND_VIDEO_EXPORT.isPorted() || chat == null || message == null || icons == null || items == null || options == null
                || options.size() != items.size() || options.size() != icons.size()
                || options.contains(OPTION_ZG_SAVE_ROUND_VIDEO) || options.contains(ChatActivity.OPTION_SAVE_TO_GALLERY)) {
                return;
            }
            if (!canSave(chat, message) || sourceFile(chat, message) == null) {
                return;
            }
            int at = options.indexOf(ChatActivity.OPTION_FORWARD);
            at = at >= 0 ? at + 1 : Math.min(options.size(), 1);
            options.add(at, OPTION_ZG_SAVE_ROUND_VIDEO);
            items.add(at, getString(R.string.SaveToGallery));
            icons.add(at, R.drawable.msg_gallery);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** ChatActivity.processSelectedOption: true when it was this option. */
    public static boolean processSelectedOption(ChatActivity chat, int option, MessageObject message) {
        if (option != OPTION_ZG_SAVE_ROUND_VIDEO) {
            return false;
        }
        save(chat, message);
        return true;
    }

    static boolean canSave(ChatActivity chat, MessageObject message) {
        return message.isRoundVideo() && !message.isRoundOnce() && !message.needDrawBluredPreview()
            && !(message.messageOwner != null && message.messageOwner.noforwards)
            && message.type != MessageObject.TYPE_PAID_MEDIA
            && !chat.isPeerNoForwards();
    }

    private static File sourceFile(ChatActivity chat, MessageObject message) {
        if (message.messageOwner == null) {
            return null;
        }
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            final File f = new File(message.messageOwner.attachPath);
            if (f.exists() && f.length() > 0) {
                return f;
            }
        }
        final File f = FileLoader.getInstance(chat.getCurrentAccount()).getPathToMessage(message.messageOwner);
        return f != null && f.exists() && f.length() > 0 ? f : null;
    }

    private static void save(ChatActivity chat, MessageObject message) {
        final Activity activity = chat.getParentActivity();
        if (activity == null || !canSave(chat, message)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE)
            && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            return;
        }
        final File source = sourceFile(chat, message);
        if (source == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        final boolean[] cancelled = {false};
        progress.setCanCancel(true);
        progress.setOnCancelListener(d -> cancelled[0] = true);
        progress.showDelayed(300);
        Utilities.globalQueue.postRunnable(() -> {
            final File branded = render(source, () -> cancelled[0]);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                if (cancelled[0]) {
                    if (branded != null) {
                        branded.delete();
                    }
                    return;
                }
                final File toSave = branded != null ? branded : source;
                MediaController.saveFile(toSave.getAbsolutePath(), activity, 1, null, "video/mp4", uri -> {
                    if (branded != null) {
                        branded.delete();
                    }
                }, false);
                BulletinFactory.of(chat).createDownloadBulletin(BulletinFactory.FileType.VIDEO, chat.getResourceProvider()).show();
            });
        });
    }

    /** Renders the branded copy; null on any failure (the caller saves the original). Background thread. */
    static File render(File source, ZgRoundVideoTranscoder.Cancel cancel) {
        File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        if (dir == null) {
            dir = ApplicationLoader.applicationContext.getExternalCacheDir();
        }
        if (dir == null) {
            return null;
        }
        dir = new File(dir, EXPORT_DIR);
        sweep(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        final File out = new File(dir, "Zenlygram_" + System.currentTimeMillis() + ".mp4");
        Bitmap background = null;
        Bitmap sheet = null;
        try {
            background = createBackground();
            sheet = loadLogoSheet();
            new ZgRoundVideoTranscoder(source, out, background, sheet, cancel).run();
            if (out.length() > 0) {
                return out;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (background != null) {
                background.recycle();
            }
            if (sheet != null) {
                sheet.recycle();
            }
        }
        out.delete();
        return null;
    }

    /** Files of earlier exports whose gallery copy finished (or failed) long ago. */
    private static void sweep(File dir) {
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        final long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    private static Bitmap loadLogoSheet() {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            final Bitmap sheet = BitmapFactory.decodeResource(ApplicationLoader.applicationContext.getResources(), R.drawable.zg_round_video_logo_frames, options);
            if (sheet != null && (sheet.getWidth() < ZgRoundVideoLayout.sheetWidth() || sheet.getHeight() < ZgRoundVideoLayout.sheetHeight())) {
                sheet.recycle();
                return null; // iOS: no logo frames, no logo
            }
            return sheet;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** The dark vertical gradient with "ZENLYGRAM" along the circle's bottom-right edge (drawn once). */
    static Bitmap createBackground() {
        final int size = ZgRoundVideoLayout.RENDER_SIZE;
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint gradient = new Paint();
        gradient.setShader(new LinearGradient(size / 2f, 0, size / 2f, size, grey(ZgRoundVideoLayout.BACKGROUND_TOP_WHITE),
            grey(ZgRoundVideoLayout.BACKGROUND_BOTTOM_WHITE), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, size, size, gradient);

        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(ZgRoundVideoLayout.WORDMARK_FONT_SIZE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        final String word = ZgRoundVideoLayout.WORDMARK;
        final float[] widths = new float[word.length()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = text.measureText(word, i, i + 1);
        }
        final Rect cap = new Rect();
        text.getTextBounds("Z", 0, 1, cap);
        final float[][] glyphs = ZgRoundVideoLayout.wordmarkGlyphs(widths, cap.height(), size / 2f, size / 2f);
        for (int i = 0; i < glyphs.length; i++) {
            canvas.save();
            canvas.translate(glyphs[i][0], glyphs[i][1]);
            canvas.rotate(glyphs[i][2]);
            canvas.drawText(word, i, i + 1, -widths[i] / 2f, 0, text);
            canvas.restore();
        }
        return bitmap;
    }

    private static int grey(float white) {
        final int v = Math.round(white * 255f);
        return Color.rgb(v, v, v);
    }
}

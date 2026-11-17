/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.View;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Settings > Zenlygram > Call recording > Call recordings (iOS SGCallRecordingsController): the recordings on this
 * device, newest first. A row offers Play / Stop, Share…, Send to Saved Messages and Delete as its policy allows
 * ({@link ZgCallRecordingPolicy.Row}); Delete All at the bottom.
 */
public class ZgCallRecordingsActivity extends UniversalFragment implements org.telegram.messenger.zenlygram.customer.ZenlyStats.NamedScreen { // ZENLYGRAM: statistics name the screen

    @Override
    public org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen zenlyScreen() {
        return org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.CALLS_RECORDINGS;
    }

    private static final int ID_EMPTY = 1;
    private static final int ID_FOOTER = 2;
    private static final int ID_DELETE_ALL = 3;
    private static final int ID_DELETE_ALL_SHADOW = 4;
    private static final int ID_ROW_BASE = 1000;

    private static WeakReference<ZgCallRecordingsActivity> visible = new WeakReference<>(null);

    private final ArrayList<ZgCallRecordingMetadata> recordings = new ArrayList<>();
    private MediaPlayer player;
    private String playingId;
    private final Runnable recorderListener = this::reload;

    /** Something changed outside the screen (a delivery confirmed, a recording finished). */
    static void notifyChanged() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            final ZgCallRecordingsActivity screen = visible.get();
            if (screen != null) {
                screen.reload();
            }
        });
    }

    @Override
    public boolean onFragmentCreate() {
        ZgCallRecorder.addListener(recorderListener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        ZgCallRecorder.removeListener(recorderListener);
        stopPlayback();
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        reload();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        visible = new WeakReference<>(this);
        reload();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_CallRecording_List_Title);
    }

    private void reload() {
        recordings.clear();
        recordings.addAll(ZgCallRecordingStore.list());
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private boolean isLive(ZgCallRecordingMetadata m) {
        return ZgCallRecorder.isRecording() && !m.isComplete && m.id.equals(ZgCallRecorder.currentId());
    }

    private ZgCallRecordingPolicy.Row rowOf(ZgCallRecordingMetadata m) {
        return new ZgCallRecordingPolicy.Row(ZgCallRecordingDelivery.privacy(m), isLive(m), m.id.equals(playingId),
            ZgCallRecordingDelivery.isSending(m.id), ZgCallRecordingDelivery.isFailed(m), m.isComplete && !m.truncated, ZgCallRecorder.isAvailable());
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (recordings.isEmpty()) {
            items.add(UItem.asShadow(ID_EMPTY, getString(R.string.ZG_CallRecording_List_Empty)));
            return;
        }
        for (int i = 0; i < recordings.size(); i++) {
            final ZgCallRecordingMetadata m = recordings.get(i);
            final ZgCallRecordingPolicy.Row row = rowOf(m);
            final String title;
            if (row.isRedacted) {
                title = getString(R.string.ZG_CallRecording_Protected_Title);
            } else {
                final String name = m.peerTitle.isEmpty() ? getString(R.string.ZG_CallRecording_UnknownPeer) : m.peerTitle;
                title = name + " · " + LocaleController.formatDateTime((long) m.startTimestamp, false);
            }
            items.add(UItem.asButton(ID_ROW_BASE + i, R.drawable.msg_voicechat2, title, valueOf(m, row)));
        }
        items.add(UItem.asShadow(ID_FOOTER, getString(R.string.ZG_CallRecording_List_Footer)));
        items.add(UItem.asButton(ID_DELETE_ALL, R.drawable.msg_delete, getString(R.string.ZG_CallRecording_List_DeleteAll)).red());
        items.add(UItem.asShadow(ID_DELETE_ALL_SHADOW, null));
    }

    /** Status first, so a truncated label can only lose the duration, never the warning (iOS rule). */
    private static String valueOf(ZgCallRecordingMetadata m, ZgCallRecordingPolicy.Row row) {
        final ZgCallRecordingPolicy.Badge badge = row.primaryBadge();
        if (badge != null) {
            switch (badge) {
                case LIVE:
                    return getString(R.string.ZG_CallRecording_Live);
                case PLAYING:
                    return getString(R.string.ZG_CallRecording_Playing);
                case SEND_FAILED:
                    return getString(R.string.ZG_CallRecording_SendToSaved_Failed);
                case INCOMPLETE:
                    return row.showsDuration ? ZgCallRecordingFormat.formatDuration(m.duration) + " · " + getString(R.string.ZG_CallRecording_Incomplete)
                        : getString(R.string.ZG_CallRecording_Incomplete);
                case SENDING:
                    return getString(R.string.ZG_CallRecording_SendToSaved_Waiting);
            }
        }
        return row.showsDuration ? ZgCallRecordingFormat.formatDuration(m.duration) : "";
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_DELETE_ALL) {
            confirmDeleteAll();
            return;
        }
        final int index = item.id - ID_ROW_BASE;
        if (index < 0 || index >= recordings.size()) {
            return;
        }
        final ZgCallRecordingMetadata m = recordings.get(index);
        final ZgCallRecordingPolicy.Row row = rowOf(m);
        if (row.badges.contains(ZgCallRecordingPolicy.Badge.LIVE)) {
            showAlert(getString(R.string.ZG_CallRecording_Live), getString(R.string.ZG_CallRecording_Live_Text));
            return;
        }
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (row.canPlay) {
            final boolean playing = m.id.equals(playingId);
            labels.add(getString(playing ? R.string.ZG_CallRecording_Stop : R.string.ZG_CallRecording_Play));
            actions.add(() -> {
                if (playing) {
                    stopPlayback();
                } else {
                    play(m);
                }
            });
        }
        if (row.canShare) {
            labels.add(getString(R.string.ZG_CallRecording_Share));
            actions.add(() -> share(m));
        }
        if (row.canSend) {
            labels.add(getString(ZgCallRecordingDelivery.isFailed(m) ? R.string.ZG_CallRecording_SendToSaved_Again : R.string.ZG_CallRecording_SendToSaved));
            actions.add(() -> confirmSend(m));
        }
        labels.add(getString(R.string.ZG_CallRecording_Delete));
        actions.add(() -> confirmDelete(m));
        if (row.isRedacted) {
            showAlert(getString(R.string.ZG_CallRecording_Protected_Title), getString(R.string.ZG_CallRecording_Protected_Unverifiable_Text));
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run());
        showDialog(builder.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void play(ZgCallRecordingMetadata m) {
        if (VoIPService.getSharedInstance() != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.ZG_CallRecording_Play_DuringCall)).show();
            return;
        }
        stopPlayback();
        final File file = ZgCallRecordingStore.audioFile(m.id);
        try {
            final MediaPlayer p = new MediaPlayer();
            p.setDataSource(file.getAbsolutePath());
            p.setOnCompletionListener(mp -> stopPlayback());
            p.prepare();
            p.start();
            player = p;
            playingId = m.id;
        } catch (Throwable e) {
            FileLog.e(e);
            stopPlayback();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.ZG_CallRecording_Play_Failed)).show();
        }
        reload();
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignore) {
            }
            player.release();
            player = null;
        }
        if (playingId != null) {
            playingId = null;
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    }

    /** Share exports a copy (the original stays private): files/cache/zg_call_share is served by the FileProvider. */
    private void share(ZgCallRecordingMetadata m) {
        try {
            final File source = ZgCallRecordingStore.audioFile(m.id);
            final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/zg_call_share");
            final File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    f.delete();
                }
            }
            dir.mkdirs();
            final String name = m.peerTitle.isEmpty() ? getString(R.string.ZG_CallRecording_UnknownPeer) : m.peerTitle;
            final File copy = new File(dir, ZgCallRecordingFormat.sanitizedFileName(name + " " + m.id + ZgCallRecordingFormat.EXTENSION));
            try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(copy)) {
                final byte[] buffer = new byte[64 * 1024];
                int r;
                while ((r = in.read(buffer)) > 0) {
                    out.write(buffer, 0, r);
                }
            }
            final Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", copy);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(ZgCallRecordingFormat.MIME);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, getString(R.string.ZG_CallRecording_Share)));
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.ZG_CallRecording_Damaged_Text)).show();
        }
    }

    private void confirmSend(ZgCallRecordingMetadata m) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.ZG_CallRecording_SendToSaved_Confirm_Title));
        builder.setMessage(getString(R.string.ZG_CallRecording_SendToSaved_Confirm_Text));
        builder.setPositiveButton(getString(R.string.ZG_CallRecording_SendToSaved_Confirm_Send), (d, w) -> {
            final ZgCallRecordingDelivery.SendResult result = ZgCallRecordingDelivery.send(m);
            switch (result) {
                case ACCOUNT_UNAVAILABLE:
                    showAlert(getString(R.string.ZG_CallRecording_SendToSaved), getString(R.string.ZG_CallRecording_SendToSaved_AccountUnavailable));
                    break;
                case REFUSED:
                    showAlert(getString(R.string.ZG_CallRecording_SendToSaved), getString(R.string.ZG_CallRecording_SendToSaved_Refused));
                    break;
                case NOT_QUEUED:
                    showAlert(getString(R.string.ZG_CallRecording_SendToSaved), getString(R.string.ZG_CallRecording_SendToSaved_NotQueued));
                    break;
                default:
                    break;
            }
            reload();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmDelete(ZgCallRecordingMetadata m) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.ZG_CallRecording_Delete_Title));
        builder.setMessage(getString(ZgCallRecordingDelivery.isSending(m.id) ? R.string.ZG_CallRecording_Delete_Text_Sending : R.string.ZG_CallRecording_Delete_Text));
        builder.setPositiveButton(getString(R.string.ZG_CallRecording_Delete), (d, w) -> {
            if (m.id.equals(playingId)) {
                stopPlayback();
            }
            if (!ZgCallRecordingStore.delete(m.id)) {
                showAlert(getString(R.string.ZG_CallRecording_Delete_Title), getString(R.string.ZG_CallRecording_Delete_Failed));
            }
            reload();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        redButton(dialog);
    }

    private void confirmDeleteAll() {
        boolean anySending = false;
        for (ZgCallRecordingMetadata m : recordings) {
            anySending |= ZgCallRecordingDelivery.isSending(m.id);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.ZG_CallRecording_DeleteAll_Title));
        builder.setMessage(getString(anySending ? R.string.ZG_CallRecording_DeleteAll_Text_Sending : R.string.ZG_CallRecording_DeleteAll_Text));
        builder.setPositiveButton(getString(R.string.ZG_CallRecording_Delete), (d, w) -> {
            stopPlayback();
            boolean ok = true;
            for (ZgCallRecordingMetadata m : new ArrayList<>(recordings)) {
                if (isLive(m)) {
                    continue; // the live recording is removed by the user once the call ends
                }
                ok &= ZgCallRecordingStore.delete(m.id);
            }
            if (!ok) {
                showAlert(getString(R.string.ZG_CallRecording_DeleteAll_Title), getString(R.string.ZG_CallRecording_DeleteAll_Failed));
            }
            reload();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        redButton(dialog);
    }

    private static void redButton(AlertDialog dialog) {
        final View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void showAlert(CharSequence title, CharSequence text) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }
}

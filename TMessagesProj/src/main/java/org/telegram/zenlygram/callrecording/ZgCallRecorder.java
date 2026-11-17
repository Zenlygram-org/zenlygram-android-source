/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.DarkAlertDialog;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.VoIPFragment;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.io.File;
import java.util.ArrayList;

/**
 * Recording of one Telegram 1:1 call (port of iOS SGCallRecordingSession + the PresentationCall glue).
 *
 * <p>Started ONLY by an explicit Record tap on the call screen after the one-time consent; never automatically, under
 * any setting. While it runs: a "REC" badge on the call screen and "Recording this call" in the ongoing-call
 * notification (the indicator outside the app). It stops when the user taps Stop or the call ends.
 *
 * <p>Pipeline: the two taps ({@link ZgCallAudioTap}) feed the {@link ZgCallRecordingMixer}; a dedicated thread drains
 * it every 200 ms into the AAC encoder; the sidecar is written at start (incomplete), every 10 s, and at the end.
 * Main thread API.
 */
public final class ZgCallRecorder {

    private static final long DRAIN_INTERVAL_MS = 200;
    private static final int METADATA_REFRESH_DRAINS = 50;
    private static final long NOTICE_OBSERVATION_MS = 60_000;

    public enum StartResult { STARTED, UNAVAILABLE, NO_CONSENT, NO_SPACE, STORAGE }

    private static Session current;
    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    private ZgCallRecorder() {
    }

    public static boolean isAvailable() {
        return ZgFeatures.CALL_RECORDING.isPorted();
    }

    /** A connected 1:1 Telegram call this build can record. */
    public static boolean canStart() {
        if (!isAvailable() || current != null) {
            return false;
        }
        final VoIPService service = VoIPService.getSharedInstance();
        return service != null && service.groupCall == null && !service.isConference() && service.getUser() != null
            && service.getCallState() == VoIPService.STATE_ESTABLISHED;
    }

    public static boolean isRecording() {
        return current != null;
    }

    /** Id of the recording being written, null when none. */
    static String currentId() {
        final Session s = current;
        return s == null ? null : s.metadata.id;
    }

    /** Milliseconds since the running recording started, -1 when none. */
    public static long elapsedMs() {
        final Session s = current;
        return s == null ? -1 : SystemClock.elapsedRealtime() - s.startElapsed;
    }

    public static void addListener(Runnable listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void changed() {
        for (Runnable r : new ArrayList<>(listeners)) {
            try {
                r.run();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        final VoIPService service = VoIPService.getSharedInstance();
        if (service != null) {
            service.zgRefreshOngoingNotification();
        }
    }

    /** The ongoing-call notification text while a recording runs (VoIPService.showNotification). */
    public static String notificationText(String text) {
        if (current == null) {
            return text;
        }
        final String marker = "● " + getString(R.string.ZG_CallRecording_Indicator);
        return text == null || text.isEmpty() ? marker : marker + " · " + text;
    }

    /** Only from the user's Record action, after consent. */
    public static StartResult start() {
        if (!canStart()) {
            return StartResult.UNAVAILABLE;
        }
        if (!ZgSettings.callRecordingConsentAccepted.get()) {
            return StartResult.NO_CONSENT;
        }
        final VoIPService service = VoIPService.getSharedInstance();
        final int account = service.getAccount();
        final TLRPC.User user = service.getUser();
        if (ZgCallRecordingStore.ensureDirectory() == null) {
            return StartResult.STORAGE;
        }
        final long free = ZgCallRecordingStore.availableBytes();
        if (free >= 0 && free < ZgCallRecordingStore.MINIMUM_FREE_BYTES) {
            return StartResult.NO_SPACE;
        }
        ZgCallRecordingStore.sweepOrphans(null);

        final String id = ZgCallRecordingStore.newId();
        final File audio = ZgCallRecordingStore.audioFile(id);
        ZgCallRecordingEncoder encoder;
        try {
            if (audio == null || !audio.createNewFile()) {
                return StartResult.STORAGE;
            }
            encoder = new ZgCallRecordingEncoder(audio);
        } catch (Throwable e) {
            FileLog.e(e);
            ZgCallRecordingStore.delete(id);
            return StartResult.STORAGE;
        }
        final ZgCallRecordingMetadata metadata = new ZgCallRecordingMetadata(id, System.currentTimeMillis() / 1000.0, 0,
            UserObject.getUserName(user), UserConfig.getInstance(account).getClientUserId(), user.id, false, false, ZgCallRecordingMetadata.DELIVERY_NONE);
        if (!ZgCallRecordingStore.writeMetadata(metadata)) {
            encoder.finish();
            ZgCallRecordingStore.delete(id);
            return StartResult.STORAGE;
        }

        final Session session = new Session(metadata, encoder, account);
        current = session;
        ZgCallAudioTap.attach(session.mixer, () -> {
            final VoIPService s = VoIPService.getSharedInstance();
            return s == null || s.isMicMute();
        });
        session.thread.start();
        changed();
        org.telegram.messenger.zenlygram.customer.ZenlyStats.featureUsed(account, org.telegram.messenger.zenlygram.customer.CustomerEvents.Feature.CALL_RECORDING); // ZENLYGRAM: statistics, feature.used (recorded only with the data consent)

        if (ZgSettings.callRecordingNotifyOtherParty.get()) {
            sendNotice(account, user.id);
            ZgCallAudioTap.playTone(ZgCallRecordingFormat.noticeTone());
        }
        return StartResult.STARTED;
    }

    /** Stop button, or the call ended. The badge stays until the file is closed (iOS: no samples after "stopped"). */
    public static void stop() {
        final Session session = current;
        if (session == null) {
            return;
        }
        ZgCallAudioTap.detach();
        session.requestStop();
    }

    /** VoIPService: the call is ending. */
    public static void onCallEnded() {
        stop();
    }

    private static void finished(Session session, boolean hasAudio, boolean truncated) {
        if (current == session) {
            current = null;
        }
        changed();
        if (!hasAudio) {
            showProblem(getString(R.string.ZG_CallRecording_Problem_Lost));
            return;
        }
        if (truncated) {
            showProblem(getString(R.string.ZG_CallRecording_Problem_Truncated));
        }
        ZgCallRecordingDelivery.recordingFinished(session.metadata.id);
    }

    // ---- the other party's notice

    private static void sendNotice(int account, long peerId) {
        final String text = getString(R.string.ZG_CallRecording_Notice_Message);
        final int localId = UserConfig.getInstance(account).lastSendMessageId;
        try {
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, peerId));
        } catch (Throwable e) {
            FileLog.e(e);
            showProblem(getString(R.string.ZG_CallRecording_Problem_NoticeNotSent));
            return;
        }
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final boolean[] done = {false};
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        final Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                center.removeObserver(holder[0], NotificationCenter.messageReceivedByServer);
                center.removeObserver(holder[0], NotificationCenter.messageSendError);
                showProblem(getString(R.string.ZG_CallRecording_Problem_NoticeUnconfirmed));
            }
        };
        holder[0] = (id, acc, args) -> {
            if (done[0] || args.length == 0 || !(args[0] instanceof Integer) || (Integer) args[0] != localId) {
                return;
            }
            done[0] = true;
            center.removeObserver(holder[0], NotificationCenter.messageReceivedByServer);
            center.removeObserver(holder[0], NotificationCenter.messageSendError);
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (id == NotificationCenter.messageSendError) {
                showProblem(getString(R.string.ZG_CallRecording_Problem_NoticeNotSent));
            }
        };
        center.addObserver(holder[0], NotificationCenter.messageReceivedByServer);
        center.addObserver(holder[0], NotificationCenter.messageSendError);
        AndroidUtilities.runOnUIThread(timeout, NOTICE_OBSERVATION_MS);
    }

    /** An alert on the call screen while it is up, otherwise on the app's top screen. */
    static void showProblem(CharSequence text) {
        try {
            final Activity activity = VoIPFragment.getInstance() != null ? LaunchActivity.instance : null;
            if (activity != null && !activity.isFinishing()) {
                new DarkAlertDialog.Builder(activity)
                    .setTitle(getString(R.string.ZG_CallRecording_Problem_Title))
                    .setMessage(text)
                    .setPositiveButton(getString(R.string.OK), null)
                    .show();
                return;
            }
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment != null && fragment.getParentActivity() != null) {
                fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(getString(R.string.ZG_CallRecording_Problem_Title))
                    .setMessage(text)
                    .setPositiveButton(getString(R.string.OK), null)
                    .create());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** One recording: the mixer the taps feed, the encoder thread, the sidecar. */
    private static final class Session {
        final ZgCallRecordingMetadata metadata;
        final ZgCallRecordingEncoder encoder;
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        final long startElapsed = SystemClock.elapsedRealtime();
        final int account;
        final Thread thread;
        private volatile boolean stopRequested;

        Session(ZgCallRecordingMetadata metadata, ZgCallRecordingEncoder encoder, int account) {
            this.metadata = metadata;
            this.encoder = encoder;
            this.account = account;
            this.thread = new Thread(this::run, "ZgCallRecording");
        }

        void requestStop() {
            stopRequested = true;
            thread.interrupt();
        }

        private void run() {
            int drains = 0;
            boolean brokenReported = false;
            while (!stopRequested) {
                try {
                    Thread.sleep(DRAIN_INTERVAL_MS);
                } catch (InterruptedException ignore) {
                }
                mixer.drain(encoder::append);
                if (encoder.isBroken() && !brokenReported) {
                    // A mid-call write failure: stop on our own rather than keep promising a recording.
                    brokenReported = true;
                    AndroidUtilities.runOnUIThread(ZgCallRecorder::stop);
                }
                if (++drains % METADATA_REFRESH_DRAINS == 0) {
                    ZgCallRecordingStore.writeMetadata(metadata.withProgress(encoder.duration(), false, false));
                }
            }
            // The taps were detached on the main thread before the stop request; a buffer already inside a tap
            // call lands in the mixer before the final drain or not at all.
            mixer.finish(encoder::append);
            encoder.finish();
            final boolean hasAudio = encoder.hasAudio();
            final boolean truncated = encoder.isBroken();
            if (hasAudio) {
                ZgCallRecordingStore.writeMetadata(metadata.withProgress(encoder.duration(), true, truncated));
            } else {
                ZgCallRecordingStore.delete(metadata.id);
            }
            AndroidUtilities.runOnUIThread(() -> finished(this, hasAudio, truncated));
        }
    }
}

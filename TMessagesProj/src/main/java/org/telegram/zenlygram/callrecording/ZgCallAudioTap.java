/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * The two audio taps of a Telegram call, called from WebRTC's Java audio device (org.webrtc.voiceengine
 * WebRtcAudioRecord / WebRtcAudioTrack), which tgcalls uses for 1:1 calls on Android. This is the app's OWN call audio
 * inside its own process; nothing here uses Android's system call recording or captures another app.
 *
 * <ul>
 *   <li>{@link #onMicrophone}: the microphone after Android's voice processing, i.e. what is sent to the other party
 *   (muted → silence, see ZgCallRecordingMixer).</li>
 *   <li>{@link #onPlayout}: the far side as this phone plays it. The local "recording started" tone is mixed into
 *   the playout buffer AFTER the tap: the user hears it, the other party never can (iOS SGCallRecordingTone).</li>
 * </ul>
 * Both are a volatile read and return immediately while nothing is recording.
 */
public final class ZgCallAudioTap {

    private static volatile ZgCallRecordingMixer mixer;
    private static volatile BooleanSupplier micMuted;

    private static volatile short[] tone;
    private static int tonePosition;

    // One scratch array per audio thread (each direction has exactly one thread).
    private static short[] micScratch = new short[0];
    private static short[] playoutScratch = new short[0];

    public interface BooleanSupplier {
        boolean get();
    }

    private ZgCallAudioTap() {
    }

    static void attach(ZgCallRecordingMixer target, BooleanSupplier muted) {
        micMuted = muted;
        mixer = target;
    }

    static void detach() {
        mixer = null;
    }

    static boolean isAttached() {
        return mixer != null;
    }

    /** Plays {@code samples} (mono 48 kHz) once into the local playout. */
    static void playTone(short[] samples) {
        synchronized (ZgCallAudioTap.class) {
            tonePosition = 0;
            tone = samples;
        }
    }

    /** WebRtcAudioRecord.AudioRecordThread, after the buffer went to WebRTC. */
    public static void onMicrophone(ByteBuffer buffer, int bytes, int sampleRate, int channels) {
        final ZgCallRecordingMixer m = mixer;
        if (m == null || buffer == null || bytes <= 0) {
            return;
        }
        try {
            final int count = bytes / 2;
            if (micScratch.length < count) {
                micScratch = new short[count];
            }
            read(buffer, micScratch, count);
            final BooleanSupplier muted = micMuted;
            m.addLocal(micScratch, count, sampleRate, channels, muted != null && muted.get());
        } catch (Throwable ignore) {
            // never disturb the call's audio thread
        }
    }

    /** WebRtcAudioTrack.AudioTrackThread, after WebRTC filled the buffer and before AudioTrack.write. */
    public static void onPlayout(ByteBuffer buffer, int bytes, int sampleRate, int channels) {
        final ZgCallRecordingMixer m = mixer;
        final short[] t = tone;
        if ((m == null && t == null) || buffer == null || bytes <= 0) {
            return;
        }
        try {
            final int count = bytes / 2;
            if (playoutScratch.length < count) {
                playoutScratch = new short[count];
            }
            read(buffer, playoutScratch, count);
            if (m != null) {
                m.addRemote(playoutScratch, count, sampleRate, channels);
            }
            if (t != null) {
                mixTone(buffer, count, sampleRate, channels, t);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void read(ByteBuffer buffer, short[] out, int count) {
        final ByteBuffer bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(0);
        final ShortBuffer shorts = bytes.asShortBuffer();
        shorts.get(out, 0, Math.min(count, shorts.remaining()));
    }

    private static void mixTone(ByteBuffer buffer, int count, int sampleRate, int channels, short[] t) {
        final ByteBuffer le = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int frames = count / Math.max(1, channels);
        final double step = 48000.0 / Math.max(1, sampleRate);
        synchronized (ZgCallAudioTap.class) {
            if (tone != t) {
                return;
            }
            double position = tonePosition;
            for (int f = 0; f < frames; f++) {
                final int i = (int) position;
                if (i >= t.length) {
                    tone = null;
                    break;
                }
                for (int c = 0; c < channels; c++) {
                    final int at = (f * channels + c) * 2;
                    le.putShort(at, ZgCallRecordingMixer.clamp(le.getShort(at) + t[i]));
                }
                position += step;
            }
            tonePosition = (int) position;
        }
    }
}

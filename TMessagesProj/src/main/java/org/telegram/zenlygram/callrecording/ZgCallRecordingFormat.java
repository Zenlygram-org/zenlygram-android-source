/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

/**
 * Pure helpers of the recording file and its messages.
 *
 * <p>iOS writes one Ogg/Opus stream because Ogg pages survive a crash mid-call. Telegram Android's Opus writer is one
 * global native object shared with voice messages, so Android writes AAC-LC in ADTS framing instead: every frame
 * carries its own header, so a file cut off by a crash or a full disk still plays up to its last whole frame, the same
 * property the iOS format was chosen for.
 */
public final class ZgCallRecordingFormat {

    public static final String EXTENSION = ".aac";
    public static final String MIME = "audio/aac";
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 1;
    public static final int BITRATE = 64000;
    /** AAC-LC frame length in samples. */
    public static final int SAMPLES_PER_FRAME = 1024;

    private ZgCallRecordingFormat() {
    }

    /** The ADTS sampling-frequency index, -1 if ADTS has none for {@code sampleRate}. */
    public static int frequencyIndex(int sampleRate) {
        final int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) {
                return i;
            }
        }
        return -1;
    }

    /** The 7-byte ADTS header (no CRC) of an AAC-LC frame of {@code payloadLength} bytes. */
    public static byte[] adtsHeader(int payloadLength, int sampleRate, int channels) {
        final int frameLength = payloadLength + 7;
        final int profile = 2; // AAC LC (audio object type 2), written as profile - 1
        final int freq = frequencyIndex(sampleRate);
        if (freq < 0 || channels < 1 || channels > 7 || frameLength > 0x1FFF) {
            throw new IllegalArgumentException("adts");
        }
        final byte[] h = new byte[7];
        h[0] = (byte) 0xFF;
        h[1] = (byte) 0xF1; // MPEG-4, layer 0, no CRC
        h[2] = (byte) (((profile - 1) << 6) | (freq << 2) | (channels >> 2));
        h[3] = (byte) (((channels & 3) << 6) | (frameLength >> 11));
        h[4] = (byte) ((frameLength >> 3) & 0xFF);
        h[5] = (byte) (((frameLength & 7) << 5) | 0x1F);
        h[6] = (byte) 0xFC;
        return h;
    }

    /** Seconds of audio in {@code frames} AAC frames. */
    public static double durationOfFrames(long frames) {
        return frames * (double) SAMPLES_PER_FRAME / SAMPLE_RATE;
    }

    /** "m:ss" or "h:mm:ss". */
    public static String formatDuration(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        final long h = s / 3600;
        s %= 3600;
        final long m = s / 60;
        s %= 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) : String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    /** A file name that survives every filesystem and client (iOS sgCallRecordingSanitizedFileName). */
    public static String sanitizedFileName(String value) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append(' ');
            } else if ("/\\:*?\"<>|".indexOf(c) >= 0) {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().trim();
        if (result.length() > 120) {
            result = result.substring(0, 120 - EXTENSION.length()) + EXTENSION;
        }
        if (result.isEmpty() || result.equals(EXTENSION)) {
            result = "call" + EXTENSION;
        }
        return result;
    }

    /** Two short 880 Hz beeps, mono 48 kHz PCM16, raised-cosine edges (iOS sgCallRecordingNoticeToneData). */
    public static short[] noticeTone() {
        final double rate = 48000.0;
        final int beep = (int) (rate * 0.12);
        final int gap = (int) (rate * 0.09);
        final int fade = Math.max(1, (int) (rate * 0.008));
        final short[] out = new short[beep * 2 + gap];
        for (int b = 0; b < 2; b++) {
            final int offset = b == 0 ? 0 : beep + gap;
            for (int i = 0; i < beep; i++) {
                final double phase = 2.0 * Math.PI * 880.0 * (i / rate);
                double envelope = 1.0;
                if (i < fade) {
                    envelope = (double) i / fade;
                } else if (i >= beep - fade) {
                    envelope = (double) (beep - i) / fade;
                }
                final double v = Math.sin(phase) * 0.22 * envelope * 32767.0;
                out[offset + i] = (short) Math.max(-32768, Math.min(32767, v));
            }
        }
        return out;
    }
}

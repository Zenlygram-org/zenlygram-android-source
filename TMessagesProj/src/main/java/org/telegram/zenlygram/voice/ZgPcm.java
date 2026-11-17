/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.voice;

/** Pure PCM16 helpers of on-device voice-to-text: decoded audio → mono 16 kHz little-endian bytes for SpeechRecognizer. */
public final class ZgPcm {

    public static final int RECOGNIZER_SAMPLE_RATE = 16000;

    private ZgPcm() {
    }

    /** Streaming downmix + linear resampler (one per input stream). */
    public static final class Converter {
        private final int targetRate;
        private double phase;
        private double previous;
        private boolean hasPrevious;

        public Converter(int targetRate) {
            this.targetRate = targetRate;
        }

        /**
         * Appends {@code count} interleaved samples (sourceRate, channels) as mono targetRate PCM16 little-endian bytes.
         * Returns the number of bytes written into {@code out} starting at {@code offset}; {@code out} must hold at least
         * {@link #maxOutputBytes}.
         */
        public int convert(short[] in, int count, int sourceRate, int channels, byte[] out, int offset) {
            if (count <= 0 || sourceRate <= 0 || channels <= 0) {
                return 0;
            }
            final double step = (double) sourceRate / targetRate;
            int at = offset;
            final int frames = count / channels;
            for (int f = 0; f < frames; f++) {
                int acc = 0;
                for (int c = 0; c < channels; c++) {
                    acc += in[f * channels + c];
                }
                final double mono = (double) acc / channels;
                if (!hasPrevious) {
                    hasPrevious = true;
                    previous = mono;
                    phase = 0;
                }
                while (phase < 1.0) {
                    final long v = Math.round(previous + (mono - previous) * phase);
                    final short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
                    out[at++] = (byte) (s & 0xFF);
                    out[at++] = (byte) ((s >> 8) & 0xFF);
                    phase += step;
                }
                phase -= 1.0;
                previous = mono;
            }
            return at - offset;
        }

        public int maxOutputBytes(int count, int sourceRate, int channels) {
            if (count <= 0 || sourceRate <= 0 || channels <= 0) {
                return 0;
            }
            return (int) Math.ceil((double) (count / channels) * targetRate / sourceRate + 2) * 2;
        }
    }

    /**
     * Collects what a recognizer reports into one transcript. On-device recognizers differ: some deliver the whole text
     * only in partial results (final results empty), some restart their partials after each phrase without a segment
     * callback. So: a partial that does not continue the current one commits the current one; segment / final
     * results commit their text; the transcript is everything committed plus the last partial.
     */
    public static final class Transcript {
        private final java.util.ArrayList<String> committed = new java.util.ArrayList<>();
        private String current;

        public void partial(String text) {
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            if (current != null && !continues(current, text)) {
                committed.add(current);
            }
            current = text;
        }

        public void segment(String text) {
            if (text != null && !text.trim().isEmpty()) {
                committed.add(text);
            }
            current = null;
        }

        /** A final result replaces the partial it finalizes; an empty one keeps the partial. */
        public void result(String text) {
            if (text != null && !text.trim().isEmpty()) {
                segment(text);
            }
        }

        public String text() {
            final java.util.ArrayList<String> all = new java.util.ArrayList<>(committed);
            if (current != null) {
                all.add(current);
            }
            return joinSegments(all);
        }

        private static boolean continues(String previous, String next) {
            final String a = firstWord(previous);
            final String b = firstWord(next);
            return a.isEmpty() || b.isEmpty() || a.startsWith(b) || b.startsWith(a);
        }

        private static String firstWord(String s) {
            final String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            final int space = t.indexOf(' ');
            return space < 0 ? t : t.substring(0, space);
        }
    }

    /** Joins recognized segments with single spaces, trimming each; empty segments are dropped. */
    public static String joinSegments(Iterable<String> segments) {
        final StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (s == null) {
                continue;
            }
            final String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}

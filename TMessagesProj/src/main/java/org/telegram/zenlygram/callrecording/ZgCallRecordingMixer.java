/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

/**
 * Brings both directions of a call into one mono 48 kHz stream (port of the iOS SGCallRecordingSession mixer).
 *
 * <ul>
 *   <li>Each direction is downmixed and linearly resampled to mono 48 kHz on arrival (its own stateful normalizer), so
 *   "the same number of samples" means "the same amount of time".</li>
 *   <li>THE MUTE GATE: while the user is muted the microphone keeps delivering audio; it is replaced by the same length
 *   of silence, so the recording never holds speech the other party never heard and the stream keeps its clock.</li>
 *   <li>The mix takes min(local, remote) and sums by index. When one side stalls and the two differ by more than one
 *   second, the short side is padded with exactly the difference in silence and the mix carries on: a recording that
 *   silently stops while the indicator says it runs is the worst outcome.</li>
 * </ul>
 * Thread-safe: the audio threads add, the encoder thread drains.
 */
public final class ZgCallRecordingMixer {

    public static final int OUTPUT_SAMPLE_RATE = 48000;
    /** One second of mono 48 kHz. */
    public static final int SKEW_TOLERANCE_SAMPLES = OUTPUT_SAMPLE_RATE;

    public interface Sink {
        void write(short[] samples, int count);
    }

    /** Downmix + linear resampler of one direction. */
    static final class Normalizer {
        private double phase;
        private double previous;
        private boolean hasPrevious;

        /** Appends the normalized form of {@code count} interleaved samples. */
        void append(short[] samples, int count, int sampleRate, int channels, boolean silence, ShortQueue out) {
            if (count <= 0 || sampleRate <= 0 || channels <= 0) {
                return;
            }
            final double step = (double) sampleRate / OUTPUT_SAMPLE_RATE;
            final int frames = count / channels;
            for (int f = 0; f < frames; f++) {
                double mono;
                if (silence) {
                    mono = 0;
                } else {
                    int acc = 0;
                    for (int c = 0; c < channels; c++) {
                        acc += samples[f * channels + c];
                    }
                    mono = (double) acc / channels;
                }
                if (!hasPrevious) {
                    hasPrevious = true;
                    previous = mono;
                    phase = 0;
                }
                while (phase < 1.0) {
                    final double v = previous + (mono - previous) * phase;
                    out.add(clamp(Math.round(v)));
                    phase += step;
                }
                phase -= 1.0;
                previous = mono;
            }
        }
    }

    /** A growable FIFO of samples. */
    static final class ShortQueue {
        private short[] data = new short[OUTPUT_SAMPLE_RATE];
        private int head;
        private int size;

        int size() {
            return size;
        }

        void add(short v) {
            if (size == data.length) {
                final short[] grown = new short[data.length * 2];
                for (int i = 0; i < size; i++) {
                    grown[i] = data[(head + i) % data.length];
                }
                data = grown;
                head = 0;
            }
            data[(head + size) % data.length] = v;
            size++;
        }

        void addSilence(int n) {
            for (int i = 0; i < n; i++) {
                add((short) 0);
            }
        }

        short poll() {
            final short v = data[head];
            head = (head + 1) % data.length;
            size--;
            return v;
        }
    }

    private final Normalizer localNormalizer = new Normalizer();
    private final Normalizer remoteNormalizer = new Normalizer();
    private final ShortQueue local = new ShortQueue();
    private final ShortQueue remote = new ShortQueue();
    private short[] scratch = new short[4800];

    /** Microphone samples (interleaved PCM16). {@code muted}: the user is muted, so silence is written instead. */
    public synchronized void addLocal(short[] samples, int count, int sampleRate, int channels, boolean muted) {
        localNormalizer.append(samples, count, sampleRate, channels, muted, local);
    }

    /** Playout samples: what the user hears from the other party. */
    public synchronized void addRemote(short[] samples, int count, int sampleRate, int channels) {
        remoteNormalizer.append(samples, count, sampleRate, channels, false, remote);
    }

    /** Mixes what both sides have in common (after the skew pad) into {@code sink}. Returns the samples written. */
    public int drain(Sink sink) {
        return drain(sink, false);
    }

    /** At the end: whatever is left, the short side padded with silence. */
    public int finish(Sink sink) {
        return drain(sink, true);
    }

    private int drain(Sink sink, boolean all) {
        final short[] mixed;
        final int n;
        synchronized (this) {
            final int diff = local.size() - remote.size();
            if (all || Math.abs(diff) > SKEW_TOLERANCE_SAMPLES) {
                if (diff > 0) {
                    remote.addSilence(diff);
                } else if (diff < 0) {
                    local.addSilence(-diff);
                }
            }
            n = Math.min(local.size(), remote.size());
            if (n == 0) {
                return 0;
            }
            if (scratch.length < n) {
                scratch = new short[n];
            }
            mixed = scratch;
            for (int i = 0; i < n; i++) {
                mixed[i] = clamp(local.poll() + remote.poll());
            }
        }
        sink.write(mixed, n);
        return n;
    }

    public synchronized int pendingLocal() {
        return local.size();
    }

    public synchronized int pendingRemote() {
        return remote.size();
    }

    static short clamp(long v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }
}

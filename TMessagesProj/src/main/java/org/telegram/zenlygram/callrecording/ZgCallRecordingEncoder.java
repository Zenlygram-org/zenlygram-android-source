/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mono 48 kHz PCM16 → AAC-LC → ADTS frames appended to the recording file. Encoder thread only.
 *
 * <p>Built and checked BEFORE the recording is announced (iOS lesson: an encoder that failed to open silently swallowed
 * every buffer while the indicator promised a recording). A failed write latches {@link #isBroken()}; the frames
 * before it are whole, so the file still plays up to there.
 */
final class ZgCallRecordingEncoder {

    private static final long TIMEOUT_US = 5_000;

    private final MediaCodec codec;
    private final FileOutputStream out;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long samplesQueued;
    private long framesWritten;
    private boolean broken;
    private boolean finished;
    private byte[] frameBytes = new byte[2048];

    ZgCallRecordingEncoder(File file) throws IOException {
        final MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ZgCallRecordingFormat.SAMPLE_RATE, ZgCallRecordingFormat.CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, ZgCallRecordingFormat.BITRATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
        MediaCodec c = null;
        FileOutputStream o = null;
        try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            o = new FileOutputStream(file, true);
        } catch (Throwable e) {
            if (c != null) {
                try { c.release(); } catch (Throwable ignore) { }
            }
            if (o != null) {
                try { o.close(); } catch (Throwable ignore) { }
            }
            throw new IOException(e);
        }
        codec = c;
        out = o;
    }

    boolean isBroken() {
        return broken;
    }

    /** At least one whole frame is on disk. */
    boolean hasAudio() {
        return framesWritten > 0;
    }

    double duration() {
        return ZgCallRecordingFormat.durationOfFrames(framesWritten);
    }

    void append(short[] samples, int count) {
        if (broken || finished || count <= 0) {
            return;
        }
        int offset = 0;
        try {
            while (offset < count) {
                final int index = codec.dequeueInputBuffer(TIMEOUT_US);
                if (index < 0) {
                    drain(false);
                    continue;
                }
                final ByteBuffer in = codec.getInputBuffer(index);
                if (in == null) {
                    throw new IOException("no input buffer");
                }
                in.clear();
                in.order(ByteOrder.LITTLE_ENDIAN);
                final int n = Math.min(count - offset, in.remaining() / 2);
                for (int i = 0; i < n; i++) {
                    in.putShort(samples[offset + i]);
                }
                final long ptsUs = samplesQueued * 1_000_000L / ZgCallRecordingFormat.SAMPLE_RATE;
                codec.queueInputBuffer(index, 0, n * 2, ptsUs, 0);
                samplesQueued += n;
                offset += n;
                drain(false);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            broken = true;
        }
    }

    /** Flushes the tail and closes the file. Safe to call twice. */
    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            if (!broken) {
                int index;
                long waited = 0;
                while ((index = codec.dequeueInputBuffer(TIMEOUT_US)) < 0 && waited < 1_000_000) {
                    drain(false);
                    waited += TIMEOUT_US;
                }
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, samplesQueued * 1_000_000L / ZgCallRecordingFormat.SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    drain(true);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            broken = true;
        }
        try { codec.stop(); } catch (Throwable ignore) { }
        try { codec.release(); } catch (Throwable ignore) { }
        try {
            out.getFD().sync();
        } catch (Throwable ignore) {
        }
        try {
            out.close();
        } catch (Throwable e) {
            broken = true;
        }
    }

    private void drain(boolean untilEnd) throws IOException {
        long idle = 0;
        while (true) {
            final int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd || (idle += TIMEOUT_US) > 2_000_000) {
                    return;
                }
                continue;
            }
            if (index < 0) {
                continue; // format / buffers changed
            }
            final ByteBuffer data = codec.getOutputBuffer(index);
            final boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && data != null) {
                if (frameBytes.length < info.size + 7) {
                    frameBytes = new byte[info.size + 7];
                }
                System.arraycopy(ZgCallRecordingFormat.adtsHeader(info.size, ZgCallRecordingFormat.SAMPLE_RATE, ZgCallRecordingFormat.CHANNELS), 0, frameBytes, 0, 7);
                data.position(info.offset);
                data.get(frameBytes, 7, info.size);
                out.write(frameBytes, 0, info.size + 7);
                framesWritten++;
            }
            codec.releaseOutputBuffer(index, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }
}

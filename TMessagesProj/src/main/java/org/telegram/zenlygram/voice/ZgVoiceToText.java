/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.voice;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.TargetApi;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice-to-text "On this device" (iOS: Voice-to-text service = Apple, SFSpeechRecognizer on the local file).
 *
 * <p>Android 13+ with an on-device speech service: the voice / round video message's own file is decoded
 * (MediaExtractor + MediaCodec), converted to mono 16 kHz PCM and streamed through a pipe into
 * {@link SpeechRecognizer#createOnDeviceSpeechRecognizer} as one session over the whole message (long silence limits,
 * so a pause does not end it; partial results are assembled by {@link ZgPcm.Transcript}, because some on-device
 * services deliver the text only as partials). The recognition language is the app's language. The audio never leaves the phone and no Telegram Premium is needed. The result is stored exactly like a
 * Telegram transcription (TranscribeButton keeps showing it). Below Android 13, or without an on-device service, the
 * option is not offered and Telegram's transcription is used as before.
 */
public final class ZgVoiceToText {

    public interface Callback {
        /** Main thread. {@code text} null on failure (the user was told why). */
        void onResult(String text);
    }

    private static final long TIMEOUT_MS = 180_000;
    /** Messages longer than this are left to Telegram (iOS caps the local path the same way in practice). */
    private static final long MAX_DURATION_US = 15 * 60 * 1_000_000L;

    private static Boolean supported;

    private ZgVoiceToText() {
    }

    /** The phone has an on-device recognizer that takes audio from a file descriptor (Android 13+). */
    public static boolean isSupportedOnThisPhone() {
        if (supported == null) {
            boolean value = false;
            try {
                value = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ApplicationLoader.applicationContext);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            supported = value;
        }
        return supported;
    }

    /** "On this device" is selected and usable. */
    public static boolean isEnabled() {
        return ZgFeatures.TRANSCRIPTION_BACKEND.isPorted()
            && ZgSettings.transcriptionBackend.is(ZgSettings.TranscriptionBackend.APPLE)
            && isSupportedOnThisPhone();
    }

    /** TranscribeButton.transcribePressed: recognizes {@code message} on the device. */
    public static void transcribe(MessageObject message, Callback callback) {
        if (Build.VERSION.SDK_INT < 33 || message == null || message.messageOwner == null) {
            fail(callback, R.string.ZG_VoiceToText_Failed);
            return;
        }
        final File file = sourceFile(message);
        if (file == null) {
            fail(callback, R.string.ZG_VoiceToText_Failed);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            byte[] pcm;
            try {
                pcm = decode(file);
            } catch (Throwable e) {
                FileLog.e(e);
                pcm = null;
            }
            final byte[] audio = pcm;
            AndroidUtilities.runOnUIThread(() -> {
                if (audio == null || audio.length == 0) {
                    fail(callback, R.string.ZG_VoiceToText_Failed);
                } else {
                    recognize(audio, callback);
                }
            });
        });
    }

    private static File sourceFile(MessageObject message) {
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            final File f = new File(message.messageOwner.attachPath);
            if (f.exists() && f.length() > 0) {
                return f;
            }
        }
        final File f = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        return f != null && f.exists() && f.length() > 0 ? f : null;
    }

    private static void fail(Callback callback, int textRes) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BulletinFactory.global().createSimpleBulletin(R.raw.error, getString(textRes)).show();
            } catch (Throwable ignore) {
            }
            callback.onResult(null);
        });
    }

    /** The message's audio track as mono 16 kHz PCM16 little-endian. Background thread. */
    static byte[] decode(File file) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                return null;
            }
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_DURATION_US) {
                return null;
            }
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            final ZgPcm.Converter converter = new ZgPcm.Converter(ZgPcm.RECOGNIZER_SAMPLE_RATE);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] shorts = new short[0];
            byte[] bytes = new byte[0];
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat f = codec.getOutputFormat();
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                        return null;
                    }
                } else if (outIndex >= 0) {
                    final ByteBuffer data = codec.getOutputBuffer(outIndex);
                    if (data != null && info.size > 0) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        final int count = info.size / 2;
                        if (shorts.length < count) {
                            shorts = new short[count];
                        }
                        data.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, count);
                        final int max = converter.maxOutputBytes(count, sampleRate, channels);
                        if (bytes.length < max) {
                            bytes = new byte[max];
                        }
                        out.write(bytes, 0, converter.convert(shorts, count, sampleRate, channels, bytes, 0));
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            return out.toByteArray();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignore) { }
                try { codec.release(); } catch (Throwable ignore) { }
            }
            extractor.release();
        }
    }

    @TargetApi(33)
    private static void recognize(byte[] pcm, Callback callback) {
        final SpeechRecognizer recognizer;
        final ParcelFileDescriptor[] pipe;
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ApplicationLoader.applicationContext);
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Throwable e) {
            FileLog.e(e);
            fail(callback, R.string.ZG_VoiceToText_Failed);
            return;
        }
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, ZgPcm.RECOGNIZER_SAMPLE_RATE);
        // One session over the whole message: a pause in the speech must not end it (the pipe's end does).
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000);

        final ZgPcm.Transcript transcript = new ZgPcm.Transcript();
        final boolean[] done = {false};
        final Runnable[] finish = new Runnable[1];
        final Runnable timeout = () -> {
            if (!done[0]) {
                finish[0].run();
            }
        };
        finish[0] = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            try {
                recognizer.destroy();
            } catch (Throwable ignore) {
            }
            closeQuietly(pipe[0]);
            final String text = transcript.text();
            if (text.isEmpty()) {
                fail(callback, R.string.ZG_VoiceToText_Failed);
            } else {
                callback.onResult(text);
            }
        };
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onPartialResults(Bundle partialResults) { transcript.partial(best(partialResults)); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onError(int error) {
                if (done[0]) {
                    return;
                }
                if (!transcript.text().isEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    finish[0].run();
                    return;
                }
                done[0] = true;
                AndroidUtilities.cancelRunOnUIThread(timeout);
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    try {
                        recognizer.triggerModelDownload(intent);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                try {
                    recognizer.destroy();
                } catch (Throwable ignore) {
                }
                closeQuietly(pipe[0]);
                fail(callback, error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ? R.string.ZG_VoiceToText_ModelDownload
                    : error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ? R.string.ZG_VoiceToText_LanguageUnsupported
                    : R.string.ZG_VoiceToText_Failed);
            }

            @Override
            public void onResults(Bundle results) {
                transcript.result(best(results));
                finish[0].run();
            }

            @Override
            public void onSegmentResults(Bundle segmentResults) {
                transcript.segment(best(segmentResults));
            }

            @Override
            public void onEndOfSegmentedSession() {
                finish[0].run();
            }
        });
        try {
            recognizer.startListening(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            done[0] = true;
            recognizer.destroy();
            closeQuietly(pipe[0]);
            closeQuietly(pipe[1]);
            fail(callback, R.string.ZG_VoiceToText_Failed);
            return;
        }
        AndroidUtilities.runOnUIThread(timeout, TIMEOUT_MS);
        // The recognizer reads the pipe at its own pace; the writer blocks while the pipe is full.
        Utilities.globalQueue.postRunnable(() -> {
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                out.write(pcm);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static String best(Bundle results) {
        if (results == null) {
            return null;
        }
        final ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list != null && !list.isEmpty() ? list.get(0) : null;
    }

    private static String languageTag() {
        try {
            final Locale locale = LocaleController.getInstance().getCurrentLocale();
            if (locale != null && !TextUtils.isEmpty(locale.getLanguage())) {
                return locale.toLanguageTag();
            }
        } catch (Throwable ignore) {
        }
        return Locale.getDefault().toLanguageTag();
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Throwable ignore) {
        }
    }
}

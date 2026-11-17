/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Decodes a round video, draws every frame into the branded 1080×1080 square on the GPU (background with the
 * wordmark, the video inside the circle, the current logo frame) and encodes it to H.264; the audio track is copied
 * unchanged. The Android counterpart of the AVVideoComposition + Core Image pass of iOS SGRoundVideoBranding.
 *
 * <p>Runs synchronously on the calling (background) thread, which owns the EGL context for the whole export. Any
 * failure throws; the caller then saves the original file, as iOS does.
 */
final class ZgRoundVideoTranscoder {

    private static final long TIMEOUT_US = 10_000;
    private static final long FRAME_WAIT_MS = 2_500;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    interface Cancel {
        boolean isCancelled();
    }

    private static final String VERTEX_2D =
        "attribute vec4 aPos;\n" +
        "attribute vec2 aTex;\n" +
        "varying vec2 vTex;\n" +
        "void main() { gl_Position = aPos; vTex = aTex; }\n";

    private static final String FRAGMENT_2D =
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform sampler2D sTex;\n" +
        "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";

    private static final String VERTEX_VIDEO =
        "attribute vec4 aPos;\n" +
        "attribute vec2 aTex;\n" +
        "attribute vec2 aPx;\n" +
        "uniform mat4 uST;\n" +
        "varying vec2 vTex;\n" +
        "varying vec2 vPx;\n" +
        "void main() { gl_Position = aPos; vTex = (uST * vec4(aTex, 0.0, 1.0)).xy; vPx = aPx; }\n";

    /** The circle mask, antialiased over one pixel, premultiplied for GL_ONE / GL_ONE_MINUS_SRC_ALPHA. */
    private static final String FRAGMENT_VIDEO =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision highp float;\n" +
        "varying vec2 vTex;\n" +
        "varying vec2 vPx;\n" +
        "uniform samplerExternalOES sTex;\n" +
        "uniform vec3 uCircle;\n" +
        "void main() {\n" +
        "  float a = clamp(uCircle.z - length(vPx - uCircle.xy) + 0.5, 0.0, 1.0);\n" +
        "  gl_FragColor = vec4(texture2D(sTex, vTex).rgb * a, a);\n" +
        "}\n";

    private final File input;
    private final File output;
    private final Bitmap background;
    private final Bitmap logoSheet;
    private final Cancel cancel;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program2d;
    private int programVideo;
    private int textureVideo;
    private int textureBackground;
    private int textureLogo;

    private SurfaceTexture surfaceTexture;
    private final Object frameLock = new Object();
    private boolean frameAvailable;
    private final float[] stMatrix = new float[16];
    private float[] uvScale = {1f, 1f};

    ZgRoundVideoTranscoder(File input, File output, Bitmap background, Bitmap logoSheet, Cancel cancel) {
        this.input = input;
        this.output = output;
        this.background = background;
        this.logoSheet = logoSheet;
        this.cancel = cancel;
    }

    void run() throws Exception {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Surface encoderSurface = null;
        Surface decoderSurface = null;
        HandlerThread callbackThread = null;
        boolean muxerStarted = false;
        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(input.getAbsolutePath());
            final int videoIndex = findTrack(videoExtractor, "video/");
            if (videoIndex < 0) {
                throw new IOException("no video track");
            }
            videoExtractor.selectTrack(videoIndex);
            final MediaFormat videoFormat = videoExtractor.getTrackFormat(videoIndex);
            final int rotation = videoFormat.containsKey(MediaFormat.KEY_ROTATION) ? videoFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
            final int[] upright = ZgRoundVideoLayout.uprightSize(videoFormat.getInteger(MediaFormat.KEY_WIDTH), videoFormat.getInteger(MediaFormat.KEY_HEIGHT), rotation);
            uvScale = ZgRoundVideoLayout.videoUvScale(upright[0], upright[1]);
            float sourceFps = 0f;
            if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    sourceFps = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                } catch (ClassCastException e) {
                    sourceFps = videoFormat.getFloat(MediaFormat.KEY_FRAME_RATE);
                }
            }
            final int fps = ZgRoundVideoLayout.outputFrameRate(sourceFps);

            MediaFormat audioFormat = null;
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(input.getAbsolutePath());
            final int audioIndex = findTrack(audioExtractor, "audio/");
            if (audioIndex >= 0) {
                audioExtractor.selectTrack(audioIndex);
                audioFormat = audioExtractor.getTrackFormat(audioIndex);
            }

            final int size = ZgRoundVideoLayout.RENDER_SIZE;
            final MediaFormat encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size, size);
            encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, ZgRoundVideoLayout.bitrate(fps));
            encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            setUpEgl(encoderSurface);
            setUpGl();

            callbackThread = new HandlerThread("ZgRoundVideoFrames");
            callbackThread.start();
            surfaceTexture = new SurfaceTexture(textureVideo);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameLock) {
                    frameAvailable = true;
                    frameLock.notifyAll();
                }
            }, new Handler(callbackThread.getLooper()));
            decoderSurface = new Surface(surfaceTexture);

            decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(videoFormat, decoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerVideoTrack = -1;
            int muxerAudioTrack = -1;

            final MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
            final MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            while (!encoderDone) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new InterruptedException("cancelled");
                }
                if (!inputDone) {
                    final int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        final ByteBuffer buffer = decoder.getInputBuffer(index);
                        final int sampleSize = buffer == null ? -1 : videoExtractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, sampleSize, videoExtractor.getSampleTime(), 0);
                            videoExtractor.advance();
                        }
                    }
                }

                // Drain the encoder first so the decoder never waits on a full encoder.
                while (true) {
                    final int index = encoder.dequeueOutputBuffer(encoderInfo, decoderDone ? TIMEOUT_US : 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw new IOException("encoder format changed twice");
                        }
                        muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(audioFormat);
                        }
                        muxer.start();
                        muxerStarted = true;
                    } else if (index >= 0) {
                        final ByteBuffer data = encoder.getOutputBuffer(index);
                        if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encoderInfo.size = 0;
                        }
                        if (encoderInfo.size > 0 && data != null) {
                            if (!muxerStarted) {
                                throw new IOException("encoder data before its format");
                            }
                            data.position(encoderInfo.offset);
                            data.limit(encoderInfo.offset + encoderInfo.size);
                            muxer.writeSampleData(muxerVideoTrack, data, encoderInfo);
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }
                }

                if (!decoderDone) {
                    final int index = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US);
                    if (index >= 0) {
                        final boolean render = decoderInfo.size > 0;
                        decoder.releaseOutputBuffer(index, render);
                        if (render) {
                            awaitFrame();
                            surfaceTexture.updateTexImage();
                            surfaceTexture.getTransformMatrix(stMatrix);
                            drawFrame(decoderInfo.presentationTimeUs);
                            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, decoderInfo.presentationTimeUs * 1000L);
                            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                                throw new IOException("eglSwapBuffers failed");
                            }
                        }
                        if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }
            }
            if (!muxerStarted) {
                throw new IOException("no frames");
            }

            if (muxerAudioTrack >= 0) {
                copyAudio(audioExtractor, audioFormat, muxer, muxerAudioTrack);
            }
            muxer.stop();
            muxerStarted = false;
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Throwable ignore) { }
                try { decoder.release(); } catch (Throwable ignore) { }
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignore) { }
                try { encoder.release(); } catch (Throwable ignore) { }
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Throwable ignore) { }
                }
                try { muxer.release(); } catch (Throwable ignore) { }
            }
            if (decoderSurface != null) {
                decoderSurface.release();
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
            }
            releaseEgl();
            if (encoderSurface != null) {
                encoderSurface.release();
            }
            if (callbackThread != null) {
                callbackThread.quitSafely();
            }
            if (videoExtractor != null) {
                videoExtractor.release();
            }
            if (audioExtractor != null) {
                audioExtractor.release();
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0, n = extractor.getTrackCount(); i < n; i++) {
            final String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void copyAudio(MediaExtractor extractor, MediaFormat format, MediaMuxer muxer, int track) {
        int capacity = 256 * 1024;
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            capacity = Math.max(capacity, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
        }
        final ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            final int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            info.set(0, size, extractor.getSampleTime(),
                (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, buffer, info);
            extractor.advance();
        }
    }

    private void awaitFrame() throws IOException, InterruptedException {
        synchronized (frameLock) {
            final long deadline = System.currentTimeMillis() + FRAME_WAIT_MS;
            while (!frameAvailable) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new IOException("frame wait timed out");
                }
                frameLock.wait(left);
            }
            frameAvailable = false;
        }
    }

    // ---- EGL / GL

    private void setUpEgl(Surface surface) throws IOException {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IOException("no EGL display");
        }
        final int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IOException("eglInitialize failed");
        }
        final int[] attributes = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        final EGLConfig[] configs = new EGLConfig[1];
        final int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IOException("no recordable EGL config");
        }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IOException("eglCreateContext failed");
        }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IOException("eglCreateWindowSurface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IOException("eglMakeCurrent failed");
        }
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private void setUpGl() throws IOException {
        program2d = buildProgram(VERTEX_2D, FRAGMENT_2D);
        programVideo = buildProgram(VERTEX_VIDEO, FRAGMENT_VIDEO);

        final int[] textures = new int[3];
        GLES20.glGenTextures(3, textures, 0);
        textureVideo = textures[0];
        textureBackground = textures[1];
        textureLogo = textures[2];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureVideo);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        uploadBitmap(textureBackground, background);
        if (logoSheet != null) {
            uploadBitmap(textureLogo, logoSheet);
        }
    }

    private static void uploadBitmap(int texture, Bitmap bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // GLUtils uploads Android's premultiplied pixels as they are: blending uses GL_ONE / GL_ONE_MINUS_SRC_ALPHA.
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    }

    private static int buildProgram(String vertex, String fragment) throws IOException {
        final int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        final int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        final int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        final int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            final String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IOException("link: " + log);
        }
        return program;
    }

    private static int compile(int type, String source) throws IOException {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            final String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IOException("compile: " + log);
        }
        return shader;
    }

    private void drawFrame(long timeUs) {
        final int size = ZgRoundVideoLayout.RENDER_SIZE;
        GLES20.glViewport(0, 0, size, size);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // 1. Background and wordmark.
        GLES20.glDisable(GLES20.GL_BLEND);
        draw2d(textureBackground, 0, 0, size, size, 0f, 0f, 1f, 1f);
        GLES20.glEnable(GLES20.GL_BLEND);

        // 2. The video in the circle: short side fills the diameter, centred (SurfaceTexture coordinates are y-up).
        final float sx = uvScale[0];
        final float sy = uvScale[1];
        final float u0 = 0.5f - 0.5f * sx;
        final float u1 = 0.5f + 0.5f * sx;
        final float vTop = 0.5f + 0.5f * sy;
        final float vBottom = 0.5f - 0.5f * sy;
        GLES20.glUseProgram(programVideo);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureVideo);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programVideo, "sTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programVideo, "uST"), 1, false, stMatrix, 0);
        final float c = size / 2f;
        GLES20.glUniform3f(GLES20.glGetUniformLocation(programVideo, "uCircle"), c, c, ZgRoundVideoLayout.CIRCLE_DIAMETER / 2f);
        // Triangle strip: top-left, bottom-left, top-right, bottom-right.
        final float[] pos = {-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f};
        final float[] tex = {u0, vTop, u0, vBottom, u1, vTop, u1, vBottom};
        final float[] px = {0f, 0f, 0f, size, size, 0f, size, size};
        bindAttribute(programVideo, "aPos", pos);
        bindAttribute(programVideo, "aTex", tex);
        bindAttribute(programVideo, "aPx", px);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 3. The logo frame.
        if (logoSheet != null) {
            final int[] cell = ZgRoundVideoLayout.logoCell(ZgRoundVideoLayout.logoFrameIndex(timeUs));
            final float w = logoSheet.getWidth();
            final float h = logoSheet.getHeight();
            draw2d(textureLogo,
                ZgRoundVideoLayout.LOGO_ORIGIN_X, ZgRoundVideoLayout.LOGO_ORIGIN_Y,
                ZgRoundVideoLayout.LOGO_ORIGIN_X + cell[2], ZgRoundVideoLayout.LOGO_ORIGIN_Y + cell[3],
                cell[0] / w, cell[1] / h, (cell[0] + cell[2]) / w, (cell[1] + cell[3]) / h);
        }
    }

    /** A bitmap texture into the render-space rectangle (y-down pixels); bitmap rows run top to bottom. */
    private void draw2d(int texture, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
        final float half = ZgRoundVideoLayout.RENDER_SIZE / 2f;
        final float l = x0 / half - 1f;
        final float r = x1 / half - 1f;
        final float t = 1f - y0 / half;
        final float b = 1f - y1 / half;
        GLES20.glUseProgram(program2d);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "sTex"), 0);
        bindAttribute(program2d, "aPos", new float[]{l, t, l, b, r, t, r, b});
        bindAttribute(program2d, "aTex", new float[]{u0, v0, u0, v1, u1, v0, u1, v1});
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void bindAttribute(int program, String name, float[] values) {
        final int location = GLES20.glGetAttribLocation(program, name);
        if (location < 0) {
            return;
        }
        final FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        GLES20.glEnableVertexAttribArray(location);
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer);
    }
}

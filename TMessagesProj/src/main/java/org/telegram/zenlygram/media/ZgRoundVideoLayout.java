/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

/**
 * The geometry of a branded round video export, the same numbers as iOS SGRoundVideoBranding (release/appstore
 * 648e15e528): a 1080×1080 square, the circle filling it, a dark vertical gradient behind it, the animated Zenlygram
 * logo (90-frame sprite sheet, 3 s at 30 fps) in the bottom-left corner and "ZENLYGRAM", widely tracked, along the
 * circle's bottom-right edge.
 *
 * <p>Pure Java (no Android types) so the layout is checked by a JVM test. Render space is y-down, in pixels.
 */
public final class ZgRoundVideoLayout {

    public static final int RENDER_SIZE = 1080;
    public static final float CIRCLE_DIAMETER = 1080f;

    /** The two ends of the background's vertical gradient, as grey levels (iOS UIColor(white: 0.17) → 0.03). */
    public static final float BACKGROUND_TOP_WHITE = 0.17f;
    public static final float BACKGROUND_BOTTOM_WHITE = 0.03f;

    public static final int LOGO_FRAME_WIDTH = 110;
    public static final int LOGO_FRAME_HEIGHT = 133;
    public static final int LOGO_FRAME_COUNT = 90;
    public static final int LOGO_FRAMES_PER_ROW = 10;
    public static final double LOGO_FRAME_RATE = 30.0;
    /** Top-left corner of the logo frame in render space. */
    public static final int LOGO_ORIGIN_X = 49;
    public static final int LOGO_ORIGIN_Y = 911;

    public static final String WORDMARK = "ZENLYGRAM";
    public static final float WORDMARK_FONT_SIZE = 27f;
    public static final float WORDMARK_TRACKING = 15f;
    public static final float WORDMARK_BASELINE_RADIUS = 573f;
    /** Centre of the wordmark run on the circle: a little below the bottom-right diagonal. */
    public static final double WORDMARK_CENTER_DEGREES = -44.8;

    /** iOS caps the composition frame rate at 60 and uses 30 when the track reports none. */
    public static final int MAX_FRAME_RATE = 60;
    public static final int DEFAULT_FRAME_RATE = 30;

    private ZgRoundVideoLayout() {
    }

    /** Which logo frame is on screen at {@code timeUs} (presentation time of the output frame). */
    public static int logoFrameIndex(long timeUs) {
        if (timeUs <= 0) {
            return 0;
        }
        final long index = (long) (timeUs / 1_000_000.0 * LOGO_FRAME_RATE);
        return (int) (index % LOGO_FRAME_COUNT);
    }

    /** The cell of {@code index} in the sprite sheet: {x, y, width, height} in sheet pixels. */
    public static int[] logoCell(int index) {
        final int i = Math.max(0, index) % LOGO_FRAME_COUNT;
        return new int[]{
            (i % LOGO_FRAMES_PER_ROW) * LOGO_FRAME_WIDTH,
            (i / LOGO_FRAMES_PER_ROW) * LOGO_FRAME_HEIGHT,
            LOGO_FRAME_WIDTH,
            LOGO_FRAME_HEIGHT
        };
    }

    /** The sprite sheet's expected size (10 × 9 cells). */
    public static int sheetWidth() {
        return LOGO_FRAMES_PER_ROW * LOGO_FRAME_WIDTH;
    }

    public static int sheetHeight() {
        return ((LOGO_FRAME_COUNT + LOGO_FRAMES_PER_ROW - 1) / LOGO_FRAMES_PER_ROW) * LOGO_FRAME_HEIGHT;
    }

    /**
     * How much of the (upright) source frame the circle shows along each axis: the short side fills the diameter and
     * the long side is cropped symmetrically, like the iOS scale-by-short-side transform. {sx, sy}, each in (0, 1].
     */
    public static float[] videoUvScale(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return new float[]{1f, 1f};
        }
        final float shortSide = Math.min(sourceWidth, sourceHeight);
        return new float[]{shortSide / sourceWidth, shortSide / sourceHeight};
    }

    /** Source size as shown, after the container's rotation (90 / 270 swap the sides). */
    public static int[] uprightSize(int width, int height, int rotationDegrees) {
        final int r = ((rotationDegrees % 360) + 360) % 360;
        return r == 90 || r == 270 ? new int[]{height, width} : new int[]{width, height};
    }

    /** Output frame rate: the source's, rounded, at most 60; 30 when unknown. */
    public static int outputFrameRate(float sourceFrameRate) {
        if (!(sourceFrameRate > 0f)) {
            return DEFAULT_FRAME_RATE;
        }
        return Math.max(1, Math.min(MAX_FRAME_RATE, Math.round(sourceFrameRate)));
    }

    /**
     * The wordmark, glyph by glyph along the arc just outside the circle, reading counter-clockwise with each glyph
     * upright towards the centre (iOS drawArcWordmark). For each glyph: {x, y, rotationDegrees} of its baseline
     * centre; the glyph is drawn centred on x with its baseline at y after rotating the canvas by rotationDegrees
     * (clockwise positive, y-down, as Android Canvas and the flipped UIKit context both do).
     *
     * @param glyphWidths advance width of each glyph at {@link #WORDMARK_FONT_SIZE}
     * @param capHeight   the font's cap height: arc length is measured on the glyphs' middle line
     */
    public static float[][] wordmarkGlyphs(float[] glyphWidths, float capHeight, float centerX, float centerY) {
        final int n = glyphWidths.length;
        final float[][] out = new float[n][];
        if (n == 0) {
            return out;
        }
        final double middleRadius = WORDMARK_BASELINE_RADIUS - capHeight / 2.0;
        double runLength = WORDMARK_TRACKING * (n - 1);
        for (float w : glyphWidths) {
            runLength += w;
        }
        double angle = Math.toRadians(WORDMARK_CENTER_DEGREES) - runLength / middleRadius / 2.0;
        for (int i = 0; i < n; i++) {
            final double w = glyphWidths[i];
            final double theta = angle + w / middleRadius / 2.0;
            final float x = (float) (centerX + WORDMARK_BASELINE_RADIUS * Math.cos(theta));
            final float y = (float) (centerY - WORDMARK_BASELINE_RADIUS * Math.sin(theta));
            final float rotation = (float) Math.toDegrees(-(Math.PI / 2.0 + theta));
            out[i] = new float[]{x, y, rotation};
            angle += (w + WORDMARK_TRACKING) / middleRadius;
        }
        return out;
    }

    /** Encoder bitrate for the 1080×1080 output: about 0.2 bits per pixel per frame, between 4 and 12 Mbit/s. */
    public static int bitrate(int frameRate) {
        final long bits = (long) RENDER_SIZE * RENDER_SIZE * Math.max(1, frameRate) / 5;
        return (int) Math.max(4_000_000L, Math.min(12_000_000L, bits));
    }
}

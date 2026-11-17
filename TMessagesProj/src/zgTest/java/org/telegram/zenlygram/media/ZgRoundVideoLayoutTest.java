/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The branded round video geometry against the iOS SGRoundVideoBranding numbers. */
public class ZgRoundVideoLayoutTest {

    @Test
    public void logoFrameLoopsEveryThreeSeconds() {
        assertEquals(0, ZgRoundVideoLayout.logoFrameIndex(0));
        assertEquals(0, ZgRoundVideoLayout.logoFrameIndex(-5));
        assertEquals(1, ZgRoundVideoLayout.logoFrameIndex(33_400));
        assertEquals(29, ZgRoundVideoLayout.logoFrameIndex(999_999));
        assertEquals(89, ZgRoundVideoLayout.logoFrameIndex(2_999_000));
        assertEquals(0, ZgRoundVideoLayout.logoFrameIndex(3_000_000));
        assertEquals(15, ZgRoundVideoLayout.logoFrameIndex(3_500_000));
    }

    @Test
    public void logoCellsTileTheSheet() {
        assertArrayEquals(new int[]{0, 0, 110, 133}, ZgRoundVideoLayout.logoCell(0));
        assertArrayEquals(new int[]{990, 0, 110, 133}, ZgRoundVideoLayout.logoCell(9));
        assertArrayEquals(new int[]{0, 133, 110, 133}, ZgRoundVideoLayout.logoCell(10));
        assertArrayEquals(new int[]{990, 1064, 110, 133}, ZgRoundVideoLayout.logoCell(89));
        assertEquals(1100, ZgRoundVideoLayout.sheetWidth());
        assertEquals(1197, ZgRoundVideoLayout.sheetHeight());
        // The logo sits inside the square, bottom-left.
        assertTrue(ZgRoundVideoLayout.LOGO_ORIGIN_Y + ZgRoundVideoLayout.LOGO_FRAME_HEIGHT < ZgRoundVideoLayout.RENDER_SIZE);
    }

    @Test
    public void videoFillsTheCircleByItsShortSide() {
        assertArrayEquals(new float[]{1f, 1f}, ZgRoundVideoLayout.videoUvScale(480, 480), 1e-6f);
        assertArrayEquals(new float[]{0.75f, 1f}, ZgRoundVideoLayout.videoUvScale(640, 480), 1e-6f);
        assertArrayEquals(new float[]{1f, 0.5f}, ZgRoundVideoLayout.videoUvScale(400, 800), 1e-6f);
        assertArrayEquals(new float[]{1f, 1f}, ZgRoundVideoLayout.videoUvScale(0, 480), 1e-6f);
        assertArrayEquals(new int[]{480, 640}, ZgRoundVideoLayout.uprightSize(640, 480, 90));
        assertArrayEquals(new int[]{640, 480}, ZgRoundVideoLayout.uprightSize(640, 480, 180));
        assertArrayEquals(new int[]{480, 640}, ZgRoundVideoLayout.uprightSize(640, 480, -90));
    }

    @Test
    public void frameRateIsCappedLikeIos() {
        assertEquals(30, ZgRoundVideoLayout.outputFrameRate(0f));
        assertEquals(30, ZgRoundVideoLayout.outputFrameRate(Float.NaN));
        assertEquals(30, ZgRoundVideoLayout.outputFrameRate(29.97f));
        assertEquals(60, ZgRoundVideoLayout.outputFrameRate(120f));
        assertTrue(ZgRoundVideoLayout.bitrate(30) >= 4_000_000);
        assertTrue(ZgRoundVideoLayout.bitrate(60) <= 12_000_000);
    }

    @Test
    public void wordmarkRunsCounterClockwiseCentredBelowTheDiagonal() {
        final float[] widths = new float[9];
        java.util.Arrays.fill(widths, 18f);
        final float c = 540f;
        final float[][] g = ZgRoundVideoLayout.wordmarkGlyphs(widths, 19f, c, c);
        assertEquals(9, g.length);
        for (float[] glyph : g) {
            // Every baseline point lies on the 573 px arc, outside the 540 px circle, in the bottom-right quadrant.
            assertEquals(573.0, Math.hypot(glyph[0] - c, glyph[1] - c), 0.01);
            assertTrue(glyph[0] > c && glyph[1] > c);
            assertTrue(glyph[0] < 1080 + 1 && glyph[1] < 1080 + 1);
        }
        // Counter-clockwise on screen: from lower (larger y) to more to the right (larger x).
        assertTrue(g[0][1] > g[8][1]);
        assertTrue(g[0][0] < g[8][0]);
        // The middle glyph sits at -44.8° and is rotated so its top points to the centre.
        final double middle = Math.toDegrees(Math.atan2(-(g[4][1] - c), g[4][0] - c));
        assertEquals(-44.8, middle, 0.01);
        assertEquals(-(90.0 - 44.8), g[4][2], 0.01);
        // Symmetric around the centre angle.
        final double first = Math.toDegrees(Math.atan2(-(g[0][1] - c), g[0][0] - c));
        final double last = Math.toDegrees(Math.atan2(-(g[8][1] - c), g[8][0] - c));
        assertEquals(-44.8, (first + last) / 2.0, 0.01);
    }
}

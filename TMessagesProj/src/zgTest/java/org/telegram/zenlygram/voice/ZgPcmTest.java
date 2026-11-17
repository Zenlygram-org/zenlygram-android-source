/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ZgPcmTest {

    @Test
    public void stereo48kBecomesMono16kLittleEndian() {
        final ZgPcm.Converter c = new ZgPcm.Converter(16000);
        final short[] in = new short[960 * 2]; // 20 ms stereo
        for (int i = 0; i < 960; i++) {
            in[i * 2] = 1000;
            in[i * 2 + 1] = 3000;
        }
        final byte[] out = new byte[c.maxOutputBytes(in.length, 48000, 2)];
        final int n = c.convert(in, in.length, 48000, 2, out, 0);
        assertEquals(320 * 2, n); // 20 ms at 16 kHz
        assertEquals(2000, (short) ((out[0] & 0xFF) | (out[1] << 8)));
        assertEquals(2000, (short) ((out[n - 2] & 0xFF) | (out[n - 1] << 8)));
    }

    @Test
    public void upsamplingStaysWithinTheBoundAndKeepsTime() {
        final ZgPcm.Converter c = new ZgPcm.Converter(16000);
        final short[] in = new short[80]; // 10 ms at 8 kHz
        Arrays.fill(in, (short) -5);
        final int max = c.maxOutputBytes(in.length, 8000, 1);
        final byte[] out = new byte[max];
        final int n = c.convert(in, in.length, 8000, 1, out, 0);
        assertEquals(160 * 2, n);
        assertTrue(n <= max);
        assertEquals(0, c.convert(in, 0, 8000, 1, out, 0));
    }

    @Test
    public void segmentsAreJoinedCleanly() {
        assertEquals("salom dunyo bugun", ZgPcm.joinSegments(Arrays.asList(" salom ", null, "", "dunyo", "bugun ")));
        assertEquals("", ZgPcm.joinSegments(Arrays.asList("  ", null)));
    }

    @Test
    public void transcriptKeepsPhrasesWhenPartialsRestart() {
        final ZgPcm.Transcript t = new ZgPcm.Transcript();
        for (String p : new String[]{"Hello", "Hello this", "Hello this is", "Record", "Recorded", "Recorded for", "Trans", "Transcription test"}) {
            t.partial(p);
        }
        t.segment("Transcription test");
        assertEquals("Hello this is Recorded for Transcription test", t.text());
    }

    @Test
    public void transcriptFallsBackToTheLastPartialWhenTheResultIsEmpty() {
        final ZgPcm.Transcript t = new ZgPcm.Transcript();
        t.partial("salom bu ovozli");
        t.partial("salom bu ovozli xabar");
        t.result(null);
        assertEquals("salom bu ovozli xabar", t.text());
        t.result("Salom, bu ovozli xabar.");
        assertEquals("Salom, bu ovozli xabar.", t.text());
        assertEquals("", new ZgPcm.Transcript().text());
    }
}

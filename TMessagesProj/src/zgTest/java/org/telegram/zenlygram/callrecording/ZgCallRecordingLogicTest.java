/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

/** The pure rules of call recording, the same cases as the iOS SGCallRecording tests where they apply. */
public class ZgCallRecordingLogicTest {

    // ---- consent flow

    @Test
    public void firstTapAsksOnceAndADoubleTapDoesNotStackPrompts() {
        final ZgCallRecordingConsentFlow flow = new ZgCallRecordingConsentFlow();
        assertEquals(ZgCallRecordingConsentFlow.Decision.ASK_CONSENT, flow.tap(false, true, false));
        final int ticket = flow.pendingTicket();
        assertEquals(ZgCallRecordingConsentFlow.Decision.IGNORE, flow.tap(false, true, false));
        assertTrue(flow.accept(ticket, false, true));
        assertFalse(flow.isPromptPending());
    }

    @Test
    public void staleOrLateAcceptanceStartsNothing() {
        final ZgCallRecordingConsentFlow flow = new ZgCallRecordingConsentFlow();
        flow.tap(false, true, false);
        final int ticket = flow.pendingTicket();
        assertFalse(flow.accept(ticket, false, false)); // the call dropped back to connecting
        flow.tap(false, true, false);
        final int second = flow.pendingTicket();
        assertFalse(flow.accept(ticket, false, true)); // an old prompt
        flow.callEnded();
        assertFalse(flow.accept(second, false, true)); // answered after the call ended
        assertEquals(ZgCallRecordingConsentFlow.Decision.IGNORE, flow.tap(false, true, true));
    }

    @Test
    public void consentGivenBeforeStartsAndStopIsAlwaysHonoured() {
        final ZgCallRecordingConsentFlow flow = new ZgCallRecordingConsentFlow();
        assertEquals(ZgCallRecordingConsentFlow.Decision.START, flow.tap(false, true, true));
        assertEquals(ZgCallRecordingConsentFlow.Decision.IGNORE, flow.tap(false, false, true));
        flow.callEnded();
        assertEquals(ZgCallRecordingConsentFlow.Decision.STOP, flow.tap(true, false, true));
    }

    @Test
    public void cancelledPromptUnblocksTheButton() {
        final ZgCallRecordingConsentFlow flow = new ZgCallRecordingConsentFlow();
        flow.tap(false, true, false);
        flow.promptClosed(flow.pendingTicket());
        assertEquals(ZgCallRecordingConsentFlow.Decision.ASK_CONSENT, flow.tap(false, true, false));
    }

    // ---- mixer

    private static final class Collect implements ZgCallRecordingMixer.Sink {
        final ArrayList<Short> out = new ArrayList<>();

        @Override
        public void write(short[] samples, int count) {
            for (int i = 0; i < count; i++) {
                out.add(samples[i]);
            }
        }
    }

    private static short[] filled(int n, int value) {
        final short[] s = new short[n];
        java.util.Arrays.fill(s, (short) value);
        return s;
    }

    @Test
    public void bothDirectionsAreSummedByTime() {
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        mixer.addLocal(filled(480, 100), 480, 48000, 1, false);
        mixer.addRemote(filled(960, 50), 960, 48000, 2); // stereo 48 kHz: 480 frames
        final Collect c = new Collect();
        assertEquals(480, mixer.drain(c));
        assertEquals(150, (int) c.out.get(0));
        assertEquals(150, (int) c.out.get(479));
    }

    @Test
    public void muteGateWritesSilenceOfTheSameLength() {
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        mixer.addLocal(filled(480, 1000), 480, 48000, 1, true);
        mixer.addRemote(filled(480, 7), 480, 48000, 1);
        final Collect c = new Collect();
        assertEquals(480, mixer.drain(c));
        for (short s : toArray(c)) {
            assertEquals(7, s);
        }
    }

    @Test
    public void lowerRatesAreResampledTo48k() {
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        mixer.addLocal(filled(160, 10), 160, 16000, 1, false); // 10 ms
        assertEquals(480, mixer.pendingLocal());
    }

    @Test
    public void aStalledSideIsPaddedAfterOneSecondNotBefore() {
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        mixer.addLocal(filled(48000, 1), 48000, 48000, 1, false);
        final Collect c = new Collect();
        assertEquals(0, mixer.drain(c)); // exactly one second ahead: still waiting
        mixer.addLocal(filled(480, 1), 480, 48000, 1, false);
        assertEquals(48480, mixer.drain(c)); // more than a second: the remote side gets silence
        assertEquals(0, mixer.pendingLocal());
    }

    @Test
    public void finishFlushesEverything() {
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        mixer.addLocal(filled(100, 3), 100, 48000, 1, false);
        mixer.addRemote(filled(40, 4), 40, 48000, 1);
        final Collect c = new Collect();
        assertEquals(100, mixer.finish(c));
        assertEquals(7, (int) c.out.get(0));
        assertEquals(3, (int) c.out.get(99));
    }

    @Test
    public void mixClampsInsteadOfWrapping() {
        final ZgCallRecordingMixer mixer = new ZgCallRecordingMixer();
        mixer.addLocal(filled(10, 30000), 10, 48000, 1, false);
        mixer.addRemote(filled(10, 30000), 10, 48000, 1);
        final Collect c = new Collect();
        mixer.drain(c);
        assertEquals(Short.MAX_VALUE, (short) c.out.get(0));
    }

    private static short[] toArray(Collect c) {
        final short[] a = new short[c.out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = c.out.get(i);
        }
        return a;
    }

    // ---- format

    @Test
    public void adtsHeaderOfA48kMonoFrame() {
        final byte[] h = ZgCallRecordingFormat.adtsHeader(200, 48000, 1);
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xF1, (byte) 0x4C, (byte) 0x40, (byte) 0x19, (byte) 0xFF, (byte) 0xFC}, h);
        // frame length (13 bits) = 207
        final int len = ((h[3] & 0x03) << 11) | ((h[4] & 0xFF) << 3) | ((h[5] & 0xE0) >> 5);
        assertEquals(207, len);
        assertEquals(3, ZgCallRecordingFormat.frequencyIndex(48000));
        assertEquals(-1, ZgCallRecordingFormat.frequencyIndex(47000));
    }

    @Test
    public void toneIsTwoBeepsWithSilenceBetween() {
        final short[] tone = ZgCallRecordingFormat.noticeTone();
        assertEquals(5760 * 2 + 4320, tone.length);
        assertEquals(0, tone[0]);
        assertEquals(0, tone[5760 + 100]);
        int peak = 0;
        for (short s : tone) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertTrue(peak > 7000 && peak <= (int) (0.22 * 32767) + 1);
    }

    @Test
    public void fileNamesAndDurations() {
        assertEquals("A-B- c.aac", ZgCallRecordingFormat.sanitizedFileName("A/B: c.aac"));
        assertEquals("call.aac", ZgCallRecordingFormat.sanitizedFileName("   "));
        assertEquals(120, ZgCallRecordingFormat.sanitizedFileName(new String(new char[300]).replace('\0', 'x') + ".aac").length());
        assertEquals("0:00", ZgCallRecordingFormat.formatDuration(0));
        assertEquals("1:05", ZgCallRecordingFormat.formatDuration(65.2));
        assertEquals("1:00:01", ZgCallRecordingFormat.formatDuration(3601));
        assertEquals(1.0, ZgCallRecordingFormat.durationOfFrames(1000) * 48000 / 1024 / 1000, 1e-9);
    }

    // ---- privacy and list policy

    @Test
    public void privacyFailsClosed() {
        final ZgCallRecordingPolicy.ProtectionLookup none = (p, a) -> ZgCallRecordingPolicy.PeerProtection.NONE;
        final ZgCallRecordingPolicy.ProtectionLookup locked = (p, a) -> ZgCallRecordingPolicy.PeerProtection.PROTECTED;
        final ZgCallRecordingPolicy.ProtectionLookup unknown = (p, a) -> ZgCallRecordingPolicy.PeerProtection.UNKNOWN;
        assertEquals(ZgCallRecordingPolicy.Privacy.OPEN, ZgCallRecordingPolicy.privacy(1L, 2L, true, none));
        assertEquals(ZgCallRecordingPolicy.Privacy.PROTECTED, ZgCallRecordingPolicy.privacy(1L, 2L, true, locked));
        assertEquals(ZgCallRecordingPolicy.Privacy.UNVERIFIABLE, ZgCallRecordingPolicy.privacy(1L, 2L, true, unknown));
        assertEquals(ZgCallRecordingPolicy.Privacy.OPEN, ZgCallRecordingPolicy.privacy(null, 2L, false, locked));
        assertEquals(ZgCallRecordingPolicy.Privacy.UNVERIFIABLE, ZgCallRecordingPolicy.privacy(null, 2L, true, none));
    }

    @Test
    public void rowsOfferOnlyWhatThePolicyAllows() {
        final ZgCallRecordingPolicy.Row live = new ZgCallRecordingPolicy.Row(ZgCallRecordingPolicy.Privacy.OPEN, true, false, false, false, false, true);
        assertEquals(ZgCallRecordingPolicy.Badge.LIVE, live.primaryBadge());
        assertFalse(live.canPlay || live.canShare || live.canSend || live.showsDuration);

        final ZgCallRecordingPolicy.Row open = new ZgCallRecordingPolicy.Row(ZgCallRecordingPolicy.Privacy.OPEN, false, false, false, true, false, true);
        assertEquals(ZgCallRecordingPolicy.Badge.SEND_FAILED, open.primaryBadge());
        assertTrue(open.badges.contains(ZgCallRecordingPolicy.Badge.INCOMPLETE));
        assertTrue(open.canPlay && open.canShare && open.canSend && !open.isRedacted);

        final ZgCallRecordingPolicy.Row noExport = new ZgCallRecordingPolicy.Row(ZgCallRecordingPolicy.Privacy.OPEN, false, false, false, false, true, false);
        assertTrue(noExport.canPlay);
        assertFalse(noExport.canShare || noExport.canSend);
        assertNull(noExport.primaryBadge());

        final ZgCallRecordingPolicy.Row prot = new ZgCallRecordingPolicy.Row(ZgCallRecordingPolicy.Privacy.PROTECTED, false, false, false, false, true, true);
        assertTrue(prot.isRedacted);
        assertFalse(prot.canPlay || prot.canShare || prot.canSend || prot.showsDate || prot.showsDuration);

        assertTrue(ZgCallRecordingPolicy.shouldAutoSend(true, ZgCallRecordingPolicy.Privacy.OPEN, true));
        assertFalse(ZgCallRecordingPolicy.shouldAutoSend(false, ZgCallRecordingPolicy.Privacy.OPEN, true));
        assertFalse(ZgCallRecordingPolicy.shouldAutoSend(true, ZgCallRecordingPolicy.Privacy.PROTECTED, true));
        assertFalse(ZgCallRecordingPolicy.shouldAutoSend(true, ZgCallRecordingPolicy.Privacy.OPEN, false));
    }

    // ---- sidecar

    @Test
    public void sidecarRoundTripsAndRejectsForeignIds() {
        final ZgCallRecordingMetadata m = new ZgCallRecordingMetadata("1763000000-0a1b2c3d", 1763000000.5, 12.5, "Aziza", 42L, 7L, true, false, ZgCallRecordingMetadata.DELIVERY_SENDING);
        final ZgCallRecordingMetadata back = ZgCallRecordingMetadata.fromJson(m.toJson());
        assertNotNull(back);
        assertEquals(m.id, back.id);
        assertEquals(12.5, back.duration, 0);
        assertEquals("Aziza", back.peerTitle);
        assertEquals(Long.valueOf(42), back.accountUserId);
        assertEquals(Long.valueOf(7), back.peerId);
        assertTrue(back.isComplete);
        assertEquals(ZgCallRecordingMetadata.DELIVERY_SENDING, back.delivery);

        final ZgCallRecordingMetadata unknown = ZgCallRecordingMetadata.fromJson(new ZgCallRecordingMetadata("1-00000000", 0, 0, null, null, null, false, false, null).toJson());
        assertNotNull(unknown);
        assertNull(unknown.accountUserId);
        assertEquals(ZgCallRecordingMetadata.DELIVERY_NONE, unknown.delivery);

        assertNull(ZgCallRecordingMetadata.fromJson("{\"id\":\"../../etc\"}"));
        assertNull(ZgCallRecordingMetadata.fromJson("not json"));
        assertFalse(ZgCallRecordingMetadata.isValidId("1763000000-0A1B2C3D"));
    }
}

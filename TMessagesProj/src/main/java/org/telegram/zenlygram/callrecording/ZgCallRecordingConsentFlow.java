/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

/**
 * What an explicit Record tap on the call screen may lead to (port of iOS SGCallRecordingConsentFlow). Free of UI so
 * the rules are testable:
 * <ul>
 *   <li>One tap, at most one prompt: while the consent prompt is up, further taps do nothing.</li>
 *   <li>Accepting counts only for the prompt that is still current, and only if the call can STILL start a recording
 *   then. A prompt answered after the call ended records nothing, not even the acceptance.</li>
 *   <li>Cancel / tap outside / the prompt going away records nothing and starts nothing.</li>
 *   <li>Once the call ended nothing can be started from its screen. Stop is always honoured.</li>
 * </ul>
 * iOS also has a "CallKit required" branch; Android has no such prerequisite (the ongoing-call notification is the
 * indicator outside the app), so it is not ported.
 *
 * <p>One instance per call screen, main thread only.
 */
public final class ZgCallRecordingConsentFlow {

    public enum Decision { STOP, START, ASK_CONSENT, IGNORE }

    private int pendingTicket;
    private int nextTicket;
    private boolean callEnded;

    /** The ticket of the prompt that {@link Decision#ASK_CONSENT} asked to present. */
    public int pendingTicket() {
        return pendingTicket;
    }

    public boolean isPromptPending() {
        return pendingTicket != 0;
    }

    /**
     * @param isRecording     a recording of this call is running
     * @param canStart        the recorder would accept a start now (connected 1:1 call, feature available)
     */
    public Decision tap(boolean isRecording, boolean canStart, boolean consentAccepted) {
        if (isRecording) {
            return Decision.STOP;
        }
        if (callEnded || pendingTicket != 0) {
            return Decision.IGNORE;
        }
        if (!canStart) {
            return Decision.IGNORE;
        }
        if (consentAccepted) {
            return Decision.START;
        }
        pendingTicket = ++nextTicket;
        return Decision.ASK_CONSENT;
    }

    /** The consent prompt's accept action; the call state is read at the moment of the answer. True = store and start. */
    public boolean accept(int ticket, boolean isRecording, boolean canStart) {
        if (ticket == 0 || ticket != pendingTicket) {
            return false;
        }
        pendingTicket = 0;
        return !callEnded && !isRecording && canStart;
    }

    /** The prompt was cancelled, tapped away or dismissed by anything else. */
    public void promptClosed(int ticket) {
        if (ticket == pendingTicket) {
            pendingTicket = 0;
        }
    }

    /** The call is terminating: an open prompt is void and nothing can be started any more. */
    public void callEnded() {
        callEnded = true;
        pendingTicket = 0;
    }

    public boolean isCallEnded() {
        return callEnded;
    }
}

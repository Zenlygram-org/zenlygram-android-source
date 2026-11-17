/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;

/**
 * "Reveal with a secret code": the one place where text typed into chat-list search may be checked against the vault
 * PIN (iOS SecretVaultCodeGate, audits #27 / #73 / #89). Plain Java (JVM-tested); one instance per search session;
 * calls are serialised by the caller ({@link ZgVaultSecretCode}).
 *
 * <ul>
 *   <li>Every check is counted against the search path's own budget (VAULT_SEARCH), never the PIN screens' one. A
 *       4-digit PIN is checked only after it settled for 0.8 s; a text password only when the user submits it; the
 *       same wrong text costs one attempt per session (memo of salted hashes, never the text).</li>
 *   <li>While the mode is on, every non-empty text is held (local results only), whatever it looks like, so the
 *       search cannot be read as an oracle; only text proven not to be the PIN, or that cannot be it, is released to
 *       the server on submit.</li>
 *   <li>A PIN that cannot be checked (HOLD_ONLY: recovery pending, unreadable) is never released.</li>
 * </ul>
 */
public final class ZgVaultCodeGate {

    public enum Mode {
        OFF,
        ARMED,
        HOLD_ONLY
    }

    public enum Outcome {
        /** Ordinary search: local and remote. */
        SEARCH,
        /** Local results only. */
        HOLD,
        /** The text was the PIN and the vault opened; the query is dropped. */
        REVEALED,
        /** Like HOLD, but the user asked for a server search: show a neutral note instead of silence. */
        HOLD_EXPLAINED
    }

    public enum Verdict {
        MATCH,
        MISMATCH,
        /** Locked out, or the credential can't be read right now. */
        UNAVAILABLE
    }

    /** What the gate needs from the app. */
    public interface Env {
        Mode mode();

        /** The vault PIN is 4 digits (else a text password). */
        boolean isPin4();

        /** VAULT_SEARCH (or the vault PIN screens) is locked out right now. */
        boolean isThrottled();

        /** Checks {@code text} against the vault credential, without counting it. */
        Verdict check(String text);

        void registerFailure();

        void schedule(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);

        void onReveal();
    }

    static final long SETTLE_DELAY_MS = 800;
    private static final int MEMO_CAPACITY = 64;

    private final Env env;
    private final byte[] nonce = new byte[16];
    private final HashSet<String> memo = new HashSet<>();
    /** Texts the user submitted that were released to the server (fingerprints). */
    private final HashSet<String> released = new HashSet<>();
    private String lastText;
    private Runnable settle;

    public ZgVaultCodeGate(Env env) {
        this.env = env;
        new SecureRandom().nextBytes(nonce);
    }

    /** A keystroke. Never releases anything to the server by itself. */
    public synchronized Outcome textChanged(String text) {
        cancelSettle();
        lastText = text;
        final Mode mode = env.mode();
        if (mode == Mode.OFF || text == null || text.isEmpty()) {
            return Outcome.SEARCH;
        }
        if (mode == Mode.ARMED && isFourDigits(text) && env.isPin4() && !memo.contains(fingerprint(text))) {
            final String candidate = text;
            settle = () -> settleFired(candidate);
            env.schedule(settle, SETTLE_DELAY_MS);
        }
        return Outcome.HOLD;
    }

    /** The user asked to search Telegram (keyboard Search): the only path that may release held text. */
    public synchronized Outcome textSubmitted(String text) {
        cancelSettle();
        lastText = text;
        final Mode mode = env.mode();
        if (text == null || text.isEmpty() || mode == Mode.OFF) {
            return Outcome.SEARCH;
        }
        if (!isCheckable(text, env.isPin4())) {
            // It cannot be the credential: released without a check (keeps global search alive in HOLD_ONLY).
            release(text);
            return Outcome.SEARCH;
        }
        if (mode != Mode.ARMED || env.isThrottled()) {
            return Outcome.HOLD_EXPLAINED;
        }
        final String fp = fingerprint(text);
        final boolean counted = memo.contains(fp);
        switch (env.check(text)) {
            case MATCH:
                env.onReveal();
                return Outcome.REVEALED;
            case MISMATCH:
                if (!counted) {
                    memoize(fp);
                    env.registerFailure();
                }
                release(text);
                return Outcome.SEARCH;
            case UNAVAILABLE:
            default:
                return Outcome.HOLD_EXPLAINED;
        }
    }

    /**
     * Whether a search for {@code text} may go to the server: only text the user submitted and that was proven not
     * to be (or cannot be) the credential. Typing alone never releases anything (#89: nothing visible depends on it).
     */
    public synchronized boolean isReleased(String text) {
        return text != null && !text.isEmpty() && released.contains(fingerprint(text));
    }

    private void release(String text) {
        if (released.size() >= MEMO_CAPACITY) {
            released.clear();
        }
        released.add(fingerprint(text));
    }

    synchronized void settleFired(String text) {
        settle = null;
        if (text == null || !text.equals(lastText)) {
            return;
        }
        final String fp = fingerprint(text);
        if (memo.contains(fp) || env.mode() != Mode.ARMED || env.isThrottled()) {
            return;
        }
        switch (env.check(text)) {
            case MATCH:
                env.onReveal();
                break;
            case MISMATCH:
                memoize(fp);
                env.registerFailure();
                break;
            default:
                break;
        }
    }

    /** Text that could be the credential: no whitespace; exactly 4 digits for a PIN vault, 4..64 characters otherwise. */
    static boolean isCheckable(String text, boolean pin4) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        if (pin4) {
            return isFourDigits(text);
        }
        return text.length() >= 4 && text.length() <= 64;
    }

    static boolean isFourDigits(String text) {
        if (text.length() != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            final char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private void cancelSettle() {
        if (settle != null) {
            env.cancel(settle);
            settle = null;
        }
    }

    private void memoize(String fp) {
        if (memo.size() >= MEMO_CAPACITY) {
            memo.clear(); // past the capacity every candidate counts again (never a free-guess pool)
        }
        memo.add(fp);
    }

    private String fingerprint(String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(nonce);
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            final byte[] hash = digest.digest();
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}

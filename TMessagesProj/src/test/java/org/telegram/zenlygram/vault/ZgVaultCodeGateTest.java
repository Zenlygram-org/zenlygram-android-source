package org.telegram.zenlygram.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.zenlygram.vault.ZgVaultCodeGate.Mode;
import org.telegram.zenlygram.vault.ZgVaultCodeGate.Outcome;
import org.telegram.zenlygram.vault.ZgVaultCodeGate.Verdict;

import java.util.ArrayList;

/** JVM tests of the secret-code gate (iOS SecretVaultCodeGate rules #27 / #73 / #89). */
public class ZgVaultCodeGateTest {

    static final class FakeEnv implements ZgVaultCodeGate.Env {
        Mode mode = Mode.ARMED;
        boolean pin4 = true;
        boolean throttled;
        String secret = "1234";
        int checks;
        int failures;
        int reveals;
        final ArrayList<Runnable> pending = new ArrayList<>();

        @Override public Mode mode() { return mode; }
        @Override public boolean isPin4() { return pin4; }
        @Override public boolean isThrottled() { return throttled; }
        @Override public Verdict check(String text) { checks++; return secret.equals(text) ? Verdict.MATCH : Verdict.MISMATCH; }
        @Override public void registerFailure() { failures++; }
        @Override public void schedule(Runnable runnable, long delayMs) { pending.add(runnable); }
        @Override public void cancel(Runnable runnable) { pending.remove(runnable); }
        @Override public void onReveal() { reveals++; }

        void fire() {
            final ArrayList<Runnable> copy = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : copy) {
                r.run();
            }
        }
    }

    @Test
    public void offSearchesNormally() {
        final FakeEnv env = new FakeEnv();
        env.mode = Mode.OFF;
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        assertEquals(Outcome.SEARCH, gate.textChanged("1234"));
        assertEquals(Outcome.SEARCH, gate.textSubmitted("1234"));
        assertEquals(0, env.checks);
    }

    @Test
    public void everyTextIsHeldWhileTyping() {
        final FakeEnv env = new FakeEnv();
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        assertEquals(Outcome.HOLD, gate.textChanged("mom"));
        assertEquals(Outcome.HOLD, gate.textChanged("12"));
        assertEquals(0, env.checks);
        assertTrue(env.pending.isEmpty());
        assertFalse(gate.isReleased("mom"));
    }

    @Test
    public void settledPinRevealsOnlyAfterTheDelay() {
        final FakeEnv env = new FakeEnv();
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        gate.textChanged("123");
        gate.textChanged("1234");
        assertEquals(0, env.checks);
        env.fire();
        assertEquals(1, env.reveals);
        assertEquals(0, env.failures);
    }

    @Test
    public void prefixesOfLongNumbersCostNothing() {
        final FakeEnv env = new FakeEnv();
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        gate.textChanged("9989");
        gate.textChanged("99890"); // typed on before the settle: cancelled
        env.fire();
        assertEquals(0, env.checks);
    }

    @Test
    public void wrongSettledCodeIsCountedOncePerSession() {
        final FakeEnv env = new FakeEnv();
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        gate.textChanged("0000");
        env.fire();
        gate.textChanged("000");
        gate.textChanged("0000");
        env.fire();
        assertEquals(1, env.failures);
        assertEquals(1, env.checks);
    }

    @Test
    public void submitReleasesOnlyProvenText() {
        final FakeEnv env = new FakeEnv();
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        assertEquals(Outcome.SEARCH, gate.textSubmitted("hello world")); // whitespace: cannot be the PIN, no check
        assertEquals(0, env.checks);
        assertTrue(gate.isReleased("hello world"));
        assertEquals(Outcome.SEARCH, gate.textSubmitted("abcd")); // wrong shape for a 4-digit vault
        assertEquals(Outcome.SEARCH, gate.textSubmitted("5555"));
        assertEquals(1, env.failures);
        assertTrue(gate.isReleased("5555"));
        assertEquals(Outcome.REVEALED, gate.textSubmitted("1234"));
        assertFalse(gate.isReleased("1234"));
    }

    @Test
    public void textPasswordIsCheckedOnlyOnSubmit() {
        final FakeEnv env = new FakeEnv();
        env.pin4 = false;
        env.secret = "s3cret";
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        gate.textChanged("s3cret");
        assertTrue(env.pending.isEmpty());
        assertEquals(Outcome.REVEALED, gate.textSubmitted("s3cret"));
    }

    @Test
    public void holdOnlyNeverReleasesACandidate() {
        final FakeEnv env = new FakeEnv();
        env.mode = Mode.HOLD_ONLY;
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        assertEquals(Outcome.HOLD_EXPLAINED, gate.textSubmitted("1234"));
        assertEquals(Outcome.SEARCH, gate.textSubmitted("two words"));
        assertEquals(0, env.checks);
    }

    @Test
    public void throttledHoldsAndChecksNothing() {
        final FakeEnv env = new FakeEnv();
        env.throttled = true;
        final ZgVaultCodeGate gate = new ZgVaultCodeGate(env);
        assertEquals(Outcome.HOLD_EXPLAINED, gate.textSubmitted("1234"));
        gate.textChanged("1234");
        env.fire();
        assertEquals(0, env.checks);
        assertEquals(0, env.reveals);
    }
}

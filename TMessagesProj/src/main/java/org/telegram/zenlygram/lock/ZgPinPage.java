/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/**
 * What one page of a {@link ZgPinDialog} shows and does. Three kinds:
 *
 * <ul>
 *   <li><b>{@link #setup}</b>: choose a new code. A 4-digit PIN by default, with "Switch to password" / "Switch to PIN"
 *       at the top (Telegram's PasscodeActivity convention); entered twice; a mismatch shakes and starts again. Never
 *       throttled. The listener gets the normalized code and its type, and decides when to close the dialog.</li>
 *   <li><b>{@link #verify}</b>: prove an existing code. The {@link Verifier} runs on a worker thread. The page applies
 *       the {@link ZgLockThrottle} of its scope (a running lockout refuses entries without checking them), counts
 *       failures, shakes, offers the optional biometric slot (button, optionally started once automatically) and the
 *       optional recovery link (always, or after {@value #STRUGGLE_FAILURES} wrong entries or during a lockout).</li>
 *   <li><b>{@link #info}</b>: a message with up to two buttons (recovery states: "the code can't be checked").</li>
 * </ul>
 *
 * <p>Every listener runs on the main thread with the dialog, so it can {@link ZgPinDialog#push} another page,
 * {@link ZgPinDialog#finish()} or keep the page open.
 */
public final class ZgPinPage {

    public static final int KIND_SETUP = 0;
    public static final int KIND_VERIFY = 1;
    public static final int KIND_INFO = 2;

    /** The recovery link is always shown. */
    public static final int RECOVERY_ALWAYS = 0;
    /** The recovery link appears after {@link #STRUGGLE_FAILURES} wrong entries on this page, or during a lockout. */
    public static final int RECOVERY_AFTER_STRUGGLE = 1;
    public static final int STRUGGLE_FAILURES = 4;

    public interface SetupListener {
        /** The same valid code was entered twice. */
        void onCodeChosen(ZgPinDialog dialog, String code, int type);
    }

    public interface Verifier {
        /** Called on a worker thread with the normalized code. Never log it. */
        @WorkerThread
        ZgLockCrypto.Verdict verify(String code);
    }

    public interface VerifiedListener {
        /**
         * The code matched ({@code code} is the proven code) or the biometric slot proved the owner ({@code code} is
         * null). {@code enrollmentChanged}: this page was told earlier that the slot's enrollment changed; the caller
         * should now offer "Keep using biometrics" / "Use pincode only". The throttle scope is already reset.
         */
        void onVerified(ZgPinDialog dialog, @Nullable String code, boolean viaBiometric, boolean enrollmentChanged);
    }

    public interface Action {
        void run(ZgPinDialog dialog);
    }

    final int kind;
    CharSequence title;
    CharSequence subtitle;
    /** Setup: the type shown first; verify: the stored type. */
    int type = ZgLockCode.TYPE_PIN;
    int textPolicy = ZgLockCode.TEXT_POLICY_TRIM;
    boolean allowTypeSwitch = true;
    SetupListener setupListener;
    CharSequence setupPinTitle;
    CharSequence setupPasswordTitle;
    CharSequence setupSubtitle;

    ZgLockThrottle.Scope scope;
    Verifier verifier;
    VerifiedListener verifiedListener;
    String biometricSlot;
    boolean biometricAuto;
    CharSequence biometricTitle;
    CharSequence biometricSubtitle;
    CharSequence recoveryLabel;
    int recoveryWhen = RECOVERY_ALWAYS;
    Action recoveryAction;
    Action unverifiableAction;

    CharSequence text;
    CharSequence primaryLabel;
    Action primaryAction;
    CharSequence secondaryLabel;
    Action secondaryAction;
    boolean secondaryDestructive;

    Action cancelAction;

    private ZgPinPage(int kind) {
        this.kind = kind;
    }

    /** A "choose a new code" page; {@code initialType} is shown first (PIN unless the caller knows better). */
    public static ZgPinPage setup(int initialType, SetupListener listener) {
        final ZgPinPage page = new ZgPinPage(KIND_SETUP);
        page.type = initialType;
        page.setupListener = listener;
        return page;
    }

    /** A "prove the code" page for a credential of {@code type}, throttled in {@code scope}. */
    public static ZgPinPage verify(int type, ZgLockThrottle.Scope scope, Verifier verifier, VerifiedListener listener) {
        final ZgPinPage page = new ZgPinPage(KIND_VERIFY);
        page.type = type;
        page.scope = scope;
        page.verifier = verifier;
        page.verifiedListener = listener;
        return page;
    }

    /** A message page (no entry). */
    public static ZgPinPage info(CharSequence title, CharSequence text) {
        final ZgPinPage page = new ZgPinPage(KIND_INFO);
        page.title = title;
        page.text = text;
        return page;
    }

    /** Verify: the bold title (default "Enter PIN" / "Enter Password"). Info: the title. */
    public ZgPinPage title(CharSequence title) {
        this.title = title;
        return this;
    }

    /** The grey line under the title (verify: e.g. the chat name; setup: replaces "4-digit code"). */
    public ZgPinPage subtitle(CharSequence subtitle) {
        this.subtitle = subtitle;
        this.setupSubtitle = subtitle;
        return this;
    }

    /** Setup: first-step titles instead of "Set PIN" / "Set Password". */
    public ZgPinPage setupTitles(CharSequence pinTitle, CharSequence passwordTitle) {
        this.setupPinTitle = pinTitle;
        this.setupPasswordTitle = passwordTitle;
        return this;
    }

    /** Text passwords: {@link ZgLockCode#TEXT_POLICY_TRIM} (default) or {@link ZgLockCode#TEXT_POLICY_NO_WHITESPACE}. */
    public ZgPinPage textPolicy(int textPolicy) {
        this.textPolicy = textPolicy;
        return this;
    }

    /** Setup: whether "Switch to password / PIN" is offered (default true). */
    public ZgPinPage allowTypeSwitch(boolean allow) {
        this.allowTypeSwitch = allow;
        return this;
    }

    /**
     * Verify: offer the biometric slot (only pass a slot whose credential has biometrics on). {@code auto} starts the
     * prompt once when the page appears. The prompt shows {@code promptTitle} / {@code promptSubtitle}.
     */
    public ZgPinPage biometric(@Nullable String slot, boolean auto, CharSequence promptTitle, @Nullable CharSequence promptSubtitle) {
        this.biometricSlot = slot;
        this.biometricAuto = auto;
        this.biometricTitle = promptTitle;
        this.biometricSubtitle = promptSubtitle;
        return this;
    }

    /** Verify: a text link under the entry ("Forgot pincode?", "Reset Chat Lock"). */
    public ZgPinPage recovery(CharSequence label, int when, Action action) {
        this.recoveryLabel = label;
        this.recoveryWhen = when;
        this.recoveryAction = action;
        return this;
    }

    /** Verify: what to do when the verifier answers UNVERIFIABLE (default: an error line). */
    public ZgPinPage onUnverifiable(Action action) {
        this.unverifiableAction = action;
        return this;
    }

    /** Info: the filled button. */
    public ZgPinPage primary(CharSequence label, Action action) {
        this.primaryLabel = label;
        this.primaryAction = action;
        return this;
    }

    /** Info: the text button under it ({@code destructive} = red). */
    public ZgPinPage secondary(CharSequence label, boolean destructive, Action action) {
        this.secondaryLabel = label;
        this.secondaryDestructive = destructive;
        this.secondaryAction = action;
        return this;
    }

    /**
     * Back / close on this page when it is the first page: runs {@code action} (the dialog is dismissed right after).
     * On a pushed page, back returns to the page below instead.
     */
    public ZgPinPage onCancel(Action action) {
        this.cancelAction = action;
        return this;
    }

    public int getKind() {
        return kind;
    }

    public int getType() {
        return type;
    }
}

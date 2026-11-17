/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CustomPhoneKeyboardView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * The full-screen PIN / password screen of every Zenlygram lock, built from Telegram's passcode parts
 * (CustomPhoneKeyboardView, OutlineTextContainerView + EditTextBoldCursor, the passcode lottie). One dialog holds a
 * stack of {@link ZgPinPage}s: "Forgot pincode?" pushes the master page into the same window, back returns.
 *
 * <pre>
 * ZgPinDialog.show(activity, ZgPinPage.verify(type, ZgLockThrottle.Scope.VAULT, code -> verifyOnWorker(code),
 *         (dialog, code, viaBiometric, enrollmentChanged) -> { dialog.finish(); openVault(); })
 *     .title(...).biometric("vault", true, reason, null)
 *     .recovery(LocaleController.getString(R.string.ZG_ChatLock_ForgotPincode), ZgPinPage.RECOVERY_AFTER_STRUGGLE, d -> d.push(...))
 *     .onCancel(d -> {}));
 * </pre>
 *
 * <p>Lifecycle: {@link #finish()} closes after a result the caller decided; back / close on the first page runs its
 * cancel action and closes; anything else that closes the window (the activity going away) runs
 * {@link #setOnInterruptedListener}. The window is FLAG_SECURE (no screenshots or screen recording of a PIN screen),
 * follows the app theme and is edge-to-edge with its own insets. Codes are never logged or kept after the page.
 */
public class ZgPinDialog extends Dialog {

    private static DispatchQueue cryptoQueue;

    private final Activity activity;
    private final FrameLayout windowView;
    private final FrameLayout container;
    private final ArrayList<PageView> pages = new ArrayList<>();
    private boolean resolved;
    private Runnable onInterrupted;

    /** Shows {@code firstPage}; null when the activity cannot show a window now. */
    @Nullable
    public static ZgPinDialog show(@Nullable Activity activity, ZgPinPage firstPage) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        final ZgPinDialog dialog = new ZgPinDialog(activity);
        dialog.push(firstPage);
        try {
            dialog.show();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        return dialog;
    }

    static synchronized DispatchQueue queue() {
        if (cryptoQueue == null) {
            cryptoQueue = new DispatchQueue("zgLockCrypto");
        }
        return cryptoQueue;
    }

    /** Runs {@code work} on the lock worker thread (hashing must never run on the main thread). */
    public static void runOnWorker(Runnable work) {
        queue().postRunnable(work);
    }

    private ZgPinDialog(@NonNull Activity activity) {
        super(activity, R.style.TransparentDialog);
        this.activity = activity;
        AndroidUtilities.enableEdgeToEdge(getWindow());
        windowView = new FrameLayout(activity);
        windowView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container = new FrameLayout(activity);
        windowView.addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(windowView, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            container.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        setCanceledOnTouchOutside(false);
        setOnDismissListener(d -> {
            if (!resolved) {
                resolved = true;
                if (onInterrupted != null) {
                    onInterrupted.run();
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setWindowAnimations(R.style.DialogNoAnimation);
        setContentView(windowView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final WindowManager.LayoutParams params = window.getAttributes();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.FILL;
        params.dimAmount = 0;
        params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
        window.setAttributes(params);
        ZgPrivacyShield.secureWindow(window);
        final boolean lightBackground = AndroidUtilities.computePerceivedBrightness(Theme.getColor(Theme.key_windowBackgroundWhite)) > 0.721f;
        AndroidUtilities.setLightStatusBar(window, lightBackground);
        AndroidUtilities.setLightNavigationBar(this, lightBackground);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        final PageView page = top();
        if (hasFocus && page != null) {
            page.onShownWithFocus();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------------------------------------------

    public Activity getActivity() {
        return activity;
    }

    /** Shows {@code page} above the current one (back returns). */
    public void push(ZgPinPage page) {
        final PageView previous = top();
        final PageView view = new PageView(activity, page, pages.isEmpty());
        pages.add(view);
        container.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (previous != null) {
            previous.onHidden();
            view.setAlpha(0f);
            view.setTranslationX(AndroidUtilities.dp(LocaleController.isRTL ? -48 : 48));
            view.animate().alpha(1f).translationX(0).setDuration(200).withEndAction(() -> previous.setVisibility(View.GONE)).start();
            view.post(view::onShownWithFocus);
        }
    }

    /** Returns to the page below; on the first page this is the same as the user pressing back. */
    public void pop() {
        if (pages.size() <= 1) {
            cancelByUser();
            return;
        }
        final PageView removed = pages.remove(pages.size() - 1);
        removed.onHidden();
        final PageView previous = top();
        if (previous != null) {
            previous.setVisibility(View.VISIBLE);
            previous.setAlpha(1f);
            previous.post(previous::onShownWithFocus);
        }
        removed.animate().alpha(0f).translationX(AndroidUtilities.dp(LocaleController.isRTL ? -48 : 48)).setDuration(180)
            .withEndAction(() -> container.removeView(removed)).start();
    }

    /** Closes with a result the caller has already handled; no cancel or interrupt callback runs. */
    public void finish() {
        resolved = true;
        dismissSafely();
    }

    /** Closes as if the window was taken away: runs the interrupted listener. */
    public void interrupt() {
        dismissSafely();
    }

    public boolean isResolved() {
        return resolved;
    }

    /** Runs when the dialog closes without {@link #finish()} and without the user cancelling it. */
    public void setOnInterruptedListener(@Nullable Runnable listener) {
        this.onInterrupted = listener;
    }

    /** Blocks input on the current page (e.g. while the caller saves a record on the worker thread). */
    public void setBusy(boolean busy) {
        final PageView page = top();
        if (page != null) {
            page.setBusy(busy);
        }
    }

    /** Shows {@code text} as an error on the current page (shake + red line, announced to TalkBack). */
    public void showError(CharSequence text) {
        final PageView page = top();
        if (page != null) {
            page.showError(text, true);
        }
    }

    /** Replaces the subtitle of the current page (e.g. the chat title resolved later). */
    public void setSubtitle(CharSequence subtitle) {
        final PageView page = top();
        if (page != null) {
            page.page.subtitle = subtitle;
            page.updateLabels();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nullable
    private PageView top() {
        return pages.isEmpty() ? null : pages.get(pages.size() - 1);
    }

    private void handleBack() {
        final PageView page = top();
        if (page == null || page.busy) {
            return;
        }
        if (pages.size() > 1) {
            pop();
        } else {
            cancelByUser();
        }
    }

    private void cancelByUser() {
        if (resolved) {
            return;
        }
        resolved = true;
        final PageView root = pages.isEmpty() ? null : pages.get(0);
        if (root != null && root.page.cancelAction != null) {
            try {
                root.page.cancelAction.run(this);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        dismissSafely();
    }

    private void dismissSafely() {
        for (PageView page : pages) {
            page.onHidden();
        }
        try {
            if (isShowing()) {
                dismiss();
            } else if (!resolved) {
                resolved = true;
                if (onInterrupted != null) {
                    onInterrupted.run();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0 && !event.isCanceled()) {
                handleBack();
            }
            return true;
        }
        final PageView page = top();
        if (page != null && page.handleHardwareKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------------------------------------------------
    // One page
    // ---------------------------------------------------------------------------------------------------------

    /** Four dots for the PIN; filled as digits are entered. */
    private static final class DotsView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int count = -1;

        DotsView(Context context) {
            super(context);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(AndroidUtilities.dp(1.5f));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
        }

        void setCount(int count) {
            if (this.count != count) {
                this.count = count;
                setContentDescription(LocaleController.formatString(R.string.ZG_Lock_A11y_PinDots, count, ZgLockCode.PIN_LENGTH));
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(AndroidUtilities.dp(14 * ZgLockCode.PIN_LENGTH + 22 * (ZgLockCode.PIN_LENGTH - 1)), AndroidUtilities.dp(40));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            fill.setColor(color);
            stroke.setColor(ColorUtils.setAlphaComponent(color, 110));
            final float radius = AndroidUtilities.dp(7);
            final float cy = getHeight() / 2f;
            for (int i = 0; i < ZgLockCode.PIN_LENGTH; i++) {
                final float cx = AndroidUtilities.dp(7 + 36 * i);
                if (i < count) {
                    canvas.drawCircle(cx, cy, radius, fill);
                } else {
                    canvas.drawCircle(cx, cy, radius - AndroidUtilities.dp(0.75f), stroke);
                }
            }
        }
    }

    private final class PageView extends FrameLayout {
        final ZgPinPage page;
        final boolean root;
        int typeNow;
        int step;
        String firstCode;
        int failures;
        boolean busy;
        boolean enrollmentChanged;
        boolean biometricAutoDone;
        boolean recoveryRevealed;
        boolean hidden;
        CharSequence errorText;

        final TextView titleView;
        final TextView subtitleView;
        final RLottieImageView lockView;
        TextView switchButton;
        DotsView dotsView;
        EditText pinField;
        OutlineTextContainerView passwordOutline;
        EditTextBoldCursor passwordField;
        ImageView biometricButton;
        TextView recoveryButton;
        CustomPhoneKeyboardView keyboard;
        ButtonWithCounterView nextButton;
        ButtonWithCounterView primaryButton;
        TextView secondaryButton;

        PageView(Context context, ZgPinPage page, boolean root) {
            super(context);
            this.page = page;
            this.root = root;
            this.typeNow = page.type;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setClickable(true);

            final LinearLayout vertical = new LinearLayout(context);
            vertical.setOrientation(LinearLayout.VERTICAL);
            addView(vertical, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            final FrameLayout topBar = new FrameLayout(context);
            vertical.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
            final ImageView closeButton = new ImageView(context);
            closeButton.setScaleType(ImageView.ScaleType.CENTER);
            closeButton.setImageResource(root ? R.drawable.ic_close_white : R.drawable.ic_ab_back);
            closeButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            closeButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            closeButton.setContentDescription(LocaleController.getString(root ? R.string.Close : R.string.Back));
            closeButton.setOnClickListener(v -> handleBack());
            topBar.addView(closeButton, LayoutHelper.createFrame(56, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
            if (page.kind == ZgPinPage.KIND_SETUP && page.allowTypeSwitch) {
                switchButton = new TextView(context);
                switchButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                switchButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                switchButton.setGravity(Gravity.CENTER);
                switchButton.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
                switchButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                switchButton.setOnClickListener(v -> switchType());
                topBar.addView(switchButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
            }

            final ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            vertical.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
            final FrameLayout scrollContent = new FrameLayout(context);
            scroll.addView(scrollContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            scrollContent.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 8, 0, 16));

            lockView = new RLottieImageView(context);
            lockView.setAnimation(R.raw.tsv_setup_intro, 100, 100);
            lockView.setAutoRepeat(false);
            lockView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            final boolean roomy = !AndroidUtilities.isSmallScreen() && AndroidUtilities.displaySize.x < AndroidUtilities.displaySize.y;
            lockView.setVisibility(roomy ? View.VISIBLE : View.GONE); // re-checked on every measure (rotation)
            column.addView(lockView, LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setGravity(Gravity.CENTER);
            column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 0, 24, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            subtitleView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            column.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 8, 24, 0));

            if (page.kind == ZgPinPage.KIND_INFO) {
                buildInfo(context, column);
            } else {
                buildEntry(context, column, vertical);
            }
            updateLabels();
            updateEntryMode(false);
        }

        private void buildInfo(Context context, LinearLayout column) {
            final TextView textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
            textView.setGravity(Gravity.CENTER);
            textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            textView.setText(page.text);
            column.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 28, 4, 28, 0));
            if (page.primaryLabel != null) {
                primaryButton = new ButtonWithCounterView(context, null);
                primaryButton.setText(page.primaryLabel, false);
                primaryButton.setOnClickListener(v -> {
                    if (!busy && page.primaryAction != null) {
                        page.primaryAction.run(ZgPinDialog.this);
                    }
                });
                column.addView(primaryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 24, 28, 24, 0));
            }
            if (page.secondaryLabel != null) {
                secondaryButton = new TextView(context);
                secondaryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                secondaryButton.setTextColor(Theme.getColor(page.secondaryDestructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText4));
                secondaryButton.setGravity(Gravity.CENTER);
                secondaryButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
                secondaryButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                secondaryButton.setText(page.secondaryLabel);
                secondaryButton.setOnClickListener(v -> {
                    if (!busy && page.secondaryAction != null) {
                        page.secondaryAction.run(ZgPinDialog.this);
                    }
                });
                column.addView(secondaryButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 12, 24, 0));
            }
        }

        private void buildEntry(Context context, LinearLayout column, LinearLayout vertical) {
            dotsView = new DotsView(context);
            dotsView.setCount(0);
            column.addView(dotsView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));

            pinField = new EditText(context);
            pinField.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ZgLockCode.PIN_LENGTH)});
            pinField.setShowSoftInputOnFocus(false);
            pinField.setFocusable(false);
            pinField.setLongClickable(false);
            pinField.setCursorVisible(false);
            pinField.setBackground(null);
            pinField.setAlpha(0f);
            pinField.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            pinField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    final int length = s.length();
                    dotsView.setCount(length);
                    if (length > 0) {
                        clearError();
                    }
                    if (length >= ZgLockCode.PIN_LENGTH && !busy) {
                        final String raw = s.toString();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!hidden && pinField.length() >= ZgLockCode.PIN_LENGTH && !busy) {
                                submit(raw);
                            }
                        }, 120);
                    }
                }
            });
            addView(pinField, new FrameLayout.LayoutParams(1, 1));

            passwordOutline = new OutlineTextContainerView(context);
            passwordOutline.setText(LocaleController.getString(R.string.ZG_Lock_PasswordField));
            passwordField = new EditTextBoldCursor(context);
            passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            passwordField.setTransformationMethod(PasswordTransformationMethod.getInstance());
            passwordField.setTypeface(android.graphics.Typeface.DEFAULT);
            passwordField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            passwordField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            passwordField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
            passwordField.setCursorSize(AndroidUtilities.dp(20));
            passwordField.setCursorWidth(1.5f);
            passwordField.setBackground(null);
            passwordField.setSingleLine(true);
            passwordField.setMaxLines(1);
            passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            passwordField.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            final int padding = AndroidUtilities.dp(16);
            passwordField.setPadding(padding, padding, padding, padding);
            passwordField.setOnFocusChangeListener((v, hasFocus) -> passwordOutline.animateSelection(hasFocus ? 1f : 0f));
            passwordField.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT
                    || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    submitPassword();
                    return true;
                }
                return false;
            });
            passwordField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (s.length() > 0) {
                        clearError();
                    }
                }
            });
            passwordOutline.addView(passwordField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            passwordOutline.attachEditText(passwordField);
            column.addView(passwordOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 24, 32, 0));

            if (page.kind == ZgPinPage.KIND_VERIFY) {
                biometricButton = new ImageView(context);
                biometricButton.setScaleType(ImageView.ScaleType.CENTER);
                biometricButton.setImageResource(R.drawable.fingerprint);
                biometricButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
                biometricButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
                biometricButton.setContentDescription(LocaleController.getString(R.string.ZG_Lock_A11y_Biometric));
                biometricButton.setOnClickListener(v -> startBiometric());
                biometricButton.setVisibility(View.GONE);
                column.addView(biometricButton, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
                checkBiometricState();

                if (page.recoveryLabel != null) {
                    recoveryButton = new TextView(context);
                    recoveryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    recoveryButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
                    recoveryButton.setGravity(Gravity.CENTER);
                    recoveryButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
                    recoveryButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                    recoveryButton.setText(page.recoveryLabel);
                    recoveryButton.setOnClickListener(v -> {
                        if (!busy && page.recoveryAction != null) {
                            page.recoveryAction.run(ZgPinDialog.this);
                        }
                    });
                    column.addView(recoveryButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 12, 24, 0));
                    updateRecoveryVisibility();
                }
            }

            keyboard = new CustomPhoneKeyboardView(context);
            keyboard.setEditText(pinField);
            vertical.addView(keyboard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP));

            nextButton = new ButtonWithCounterView(context, null);
            nextButton.setOnClickListener(v -> submitPassword());
            vertical.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 16));
        }

        // -----------------------------------------------------------------------------------------------------

        void updateLabels() {
            if (page.kind == ZgPinPage.KIND_INFO) {
                titleView.setText(page.title);
                subtitleView.setVisibility(TextUtils.isEmpty(page.subtitle) ? View.GONE : View.VISIBLE);
                subtitleView.setText(page.subtitle);
                subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
                return;
            }
            final boolean pin = typeNow == ZgLockCode.TYPE_PIN;
            CharSequence title;
            CharSequence subtitle;
            if (page.kind == ZgPinPage.KIND_SETUP) {
                if (step == 0) {
                    title = pin ? (page.setupPinTitle != null ? page.setupPinTitle : LocaleController.getString(R.string.ZG_Lock_SetPin))
                        : (page.setupPasswordTitle != null ? page.setupPasswordTitle : LocaleController.getString(R.string.ZG_Lock_SetPassword));
                    subtitle = page.setupSubtitle != null ? page.setupSubtitle
                        : LocaleController.getString(pin ? R.string.ZG_Lock_SetPinSubtitle : R.string.ZG_Lock_SetPasswordSubtitle);
                } else {
                    title = LocaleController.getString(pin ? R.string.ZG_Lock_ConfirmPin : R.string.ZG_Lock_ConfirmPassword);
                    subtitle = LocaleController.getString(pin ? R.string.ZG_Lock_ConfirmPinSubtitle : R.string.ZG_Lock_ConfirmPasswordSubtitle);
                }
                if (switchButton != null) {
                    switchButton.setText(LocaleController.getString(pin ? R.string.PasscodeSwitchToPassword : R.string.PasscodeSwitchToPIN));
                    switchButton.setVisibility(step == 0 ? View.VISIBLE : View.INVISIBLE);
                }
                if (nextButton != null) {
                    nextButton.setText(LocaleController.getString(step == 0 ? R.string.Next : R.string.Done), false);
                }
            } else {
                title = page.title != null ? page.title : LocaleController.getString(pin ? R.string.ZG_Lock_EnterPin : R.string.ZG_Lock_EnterPassword);
                subtitle = page.subtitle;
                if (enrollmentChanged) {
                    subtitle = LocaleController.getString(R.string.ZG_Lock_BiometricChanged);
                } else if (recoveryRevealed && recoveryButton != null) {
                    subtitle = LocaleController.getString(R.string.ZG_Lock_RecoveryHint);
                }
                if (nextButton != null) {
                    nextButton.setText(LocaleController.getString(R.string.Done), false);
                }
            }
            titleView.setText(title);
            if (errorText != null) {
                subtitleView.setText(errorText);
                subtitleView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                subtitleView.setVisibility(View.VISIBLE);
            } else {
                subtitleView.setText(subtitle);
                subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
                subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
            }
        }

        /** Shows the keypad and dots for a PIN, the outlined field and the IME for a password. */
        void updateEntryMode(boolean focus) {
            if (page.kind == ZgPinPage.KIND_INFO || dotsView == null) {
                return;
            }
            final boolean pin = typeNow == ZgLockCode.TYPE_PIN;
            dotsView.setVisibility(pin ? View.VISIBLE : View.GONE);
            keyboard.setVisibility(pin ? View.VISIBLE : View.GONE);
            passwordOutline.setVisibility(pin ? View.GONE : View.VISIBLE);
            nextButton.setVisibility(pin ? View.GONE : View.VISIBLE);
            final Window window = getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | (pin ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN : WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE));
            }
            if (!focus) {
                return;
            }
            if (pin) {
                AndroidUtilities.hideKeyboard(passwordField);
                passwordField.clearFocus();
            } else {
                passwordField.requestFocus();
                AndroidUtilities.runOnUIThread(() -> {
                    if (!hidden && passwordField.hasFocus()) {
                        AndroidUtilities.showKeyboard(passwordField);
                    }
                }, 150);
            }
        }

        void onShownWithFocus() {
            if (hidden) {
                return;
            }
            lockView.playAnimation();
            updateEntryMode(true);
            if (page.kind == ZgPinPage.KIND_VERIFY && page.biometricAuto && !biometricAutoDone
                && biometricButton != null && biometricButton.getVisibility() == View.VISIBLE) {
                biometricAutoDone = true;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!hidden && !busy && isShowing()) {
                        startBiometric();
                    }
                }, 250);
            }
        }

        void onHidden() {
            if (passwordField != null) {
                AndroidUtilities.hideKeyboard(passwordField);
            }
        }

        void setBusy(boolean busy) {
            this.busy = busy;
            if (keyboard != null) {
                keyboard.setEditText(busy ? null : pinField);
                keyboard.setAlpha(busy ? 0.6f : 1f);
            }
            if (passwordField != null) {
                passwordField.setEnabled(!busy);
            }
            if (nextButton != null) {
                nextButton.setLoading(busy);
            }
            if (primaryButton != null) {
                primaryButton.setLoading(busy);
            }
        }

        void showError(CharSequence text, boolean shake) {
            errorText = text;
            updateLabels();
            if (shake) {
                final View target = typeNow == ZgLockCode.TYPE_PIN && dotsView != null ? dotsView : passwordOutline != null ? passwordOutline : titleView;
                AndroidUtilities.shakeViewSpring(target, 8);
            }
            if (text != null) {
                subtitleView.announceForAccessibility(text);
            }
        }

        void clearError() {
            if (errorText != null) {
                errorText = null;
                updateLabels();
            }
        }

        void clearEntry() {
            if (pinField != null) {
                pinField.setText("");
                pinField.setSelection(0);
                dotsView.setCount(0);
            }
            if (passwordField != null) {
                passwordField.setText("");
            }
        }

        boolean handleHardwareKey(KeyEvent event) {
            if (page.kind == ZgPinPage.KIND_INFO || typeNow != ZgLockCode.TYPE_PIN || pinField == null) {
                return false;
            }
            final int keyCode = event.getKeyCode();
            final boolean digit = keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9
                || keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9;
            if (!digit && keyCode != KeyEvent.KEYCODE_DEL) {
                return false;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || busy) {
                return true;
            }
            final Editable text = pinField.getText();
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (text.length() > 0) {
                    text.delete(text.length() - 1, text.length());
                }
            } else if (text.length() < ZgLockCode.PIN_LENGTH) {
                final int value = keyCode >= KeyEvent.KEYCODE_NUMPAD_0 ? keyCode - KeyEvent.KEYCODE_NUMPAD_0 : keyCode - KeyEvent.KEYCODE_0;
                text.append(String.valueOf(value));
            }
            pinField.setSelection(pinField.length());
            return true;
        }

        private void switchType() {
            if (busy || step != 0) {
                return;
            }
            typeNow = typeNow == ZgLockCode.TYPE_PIN ? ZgLockCode.TYPE_TEXT : ZgLockCode.TYPE_PIN;
            firstCode = null;
            errorText = null;
            clearEntry();
            updateLabels();
            updateEntryMode(true);
        }

        private void submitPassword() {
            if (busy || passwordField == null || typeNow != ZgLockCode.TYPE_TEXT) {
                return;
            }
            final Editable text = passwordField.getText();
            submit(text != null ? text.toString() : "");
        }

        private void submit(String raw) {
            if (busy || hidden) {
                return;
            }
            if (page.kind == ZgPinPage.KIND_SETUP) {
                submitSetup(raw);
            } else if (page.kind == ZgPinPage.KIND_VERIFY) {
                submitVerify(raw);
            }
        }

        private void submitSetup(String raw) {
            final String code = ZgLockCode.normalize(typeNow, raw, page.textPolicy);
            if (code == null) {
                clearEntry();
                if (typeNow == ZgLockCode.TYPE_TEXT && ZgLockCode.hasWhitespace(raw) && page.textPolicy == ZgLockCode.TEXT_POLICY_NO_WHITESPACE) {
                    showError(LocaleController.getString(R.string.ZG_Lock_PasswordNoSpaces), true);
                } else {
                    showError(LocaleController.getString(typeNow == ZgLockCode.TYPE_TEXT ? R.string.ZG_Lock_PasswordEmpty : R.string.ZG_Lock_SetPinSubtitle), true);
                }
                return;
            }
            if (step == 0) {
                firstCode = code;
                step = 1;
                errorText = null;
                clearEntry();
                updateLabels();
                if (typeNow == ZgLockCode.TYPE_TEXT) {
                    passwordField.requestFocus();
                }
                return;
            }
            if (!code.equals(firstCode)) {
                step = 0;
                firstCode = null;
                clearEntry();
                showError(LocaleController.getString(typeNow == ZgLockCode.TYPE_PIN ? R.string.ZG_Lock_CodesMismatch : R.string.ZG_Lock_PasswordsMismatch), true);
                return;
            }
            firstCode = null;
            if (page.setupListener != null) {
                page.setupListener.onCodeChosen(ZgPinDialog.this, code, typeNow);
            }
        }

        private void submitVerify(String raw) {
            if (typeNow == ZgLockCode.TYPE_TEXT && TextUtils.isEmpty(raw.trim())) {
                showError(LocaleController.getString(R.string.ZG_Lock_PasswordEmpty), true);
                return;
            }
            final int remaining = ZgLockThrottle.remainingSeconds(page.scope);
            if (remaining > 0) {
                clearEntry();
                revealRecoveryIfAllowed(true);
                showError(LocaleController.formatString(R.string.ZG_Lock_TooManyAttempts, remaining), true);
                return;
            }
            final String code = ZgLockCode.normalize(typeNow, raw, page.textPolicy);
            if (code == null) {
                // Not a possible code at all: a wrong entry without a check.
                ZgLockThrottle.registerFailure(page.scope);
                onVerdict(ZgLockCrypto.Verdict.MISMATCH, null, null);
                return;
            }
            setBusy(true);
            // Counted before the check: a process killed during the hashing still pays for the guess.
            final Object attempt = ZgLockThrottle.beginAttempt(page.scope);
            final ZgPinPage.Verifier verifier = page.verifier;
            runOnWorker(() -> {
                ZgLockCrypto.Verdict verdict;
                try {
                    verdict = verifier != null ? verifier.verify(code) : ZgLockCrypto.Verdict.ERROR;
                } catch (Throwable e) {
                    FileLog.e("ZgPinDialog: verifier failed: " + e.getClass().getSimpleName());
                    verdict = ZgLockCrypto.Verdict.ERROR;
                }
                final ZgLockCrypto.Verdict result = verdict == null ? ZgLockCrypto.Verdict.ERROR : verdict;
                AndroidUtilities.runOnUIThread(() -> onVerdict(result, code, attempt));
            });
        }

        /** {@code attempt}: the failure {@link ZgLockThrottle#beginAttempt} already counted for this entry (or null). */
        private void onVerdict(ZgLockCrypto.Verdict verdict, @Nullable String code, @Nullable Object attempt) {
            if (verdict == ZgLockCrypto.Verdict.MATCH) {
                ZgLockThrottle.reset(page.scope);
            } else if (verdict != ZgLockCrypto.Verdict.MISMATCH) {
                ZgLockThrottle.cancelAttempt(attempt);
            }
            if (hidden && !isShowing()) {
                return;
            }
            setBusy(false);
            switch (verdict) {
                case MATCH:
                    clearEntry();
                    if (page.verifiedListener != null) {
                        page.verifiedListener.onVerified(ZgPinDialog.this, code, false, enrollmentChanged);
                    }
                    break;
                case MISMATCH: {
                    failures++;
                    clearEntry();
                    revealRecoveryIfAllowed(false);
                    showError(LocaleController.getString(typeNow == ZgLockCode.TYPE_PIN ? R.string.ZG_Lock_WrongCode : R.string.ZG_Lock_WrongPassword), true);
                    break;
                }
                case UNVERIFIABLE:
                    clearEntry();
                    if (page.unverifiableAction != null) {
                        page.unverifiableAction.run(ZgPinDialog.this);
                    } else {
                        showError(LocaleController.getString(R.string.ZG_Lock_CheckFailed), true);
                    }
                    break;
                case ERROR:
                default:
                    clearEntry();
                    showError(LocaleController.getString(R.string.ZG_Lock_CheckFailed), true);
                    break;
            }
        }

        private void revealRecoveryIfAllowed(boolean lockedOut) {
            if (recoveryButton == null) {
                return;
            }
            final boolean struggled = failures >= ZgPinPage.STRUGGLE_FAILURES || lockedOut || ZgLockThrottle.remainingSeconds(page.scope) > 0;
            if (!struggled) {
                return;
            }
            final boolean wasVisible = recoveryButton.getVisibility() == View.VISIBLE;
            recoveryRevealed = true;
            updateRecoveryVisibility();
            if (!wasVisible || page.recoveryWhen == ZgPinPage.RECOVERY_ALWAYS) {
                recoveryButton.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).withEndAction(() ->
                    recoveryButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start()).start();
            }
        }

        private void updateRecoveryVisibility() {
            if (recoveryButton == null) {
                return;
            }
            final boolean visible = page.recoveryWhen == ZgPinPage.RECOVERY_ALWAYS || recoveryRevealed
                || failures >= ZgPinPage.STRUGGLE_FAILURES || ZgLockThrottle.remainingSeconds(page.scope) > 0;
            recoveryButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        private void checkBiometricState() {
            if (biometricButton == null || page.biometricSlot == null) {
                return;
            }
            final ZgLockBiometrics.Enrollment enrollment = ZgLockBiometrics.enrollment(page.biometricSlot);
            if (enrollment == ZgLockBiometrics.Enrollment.CHANGED || enrollment == ZgLockBiometrics.Enrollment.MISSING) {
                enrollmentChanged = true;
            }
            biometricButton.setVisibility(enrollment == ZgLockBiometrics.Enrollment.UNCHANGED && !enrollmentChanged ? View.VISIBLE : View.GONE);
        }

        private void startBiometric() {
            if (busy || page.biometricSlot == null || enrollmentChanged) {
                return;
            }
            ZgLockBiometrics.authenticate(activity, page.biometricSlot, page.biometricTitle != null ? page.biometricTitle : titleView.getText(),
                page.biometricSubtitle, LocaleController.getString(R.string.ZG_Lock_UsePincode), result -> {
                    if (hidden && !isShowing()) {
                        return;
                    }
                    switch (result) {
                        case SUCCESS:
                            ZgLockThrottle.reset(page.scope);
                            if (page.verifiedListener != null) {
                                page.verifiedListener.onVerified(ZgPinDialog.this, null, true, false);
                            }
                            break;
                        case ENROLLMENT_CHANGED:
                            enrollmentChanged = true;
                            biometricButton.setVisibility(View.GONE);
                            updateLabels();
                            subtitleView.announceForAccessibility(LocaleController.getString(R.string.ZG_Lock_BiometricChanged));
                            break;
                        case CANCELLED:
                        case UNAVAILABLE:
                        default:
                            updateEntryMode(true);
                            break;
                    }
                });
        }

        /**
         * Rotation (LaunchActivity handles it itself, so nothing is rebuilt): in landscape the lottie goes and the keypad
         * gets shorter, so the title, the dots and the error / lockout line stay on screen above it.
         */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = MeasureSpec.getSize(heightMeasureSpec);
            if (width > 0 && height > 0) {
                final boolean landscape = width > height;
                final int lockVisibility = !landscape && !AndroidUtilities.isSmallScreen() ? View.VISIBLE : View.GONE;
                if (lockView.getVisibility() != lockVisibility) {
                    lockView.setVisibility(lockVisibility);
                }
                if (keyboard != null && keyboard.getLayoutParams() != null) {
                    final int full = AndroidUtilities.dp(CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP);
                    final int target = landscape ? Math.max(AndroidUtilities.dp(132), Math.min(full, height * 2 / 5)) : full;
                    if (keyboard.getLayoutParams().height != target) {
                        keyboard.getLayoutParams().height = target;
                    }
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            hidden = true;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            hidden = false;
        }
    }
}

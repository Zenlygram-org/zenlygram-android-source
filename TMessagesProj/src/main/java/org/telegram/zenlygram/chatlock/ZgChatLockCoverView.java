/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.HashMap;
import java.util.Map;

/**
 * The opaque backstop over every screen of a locked chat until its grant exists (iOS ChatLockControllerGate cover).
 * Added as the last child of the host's fragment view, above everything (translationZ), consuming every touch, drag
 * and key, hiding its siblings from TalkBack while shown.
 *
 * <ul>
 *   <li>{@link #STYLE_INTERACTIVE}: back arrow + chat title (never for a vaulted chat), the lock, "This chat is
 *       locked", Unlock and Close.</li>
 *   <li>{@link #STYLE_PREVIEW} (avatar peek): only a large lock; a tap expands the peek, which then asks.</li>
 *   <li>{@link #STYLE_PASSIVE} (embedded screens): the lock, the title text and Unlock.</li>
 * </ul>
 */
@SuppressLint("ViewConstructor")
public class ZgChatLockCoverView extends FrameLayout {

    public static final int STYLE_INTERACTIVE = 0;
    public static final int STYLE_PREVIEW = 1;
    public static final int STYLE_PASSIVE = 2;

    public interface Delegate {
        void onUnlockClick();

        void onCloseClick();

        void onPreviewClick();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final LinearLayout topRow;
    private final TextView chatTitleView;
    private final LinearLayout center;
    private final RLottieImageView lockView;
    private final TextView coverTitleView;
    private final ButtonWithCounterView unlockButton;
    private final TextView closeButton;
    private final ImageView previewLock;
    private int style = -1;
    private boolean occupyStatusBar = true;
    private final HashMap<View, Integer> hiddenSiblings = new HashMap<>();

    public ZgChatLockCoverView(Context context, @Nullable Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setTranslationZ(AndroidUtilities.dp(1000));
        setOutlineProvider(null);
        setFitsSystemWindows(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView backButton = new ImageView(context);
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setColorFilter(new PorterDuffColorFilter(color(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        backButton.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        backButton.setContentDescription(LocaleController.getString(R.string.Back));
        backButton.setOnClickListener(v -> delegate.onCloseClick());
        topRow.addView(backButton, LayoutHelper.createLinear(56, 56));
        chatTitleView = new TextView(context);
        chatTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        chatTitleView.setTypeface(AndroidUtilities.bold());
        chatTitleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        chatTitleView.setSingleLine(true);
        chatTitleView.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(chatTitleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 8, 0, 16, 0));
        addView(topRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));

        center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        lockView = new RLottieImageView(context);
        lockView.setAnimation(R.raw.passcode_lock_close, 90, 90);
        lockView.setAutoRepeat(false);
        lockView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        center.addView(lockView, LayoutHelper.createLinear(90, 90, Gravity.CENTER_HORIZONTAL));
        coverTitleView = new TextView(context);
        coverTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        coverTitleView.setTypeface(AndroidUtilities.bold());
        coverTitleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        coverTitleView.setGravity(Gravity.CENTER);
        coverTitleView.setText(LocaleController.getString(R.string.ZG_ChatLock_CoverTitle));
        center.addView(coverTitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 16, 24, 0));
        unlockButton = new ButtonWithCounterView(context, resourcesProvider);
        unlockButton.setText(LocaleController.getString(R.string.ZG_ChatLock_Unlock), false);
        unlockButton.setOnClickListener(v -> delegate.onUnlockClick());
        center.addView(unlockButton, LayoutHelper.createLinear(220, 44, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));
        closeButton = new TextView(context);
        closeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        closeButton.setTextColor(color(Theme.key_windowBackgroundWhiteBlueText4));
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        closeButton.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        closeButton.setText(LocaleController.getString(R.string.Close));
        closeButton.setOnClickListener(v -> delegate.onCloseClick());
        center.addView(closeButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        addView(center, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        previewLock = new ImageView(context);
        previewLock.setScaleType(ImageView.ScaleType.CENTER);
        previewLock.setImageResource(R.drawable.ic_lock_header);
        previewLock.setColorFilter(new PorterDuffColorFilter(color(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
        previewLock.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(previewLock, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        setOnClickListener(v -> {
            if (style == STYLE_PREVIEW) {
                delegate.onPreviewClick();
            }
        });
        setContentDescription(LocaleController.getString(R.string.ZG_ChatLock_CoverTitle));
        setStyle(STYLE_INTERACTIVE);
    }

    public void setStyle(int style) {
        if (this.style == style) {
            return;
        }
        this.style = style;
        final boolean preview = style == STYLE_PREVIEW;
        topRow.setVisibility(style == STYLE_INTERACTIVE ? VISIBLE : GONE);
        center.setVisibility(preview ? GONE : VISIBLE);
        previewLock.setVisibility(preview ? VISIBLE : GONE);
        closeButton.setVisibility(style == STYLE_INTERACTIVE ? VISIBLE : GONE);
        requestLayout();
    }

    public int getStyle() {
        return style;
    }

    /** The chat title in the top row; null hides it (vaulted or unknown chats are never named). */
    public void setChatTitle(@Nullable CharSequence title) {
        chatTitleView.setText(title);
        chatTitleView.setVisibility(TextUtils.isEmpty(title) ? INVISIBLE : VISIBLE);
    }

    /** False for hosts drawn below the status bar (previews, embedded screens): no top inset. */
    public void setOccupyStatusBar(boolean occupyStatusBar) {
        if (this.occupyStatusBar != occupyStatusBar) {
            this.occupyStatusBar = occupyStatusBar;
            requestLayout();
        }
    }

    /** Shows the cover at once (relock: synchronous, no animation). */
    public void showNow() {
        animate().cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);
        hideSiblingsFromAccessibility();
        lockView.setProgress(0f);
        lockView.playAnimation();
        if (isAttachedToWindow()) {
            requestFocus();
        }
    }

    /** Fades out (200 ms) or hides at once; siblings come back to TalkBack. */
    public void hide(boolean animated) {
        animate().cancel();
        restoreSiblingsAccessibility();
        if (!animated || getVisibility() != VISIBLE) {
            setVisibility(GONE);
            setAlpha(1f);
            return;
        }
        animate().alpha(0f).setDuration(200).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
        }).start();
    }

    /** Fully opaque and on screen: nothing under it may draw. */
    public boolean isOpaqueNow() {
        return getVisibility() == VISIBLE && getAlpha() >= 1f && getWidth() > 0 && getHeight() > 0;
    }

    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;
        if (insets != null) {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            left = bars.left;
            top = occupyStatusBar ? bars.top : 0;
            right = bars.right;
            bottom = bars.bottom;
        } else if (occupyStatusBar) {
            top = AndroidUtilities.statusBarHeight;
        }
        if (getPaddingLeft() != left || getPaddingTop() != top || getPaddingRight() != right || getPaddingBottom() != bottom) {
            setPadding(left, top, right, bottom);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** Insets are applied in {@link #onMeasure}; fitsSystemWindows is only set for full-height measuring. */
    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        return insets;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onDragEvent(DragEvent event) {
        // Nothing may be dropped into a covered chat.
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER && getFocusedChild() != null) {
            return super.dispatchKeyEvent(event);
        }
        // Typing into a covered chat (hardware keyboard) goes nowhere.
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            hideSiblingsFromAccessibility();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        restoreSiblingsAccessibility();
    }

    private void hideSiblingsFromAccessibility() {
        if (!(getParent() instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            final View child = parent.getChildAt(i);
            if (child == this || hiddenSiblings.containsKey(child)) {
                continue;
            }
            hiddenSiblings.put(child, child.getImportantForAccessibility());
            child.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    private void restoreSiblingsAccessibility() {
        for (Map.Entry<View, Integer> entry : hiddenSiblings.entrySet()) {
            entry.getKey().setImportantForAccessibility(entry.getValue());
        }
        hiddenSiblings.clear();
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @NonNull
    @Override
    public CharSequence getAccessibilityClassName() {
        return FrameLayout.class.getName();
    }
}

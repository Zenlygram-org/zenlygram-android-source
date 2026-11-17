/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * The rows only the "Link Telegram Account" screen has (iOS SGLinkAccountView): the line that says what is being
 * waited on, the linking code, and the screen's one filled button. Everything else on that screen is a settings row
 * (ZgSettingRowCell) or one of Telegram's own cells.
 *
 * <p>As the other Zenlygram rows: a row measures with an exact width (the list gives its rows WRAP_CONTENT, see
 * ZgTaskCell.onMeasure), text sizes are in dp like Telegram's, and the app does not mirror layouts, so right-to-left
 * swaps the sides by hand.
 */
final class ZgLinkCells {

    private ZgLinkCells() {
    }

    /**
     * Work in progress: a spinner and the words for what is being waited on ("Linking…", "Waiting for
     * confirmation…"), in a card of its own so that it is taken neither for an error nor for a result. Not clickable.
     */
    static final class ProgressCell extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final RadialProgressView progressView;
        private final TextView textView;

        ProgressCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);
            setMinimumHeight(dp(50));

            progressView = new RadialProgressView(context, resourcesProvider);
            progressView.setSize(dp(20));
            progressView.setStrokeWidth(2);
            progressView.setProgressColorKey(Theme.key_windowBackgroundWhiteBlueText4);
            progressView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            textView.setPadding(0, dp(13), 0, dp(13));

            // The spinner sits where a settings row has its icon tile, so the text lines up with the rows around it.
            if (LocaleController.isRTL) {
                addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 20, 0, 18, 0));
                addView(progressView, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
            } else {
                addView(progressView, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
                addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 18, 0, 20, 0));
            }
            updateColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Full width (see ZgTaskCell.onMeasure); the height follows the text, never less than the minimum height.
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
        }

        void set(CharSequence text) {
            textView.setText(text);
            setContentDescription(text);
        }

        @Override
        public void updateColors() {
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            progressView.updateColors();
        }

        static final class Factory extends UItem.UItemFactory<ProgressCell> {
            static {
                setup(new Factory());
            }

            @Override
            public ProgressCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ProgressCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((ProgressCell) view).set(item.text);
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            static UItem of(int id, CharSequence text) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = text;
                item.enabled = false;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return a.id == b.id && TextUtils.equals(a.text, b.text);
            }
        }
    }

    /**
     * The linking code: one large monospace token that copies on tap, with what happened to it under it ("Copied.",
     * "Expires in 4:32"). TalkBack reads the code character by character, which is how it is compared with the bot's.
     *
     * <p>The countdown changes every second, so the screen writes it straight into the bound view
     * ({@link #setExpires}); it is not part of what makes two list items differ, or the row would be rebound (and
     * blink) every second.
     */
    static final class CodeCell extends LinearLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView codeView;
        private final TextView copiedView;
        private final TextView expiresView;
        private final ImageView iconView;
        private CharSequence code;
        private boolean copied;

        CodeCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            setOrientation(HORIZONTAL);
            setMinimumHeight(dp(56));

            final int textGravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            // The card's header ("Your linking code") is right above and brings the space over the code.
            texts.setPadding(0, dp(2), 0, dp(12));

            codeView = new TextView(context);
            codeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            codeView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            codeView.setLetterSpacing(0.12f);
            // A code is one token: it is never broken in the middle.
            codeView.setSingleLine(true);
            codeView.setGravity(textGravity);
            // Latin letters and digits: left to right in every language.
            codeView.setTextDirection(TEXT_DIRECTION_LTR);
            texts.addView(codeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            copiedView = new TextView(context);
            copiedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            copiedView.setGravity(textGravity);
            texts.addView(copiedView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            expiresView = new TextView(context);
            expiresView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            expiresView.setGravity(textGravity);
            texts.addView(expiresView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

            if (LocaleController.isRTL) {
                addView(iconView, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
                addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 20, 0));
            } else {
                addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 20, 0, 16, 0));
                addView(iconView, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            }
            updateColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Full width (see ZgTaskCell.onMeasure); the height follows the lines that are shown.
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
        }

        void set(CharSequence code, boolean copied, CharSequence expires) {
            this.code = code;
            this.copied = copied;
            codeView.setText(code);
            copiedView.setText(LocaleController.getString(R.string.ZG_LinkAccount_Code_Copied));
            copiedView.setVisibility(copied ? VISIBLE : GONE);
            iconView.setImageResource(copied ? R.drawable.ic_ab_done : R.drawable.msg_copy);
            setExpires(expires);
            updateColors();
        }

        /** "Expires in 4:32", or nothing once the time is up. */
        void setExpires(CharSequence expires) {
            if (!TextUtils.equals(expiresView.getText(), expires)) {
                expiresView.setText(expires);
            }
            final int visibility = TextUtils.isEmpty(expires) ? GONE : VISIBLE;
            if (expiresView.getVisibility() != visibility) {
                expiresView.setVisibility(visibility);
            }
        }

        @Override
        public void updateColors() {
            codeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            copiedView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText, resourcesProvider));
            expiresView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(
                copied ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider),
                PorterDuff.Mode.SRC_IN));
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            final StringBuilder text = new StringBuilder(ZgLinkRules.spelled(code == null ? null : code.toString()));
            if (copied) {
                text.append(". ").append(copiedView.getText());
            }
            if (expiresView.getVisibility() == VISIBLE) {
                text.append(". ").append(expiresView.getText());
            }
            info.setContentDescription(text);
            info.setClassName("android.widget.Button");
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                LocaleController.getString(R.string.ZG_LinkAccount_Code_CopyHint)));
        }

        static final class Factory extends UItem.UItemFactory<CodeCell> {
            static {
                setup(new Factory());
            }

            @Override
            public CodeCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new CodeCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((CodeCell) view).set(item.text, item.checked, item.subtext);
            }

            /** {@code expires} is what the row shows when it is bound; the screen keeps it current afterwards. */
            static UItem of(int id, CharSequence code, boolean copied, CharSequence expires) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = code;
                item.checked = copied;
                item.subtext = expires;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return a.id == b.id && a.checked == b.checked && TextUtils.equals(a.text, b.text);
            }
        }
    }

    /**
     * The screen's one filled button ("Open Telegram", "Get a New Code", "Link Telegram Account"), on the list
     * background between the cards and as wide as they are. The button takes its own taps (the list's row selector
     * would draw a rectangle over it), so the item carries the click listener.
     */
    static final class ButtonCell extends FrameLayout {
        private final ButtonWithCounterView button;

        ButtonCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            // Not a card of the sectioned list: it stands between the cards.
            setTag(RecyclerListView.TAG_NOT_SECTION);
            button = new ButtonWithCounterView(context, resourcesProvider);
            // 12dp at the sides: the inset of the list's cards.
            addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 12, 12, 12, 12));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Full width (see ZgTaskCell.onMeasure), as high as the button and its margins.
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY)
            );
        }

        void set(CharSequence text, OnClickListener onClick) {
            button.setText(text, false);
            button.setContentDescription(text);
            button.setOnClickListener(onClick);
        }

        static final class Factory extends UItem.UItemFactory<ButtonCell> {
            static {
                setup(new Factory());
            }

            @Override
            public ButtonCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new ButtonCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((ButtonCell) view).set(item.text, item.clickCallback);
            }

            @Override
            public boolean isShadow() {
                return true;
            }

            @Override
            public boolean isClickable() {
                return false;
            }

            static UItem of(int id, CharSequence text, OnClickListener onClick) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = text;
                item.clickCallback = onClick;
                item.enabled = false;
                return item;
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                // The listener is rebuilt with every list; a row that says the same thing is the same row.
                return a.id == b.id && TextUtils.equals(a.text, b.text);
            }
        }
    }
}

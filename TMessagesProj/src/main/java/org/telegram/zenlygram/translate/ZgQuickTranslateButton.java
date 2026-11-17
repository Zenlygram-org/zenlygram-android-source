/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.translate;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Paints the Quick Translate button of a ChatMessageCell: a 32 dp circle on the chat's service background (the same
 * paints as the share button, so wallpapers and dark themes work) with the translate glyph. While the message is
 * shown translated, the glyph sits on the selected service colour so the state is visible.
 */
public final class ZgQuickTranslateButton {

    public static final int SIZE_DP = 32;

    private final RectF rect = new RectF();
    private Drawable icon;
    private int iconColor;

    public void draw(Canvas canvas, ChatMessageCell cell, float x, float y, boolean pressed, boolean translated) {
        rect.set(x, y, x + dp(SIZE_DP), y + dp(SIZE_DP));
        cell.applyServiceShaderMatrix();
        canvas.drawRoundRect(rect, dp(SIZE_DP / 2f), dp(SIZE_DP / 2f), cell.getThemedPaint(pressed || translated ? Theme.key_paint_chatActionBackgroundSelected : Theme.key_paint_chatActionBackground));
        if (cell.hasGradientService()) {
            canvas.drawRoundRect(rect, dp(SIZE_DP / 2f), dp(SIZE_DP / 2f), Theme.chat_actionBackgroundGradientDarkenPaint);
        }
        if (icon == null) {
            final Drawable drawable = ContextCompat.getDrawable(cell.getContext(), R.drawable.msg_translate);
            if (drawable == null) {
                return;
            }
            icon = drawable.mutate();
        }
        final int color = cell.getThemedColor(Theme.key_chat_serviceIcon);
        if (color != iconColor || icon.getColorFilter() == null) {
            iconColor = color;
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
        final int size = dp(20);
        final int left = (int) (rect.centerX() - size / 2f);
        final int top = (int) (rect.centerY() - size / 2f);
        icon.setBounds(left, top, left + size, top + size);
        icon.draw(canvas);
    }
}

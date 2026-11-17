/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlist;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Chat list layout switches of Settings > Zenlygram > Chat list.
 *
 * <ul>
 *   <li>Compact Message Preview is Telegram's own Chat Settings > Chat list view > Two lines
 *       ({@link SharedConfig#useThreeLinesLayout} off): the row 1002 writes that setting.</li>
 *   <li>Compact Chat List (iOS compactChatList) shrinks the rows of the chat lists (main list, archive, folders,
 *       communities, the forward / share pickers: every DialogCell that DialogsAdapter creates). It builds on Telegram's
 *       two-line row, so {@link #setCompact(boolean)} also selects Two lines, and choosing Three lines in Chat Settings
 *       turns it off (ThemeActivity hook). Search results, shared media, previews and the topic rows of a forum keep
 *       Telegram's layout (their DialogCells have no DialogsActivity, force three lines or are topics).</li>
 * </ul>
 *
 * <p>Compact geometry, derived from Telegram's two-line row (70 dp, 52 dp avatar at 11/9 dp, name at 76 dp) and iOS
 * (avatar 60 / 1.5 = 40 pt, title + one line, text-only swipe buttons, smaller inline button):
 * <ul>
 *   <li>Row 56 dp (+1 px separator): the 40 dp avatar with Telegram's own 8 dp margins above and below. The text keeps
 *       Telegram's fixed sizes (name 16 dp, message 15 dp; DialogCell sets them in dp, so the system font scale does not
 *       grow them), only moves up {@link #COMPACT_SHIFT_DP} = (70 - 56) / 2: the name + message block (42.6 dp) stays
 *       centred, 7 dp from the top and 6.4 dp from the bottom, with Telegram's 6.25 dp between the two lines.</li>
 *   <li>Name and message start at 64 dp ({@link #COMPACT_PADDING_START_DP} + 4): 13 dp after the avatar, as in
 *       Telegram (63 to 76). The separator starts at 60 dp.</li>
 *   <li>Counters, mention / reaction badges, error mark, read checks, pin, mute, verified, emoji status, secret lock,
 *       bot badges and media thumbnails keep their sizes and their place relative to the name / message lines.</li>
 *   <li>Overlays on the avatar (online dot, group call, auto-delete timer, Premium lock, community arrow, selection
 *       check box) keep their sizes and sit at the same spot of the smaller avatar's edge: their offsets scale by
 *       {@link #COMPACT_AVATAR_SCALE} = 40 / 52. Avatar icons (Saved Messages, Replies, the archive box) and the forum /
 *       community corner radii scale the same way.</li>
 *   <li>Forum rows (name, topics, last message): {@link #COMPACT_FORUM_ROW_DP} = 79 dp, the same 7 dp shift and the
 *       same 6.4 dp under the last line as every compact row. Folder tags are not drawn (no room under the message
 *       line). Swipe actions show their label only, centred (iOS; the 36 dp icon would leave 2 dp above it). The bot
 *       Open button is a 24 dp pill on the message line that keeps Telegram's 28 dp touch height.</li>
 * </ul>
 */
public final class ZgChatListLayout {

    /** Height of a compact row, without the 1 px separator (Telegram two-line: 70 dp, three-line: 76 dp). */
    public static final int COMPACT_ROW_DP = 56;
    /** Height of a compact forum row (Telegram two-line: 91 dp): last line 55 + 17.6 dp, then 6.4 dp like every row. */
    public static final int COMPACT_FORUM_ROW_DP = 79;
    /** Avatar diameter (Telegram two-line: 52 dp; iOS compact: 40 pt). */
    public static final int COMPACT_AVATAR_DP = 40;
    /** Top of the avatar: centred, (56 - 40) / 2 (Telegram two-line: 9 dp). */
    public static final int COMPACT_AVATAR_TOP_DP = 8;
    /** DialogCell.messagePaddingStart (Telegram: 72): the name and the message start 4 dp after it. */
    public static final int COMPACT_PADDING_START_DP = 60;
    /** Every text and icon y of Telegram's two-line row moves up by this much: (70 - 56) / 2. */
    public static final int COMPACT_SHIFT_DP = 7;
    /** The selection check box moves up by this much, so it stays on the edge of the 40 dp avatar (Telegram: 42 dp). */
    public static final int COMPACT_CHECKBOX_SHIFT_DP = 12;
    /** Avatar-relative offsets, radii and avatar icons scale by this (40 dp avatar instead of 52 dp). */
    public static final float COMPACT_AVATAR_SCALE = COMPACT_AVATAR_DP / 52f;
    /** The bot Open button: 24 dp pill (Telegram: 28 dp at 36 dp) centred on the message line, 6 dp under the time. */
    public static final int COMPACT_BOT_BUTTON_TOP_DP = 29;
    public static final int COMPACT_BOT_BUTTON_HEIGHT_DP = 24;
    /** Added above and below the compact Open pill for touches: Telegram's 28 dp touch height. */
    public static final float COMPACT_BOT_BUTTON_TOUCH_SLOP_DP = (28 - COMPACT_BOT_BUTTON_HEIGHT_DP) / 2f;
    /** Long press left of this opens the chat preview, like a press on the avatar (Telegram: 60 dp, avatar end - 3). */
    public static final int COMPACT_AVATAR_HIT_DP = 48;
    /** Collapsed chat list beside an open forum (Telegram: 76 = 11 + 52 + 13 dp): 11 + 40 + 13 dp. */
    public static final int COMPACT_COLLAPSED_COLUMN_DP = 64;
    /** The archive pull arrow starts under the avatar centre (11 + 20 - 9; Telegram: 28 for the 52 dp avatar). */
    public static final int COMPACT_PULL_ARROW_START_DP = 22;
    /** Loading placeholder of a compact row: Telegram's placeholder is 2 dp taller than its row (72 for 70). */
    public static final int COMPACT_PLACEHOLDER_ROW_DP = COMPACT_ROW_DP + 2;

    private ZgChatListLayout() {
    }

    /**
     * Whether the compact chat list is on: its feature is ported, the switch is on and Telegram's two-line layout is
     * active (the compact list builds on it; the switches keep the two in step, this only guards stale values).
     */
    public static boolean isCompactEnabled() {
        return ZgFeatures.COMPACT_CHAT_LIST.isPorted() && ZgSettings.compactChatList.get() && !SharedConfig.useThreeLinesLayout;
    }

    /**
     * Compact Chat List switch: on also selects Telegram's two-line chat list, which the compact list builds on. Open
     * chat lists re-lay out: a paused list when it resumes (DialogsActivity.onResume re-binds its pages), a visible one
     * (tablets) on the dialogsNeedReload below (its rows run DialogCell.update on the next draw).
     */
    public static void setCompact(boolean compact) {
        final boolean wasEnabled = isCompactEnabled();
        if (ZgSettings.compactChatList.get() != compact) {
            ZgSettings.compactChatList.set(compact);
        }
        if (compact && SharedConfig.useThreeLinesLayout) {
            SharedConfig.setUseThreeLinesLayout(false);
        }
        if (isCompactEnabled() != wasEnabled) {
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.dialogsNeedReload, true);
        }
    }

    /** Height of a chat-list row for DialogsAdapter / DialogsActivity estimates: compact, else Telegram's own value. */
    public static int rowHeightDp(int telegramDp) {
        return isCompactEnabled() ? COMPACT_ROW_DP : telegramDp;
    }

    /** Height of a forum row for DialogsAdapter estimates: compact, else Telegram's own value. */
    public static int forumRowHeightDp(int telegramDp) {
        return isCompactEnabled() ? COMPACT_FORUM_ROW_DP : telegramDp;
    }

    /** Height of the white band the delete easter egg (PacmanAnimation) draws over a row: compact, else Telegram's. */
    public static int placeholderRowDp(int telegramDp) {
        return isCompactEnabled() ? COMPACT_PLACEHOLDER_ROW_DP : telegramDp;
    }

    /** Width of the collapsed chat list beside an open forum (RightSlidingDialogContainer): compact, else Telegram's. */
    public static int collapsedColumnDp(int telegramDp) {
        return isCompactEnabled() ? COMPACT_COLLAPSED_COLUMN_DP : telegramDp;
    }

    /**
     * Whether a forum open beside the collapsed list (RightSlidingDialogContainer, left margin in px) was laid out for
     * the other Compact Chat List state than the column it needs now ({@code columnDp}). Never true for Telegram's own
     * 74 / 76 dp columns, so a stock layout is never redone.
     */
    public static boolean isCollapsedColumnStale(int leftMarginPx, int columnDp) {
        return leftMarginPx != AndroidUtilities.dp(columnDp)
            && (leftMarginPx == AndroidUtilities.dp(COMPACT_COLLAPSED_COLUMN_DP) || columnDp == COMPACT_COLLAPSED_COLUMN_DP);
    }

    /** Start of the archive pull arrow (PullForegroundDrawable): under the avatar centre of a compact archive row. */
    public static int pullArrowStartDp(View cell, int telegramDp) {
        return isCompactCell(cell) ? COMPACT_PULL_ARROW_START_DP : telegramDp;
    }

    /**
     * Final scale of the archive box that PullForegroundDrawable flies into the archive row's avatar: the box of a compact
     * row is drawn at {@link #COMPACT_AVATAR_SCALE} (AvatarDrawable scales it with the 40 dp avatar), else Telegram's 1.
     */
    public static float archiveIconScale(View cell) {
        return isCompactCell(cell) ? COMPACT_AVATAR_SCALE : 1f;
    }

    private static boolean isCompactCell(View cell) {
        return cell instanceof DialogCell && ((DialogCell) cell).isZgCompact();
    }
}

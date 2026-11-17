/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.contacts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Contacts drag-to-select (iOS ContactListPanSelection + the hold-then-drag of feat/lenta-v6):
 * <ul>
 *     <li>Long-press a contact (which checks it and starts selection mode) and, without lifting, drag up or down:
 *     every row the finger passes gets the long-pressed row's new state.</li>
 *     <li>In selection mode, a vertical drag that starts on the avatar / checkbox column checks (from an unchecked
 *     row) or unchecks (from a checked row) every row it passes; a drag elsewhere still scrolls, a tap still toggles
 *     one row.</li>
 *     <li>Dragging back gives rows their pre-drag state back ({@link ZgRangeSelection}); holding the finger near the
 *     top or bottom edge scrolls the list and keeps selecting.</li>
 * </ul>
 */
public final class ZgContactsDragSelect implements RecyclerView.OnItemTouchListener {

    public interface Host extends ZgRangeSelection.Rows {
        /** Whether the selection action mode is on. */
        boolean isSelectionMode();

        /** Called once per drag, after the last row change (show/hide the action mode, update its count). */
        void onDragSelectionChanged();

        /** Called when a drag ends (finger lifted or cancelled). */
        void onDragSelectionEnded();
    }

    private static final int COLUMN_DP = 72;
    private static final int EDGE_DP = 56;
    private static final int MAX_SCROLL_DP = 16;

    private final RecyclerListView listView;
    private final Host host;
    private final ZgRangeSelection range;
    private final int touchSlop;

    private float downX, downY;
    private float lastX, lastY;
    /** A pending drag: the long-pressed row, or the column row of a touch in selection mode; -1 for none. */
    private int armedPosition = -1;
    /** The armed drag came from a long press: its row already has the drag's state. */
    private boolean armedByLongPress;
    private boolean dragging;
    private int scrollSpeed;

    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging || scrollSpeed == 0) {
                return;
            }
            listView.scrollBy(0, scrollSpeed);
            updateUnderFinger();
            listView.postOnAnimation(this);
        }
    };

    public ZgContactsDragSelect(RecyclerListView listView, Host host) {
        this.listView = listView;
        this.host = host;
        this.range = new ZgRangeSelection(host);
        this.touchSlop = ViewConfiguration.get(listView.getContext()).getScaledTouchSlop();
    }

    public static ZgContactsDragSelect attach(RecyclerListView listView, Host host) {
        final ZgContactsDragSelect helper = new ZgContactsDragSelect(listView, host);
        listView.addOnItemTouchListener(helper);
        return helper;
    }

    /** Whether a drag is selecting rows now: list positions must not change meanwhile. */
    public boolean isDragging() {
        return dragging;
    }

    /** The long press on {@code position} has just checked or unchecked it: a drag may follow. */
    public void armFromLongPress(int position) {
        if (position < 0 || dragging) {
            return;
        }
        armedPosition = position;
        armedByLongPress = true;
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                armedPosition = -1;
                armedByLongPress = false;
                if (host.isSelectionMode() && rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && inColumn(e.getX())) {
                    final int position = positionAt(e.getY());
                    if (position >= 0 && host.isSelectable(position)) {
                        armedPosition = position;
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    return true;
                }
                if (armedPosition < 0 || e.getPointerCount() > 1) {
                    return false;
                }
                final float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                if (!armedByLongPress && dx > touchSlop && dx > dy) {
                    armedPosition = -1; // a sideways swipe (back gesture, pager) is not a selection drag
                    return false;
                }
                if (dy > touchSlop && (armedByLongPress || dy > dx)) {
                    startDrag(e);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                armedPosition = -1;
                armedByLongPress = false;
                if (dragging) {
                    finishDrag();
                }
                return false;
        }
        return false;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        if (!dragging) {
            return;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                lastX = e.getX();
                lastY = e.getY();
                updateUnderFinger();
                updateAutoScroll();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                finishDrag();
                break;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
    }

    private void startDrag(MotionEvent e) {
        final int anchor = armedPosition;
        final boolean selecting = armedByLongPress ? host.isSelected(anchor) : ZgRangeSelection.directionFor(host.isSelected(anchor));
        armedPosition = -1;
        dragging = true;
        // The row under the finger must not open on lift, and the parent (pager, swipe-back) must not take the touch.
        listView.cancelClickRunnables(true);
        final ViewParent parent = listView.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        lastX = e.getX();
        lastY = e.getY();
        if (range.begin(anchor, selecting) > 0) {
            haptic();
        }
        updateUnderFinger();
        host.onDragSelectionChanged();
    }

    private void finishDrag() {
        dragging = false;
        scrollSpeed = 0;
        listView.removeCallbacks(autoScroll);
        range.end();
        host.onDragSelectionChanged();
        host.onDragSelectionEnded();
    }

    private void updateUnderFinger() {
        final int position = positionAt(lastY);
        if (position >= 0 && range.update(position) > 0) {
            haptic();
            host.onDragSelectionChanged();
        }
    }

    private void updateAutoScroll() {
        final int top = listView.getPaddingTop(), bottom = listView.getHeight() - listView.getPaddingBottom();
        final int edge = dp(EDGE_DP), max = dp(MAX_SCROLL_DP);
        int speed = 0;
        if (lastY < top + edge) {
            speed = -(int) Math.ceil(max * Math.min(1f, (top + edge - lastY) / (float) edge));
        } else if (lastY > bottom - edge) {
            speed = (int) Math.ceil(max * Math.min(1f, (lastY - (bottom - edge)) / (float) edge));
        }
        final boolean wasScrolling = scrollSpeed != 0;
        scrollSpeed = speed;
        if (speed != 0 && !wasScrolling) {
            listView.postOnAnimation(autoScroll);
        } else if (speed == 0) {
            listView.removeCallbacks(autoScroll);
        }
    }

    /** The adapter position of the row at {@code y} (any x), clamped to the visible rows; -1 if none. */
    private int positionAt(float y) {
        final float clampedY = Math.max(listView.getPaddingTop() + 1, Math.min(listView.getHeight() - listView.getPaddingBottom() - 1, y));
        final View child = listView.findChildViewUnder(listView.getWidth() / 2f, clampedY);
        if (child == null) {
            return -1;
        }
        final int position = listView.getChildAdapterPosition(child);
        return position == RecyclerView.NO_POSITION ? -1 : position;
    }

    private boolean inColumn(float x) {
        final int column = dp(COLUMN_DP);
        return LocaleController.isRTL ? x > listView.getWidth() - column : x < column;
    }

    private void haptic() {
        try {
            listView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignored) {
        }
    }
}

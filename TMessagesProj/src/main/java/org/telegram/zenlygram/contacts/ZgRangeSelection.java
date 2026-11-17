/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.contacts;

import java.util.HashMap;

/**
 * Range selection of a drag across list rows (iOS ContactListPanSelection): every selectable row between the anchor
 * (the row the drag started on) and the current row gets the drag's state, and a row that leaves that range gets back
 * the state it had before the drag, so dragging back undoes. Rows the drag never covered are not touched.
 *
 * <p>The direction comes from the first row: from an unchecked row the drag checks, from a checked row it unchecks
 * ({@link #directionFor}). Pure Java (no Android types) so it can be unit tested; indices are list positions, which
 * must not change while a drag runs.
 */
public final class ZgRangeSelection {

    /** The list the drag runs over. */
    public interface Rows {
        /** Whether the row can be checked (a contact, not a header or a menu row). */
        boolean isSelectable(int index);

        boolean isSelected(int index);

        void setSelected(int index, boolean selected);
    }

    private final Rows rows;
    /** The state each covered row had when the drag first covered it. */
    private final HashMap<Integer, Boolean> original = new HashMap<>();
    private boolean active;
    private boolean selecting;
    private int anchor = -1;
    private int current = -1;

    public ZgRangeSelection(Rows rows) {
        this.rows = rows;
    }

    /** The drag's state for a drag that starts on a row with {@code anchorSelected}: the opposite one. */
    public static boolean directionFor(boolean anchorSelected) {
        return !anchorSelected;
    }

    /**
     * Starts a drag on {@code anchor}: the anchor (if selectable) gets {@code selecting} at once.
     *
     * @return the number of rows whose state changed
     */
    public int begin(int anchor, boolean selecting) {
        end();
        this.active = true;
        this.anchor = anchor;
        this.current = anchor;
        this.selecting = selecting;
        return apply(anchor, anchor);
    }

    /**
     * Moves the drag's end to {@code index}: rows that left the range get their pre-drag state back, rows in the range
     * get the drag's state.
     *
     * @return the number of rows whose state changed
     */
    public int update(int index) {
        if (!active || index < 0 || index == current) {
            return 0;
        }
        final int oldLo = Math.min(anchor, current), oldHi = Math.max(anchor, current);
        final int newLo = Math.min(anchor, index), newHi = Math.max(anchor, index);
        current = index;
        int changed = 0;
        for (int i = oldLo; i <= oldHi; i++) {
            if (i < newLo || i > newHi) {
                changed += restore(i);
            }
        }
        return changed + apply(newLo, newHi);
    }

    /** Ends the drag; the rows keep their current state. */
    public void end() {
        active = false;
        original.clear();
        anchor = -1;
        current = -1;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSelecting() {
        return selecting;
    }

    public int getAnchor() {
        return anchor;
    }

    public int getCurrent() {
        return current;
    }

    private int apply(int lo, int hi) {
        int changed = 0;
        for (int i = lo; i <= hi; i++) {
            if (!rows.isSelectable(i)) {
                continue;
            }
            final boolean state = rows.isSelected(i);
            if (!original.containsKey(i)) {
                original.put(i, state);
            }
            if (state != selecting) {
                rows.setSelected(i, selecting);
                changed++;
            }
        }
        return changed;
    }

    private int restore(int i) {
        final Boolean before = original.get(i);
        if (before == null || !rows.isSelectable(i)) {
            return 0;
        }
        if (rows.isSelected(i) != before) {
            rows.setSelected(i, before);
            return 1;
        }
        return 0;
    }
}

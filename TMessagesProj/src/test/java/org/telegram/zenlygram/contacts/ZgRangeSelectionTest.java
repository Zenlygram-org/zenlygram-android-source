package org.telegram.zenlygram.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ZgRangeSelectionTest {

    /** 20 rows; rows in {@code headers} are not selectable. */
    private static final class FakeRows implements ZgRangeSelection.Rows {
        final Set<Integer> selected = new HashSet<>();
        final Set<Integer> headers = new HashSet<>();
        int writes;

        @Override
        public boolean isSelectable(int index) {
            return index >= 0 && index < 20 && !headers.contains(index);
        }

        @Override
        public boolean isSelected(int index) {
            return selected.contains(index);
        }

        @Override
        public void setSelected(int index, boolean value) {
            writes++;
            if (value) {
                selected.add(index);
            } else {
                selected.remove(index);
            }
        }
    }

    private static Set<Integer> set(int... values) {
        Set<Integer> s = new HashSet<>();
        for (int v : values) {
            s.add(v);
        }
        return s;
    }

    @Test
    public void directionIsOppositeOfAnchor() {
        assertTrue(ZgRangeSelection.directionFor(false));
        assertFalse(ZgRangeSelection.directionFor(true));
    }

    @Test
    public void beginChecksAnchor() {
        FakeRows rows = new FakeRows();
        ZgRangeSelection s = new ZgRangeSelection(rows);
        assertEquals(1, s.begin(5, true));
        assertTrue(s.isActive());
        assertEquals(set(5), rows.selected);
    }

    @Test
    public void dragDownChecksRangeAndBackRestores() {
        FakeRows rows = new FakeRows();
        ZgRangeSelection s = new ZgRangeSelection(rows);
        s.begin(3, true);
        assertEquals(4, s.update(7));
        assertEquals(set(3, 4, 5, 6, 7), rows.selected);
        assertEquals(2, s.update(5));
        assertEquals(set(3, 4, 5), rows.selected);
        s.update(3);
        assertEquals(set(3), rows.selected);
    }

    @Test
    public void crossingTheAnchorRestoresTheOtherSide() {
        FakeRows rows = new FakeRows();
        ZgRangeSelection s = new ZgRangeSelection(rows);
        s.begin(10, true);
        s.update(13);
        s.update(8);
        assertEquals(set(8, 9, 10), rows.selected);
    }

    @Test
    public void rowsOutsideTheRangeKeepPreDragState() {
        FakeRows rows = new FakeRows();
        rows.selected.addAll(set(1, 6, 15));
        ZgRangeSelection s = new ZgRangeSelection(rows);
        s.begin(4, true);
        s.update(8);
        assertEquals(set(1, 4, 5, 6, 7, 8, 15), rows.selected);
        s.update(4);
        // 6 was checked before the drag: it stays checked after the drag leaves it.
        assertEquals(set(1, 4, 6, 15), rows.selected);
    }

    @Test
    public void uncheckDragRestoresCheckedRows() {
        FakeRows rows = new FakeRows();
        rows.selected.addAll(set(2, 3, 4, 5, 9));
        ZgRangeSelection s = new ZgRangeSelection(rows);
        s.begin(2, ZgRangeSelection.directionFor(rows.isSelected(2)));
        s.update(6);
        assertEquals(set(9), rows.selected);
        s.update(3);
        assertEquals(set(4, 5, 9), rows.selected);
    }

    @Test
    public void headersAreSkipped() {
        FakeRows rows = new FakeRows();
        rows.headers.add(5);
        ZgRangeSelection s = new ZgRangeSelection(rows);
        s.begin(3, true);
        s.update(7);
        assertEquals(set(3, 4, 6, 7), rows.selected);
    }

    @Test
    public void sameIndexAndInactiveAreNoOps() {
        FakeRows rows = new FakeRows();
        ZgRangeSelection s = new ZgRangeSelection(rows);
        assertEquals(0, s.update(4));
        s.begin(4, true);
        int writes = rows.writes;
        assertEquals(0, s.update(4));
        assertEquals(0, s.update(-1));
        assertEquals(writes, rows.writes);
        s.end();
        assertFalse(s.isActive());
        assertEquals(0, s.update(9));
        assertEquals(set(4), rows.selected);
    }

    @Test
    public void longDragAcrossManyRows() {
        FakeRows rows = new FakeRows();
        ZgRangeSelection s = new ZgRangeSelection(rows);
        s.begin(0, true);
        for (int i = 1; i < 20; i++) {
            s.update(i);
        }
        assertEquals(20, rows.selected.size());
        s.update(0);
        assertEquals(set(0), rows.selected);
    }
}

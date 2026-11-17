/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import org.telegram.zenlygram.core.ZgFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One section of Settings > Zenlygram: a header and its rows, in iOS order.
 *
 * <p>Ids are fixed: the header is {@code id * 100}, rows are {@code id * 100 + 1 ... + 98}; {@code + 99} is the gap
 * after the section. Ids of rows that are missing on Android stay reserved, so rows never change ids.
 */
public final class ZgSection {

    public final int id;
    /** Default feature of the rows. Null: every row names its own (a row without any feature is never shown). */
    ZgFeatures feature;
    ZgRow.Condition visibleWhen;
    ZgRow.Condition availableWhen;
    final ArrayList<ZgRow> rows = new ArrayList<>();

    ZgSection(int id, ZgRow.Text header) {
        this.id = id;
        add(ZgRow.header(id * 100, header));
    }

    /** Default feature of the rows (a row can name its own). */
    ZgSection feature(ZgFeatures feature) {
        this.feature = feature;
        return this;
    }

    /** State condition: false hides the whole section, preview mode included. */
    ZgSection visibleWhen(ZgRow.Condition condition) {
        this.visibleWhen = condition;
        return this;
    }

    /** Environment condition: false hides the section in normal use; preview mode still shows it. */
    ZgSection availableWhen(ZgRow.Condition condition) {
        this.availableWhen = condition;
        return this;
    }

    ZgSection add(ZgRow row) {
        row.section = this;
        rows.add(row);
        return this;
    }

    public ZgRow getHeader() {
        return rows.get(0);
    }

    public List<ZgRow> getRows() {
        return Collections.unmodifiableList(rows);
    }
}

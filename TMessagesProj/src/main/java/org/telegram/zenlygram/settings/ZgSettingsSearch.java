/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Search of Settings > Zenlygram, a port of iOS filterSGItemListUIEntrires (SGItemListUI.swift).
 *
 * <ul>
 *   <li>Case-insensitive substring match on headers, titles, descriptions, values and notices. Sliders are never
 *       matched. Apostrophe look-alikes count as one character, so "qo'ng'iroq" finds "Qoʻngʻiroq" (Uzbek writes
 *       oʻ / gʻ with ', ‘, ’, ʻ or ʼ).</li>
 *   <li>A header that matches keeps its whole section.</li>
 *   <li>A row that matches also keeps the notice right after it; a notice that matches also keeps the row right
 *       before it (unless that is a notice too).</li>
 *   <li>The headers of every section with a match are kept.</li>
 * </ul>
 * Entries keep their original order. An empty query keeps everything; a query that matches nothing keeps nothing.
 */
public final class ZgSettingsSearch {

    private ZgSettingsSearch() {
    }

    public interface Entry {
        int getSectionId();

        boolean isHeader();

        boolean isNotice();

        /** {@code query} is already folded ({@link #normalizeQuery}). */
        boolean matches(String query);
    }

    public static String normalizeQuery(String query) {
        return query == null ? "" : fold(query.trim());
    }

    /** Whether any of {@code texts} contains {@code query} (folded by {@link #normalizeQuery}), ignoring case. */
    public static boolean contains(String query, CharSequence... texts) {
        for (CharSequence text : texts) {
            if (!TextUtils.isEmpty(text) && fold(text).contains(query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lower case, the same for the query and the texts whatever the device locale is, with every apostrophe
     * look-alike (‘ ’ ʻ ʼ ` ´) turned into '.
     */
    static String fold(CharSequence text) {
        final String lower = text.toString().toLowerCase(Locale.ROOT);
        StringBuilder folded = null;
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            if (c == '\u2018' || c == '\u2019' || c == '\u02BB' || c == '\u02BC' || c == '`' || c == '\u00B4') {
                if (folded == null) {
                    folded = new StringBuilder(lower);
                }
                folded.setCharAt(i, '\'');
            }
        }
        return folded != null ? folded.toString() : lower;
    }

    public static <T extends Entry> ArrayList<T> filter(List<T> entries, String rawQuery) {
        final String query = normalizeQuery(rawQuery);
        if (query.isEmpty()) {
            return new ArrayList<>(entries);
        }
        final int count = entries.size();
        final boolean[] matches = new boolean[count];
        final HashSet<Integer> sectionsWithMatches = new HashSet<>();
        for (int i = 0; i < count; i++) {
            final T entry = entries.get(i);
            matches[i] = entry.matches(query);
            if (matches[i]) {
                sectionsWithMatches.add(entry.getSectionId());
            }
        }

        final HashSet<Integer> sectionsKeptWhole = new HashSet<>();
        final boolean[] keep = new boolean[count];
        for (int i = 0; i < count; i++) {
            final T entry = entries.get(i);
            final int section = entry.getSectionId();
            if (entry.isHeader()) {
                if (matches[i]) {
                    sectionsKeptWhole.add(section);
                    keep[i] = true;
                }
                if (sectionsWithMatches.contains(section)) {
                    keep[i] = true;
                }
            } else if (matches[i]) {
                keep[i] = true;
                if (entry.isNotice()) {
                    // The row the notice explains.
                    if (i > 0 && !entries.get(i - 1).isNotice()) {
                        keep[i - 1] = true;
                    }
                } else if (i + 1 < count && entries.get(i + 1).isNotice()) {
                    // The notice that explains this row.
                    keep[i + 1] = true;
                }
            } else if (sectionsKeptWhole.contains(section)) {
                keep[i] = true;
            }
        }

        final ArrayList<T> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                result.add(entries.get(i));
            }
        }
        return result;
    }
}

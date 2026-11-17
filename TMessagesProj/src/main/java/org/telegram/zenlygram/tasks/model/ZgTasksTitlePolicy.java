/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one title and assignee rule of Tasks, shared by the UI preflight and the store (iOS TodoStorage.validatedTitle /
 * validatedAssignee, tasksValidateTitle, tasksAcceptsDraftEdit). Pure Java: no Android classes, so it runs in JVM tests.
 *
 * <ul>
 *   <li>Trim: Unicode white space and line breaks at both ends (Swift whitespacesAndNewlines: the Z* categories,
 *       U+0009..U+000D and U+0085).</li>
 *   <li>Visible: at least one code point that is not a control, format, combining mark, separator or lone
 *       surrogate. "​́" is empty.</li>
 *   <li>Length: at most {@link #TASK_LIMIT} (task) or {@link #FOLDER_LIMIT} (list) user-perceived characters (grapheme
 *       clusters, so "é" written as e + U+0301 counts once) and at most {@link #MAX_TITLE_UTF8_BYTES} UTF-8 bytes.</li>
 * </ul>
 */
public final class ZgTasksTitlePolicy {

    public static final int TASK_LIMIT = 200;
    public static final int FOLDER_LIMIT = 100;
    public static final int MAX_TITLE_UTF8_BYTES = 8192;
    public static final int ASSIGNEE_NAME_LIMIT = 256;
    public static final int MAX_ASSIGNEE_UTF8_BYTES = 4096;

    /** Marks a title cut from a longer text; one character, 3 bytes in UTF-8. */
    public static final String ELLIPSIS = "\u2026";
    private static final int ELLIPSIS_UTF8_BYTES = 3;
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\t\\n\\x0B\\f\\r\\u0085\\u2028\\u2029]+");
    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");

    private ZgTasksTitlePolicy() {
    }

    public enum Kind {
        VALID,
        /** Nothing visible: empty, white space, or only control / format / combining characters. */
        EMPTY,
        /** More than the limit after trimming. */
        TOO_LONG
    }

    public static final class Result {
        public final Kind kind;
        /** The trimmed title, only for {@link Kind#VALID}. */
        public final String title;

        Result(Kind kind, String title) {
            this.kind = kind;
            this.title = title;
        }
    }

    /** Classifies a title for the message the UI shows; never accepts a title the store would refuse. */
    public static Result validate(CharSequence raw, int limit) {
        final String valid = validatedTitle(raw, limit);
        if (valid != null) {
            return new Result(Kind.VALID, valid);
        }
        final String trimmed = trim(raw);
        if (graphemeCount(trimmed) > limit || utf8Length(trimmed) > MAX_TITLE_UTF8_BYTES) {
            return new Result(Kind.TOO_LONG, null);
        }
        return new Result(Kind.EMPTY, null);
    }

    /** The trimmed title, or null when it is empty or too long. */
    public static String validatedTitle(CharSequence raw, int limit) {
        if (raw == null) {
            return null;
        }
        final String trimmed = trim(raw);
        if (utf8Length(trimmed) > MAX_TITLE_UTF8_BYTES || graphemeCount(trimmed) > limit || !containsVisible(trimmed)) {
            return null;
        }
        return trimmed;
    }

    /** The checked assignee (trimmed name), or null: the id must be positive and the name visible and not too long. */
    public static ZgTodoAssignee validatedAssignee(long peerId, CharSequence displayName) {
        if (peerId <= 0 || displayName == null) {
            return null;
        }
        final String trimmed = trim(displayName);
        if (utf8Length(trimmed) > MAX_ASSIGNEE_UTF8_BYTES || graphemeCount(trimmed) > ASSIGNEE_NAME_LIMIT || !containsVisible(trimmed)) {
            return null;
        }
        return new ZgTodoAssignee(peerId, trimmed);
    }

    /**
     * Whether a typing or paste edit of the task title is let through: growth past {@link #TASK_LIMIT} characters is
     * refused, any edit that shortens the text is allowed (so an over-long text can still be fixed). Submission
     * validates the complete title again (dictation and other inputs bypass this).
     */
    public static boolean acceptsDraftEdit(CharSequence current, CharSequence updated) {
        final int updatedCount = graphemeCount(updated);
        return updatedCount <= TASK_LIMIT || updatedCount < graphemeCount(current);
    }

    /**
     * A task title made from any text (Android "Add to Tasks": a message's text or caption): line breaks become spaces,
     * runs of spaces collapse to one and the ends are trimmed. A text over {@link #TASK_LIMIT} characters or
     * {@link #MAX_TITLE_UTF8_BYTES} bytes keeps the whole characters (grapheme clusters) that fit with room for "…",
     * which then ends it. Null when nothing visible is left or when not even the first character fits (one cluster of
     * combining marks or a long emoji sequence can be thousands of bytes). One pass over the text, so any message is
     * cheap on the UI thread.
     */
    public static String titleFromText(CharSequence text) {
        if (text == null) {
            return null;
        }
        final String oneLine = trim(SPACE_RUNS.matcher(LINE_BREAKS.matcher(text).replaceAll(" ")).replaceAll(" "));
        if (!containsVisible(oneLine)) {
            return null;
        }
        if (utf8Length(oneLine) <= MAX_TITLE_UTF8_BYTES && graphemeCount(oneLine) <= TASK_LIMIT) {
            return validatedTitle(oneLine, TASK_LIMIT);
        }
        final int maxClusters = TASK_LIMIT - 1;
        final int maxBytes = MAX_TITLE_UTF8_BYTES - ELLIPSIS_UTF8_BYTES;
        final BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(oneLine);
        int clusters = 0;
        int bytes = 0;
        int end = 0;
        int start = it.first();
        for (int next = it.next(); next != BreakIterator.DONE; start = next, next = it.next()) {
            final int clusterBytes = utf8Length(oneLine, start, next);
            if (clusters >= maxClusters || bytes + clusterBytes > maxBytes) {
                break;
            }
            clusters++;
            bytes += clusterBytes;
            end = next;
        }
        if (end == 0) {
            return null;
        }
        final String head = trim(oneLine.substring(0, end));
        if (!containsVisible(head)) {
            return null;
        }
        return validatedTitle(head + ELLIPSIS, TASK_LIMIT);
    }

    /** At most {@code limit} grapheme clusters of {@code text}, cut at a cluster boundary. */
    public static String clampGraphemes(String text, int limit) {
        if (text == null) {
            return null;
        }
        final BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(text);
        int count = 0;
        int end = it.first();
        while (true) {
            final int next = it.next();
            if (next == BreakIterator.DONE) {
                return text;
            }
            if (++count > limit) {
                return text.substring(0, end);
            }
            end = next;
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Removes white space and line breaks at both ends (Swift trimmingCharacters(in: .whitespacesAndNewlines)). */
    public static String trim(CharSequence raw) {
        if (raw == null) {
            return "";
        }
        final String s = raw.toString();
        int start = 0;
        int end = s.length();
        while (start < end) {
            final int cp = s.codePointAt(start);
            if (!isWhitespaceOrNewline(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            final int cp = s.codePointBefore(end);
            if (!isWhitespaceOrNewline(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    static boolean isWhitespaceOrNewline(int cp) {
        return (cp >= 0x09 && cp <= 0x0D) || cp == 0x85 || Character.isSpaceChar(cp);
    }

    /** True when at least one code point would show something. */
    public static boolean containsVisible(CharSequence text) {
        if (text == null) {
            return false;
        }
        final String s = text.toString();
        for (int i = 0; i < s.length(); ) {
            final int cp = s.codePointAt(i);
            switch (Character.getType(cp)) {
                case Character.CONTROL:
                case Character.FORMAT:
                case Character.NON_SPACING_MARK:
                case Character.COMBINING_SPACING_MARK:
                case Character.ENCLOSING_MARK:
                case Character.SPACE_SEPARATOR:
                case Character.LINE_SEPARATOR:
                case Character.PARAGRAPH_SEPARATOR:
                case Character.SURROGATE:
                    break;
                default:
                    return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** User-perceived characters (grapheme clusters, as Swift String.count). */
    public static int graphemeCount(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        final BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(text.toString());
        int count = 0;
        it.first();
        while (it.next() != BreakIterator.DONE) {
            count++;
        }
        return count;
    }

    /** UTF-8 length; a lone surrogate counts as the 3-byte replacement character it becomes. */
    public static int utf8Length(CharSequence text) {
        return text == null ? 0 : utf8Length(text, 0, text.length());
    }

    /** UTF-8 length of {@code text[start, end)}, counted as {@link #utf8Length(CharSequence)} does. */
    public static int utf8Length(CharSequence text, int start, int end) {
        if (text == null) {
            return 0;
        }
        int bytes = 0;
        final int n = Math.min(end, text.length());
        for (int i = Math.max(0, start); i < n; i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}

package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Markdown subset of the consent texts (contract §5.5, design 50 §5.3), pure and host-tested. It parses the
 * CANONICAL text (what is hashed), so what is hashed is what is shown: {@code # } / {@code ## } headings, paragraphs
 * (lines between blank lines, kept joined by LF), one bullet per {@code - } line; inline {@code **bold**} and
 * {@code [text](https://…)} links. A link with any other target shows its text only; an unpaired {@code **} or
 * anything else outside the subset is plain text, and no HTML is interpreted. {@link #split} finds the final
 * {@code ##} section, whose first paragraph is the age statement (the label of the required checkbox).
 */
public final class ConsentMarkdown {
    private ConsentMarkdown() {}

    /** A run of text with one style. */
    public static final class Inline {
        public final String text;
        public final boolean bold;
        /** Set only for an {@code https://} target. */
        public final String url;

        public Inline(String text, boolean bold, String url) {
            this.text = text;
            this.bold = bold;
            this.url = url;
        }
    }

    /** One block of the text. */
    public static final class Block {
        public enum Kind { HEADING, PARAGRAPH, BULLET }

        public final Kind kind;
        /** 1 or 2 for a heading, 0 otherwise. */
        public final int level;
        public final List<Inline> inlines;

        Block(Kind kind, int level, List<Inline> inlines) {
            this.kind = kind;
            this.level = level;
            this.inlines = Collections.unmodifiableList(inlines);
        }

        /** The text of the block without markup. */
        public String plainText() {
            StringBuilder out = new StringBuilder();
            for (Inline inline : inlines) out.append(inline.text);
            return out.toString();
        }
    }

    /** The parts of a parsed text the wall lays out separately. */
    public static final class Split {
        /** The scrolling body: every block before {@link #finalHeading} (all blocks when there is none). */
        public final List<Block> body;
        /** The last level-2 heading, or null. */
        public final Block finalHeading;
        /** The first paragraph after {@link #finalHeading} (before any later heading), or null. */
        public final Block ageStatement;
        /** Every other block after {@link #finalHeading}. */
        public final List<Block> tail;

        Split(List<Block> body, Block finalHeading, Block ageStatement, List<Block> tail) {
            this.body = Collections.unmodifiableList(body);
            this.finalHeading = finalHeading;
            this.ageStatement = ageStatement;
            this.tail = Collections.unmodifiableList(tail);
        }
    }

    /** The blocks of a canonical consent text. Never fails: anything outside the subset is plain text. */
    public static List<Block> parse(String canonical) {
        ArrayList<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = null;
        for (String line : canonical.split("\n", -1)) {
            boolean blank = isBlank(line);
            Block block = null;
            if (!blank) {
                if (line.startsWith("## ")) {
                    block = new Block(Block.Kind.HEADING, 2, inline(line.substring(3)));
                } else if (line.startsWith("# ")) {
                    block = new Block(Block.Kind.HEADING, 1, inline(line.substring(2)));
                } else if (line.startsWith("- ")) {
                    block = new Block(Block.Kind.BULLET, 0, inline(line.substring(2)));
                }
            }
            if (blank || block != null) {
                if (paragraph != null) {
                    blocks.add(new Block(Block.Kind.PARAGRAPH, 0, inline(paragraph.toString())));
                    paragraph = null;
                }
                if (block != null) blocks.add(block);
            } else if (paragraph == null) {
                paragraph = new StringBuilder(line);
            } else {
                paragraph.append('\n').append(line);
            }
        }
        if (paragraph != null) blocks.add(new Block(Block.Kind.PARAGRAPH, 0, inline(paragraph.toString())));
        return Collections.unmodifiableList(blocks);
    }

    /** Splits parsed blocks around the final level-2 heading (design 50 §5.3). */
    public static Split split(List<Block> blocks) {
        int last = -1;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.kind == Block.Kind.HEADING && block.level == 2) last = i;
        }
        if (last < 0) return new Split(new ArrayList<>(blocks), null, null, new ArrayList<Block>());
        int age = -1;
        for (int i = last + 1; i < blocks.size() && blocks.get(i).kind != Block.Kind.HEADING; i++) {
            if (blocks.get(i).kind == Block.Kind.PARAGRAPH) {
                age = i;
                break;
            }
        }
        ArrayList<Block> tail = new ArrayList<>();
        for (int i = last + 1; i < blocks.size(); i++) {
            if (i != age) tail.add(blocks.get(i));
        }
        return new Split(new ArrayList<>(blocks.subList(0, last)), blocks.get(last), age < 0 ? null : blocks.get(age), tail);
    }

    private static boolean isBlank(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    /**
     * Inline markup of one block's text: {@code **} toggles bold when a closing {@code **} follows (an unpaired one is
     * literal); {@code [text](target)} with a non-empty text without '[', ']' or LF and a non-empty target without
     * '(', ')' or White_Space is a link run (its URL set only for {@code https://}).
     */
    static List<Inline> inline(String text) {
        ArrayList<Inline> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean bold = false;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '*' && text.startsWith("*", index + 1) && (bold || text.indexOf("**", index + 2) >= 0)) {
                flush(runs, current, bold);
                bold = !bold;
                index += 2;
                continue;
            }
            if (c == '[') {
                int[] link = link(text, index);
                if (link != null) {
                    flush(runs, current, bold);
                    String target = text.substring(link[1], link[2]);
                    runs.add(new Inline(text.substring(index + 1, link[0]), bold, target.startsWith("https://") ? target : null));
                    index = link[2] + 1;
                    continue;
                }
            }
            current.append(c);
            index++;
        }
        flush(runs, current, bold);
        return runs;
    }

    private static void flush(List<Inline> runs, StringBuilder current, boolean bold) {
        if (current.length() > 0) {
            runs.add(new Inline(current.toString(), bold, null));
            current.setLength(0);
        }
    }

    /** {textEnd, targetStart, targetEnd} of a link starting at {@code start} ('['), or null. */
    private static int[] link(String text, int start) {
        int index = start + 1;
        while (index < text.length() && text.charAt(index) != ']') {
            char c = text.charAt(index);
            if (c == '[' || c == '\n') return null;
            index++;
        }
        int textEnd = index;
        if (textEnd == start + 1 || textEnd + 1 >= text.length() || text.charAt(textEnd + 1) != '(') return null;
        index = textEnd + 2;
        while (index < text.length() && text.charAt(index) != ')') {
            char c = text.charAt(index);
            if (c == '(' || UnicodeText.isWhiteSpace(c)) return null;
            index++;
        }
        if (index >= text.length() || index == textEnd + 2) return null;
        return new int[] {textEnd, textEnd + 2, index};
    }
}

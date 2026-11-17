/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import org.telegram.messenger.zenlygram.customer.ConsentMarkdown;

import java.util.List;

/**
 * Shows the consent document (iOS SGConsentTextView). The text is parsed by the cloud client
 * ({@link ConsentMarkdown}: {@code #} / {@code ##} headings, paragraphs, {@code - } bullets, {@code **bold**} and
 * {@code [text](https://…)} links, nothing else), from the canonical text whose hash a grant records, so what is hashed
 * is what is shown; only the presentation is decided here. Links open outside the app (a system browser), never in a
 * screen that would land under the wall.
 */
final class ZgConsentMarkdown {

    private ZgConsentMarkdown() {
    }

    /** The whole document, the age statement included. */
    static CharSequence render(String canonical) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        ConsentMarkdown.Block previous = null;
        for (ConsentMarkdown.Block block : ConsentMarkdown.parse(canonical)) {
            if (previous != null) {
                // Bullets of one list sit on consecutive lines; every other block starts after a blank line.
                final boolean sameList = previous.kind == ConsentMarkdown.Block.Kind.BULLET && block.kind == ConsentMarkdown.Block.Kind.BULLET;
                out.append(sameList ? "\n" : "\n\n");
            }
            final int start = out.length();
            if (block.kind == ConsentMarkdown.Block.Kind.BULLET) {
                out.append("•  ");
            }
            appendInline(out, block.inlines);
            if (block.kind == ConsentMarkdown.Block.Kind.HEADING) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(block.level == 1 ? 1.3f : 1.12f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            previous = block;
        }
        return out;
    }

    private static void appendInline(SpannableStringBuilder out, List<ConsentMarkdown.Inline> inlines) {
        for (ConsentMarkdown.Inline run : inlines) {
            final int start = out.length();
            out.append(run.text);
            if (start == out.length()) {
                continue;
            }
            if (run.bold) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (run.url != null) {
                out.setSpan(new URLSpan(run.url), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** The age statement of the text (the first paragraph of its last section): the label of the wall's checkbox. */
    static String ageStatement(String canonical) {
        final ConsentMarkdown.Block statement = ConsentMarkdown.split(ConsentMarkdown.parse(canonical)).ageStatement;
        return statement == null ? null : statement.plainText();
    }
}

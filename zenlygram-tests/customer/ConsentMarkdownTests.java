package org.telegram.messenger.zenlygram.customer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-JVM checks of the consent Markdown subset (contract §5.5, design 50 §5.3): headings, paragraphs and bullets,
 * bold, https-only links, unpaired markup and HTML as plain text, the final-section split, and on the real v1 texts
 * (filled with the shared v1-uz vector's operator values) that the age statement is found and that the rendered blocks
 * lose no text — what is hashed is what is shown. Run with run-tests.sh, which passes the Android repository root.
 */
public final class ConsentMarkdownTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ConsentMarkdownTests <android repository root>");
        Path root = Paths.get(args[0]);
        blocks();
        inlines();
        splits();
        realTexts(root);
        System.out.println("Consent Markdown: " + checks + " checks passed");
    }

    private static void blocks() {
        List<ConsentMarkdown.Block> blocks = ConsentMarkdown.parse("# Title\n\nFirst line\nsecond line\n## Section\n- one\n- **two**\n\n"
                + "### deeper\n\n#nospace\n-not a bullet\n\n\t \nlast");
        check(blocks.size() == 8, "eight blocks");
        expect(blocks.get(0), ConsentMarkdown.Block.Kind.HEADING, 1, "Title");
        expect(blocks.get(1), ConsentMarkdown.Block.Kind.PARAGRAPH, 0, "First line\nsecond line");
        expect(blocks.get(2), ConsentMarkdown.Block.Kind.HEADING, 2, "Section");
        expect(blocks.get(3), ConsentMarkdown.Block.Kind.BULLET, 0, "one");
        expect(blocks.get(4), ConsentMarkdown.Block.Kind.BULLET, 0, "two");
        check(blocks.get(4).inlines.get(0).bold, "bold inside a bullet");
        expect(blocks.get(5), ConsentMarkdown.Block.Kind.PARAGRAPH, 0, "### deeper");
        expect(blocks.get(6), ConsentMarkdown.Block.Kind.PARAGRAPH, 0, "#nospace\n-not a bullet");
        expect(blocks.get(7), ConsentMarkdown.Block.Kind.PARAGRAPH, 0, "last");
        check(ConsentMarkdown.parse("").isEmpty() && ConsentMarkdown.parse("\n\n").isEmpty(), "no blocks in an empty text");
        try {
            blocks.add(blocks.get(0));
            check(false, "parsed blocks are immutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "parsed blocks are immutable");
        }
        try {
            blocks.get(0).inlines.clear();
            check(false, "inline runs are immutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "inline runs are immutable");
        }
    }

    private static void inlines() {
        List<ConsentMarkdown.Inline> runs = ConsentMarkdown.inline("a **b** c **d");
        check(runs.size() == 3 && "a ".equals(runs.get(0).text) && !runs.get(0).bold && "b".equals(runs.get(1).text) && runs.get(1).bold
                && " c **d".equals(runs.get(2).text) && !runs.get(2).bold, "paired ** toggles bold; an unpaired one is literal");
        runs = ConsentMarkdown.inline("see [the page](https://zenlygram.org/account/delete) now");
        check(runs.size() == 3 && "the page".equals(runs.get(1).text) && "https://zenlygram.org/account/delete".equals(runs.get(1).url)
                && runs.get(0).url == null && " now".equals(runs.get(2).text), "an https link");
        runs = ConsentMarkdown.inline("[plain](http://example.org) [js](javascript:alert(1)) [mail](mailto:a@b.org)");
        check("plain [js](javascript:alert(1)) mail".equals(text(runs)), "non-https targets show their text only (or stay literal)");
        for (ConsentMarkdown.Inline run : runs) check(run.url == null, "no URL for a non-https target");
        runs = ConsentMarkdown.inline("**bold [link](https://a.org/x) tail**");
        check(runs.size() == 3 && runs.get(0).bold && runs.get(1).bold && "https://a.org/x".equals(runs.get(1).url) && runs.get(2).bold,
                "a link inside bold is bold");
        for (String literal : new String[] {"[](https://a.org)", "[a](https://a.org b)", "[a]", "[a](https://a.org", "[a] (https://a.org)",
                "[a](https://(a).org)", "[a]()"}) {
            runs = ConsentMarkdown.inline(literal);
            check(literal.equals(text(runs)) && runs.size() == 1 && runs.get(0).url == null, "not a link: " + literal);
        }
        runs = ConsentMarkdown.inline("[a[b](https://a.org)");
        check(runs.size() == 2 && "[a".equals(runs.get(0).text) && "b".equals(runs.get(1).text) && runs.get(1).url != null,
                "the innermost bracket starts the link");
        check("<b>x</b> &amp; <script>".equals(text(ConsentMarkdown.inline("<b>x</b> &amp; <script>"))), "no HTML is interpreted");
        check(ConsentMarkdown.inline("").isEmpty() && ConsentMarkdown.inline("****").isEmpty(), "an empty bold pair renders nothing");
    }

    private static void splits() {
        ConsentMarkdown.Split split = ConsentMarkdown.split(ConsentMarkdown.parse("# T\n\nbody\n\n## Final\n\n- item\n\nI am 18.\n\nmore\n\n- last"));
        check(split.body.size() == 2 && "T".equals(split.body.get(0).plainText()) && "body".equals(split.body.get(1).plainText()), "body");
        check("Final".equals(split.finalHeading.plainText()), "the last ## heading");
        check("I am 18.".equals(split.ageStatement.plainText()), "the first paragraph of the final section is the age statement");
        check(split.tail.size() == 3 && "item".equals(split.tail.get(0).plainText()) && "more".equals(split.tail.get(1).plainText())
                && "last".equals(split.tail.get(2).plainText()), "every other later block is the tail");
        split = ConsentMarkdown.split(ConsentMarkdown.parse("## One\n\nx\n\n## Two\n\n- only a bullet"));
        check("Two".equals(split.finalHeading.plainText()) && split.ageStatement == null && split.tail.size() == 1 && split.body.size() == 2,
                "a final section without a paragraph has no age statement");
        split = ConsentMarkdown.split(ConsentMarkdown.parse("## Final\n\n# Top again\n\npara"));
        check(split.ageStatement == null && split.tail.size() == 2, "a later heading ends the search for the age statement");
        split = ConsentMarkdown.split(ConsentMarkdown.parse("# Only\n\ntext"));
        check(split.finalHeading == null && split.ageStatement == null && split.body.size() == 2 && split.tail.isEmpty(), "no ## heading");
    }

    /** The real v1 texts, filled with the shared v1-uz vector's operator values. */
    private static void realTexts(Path root) throws Exception {
        Path assets = root.resolve("TMessagesProj/src/main/assets/zenlygram/consent");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("OPERATOR_NAME", "«Zenlygram» MChJ");
        values.put("OPERATOR_STIR", "123456789");
        values.put("RESPONSIBLE_PERSON", " Ali Valiyev ");
        values.put("CONTACT_EMAIL", "privacy@zenlygram.org");
        ConsentManifest manifest = ConsentManifest.fromTree(MiniJson.parseFile(assets.resolve("manifest.json")));
        String[][] expected = {
                {"uz", "8. Yoshni tasdiqlash", "Menga 18 yosh toʻlgan yoki ota-onam yoxud vasiyim bunga rozilik bergan."},
                {"ru", "8. Подтверждение возраста",
                        "Мне исполнилось 18 лет, или "
                                + "мой родитель либо опекун "
                                + "дал на это согласие."},
                {"en", "8. Age confirmation", "I am 18 or older, or my parent or guardian has agreed to this."}};
        for (String[] text : expected) {
            String locale = text[0];
            String template = ConsentText.decodeUtf8(Files.readAllBytes(assets.resolve(manifest.current().text(locale).file)));
            ConsentText.Shown shown = ConsentText.shown(manifest.current(), locale, template, values, manifest.placeholders);
            List<ConsentMarkdown.Block> blocks = ConsentMarkdown.parse(shown.canonical);
            ConsentMarkdown.Split split = ConsentMarkdown.split(blocks);
            check(text[1].equals(split.finalHeading.plainText()), locale + ": the final heading");
            check(text[2].equals(split.ageStatement.plainText()), locale + ": the age statement");
            check(split.tail.isEmpty(), locale + ": nothing after the age statement");
            check(split.body.get(0).kind == ConsentMarkdown.Block.Kind.HEADING && split.body.get(0).level == 1, locale + ": one title first");
            int headings = 0;
            List<String> urls = new ArrayList<>();
            for (ConsentMarkdown.Block block : blocks) {
                if (block.kind == ConsentMarkdown.Block.Kind.HEADING && block.level == 2) headings++;
                for (ConsentMarkdown.Inline inline : block.inlines) {
                    if (inline.url != null) urls.add(inline.url);
                    check(!inline.text.contains("**") && !inline.text.contains("]("), locale + ": no markup left in a run");
                }
            }
            check(headings == 8, locale + ": eight numbered sections");
            check(urls.contains("https://zenlygram.org/account/delete") && urls.contains("https://zenlygram.org/privacy-policy.html"),
                    locale + ": the deletion page and the privacy policy are links");
            check(plain(blocks).equals(stripped(shown.canonical)), locale + ": the rendered blocks keep every character of the hashed text");
            check(shown.canonical.contains("«Zenlygram» MChJ") && shown.canonical.contains("Ali Valiyev.")
                    && !shown.canonical.contains("{{"), locale + ": the operator values are filled and trimmed");
        }
    }

    /** The blocks' plain text, one block per line. */
    private static String plain(List<ConsentMarkdown.Block> blocks) {
        StringBuilder out = new StringBuilder();
        for (ConsentMarkdown.Block block : blocks) {
            if (out.length() > 0) out.append('\n');
            out.append(block.plainText());
        }
        return out.toString();
    }

    private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]\\n]+)\\]\\(([^()\\s]+)\\)");

    /** The canonical text with its markup removed by regex (an independent reading of the subset), blank lines dropped. */
    private static String stripped(String canonical) {
        StringBuilder out = new StringBuilder();
        for (String line : canonical.split("\n", -1)) {
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("## ")) {
                line = line.substring(3);
            } else if (line.startsWith("# ")) {
                line = line.substring(2);
            } else if (line.startsWith("- ")) {
                line = line.substring(2);
            }
            line = line.replace("**", "");
            Matcher matcher = LINK.matcher(line);
            line = matcher.replaceAll("$1");
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static String text(List<ConsentMarkdown.Inline> runs) {
        StringBuilder out = new StringBuilder();
        for (ConsentMarkdown.Inline run : runs) out.append(run.text);
        return out.toString();
    }

    private static void expect(ConsentMarkdown.Block block, ConsentMarkdown.Block.Kind kind, int level, String text) {
        check(block.kind == kind && block.level == level && block.plainText().equals(text), kind + " " + level + " [" + text + "]");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

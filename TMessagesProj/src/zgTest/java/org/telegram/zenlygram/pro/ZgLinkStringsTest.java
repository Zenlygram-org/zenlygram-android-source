package org.telegram.zenlygram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The strings of the account link exist in English, Russian and Uzbek, with the same placeholders, and the Uzbek ones
 * use the letters ʻ (U+02BB) and ʼ (U+02BC), never an apostrophe. Reads the source tree (the module directory is the
 * tests' working directory).
 */
public class ZgLinkStringsTest {

    private static final String[] LANGUAGES = {"values", "values-ru", "values-uz"};
    private static final Pattern STRING = Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd]");
    private static final Pattern USED = Pattern.compile("R\\.string\\.([A-Za-z0-9_]+)");

    private static File module() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "src/main/res/values/strings_zenlygram_link.xml").isFile()) {
                return dir;
            }
            final File nested = new File(dir, "TMessagesProj");
            if (new File(nested, "src/main/res/values/strings_zenlygram_link.xml").isFile()) {
                return nested;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("the module directory was not found from " + new File("").getAbsolutePath());
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> strings(File file) throws Exception {
        final Map<String, String> result = new LinkedHashMap<>();
        final Matcher matcher = STRING.matcher(read(file));
        while (matcher.find()) {
            assertTrue("defined twice in " + file.getName() + ": " + matcher.group(1), result.put(matcher.group(1), matcher.group(2)) == null);
        }
        return result;
    }

    /** Every Zenlygram string of a language: the files that follow the in-app language. */
    private static Map<String, String> zenlygramStrings(String language) throws Exception {
        final Map<String, String> result = new LinkedHashMap<>();
        final File[] files = new File(module(), "src/main/res/" + language).listFiles();
        assertNotNull(files);
        for (File file : files) {
            if (file.getName().startsWith("strings_zenlygram") && file.getName().endsWith(".xml")) {
                result.putAll(strings(file));
            }
        }
        return result;
    }

    private static List<String> placeholders(String value) {
        final List<String> result = new ArrayList<>();
        final Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    @Test
    public void theLinkFileHasTheSameStringsInThreeLanguages() throws Exception {
        final Map<String, String> english = strings(new File(module(), "src/main/res/values/strings_zenlygram_link.xml"));
        assertFalse(english.isEmpty());
        for (String language : LANGUAGES) {
            final Map<String, String> translated = strings(new File(module(), "src/main/res/" + language + "/strings_zenlygram_link.xml"));
            assertEquals(language, new ArrayList<>(english.keySet()), new ArrayList<>(translated.keySet()));
            for (Map.Entry<String, String> entry : translated.entrySet()) {
                final String name = entry.getKey();
                final String value = entry.getValue();
                assertTrue(name + " has the file's prefix", name.startsWith("ZGL_"));
                // A name ending in a plural form would be read as one form of a plural (the build's string task).
                assertFalse(name, name.matches(".*_(zero|one|two|few|many|other)"));
                assertFalse(language + " " + name + " is empty", value.trim().isEmpty());
                assertEquals(language + " " + name, new TreeSet<>(placeholders(english.get(name))), new TreeSet<>(placeholders(value)));
                assertFalse(language + " " + name + " has an unescaped apostrophe", Pattern.compile("(?<!\\\\)'").matcher(value).find());
            }
        }
    }

    @Test
    public void uzbekUsesItsOwnLettersNotApostrophes() throws Exception {
        for (Map.Entry<String, String> entry : strings(new File(module(), "src/main/res/values-uz/strings_zenlygram_link.xml")).entrySet()) {
            final String value = entry.getValue();
            for (char forbidden : new char[]{'\'', '’', '‘', '`', 'ʹ'}) {
                assertTrue(entry.getKey() + " contains U+" + Integer.toHexString(forbidden), value.indexOf(forbidden) < 0);
            }
        }
    }

    @Test
    public void everyStringTheLinkScreenReadsExistsInThreeLanguages() throws Exception {
        final TreeSet<String> used = new TreeSet<>();
        final File sources = new File(module(), "src/main/java/org/telegram/zenlygram/pro");
        for (String name : new String[]{"ZgLinkAccountActivity.java", "ZgLinkCells.java"}) {
            final Matcher matcher = USED.matcher(read(new File(sources, name)));
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
        }
        assertTrue("the screen reads its strings by id", used.size() >= 20);
        // Telegram's own strings (Cancel) come translated with Telegram's language packs.
        final Map<String, String> telegram = strings(new File(module(), "src/main/res/values/strings.xml"));
        final Map<String, String> english = zenlygramStrings("values");
        for (String language : LANGUAGES) {
            final Map<String, String> zenlygram = zenlygramStrings(language);
            for (String name : used) {
                if (telegram.containsKey(name)) {
                    continue;
                }
                assertTrue(language + " lacks " + name, zenlygram.containsKey(name));
                assertEquals(language + " " + name, new TreeSet<>(placeholders(english.get(name))), new TreeSet<>(placeholders(zenlygram.get(name))));
            }
        }
    }
}

package org.telegram.zenlygram.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.zenlygram.customer.ConsentManifest;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.zenlygram.customer.ZgConsentBundle;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bundled consent tree verifies against its manifest (the hashes the server and iOS use), and the one renderer the
 * app has — the cloud client's {@link ConsentText}, reached through the app's verified bundle — gives the hashes the
 * app's earlier renderer (ZgConsentText, removed) and the owner's iPhone gave for the same operator values. A consent
 * recorded by an earlier build therefore still matches the text this build shows.
 */
public class ZgConsentTextTest {

    private static File root() {
        // Unit tests run with the module (TMessagesProj) as the working directory.
        File dir = new File("src/main/assets/zenlygram/consent");
        if (!dir.isDirectory()) {
            dir = new File("TMessagesProj/src/main/assets/zenlygram/consent");
        }
        return dir;
    }

    private static ZgConsentBundle.Source files(File root) {
        return path -> {
            try {
                return Files.readAllBytes(new File(root, path).toPath());
            } catch (Exception e) {
                return null;
            }
        };
    }

    private static Map<String, String> values(String name, String stir, String person, String email) {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("OPERATOR_NAME", name);
        values.put("OPERATOR_STIR", stir);
        values.put("RESPONSIBLE_PERSON", person);
        values.put("CONTACT_EMAIL", email);
        return values;
    }

    /** The committed operator defaults (zenlygram-cloud.gradle, owner-confirmed 2026-11-13). */
    private static Map<String, String> production() {
        return values("\"ZENLYGRAM\" MCHJ", "313454883", "Abdurahmonov Abdurashid", "info@zenlygram.com");
    }

    @Test
    public void bundledTreeVerifies() {
        final ZgConsentBundle bundle = ZgConsentBundle.load(files(root()));
        assertNotNull(bundle);
        assertEquals(2, bundle.manifest.currentVersion);
        assertEquals(1, bundle.manifest.minValidVersion);
        final ConsentText.Shown shown = bundle.shown(1, "uz", values("Test MChJ", "123456789", "A. Test", "privacy@example.org"));
        assertNotNull(shown);
        assertTrue(shown.canonical.contains("Test MChJ"));
        assertEquals(64, shown.sha256.length());
        assertEquals(Arrays.asList("link", "profile", "statistics", "tasks_sync"), shown.scopes);
        // Unfilled render == the manifest's template hash.
        assertEquals("384fe0cb77d998b29fa1e6843feea647b80cd69b1af35bf4d146b6be2d07df65",
            bundle.renderedSha256(1, "uz", Collections.<String, String>emptyMap()));
        assertNotNull("version 2 is bundled now", bundle.shown(2, "uz", production()));
        assertNull("a version that is not bundled has no text", bundle.shown(3, "uz", production()));
        assertNull("a language that is not bundled has no text", bundle.shown(1, "de", production()));
    }

    /**
     * en: the text_sha256 the owner's iPhone (iOS build 6643) recorded on consent; uz / ru: the same canonical form.
     * These are also the hashes the app's earlier renderer gave (this test asserted them over ZgConsentText until the
     * duplicate was removed), i.e. what a consent recorded before the cloud client's store holds.
     */
    @Test
    public void productionOperatorValuesGiveTheIosHashes() {
        final ZgConsentBundle bundle = ZgConsentBundle.load(files(root()));
        assertNotNull(bundle);
        assertEquals("4f0ef5fbacd9f7891696bf3286229d98cfb2b75df4a68c235e5d73d515f77b8d", bundle.renderedSha256(1, "en", production()));
        assertEquals("6745535fe9efc055e0aed852a12b972c0cf06dc2035cf22cdbf142486df7dddf", bundle.renderedSha256(1, "uz", production()));
        assertEquals("c10fcd4c52e127735555d9eeda705fe491728913328f9383067f758c48ecaf87", bundle.renderedSha256(1, "ru", production()));
    }

    /**
     * The client reads the operator values under its own BuildConfig names; the gradle file fills both sets from the
     * same properties. Were they to differ, the wall would show one text and the client hash another.
     */
    @Test
    public void bothBuildConfigNamesCarryTheSameOperatorValues() {
        assertEquals(BuildConfig.ZG_CONSENT_OPERATOR_NAME, BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_NAME);
        assertEquals(BuildConfig.ZG_CONSENT_OPERATOR_STIR, BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_STIR);
        assertEquals(BuildConfig.ZG_CONSENT_OPERATOR_PERSON, BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_RESPONSIBLE_PERSON);
        assertEquals(BuildConfig.ZG_CONSENT_OPERATOR_EMAIL, BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_CONTACT_EMAIL);
        assertEquals("required", BuildConfig.ZENLYGRAM_CONSENT_MODE);
        // And they are the values the hashes above were made with, unless a build overrides them on purpose.
        final ZgConsentBundle bundle = ZgConsentBundle.load(files(root()));
        assertNotNull(bundle);
        final Map<String, String> build = values(BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_NAME, BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_STIR,
            BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_RESPONSIBLE_PERSON, BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_CONTACT_EMAIL);
        if (build.equals(production())) {
            for (String locale : ConsentManifest.LOCALES) {
                assertEquals(bundle.renderedSha256(1, locale, production()), bundle.renderedSha256(1, locale, build));
            }
        }
    }

    @Test
    public void tamperedTemplateFailsClosed() {
        final ZgConsentBundle.Source real = files(root());
        final ZgConsentBundle.Source tampered = path -> {
            final byte[] bytes = real.read(path);
            if (bytes != null && path.endsWith("ru.md")) {
                final byte[] copy = bytes.clone();
                copy[copy.length / 2] ^= 0x01;
                return copy;
            }
            return bytes;
        };
        assertNull(ZgConsentBundle.load(tampered));
        // A missing file or manifest fails closed the same way.
        assertNull(ZgConsentBundle.load(path -> path.endsWith("en.md") ? null : real.read(path)));
        assertNull(ZgConsentBundle.load(path -> path.equals("manifest.json") ? null : real.read(path)));
        assertNull(ZgConsentBundle.load(path -> path.equals("manifest.json") ? "{}".getBytes() : real.read(path)));
    }

    @Test
    public void refusedOperatorValueGivesNoText() {
        final ZgConsentBundle bundle = ZgConsentBundle.load(files(root()));
        assertNotNull(bundle);
        assertNull(bundle.shown(1, "uz", values("two\nlines", "1", "2", "3")));
        assertNull(bundle.renderedSha256(1, "uz", values("a{{b", "1", "2", "3")));
        // A missing value keeps its placeholder literally (local stacks): the text still renders, with another hash.
        final ConsentText.Shown partial = bundle.shown(1, "uz", values("", "1", "2", "3"));
        assertNotNull(partial);
        assertTrue(partial.canonical.contains("{{OPERATOR_NAME}}"));
        assertNotEquals(bundle.renderedSha256(1, "uz", production()), partial.sha256);
    }

    @Test
    public void canonicalForm() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("OPERATOR_NAME", "  Café  ");
        final String canonical = ConsentText.canonical("﻿\n  A {{OPERATOR_NAME}} {{OTHER}}  \r\nB\t \r\n\n", values,
            Collections.singletonList("OPERATOR_NAME"));
        assertEquals("A Café {{OTHER}}\nB", canonical);
        values.put("OPERATOR_NAME", "two\nlines");
        try {
            ConsentText.canonical("{{OPERATOR_NAME}}", values, Collections.singletonList("OPERATOR_NAME"));
            fail("expected refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("OPERATOR_NAME"));
        }
        assertNotEquals(ConsentText.sha256Hex("a"), ConsentText.sha256Hex("b"));
    }
}

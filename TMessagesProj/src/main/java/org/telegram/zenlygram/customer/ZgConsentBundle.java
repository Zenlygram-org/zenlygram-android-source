/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.telegram.messenger.zenlygram.customer.ConsentManifest;
import org.telegram.messenger.zenlygram.customer.ConsentText;

import java.util.HashMap;
import java.util.Map;

/**
 * The bundled consent texts, verified (iOS SGConsentTextRepository): {@code assets/zenlygram/consent/manifest.json} and
 * {@code v<N>/{uz,ru,en}.md}, a byte-identical copy of the server's and of the iOS bundle. Every template's
 * {@code template_sha256} (the canonical form with no values filled) is checked when the tree is loaded; one unreadable
 * file or one mismatch and there is no bundle, so consent is not active on this build (fail closed: no wall, no grant).
 *
 * <p>The cloud client renders and hashes the text a grant names ({@code ZenlyConsent.text}, {@code ConsentText}); this
 * class only adds the check it leaves to the build, with the client's own canonical form. Pure Java.
 */
public final class ZgConsentBundle {

    /** Reads a file of the consent tree by its relative path, or null. */
    public interface Source {
        byte[] read(String relativePath);
    }

    public final ConsentManifest manifest;
    /** Manifest file path ("v1/uz.md") → the strictly decoded template. */
    private final Map<String, String> templates;

    private ZgConsentBundle(ConsentManifest manifest, Map<String, String> templates) {
        this.manifest = manifest;
        this.templates = templates;
    }

    /** The verified bundle, or null (unreadable manifest, a missing or non-UTF-8 template, a hash mismatch). */
    public static ZgConsentBundle load(Source source) {
        try {
            final byte[] manifestBytes = source.read("manifest.json");
            final String manifestText = manifestBytes == null ? null : ConsentText.decodeUtf8(manifestBytes);
            final Object tree = ZgJson.parse(manifestText);
            final ConsentManifest manifest = tree == null ? null : ConsentManifest.fromTree(tree);
            if (manifest == null) {
                return null;
            }
            final HashMap<String, String> templates = new HashMap<>();
            for (ConsentManifest.Version version : manifest.versions()) {
                for (String locale : version.locales()) {
                    final ConsentManifest.Text text = version.text(locale);
                    final byte[] bytes = source.read(text.file);
                    final String template = bytes == null ? null : ConsentText.decodeUtf8(bytes);
                    if (template == null || !text.templateSha256.equals(ConsentText.templateSha256(template, manifest.placeholders))) {
                        return null;
                    }
                    templates.put(text.file, template);
                }
            }
            return new ZgConsentBundle(manifest, templates);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The text of {@code version} in {@code locale} filled with {@code operatorValues}, exactly as the client shows and
     * hashes it; null when this build does not bundle it or a value is refused.
     */
    public ConsentText.Shown shown(int version, String locale, Map<String, String> operatorValues) {
        final ConsentManifest.Version entry = manifest.version(version);
        final ConsentManifest.Text text = entry == null ? null : entry.text(locale);
        final String template = text == null ? null : templates.get(text.file);
        if (template == null) {
            return null;
        }
        try {
            return ConsentText.shown(entry, locale, template, operatorValues, manifest.placeholders);
        } catch (IllegalArgumentException refusedValue) {
            return null;
        }
    }

    /** This build's {@code text_sha256} for a grant of {@code version} in {@code locale}, or null (nothing to compare). */
    public String renderedSha256(int version, String locale, Map<String, String> operatorValues) {
        final ConsentText.Shown shown = shown(version, locale, operatorValues);
        return shown == null ? null : shown.sha256;
    }
}

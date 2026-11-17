/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import java.util.regex.Pattern;

/**
 * An app version of the update policy (docs/entitlement.md §3.9c): {@code ^[0-9]{1,5}(\.[0-9]{1,5}){1,3}$}, compared
 * component-wise as integers with missing components = 0 ({@code 12.9 == 12.9.0}, {@code 12.9.2 < 12.10.0}). Anything
 * else (a "-debug" suffix included) does not parse, and an unparsable installed version means "no decision".
 */
public final class ZgAppVersion implements Comparable<ZgAppVersion> {

    private static final Pattern PATTERN = Pattern.compile("^[0-9]{1,5}(\\.[0-9]{1,5}){1,3}$");

    public final String raw;
    private final int[] parts;

    private ZgAppVersion(String raw, int[] parts) {
        this.raw = raw;
        this.parts = parts;
    }

    public static ZgAppVersion parse(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            return null;
        }
        final String[] split = value.split("\\.");
        final int[] parts = new int[4];
        for (int i = 0; i < split.length; i++) {
            parts[i] = Integer.parseInt(split[i]);
        }
        return new ZgAppVersion(value, parts);
    }

    @Override
    public int compareTo(ZgAppVersion other) {
        for (int i = 0; i < 4; i++) {
            if (parts[i] != other.parts[i]) {
                return parts[i] < other.parts[i] ? -1 : 1;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return raw;
    }
}

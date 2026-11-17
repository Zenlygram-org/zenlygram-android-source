/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import java.io.ByteArrayOutputStream;

/**
 * Strict base64 / base64url for the Zenlygram server contract (iOS SGEntitlementCrypto). java.util.Base64 needs API 26
 * and android.util.Base64 is a stub in JVM tests, so this is our own. A length of 1 mod 4 is never "fixed" by padding.
 */
public final class ZgBase64 {

    private static final char[] STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private ZgBase64() {
    }

    /** base64url without padding (the JWS alphabet); null for anything else. */
    public static byte[] decodeUrl(String value) {
        if (value == null || value.indexOf('=') >= 0 || value.indexOf('+') >= 0 || value.indexOf('/') >= 0) {
            return null;
        }
        return decode(value.replace('-', '+').replace('_', '/'), false);
    }

    /** Standard base64 with optional padding; null for anything else. */
    public static byte[] decodeStandard(String value) {
        if (value == null || value.indexOf('-') >= 0 || value.indexOf('_') >= 0) {
            return null;
        }
        return decode(value, true);
    }

    public static String encodeStandard(byte[] data) {
        return encode(data, STANDARD, true);
    }

    public static String encodeUrl(byte[] data) {
        return encode(data, URL, false);
    }

    private static byte[] decode(String value, boolean paddingAllowed) {
        String s = value;
        if (paddingAllowed) {
            if (s.length() % 4 != 0 && s.endsWith("=")) {
                return null;
            }
            if (s.endsWith("==")) {
                s = s.substring(0, s.length() - 2);
            } else if (s.endsWith("=")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        if (s.length() % 4 == 1) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            final int v = indexOf(s.charAt(i));
            if (v < 0) {
                return null;
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static int indexOf(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    private static String encode(byte[] data, char[] alphabet, boolean pad) {
        final StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= data.length) {
            final int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(alphabet[(n >> 18) & 63]).append(alphabet[(n >> 12) & 63]).append(alphabet[(n >> 6) & 63]).append(alphabet[n & 63]);
            i += 3;
        }
        final int rest = data.length - i;
        if (rest == 1) {
            final int n = (data[i] & 0xFF) << 16;
            sb.append(alphabet[(n >> 18) & 63]).append(alphabet[(n >> 12) & 63]);
            if (pad) sb.append("==");
        } else if (rest == 2) {
            final int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(alphabet[(n >> 18) & 63]).append(alphabet[(n >> 12) & 63]).append(alphabet[(n >> 6) & 63]);
            if (pad) sb.append('=');
        }
        return sb.toString();
    }
}

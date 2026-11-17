/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import androidx.annotation.Nullable;

/**
 * The two kinds of credential every Zenlygram lock uses (Chat Lock master, per-chat pincodes, the Secret Vault PIN):
 * a 4-digit PIN (the default, like Telegram's own passcode) or a text password.
 *
 * <p>{@link #normalize} is the single place that decides what is stored and what is compared, so the set-up and the
 * unlock screens can never disagree about whitespace or digits.
 */
public final class ZgLockCode {

    /** Exactly {@link #PIN_LENGTH} ASCII digits. */
    public static final int TYPE_PIN = 0;
    /** Any non-empty text (see the text policies). */
    public static final int TYPE_TEXT = 1;

    public static final int PIN_LENGTH = 4;

    /** Chat Lock (iOS): whitespace around the password is trimmed; what is left must not be empty. */
    public static final int TEXT_POLICY_TRIM = 0;
    /**
     * Secret Vault: no whitespace anywhere (its secret-code reveal never checks text with spaces); must not be
     * empty.
     */
    public static final int TEXT_POLICY_NO_WHITESPACE = 1;

    private ZgLockCode() {
    }

    /**
     * The code to hash or compare, or null when {@code raw} is not an acceptable credential of {@code type}: a PIN
     * that is not exactly 4 ASCII digits, an empty password, or (with {@link #TEXT_POLICY_NO_WHITESPACE}) a password
     * that contains whitespace.
     */
    @Nullable
    public static String normalize(int type, @Nullable CharSequence raw, int textPolicy) {
        if (raw == null) {
            return null;
        }
        final String value = raw.toString();
        if (type == TYPE_PIN) {
            if (value.length() != PIN_LENGTH) {
                return null;
            }
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            return value;
        }
        if (textPolicy == TEXT_POLICY_NO_WHITESPACE) {
            if (value.isEmpty()) {
                return null;
            }
            for (int i = 0; i < value.length(); i++) {
                if (Character.isWhitespace(value.charAt(i))) {
                    return null;
                }
            }
            return value;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Whether {@code raw} contains whitespace that {@link #TEXT_POLICY_NO_WHITESPACE} refuses (for the error text). */
    public static boolean hasWhitespace(@Nullable CharSequence raw) {
        if (raw == null) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isWhitespace(raw.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Stored form of a type in metadata strings ("pin" / "text"). */
    public static String typeToString(int type) {
        return type == TYPE_TEXT ? "text" : "pin";
    }

    public static int typeFromString(@Nullable String value) {
        return "text".equals(value) ? TYPE_TEXT : TYPE_PIN;
    }

    /** Metadata string "pin:1" / "text:0": the credential type and whether biometric unlock is on. */
    public static String encodeMeta(int type, boolean biometric) {
        return typeToString(type) + ":" + (biometric ? "1" : "0");
    }

    public static int metaType(@Nullable String meta) {
        if (meta == null) {
            return TYPE_PIN;
        }
        final int colon = meta.indexOf(':');
        return typeFromString(colon >= 0 ? meta.substring(0, colon) : meta);
    }

    public static boolean metaBiometric(@Nullable String meta) {
        return meta != null && meta.endsWith(":1");
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.dialogs;

import java.util.Arrays;
import java.util.HashSet;

/**
 * The calling code of a phone number: a verbatim port of iOS sgExtractCountryCode (SGForeignUserBlock.swift), with
 * the same 3-, 2- and 1-digit tables, so both apps hide exactly the same chats. "998901234567" gives "998".
 *
 * <p>Shared codes stay shared on purpose (+1 for the whole NANP area, +7 for Russia and Kazakhstan), and a code
 * missing from the tables gives null, which never hides anything.
 */
public final class ZgCountryCode {

    private static final HashSet<String> THREE = new HashSet<>(Arrays.asList(
        "998", "992", "993", "994", "995", "996", "374", "375", "380", "373", "371", "370", "372",
        "971", "966", "965", "968", "974", "973", "964", "963", "962", "961", "967",
        "856", "855", "852", "853", "886", "880", "977", "960", "976", "975", "670", "673", "959",
        "234", "254", "255", "256", "251", "233", "237", "243", "221", "225", "227", "223", "226",
        "229", "228", "231", "232", "235", "236", "241", "242", "244", "249", "252", "253", "257",
        "258", "261", "263", "260", "264", "265", "266", "267", "268", "269",
        "351", "352", "353", "354", "355", "356", "357", "358", "359",
        "381", "382", "383", "385", "386", "387", "389", "420", "421",
        "591", "592", "593", "594", "595", "596", "597", "598",
        "212", "213", "216", "218", "220", "222", "238", "239", "240", "245", "246", "247", "248",
        "250", "262", "290", "291", "297", "298", "299"));

    private static final HashSet<String> TWO = new HashSet<>(Arrays.asList(
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58",
        "60", "61", "62", "63", "64", "65", "66",
        "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98"));

    private static final HashSet<String> ONE = new HashSet<>(Arrays.asList("1", "7"));

    private ZgCountryCode() {
    }

    /** The calling code of {@code phone} (any formatting, only its digits count), or null if it is not known. */
    public static String extract(String phone) {
        final String digits = digitsOf(phone);
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() >= 3) {
            final String p3 = digits.substring(0, 3);
            if (THREE.contains(p3)) {
                return p3;
            }
        }
        if (digits.length() >= 2) {
            final String p2 = digits.substring(0, 2);
            if (TWO.contains(p2)) {
                return p2;
            }
        }
        final String p1 = digits.substring(0, 1);
        return ONE.contains(p1) ? p1 : null;
    }

    private static String digitsOf(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "";
        }
        boolean onlyDigits = true;
        for (int i = 0; i < phone.length(); i++) {
            final char c = phone.charAt(i);
            if (c < '0' || c > '9') {
                onlyDigits = false;
                break;
            }
        }
        if (onlyDigits) {
            return phone;
        }
        final StringBuilder sb = new StringBuilder(phone.length());
        for (int i = 0; i < phone.length(); i++) {
            final char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

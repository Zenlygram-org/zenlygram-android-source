/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import android.text.TextUtils;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zenlygram.customer.CustomerJson;
import org.telegram.tgnet.TLRPC;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * How the customer screens write dates, times and the account line (iOS SGCustomerSyncFormat, SGProfileReader.accountLine):
 * the wording rules live here once, so the wall, Data &amp; consent and My data read the same.
 */
final class ZgCustomerFormat {

    private ZgCustomerFormat() {
    }

    private static Locale locale() {
        final Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null ? locale : Locale.getDefault();
    }

    /** A consent decision's date: medium style, no time, in Asia/Tashkent (iOS decisionDate); "—" when unknown. */
    static String decisionDate(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "—";
        }
        final DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM, locale());
        format.setTimeZone(TimeZone.getTimeZone("Asia/Tashkent"));
        return format.format(new Date(epochSeconds * 1000L));
    }

    /**
     * A caption that ends with a date: a date that itself ends with an abbreviation's period ("19 сент. 2026 г.") also
     * ends the sentence, so the period is not doubled.
     */
    static String sentence(String text) {
        return text == null ? null : text.replace("..", ".");
    }

    /**
     * Epoch seconds of a server instant ("2026-11-01T10:00:00Z"; a fraction of a second is tolerated), or -1.
     * Pure, so My data's ordering can be tested.
     */
    static long serverInstant(String text) {
        if (text == null) {
            return -1;
        }
        final long exact = CustomerJson.parseInstant(text);
        if (exact >= 0) {
            return exact;
        }
        // "…T10:00:00.123456Z": the same instant with a fraction.
        final int dot = text.indexOf('.');
        if (dot == 19 && text.endsWith("Z") && text.length() > 21) {
            for (int i = dot + 1; i < text.length() - 1; i++) {
                final char c = text.charAt(i);
                if (c < '0' || c > '9') {
                    return -1;
                }
            }
            return CustomerJson.parseInstant(text.substring(0, 19) + "Z");
        }
        return -1;
    }

    /**
     * A server time on My data: medium date and short time in the device's time zone; the raw text when it is not an
     * instant, null for null.
     */
    static String serverTime(String text) {
        if (text == null) {
            return null;
        }
        final long seconds = serverInstant(text);
        if (seconds < 0) {
            return text;
        }
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()).format(new Date(seconds * 1000L));
    }

    /** A duration in hours and minutes ("1 hour 5 minutes"). */
    static String duration(long seconds) {
        final long clamped = Math.max(0, Math.min(seconds, Integer.MAX_VALUE));
        final int hours = (int) (clamped / 3600);
        final int minutes = (int) (clamped / 60 % 60);
        if (hours == 0) {
            return LocaleController.formatPluralString("Minutes", minutes);
        }
        final String text = LocaleController.formatPluralString("Hours", hours);
        return minutes == 0 ? text : text + " " + LocaleController.formatPluralString("Minutes", minutes);
    }

    /** "%1$d unsent task changes will be lost." in the plural form of the app language. */
    static String unsentChanges(int count) {
        String form;
        try {
            form = LocaleController.getStringParamForNumber(count);
        } catch (RuntimeException rulesNotLoaded) {
            form = "other";
        }
        final int res;
        switch (String.valueOf(form)) {
            case "zero":
                res = R.string.ZenlyDataConsentWithdrawUnsent_zero;
                break;
            case "one":
                res = R.string.ZenlyDataConsentWithdrawUnsent_one;
                break;
            case "two":
                res = R.string.ZenlyDataConsentWithdrawUnsent_two;
                break;
            case "few":
                res = R.string.ZenlyDataConsentWithdrawUnsent_few;
                break;
            case "many":
                res = R.string.ZenlyDataConsentWithdrawUnsent_many;
                break;
            default:
                res = R.string.ZenlyDataConsentWithdrawUnsent_other;
                break;
        }
        return LocaleController.formatString(res, count);
    }

    /** The account as the wall names it: the name, else @username, else the phone with its middle digits hidden. */
    static String accountLabel(TLRPC.User user) {
        if (user == null) {
            return null;
        }
        final String name = ContactsController.formatName(user.first_name, user.last_name);
        if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(name.trim())) {
            return name.trim();
        }
        final String username = UserObject.getPublicUsername(user);
        if (!TextUtils.isEmpty(username)) {
            return "@" + username;
        }
        return maskedPhone(user.phone);
    }

    /** "+998 •••••• 67": the first three and the last two digits of a phone number, or null. */
    static String maskedPhone(String phone) {
        if (phone == null) {
            return null;
        }
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < phone.length(); i++) {
            final char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() < 6) {
            return null;
        }
        final StringBuilder masked = new StringBuilder("+").append(digits, 0, 3).append(' ');
        for (int i = 3; i < digits.length() - 2; i++) {
            masked.append('•');
        }
        return masked.append(' ').append(digits, digits.length() - 2, digits.length()).toString();
    }
}

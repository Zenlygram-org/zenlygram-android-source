/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import java.util.Locale;

/**
 * The consent wall decision, a pure Java port of iOS {@code SGConsentWallPolicy.decide} (release/appstore,
 * docs/customer-data.md §4.2), plus the operator-values rule of {@code SGConsentOperatorValues.isUsable(apiHost:)}
 * (§2). No Android type, tested on the JVM (ZgConsentWallPolicyTest).
 *
 * <pre>
 * | # | Condition                                                       | Required             | Optional                       |
 * | 1 | store unreadable                                                | deferred             | deferred                       |
 * | 2 | deleted notice pending                                          | wall(deleted)        | none                           |
 * | 3 | granted, link ended                                             | wall(linkEnded)      | prompt(linkEnded)              |
 * | 4 | granted, rejected consent_version_retired, version &gt;= current | textUnavailable      | none                           |
 * | 5 | granted, version &lt; min, or rejected consent_version_retired     | wall(reconsent)      | prompt(reconsent)              |
 * | 6 | granted, this build renders the record's text with another hash | wall(operatorChanged)| prompt(operatorChanged)        |
 * | 7 | granted                                                         | none                 | none                           |
 * | 8 | none / declined                                                 | wall(none)           | none → prompt, declined → none |
 * | 9 | withdrawn / withdrawn_remote                                    | wall(withdrawn…)     | none                           |
 * | 10| needs_reconsent                                                 | wall(stored notice)  | prompt(stored notice)          |
 * </pre>
 */
public final class ZgConsentWallPolicy {

    public static final String VERSION_RETIRED_CODE = "consent_version_retired";

    private ZgConsentWallPolicy() {
    }

    public enum Presentation {
        /** Store unreadable: decide again later, show and write nothing. */
        DEFERRED,
        NONE,
        /** The blocking wall (required mode). */
        WALL,
        /** The dismissable prompt (optional mode). */
        PROMPT,
        /** The bundled text itself is retired: no text and no Agree (required mode). */
        TEXT_UNAVAILABLE
    }

    public enum Notice {
        NONE, DELETED, LINK_ENDED, RECONSENT, OPERATOR_CHANGED, WITHDRAWN, WITHDRAWN_REMOTE
    }

    /** Why a record is needs_reconsent (iOS SGConsentStoredNotice). */
    public enum StoredNotice {
        RECONSENT, OPERATOR_CHANGED, LINK_ENDED
    }

    public static final class Inputs {
        public boolean required = true;
        public boolean readable = true;
        /** none / granted / declined / withdrawn / withdrawn_remote / needs_reconsent (ZgConsentStore.State wire). */
        public String state = "none";
        public int recordTextVersion;
        public String recordTextSha256;
        public StoredNotice storedNotice;
        public int bundledCurrentVersion;
        public int bundledMinValidVersion;
        /** This build's sha256 of (record version, record locale), or null when that version is not bundled. */
        public String renderedSha256ForRecord;
        public String latestGrantRejection;
        public boolean linkEnded;
        public boolean deletedNotice;
    }

    public static final class Decision {
        public final Presentation presentation;
        public final Notice notice;
        /**
         * granted → needs_reconsent with this notice. iOS writes it to its store; here the cloud client's own machine
         * stores that change (ConsentMachine), so the wall writes nothing: the field stays for the table's parity.
         */
        public final StoredNotice normalise;

        Decision(Presentation presentation, Notice notice, StoredNotice normalise) {
            this.presentation = presentation;
            this.notice = notice;
            this.normalise = normalise;
        }

        public boolean blocks() {
            return presentation == Presentation.WALL || presentation == Presentation.TEXT_UNAVAILABLE;
        }

        @Override
        public String toString() {
            return presentation + "(" + notice + ")" + (normalise != null ? " normalise " + normalise : "");
        }
    }

    public static Decision decide(Inputs in) {
        final boolean required = in.required;
        if (!in.readable) {
            return new Decision(Presentation.DEFERRED, Notice.NONE, null);
        }
        if (in.deletedNotice) {
            return required ? ask(true, Notice.DELETED, null) : none();
        }
        final String state = in.state == null ? "none" : in.state;
        switch (state) {
            case "granted": {
                final int version = in.recordTextVersion;
                final boolean retired = VERSION_RETIRED_CODE.equals(in.latestGrantRejection);
                if (in.linkEnded) {
                    return ask(required, Notice.LINK_ENDED, StoredNotice.LINK_ENDED);
                }
                if (retired && version >= in.bundledCurrentVersion) {
                    return required ? new Decision(Presentation.TEXT_UNAVAILABLE, Notice.NONE, null) : none();
                }
                if (version < in.bundledMinValidVersion || retired) {
                    return ask(required, Notice.RECONSENT, StoredNotice.RECONSENT);
                }
                if (in.renderedSha256ForRecord != null && !in.renderedSha256ForRecord.equals(in.recordTextSha256)) {
                    return ask(required, Notice.OPERATOR_CHANGED, StoredNotice.OPERATOR_CHANGED);
                }
                return none();
            }
            case "declined":
                return required ? ask(true, Notice.NONE, null) : none();
            case "withdrawn":
                return required ? ask(true, Notice.WITHDRAWN, null) : none();
            case "withdrawn_remote":
                return required ? ask(true, Notice.WITHDRAWN_REMOTE, null) : none();
            case "needs_reconsent": {
                final Notice notice;
                if (in.storedNotice == StoredNotice.OPERATOR_CHANGED) {
                    notice = Notice.OPERATOR_CHANGED;
                } else if (in.storedNotice == StoredNotice.LINK_ENDED) {
                    notice = Notice.LINK_ENDED;
                } else {
                    notice = Notice.RECONSENT;
                }
                return ask(required, notice, null);
            }
            case "none":
            default:
                return ask(required, Notice.NONE, null);
        }
    }

    private static Decision ask(boolean required, Notice notice, StoredNotice normalise) {
        return new Decision(required ? Presentation.WALL : Presentation.PROMPT, notice, normalise);
    }

    private static Decision none() {
        return new Decision(Presentation.NONE, Notice.NONE, null);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Operator values (iOS SGConsentOperatorValues)
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Whether a build for this API host may render the consent text (iOS {@code isUsable(apiHost:)}): no value
     * invalid, and all four present — or the host is local, where missing values stay literal placeholders.
     * Otherwise iOS's activation is {@code .failed(.operatorMissing)}: fail closed, no wall.
     */
    public static boolean operatorUsable(boolean complete, boolean hasInvalid, String apiHost) {
        return !hasInvalid && (complete || acceptsMissingValues(apiHost));
    }

    /**
     * iOS {@code SGConsentOperatorValues.acceptsMissingValues(apiHost:)}: loopback ({@code 127.0.0.0/8}, {@code ::1},
     * {@code localhost}), RFC 1918 private IPv4 ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) or a name under
     * {@code .local} — the local stack, never a public server.
     */
    public static boolean acceptsMissingValues(String apiHost) {
        if (apiHost == null || apiHost.isEmpty()) {
            return false;
        }
        String host = apiHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("localhost") || host.equals("::1") || (host.endsWith(".local") && host.length() > ".local".length())) {
            return true;
        }
        final String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        final int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            final String octet = octets[i];
            if (octet.length() < 1 || octet.length() > 3) {
                return false;
            }
            for (int c = 0; c < octet.length(); c++) {
                if (octet.charAt(c) < '0' || octet.charAt(c) > '9') {
                    return false;
                }
            }
            values[i] = Integer.parseInt(octet);
            if (values[i] > 255) {
                return false;
            }
        }
        if (values[0] == 127 || values[0] == 10 || (values[0] == 192 && values[1] == 168)) {
            return true;
        }
        return values[0] == 172 && values[1] >= 16 && values[1] <= 31;
    }
}

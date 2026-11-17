/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.CustomerJson;

/**
 * What other packages read of an account's consent: its state and the latest grant, as {@link ZgCustomer#record}
 * returns them. It stores nothing: the consent store is the cloud client's
 * ({@code org.telegram.messenger.zenlygram.customer.ZenlyConsent}), and the store this app had before it
 * ({@code zg.consent.*} keys) is moved over once by {@link ZgConsentMigration}. The customer screens read the client's
 * {@link ConsentSnapshot} directly ({@link ZgCustomer#snapshot}).
 */
public final class ZgConsentStore {

    public enum State {
        NONE("none"),
        GRANTED("granted"),
        DECLINED("declined"),
        WITHDRAWN("withdrawn"),
        WITHDRAWN_REMOTE("withdrawn_remote"),
        NEEDS_RECONSENT("needs_reconsent");

        public final String wire;

        State(String wire) {
            this.wire = wire;
        }

        static State of(ConsentMachine.State state) {
            if (state != null) {
                for (State value : values()) {
                    if (value.wire.equals(state.wire)) {
                        return value;
                    }
                }
            }
            return NONE;
        }
    }

    /** One account's consent, read only. */
    public static final class Record {
        /**
         * The effective state: a grant the server's side ended (the link ended, the text version was retired) reads
         * needs_reconsent, exactly as the client's gates see it.
         */
        public final State state;
        /** The latest grant: its id, text version, hash, locale and mode; absent when none was ever made. */
        public final String consentId;
        public final int textVersion;
        public final String textSha256;
        public final String locale;
        public final String mode;
        /** RFC 3339 UTC time of the latest state change, or null. */
        public final String decidedAt;

        private Record(State state, String consentId, int textVersion, String textSha256, String locale, String mode, String decidedAt) {
            this.state = state;
            this.consentId = consentId;
            this.textVersion = textVersion;
            this.textSha256 = textSha256;
            this.locale = locale;
            this.mode = mode;
            this.decidedAt = decidedAt;
        }

        static final Record NONE = new Record(State.NONE, null, 0, null, null, null, null);

        static Record of(ConsentSnapshot snapshot) {
            if (snapshot == null || snapshot.record.state == ConsentMachine.State.NONE) {
                return NONE;
            }
            final ConsentMachine.Grant grant = snapshot.latestGrant();
            return new Record(State.of(snapshot.effectiveState()), grant == null ? null : grant.consentId,
                grant == null ? 0 : grant.textVersion, grant == null ? null : grant.textSha256, grant == null ? null : grant.locale,
                grant == null ? null : grant.consentMode, CustomerJson.instant(snapshot.record.changedAt));
        }
    }

    private ZgConsentStore() {
    }
}

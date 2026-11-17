package org.telegram.messenger.zenlygram.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host fake of the ZenlyPro network/session boundary that the real GooglePlayCheckout calls: the
 * snapshot it reads, the google/prepare request (recorded, answered only when a test says so, like
 * the real worker-then-UI-thread callback) and the pending attach hashes. Nested type shapes mirror
 * production so the real checkout compiles unchanged. No signature, time or persistence rule lives
 * here: those stay in production ZenlyPro and are covered by the shared pro suite.
 */
public final class ZenlyPro {
    public enum Status { FREE, ACTIVE }

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Error error);
    }

    public static final class Error extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code, message;
        public final int httpStatus, retryAfterSeconds;
        Error(String code, String message, int httpStatus, int retryAfterSeconds) {
            super(message);
            this.code = code;
            this.message = message;
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }
    }

    public static final class Snapshot {
        public final Status status;
        public final long untilEpochSeconds;
        public final boolean linked, configured;
        public final boolean purchasesAvailable;
        public final String message;
        Snapshot(Status status, long until, boolean linked, boolean configured, boolean purchasesAvailable, String message) {
            this.status = status;
            this.untilEpochSeconds = until;
            this.linked = linked;
            this.configured = configured;
            this.purchasesAvailable = purchasesAvailable;
            this.message = message;
        }
    }

    public static final class PlayAccount {
        public final GooglePlayModel.Catalog catalog;
        final long telegramId;
        final String accountId;
        PlayAccount(GooglePlayModel.Catalog catalog, long telegramId, String accountId) {
            this.catalog = catalog;
            this.telegramId = telegramId;
            this.accountId = accountId;
        }
    }

    /** One google/prepare request: restore=true is {"purpose":"restore"}, false the new-purchase {}. */
    static final class Prepare {
        final boolean restore;
        final Callback<PlayAccount> callback;
        Prepare(boolean restore, Callback<PlayAccount> callback) {
            this.restore = restore;
            this.callback = callback;
        }
    }

    private static final Map<Integer, ZenlyPro> INSTANCES = new HashMap<>();

    /** Test controls: the verified policy (purchasesAvailable = paid), binding and configuration. */
    boolean purchasesAvailable = true, linked = true, configured = true;
    Set<String> pendingAttaches = Collections.emptySet();
    final List<Prepare> prepares = new ArrayList<>();

    private ZenlyPro() { }

    public static synchronized ZenlyPro get(int account) {
        ZenlyPro result = INSTANCES.get(account);
        if (result == null) {
            result = new ZenlyPro();
            INSTANCES.put(account, result);
        }
        return result;
    }

    static synchronized void reset() { INSTANCES.clear(); }

    public Snapshot snapshot() {
        return new Snapshot(purchasesAvailable ? Status.ACTIVE : Status.FREE, 0, linked, configured, purchasesAvailable, null);
    }

    public Set<String> pendingGoogleAttaches() { return pendingAttaches; }

    public void prepareGooglePlay(boolean restore, Callback<PlayAccount> callback) {
        prepares.add(new Prepare(restore, callback));
    }
}

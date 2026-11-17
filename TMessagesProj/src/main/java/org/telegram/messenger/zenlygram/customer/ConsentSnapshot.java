package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The immutable consent state of one Telegram account (design 50 §5.4), pure and host-tested: the stored record, the
 * outbox of exact ConsentEvents (oldest first), the acked consent ids per authority and the rejected entries, plus the
 * in-memory binding loss and the bundled {@code min_valid_version}. ZenlyConsent publishes one per account through a
 * volatile copy-on-write map after every successful commit, so a reader on any thread sees a whole state, never a
 * half-applied change or a grant after its withdrawal. Transitions live in {@link ConsentMachine}.
 */
public final class ConsentSnapshot {
    public final long tgUserId;
    public final ConsentMachine.Record record;
    /** Exact ConsentEvents, oldest first (unmodifiable). */
    public final List<ConsentEvent> outbox;
    /** Authority → acked consent ids (unmodifiable). */
    public final Map<String, Set<String>> acked;
    /** Consent id → the error code that refused it (unmodifiable). */
    public final Map<String, String> rejected;
    /** Epoch seconds of a binding loss at the current origin, 0 = none (fed by ZenlyCustomer, never stored here). */
    public final long bindingLostAt;
    /** The bundled manifest's {@code min_valid_version}; 0 when the manifest is unavailable. */
    public final int minValidVersion;

    private ConsentSnapshot(long tgUserId, ConsentMachine.Record record, List<ConsentEvent> outbox, Map<String, Set<String>> acked,
            Map<String, String> rejected, long bindingLostAt, int minValidVersion) {
        this.tgUserId = tgUserId;
        this.record = record;
        this.outbox = Collections.unmodifiableList(new ArrayList<>(outbox));
        LinkedHashMap<String, Set<String>> ackedCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : acked.entrySet()) {
            if (!entry.getValue().isEmpty()) ackedCopy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        this.acked = Collections.unmodifiableMap(ackedCopy);
        this.rejected = Collections.unmodifiableMap(new LinkedHashMap<>(rejected));
        this.bindingLostAt = Math.max(0, bindingLostAt);
        this.minValidVersion = Math.max(0, minValidVersion);
    }

    /** No consent state for {@code tgUserId} (state none). */
    public static ConsentSnapshot empty(long tgUserId, int minValidVersion) {
        return new ConsentSnapshot(tgUserId, ConsentMachine.Record.none(tgUserId), Collections.<ConsentEvent>emptyList(),
                Collections.<String, Set<String>>emptyMap(), Collections.<String, String>emptyMap(), 0, minValidVersion);
    }

    ConsentSnapshot with(ConsentMachine.Record record, List<ConsentEvent> outbox, Map<String, Set<String>> acked, Map<String, String> rejected,
            long bindingLostAt) {
        return new ConsentSnapshot(tgUserId, record, outbox, acked, rejected, bindingLostAt, minValidVersion);
    }

    /** The same state judged against another bundled {@code min_valid_version}. */
    public ConsentSnapshot withMinValidVersion(int value) {
        return value == minValidVersion ? this : new ConsentSnapshot(tgUserId, record, outbox, acked, rejected, bindingLostAt, value);
    }

    /** Nothing stored and nothing in memory for this account. */
    public boolean isEmpty() {
        return record.state == ConsentMachine.State.NONE && outbox.isEmpty() && acked.isEmpty() && rejected.isEmpty() && bindingLostAt == 0;
    }

    // ---- Effective state ----

    /**
     * The state the wall, the pipeline and every gate use: the stored state, except that a stored grant reads as
     * needs_reconsent after a binding loss at or after its decided_at (LINK_ENDED; until ZenlyConsent has stored it so,
     * {@link ConsentMachine#linkEnded}) or when its version is below the bundled {@code min_valid_version} (RECONSENT).
     */
    public ConsentMachine.State effectiveState() {
        if (record.state == ConsentMachine.State.GRANTED && record.grant != null && (lostAfterGrant() || versionRetired())) {
            return ConsentMachine.State.NEEDS_RECONSENT;
        }
        return record.state;
    }

    /** What the wall and Data & consent explain for the effective state. */
    public ConsentMachine.Notice notice() {
        switch (effectiveState()) {
            case NEEDS_RECONSENT:
                if (record.state == ConsentMachine.State.NEEDS_RECONSENT) return record.cause;
                return lostAfterGrant() ? ConsentMachine.Notice.LINK_ENDED : ConsentMachine.Notice.RECONSENT;
            case WITHDRAWN:
                return ConsentMachine.Notice.WITHDRAWN;
            case WITHDRAWN_REMOTE:
                return ConsentMachine.Notice.WITHDRAWN_REMOTE;
            default:
                return ConsentMachine.Notice.NONE;
        }
    }

    /** The recorded binding loss ended the latest grant: it is at or after that grant's decided_at. */
    boolean lostAfterGrant() { return bindingLostAt > 0 && record.grant != null && bindingLostAt >= record.grant.decidedAt; }

    private boolean versionRetired() { return minValidVersion > 0 && record.grant != null && record.grant.textVersion < minValidVersion; }

    /** The wall for this account in {@code mode} (design 50 §5.6). */
    public ConsentMachine.Wall wall(ConsentMachine.Mode mode) { return ConsentMachine.wall(mode, effectiveState()); }

    // ---- Grants and the outbox ----

    /** The latest grant, or null when none was ever made. */
    public ConsentMachine.Grant latestGrant() { return record.grant; }

    /** The outbox event with {@code consentId}, or null. */
    public ConsentEvent event(String consentId) {
        for (int i = outbox.size() - 1; i >= 0; i--) {
            if (outbox.get(i).consentId.equals(consentId)) return outbox.get(i);
        }
        return null;
    }

    /** The latest grant's event, or null. */
    public ConsentEvent latestGrantEvent() {
        ConsentEvent event = record.grant == null ? null : event(record.grant.consentId);
        return event != null && event.isGrant() ? event : null;
    }

    /** The exact JSON of the latest grant's event, or null. */
    public String latestGrantJson() {
        ConsentEvent event = latestGrantEvent();
        return event == null ? null : event.json();
    }

    /** The latest withdrawal in the outbox, or null. */
    public ConsentEvent latestWithdrawEvent() {
        for (int i = outbox.size() - 1; i >= 0; i--) {
            if (outbox.get(i).isWithdraw()) return outbox.get(i);
        }
        return null;
    }

    /** Whether the server refused the latest grant (design 50 §5.5 terminal row). */
    public boolean latestRejected() { return record.grant != null && rejected.containsKey(record.grant.consentId); }

    /** The error code that refused the latest grant, or null. */
    public String latestRejectedCode() { return record.grant == null ? null : rejected.get(record.grant.consentId); }

    public boolean isAcked(String authority, String consentId) {
        Set<String> ids = authority == null ? null : acked.get(authority);
        return ids != null && ids.contains(consentId);
    }

    public boolean isAckedAnywhere(String consentId) {
        for (Set<String> ids : acked.values()) {
            if (ids.contains(consentId)) return true;
        }
        return false;
    }

    /** Outbox entries still to upload at {@code authority} (not acked there, not rejected), oldest first. */
    public List<ConsentEvent> pending(String authority) {
        ArrayList<ConsentEvent> result = new ArrayList<>();
        for (ConsentEvent event : outbox) {
            boolean acknowledged = authority == null ? isAckedAnywhere(event.consentId) : isAcked(authority, event.consentId);
            if (!acknowledged && !rejected.containsKey(event.consentId)) result.add(event);
        }
        return Collections.unmodifiableList(result);
    }

    /** An un-acked, non-rejected withdrawal exists at {@code authority}. */
    public boolean pendingWithdraw(String authority) {
        for (ConsentEvent event : pending(authority)) {
            if (event.isWithdraw()) return true;
        }
        return false;
    }

    /**
     * The next consents request at {@code authority} (design 50 §5.7): the oldest pending entry; a grant goes alone, a
     * withdrawal together with up to nine directly following pending withdrawals. Empty when nothing is pending.
     */
    public List<ConsentEvent> nextUpload(String authority) {
        List<ConsentEvent> pending = pending(authority);
        ArrayList<ConsentEvent> batch = new ArrayList<>();
        for (ConsentEvent event : pending) {
            if (event.isGrant()) {
                if (batch.isEmpty()) batch.add(event);
                break;
            }
            batch.add(event);
            if (batch.size() == ConsentEvent.MAX_WITHDRAWALS_PER_REQUEST) break;
        }
        return Collections.unmodifiableList(batch);
    }

    /**
     * An earlier grant may still be effective on the server: the last outbox entry the server acknowledged (at any
     * authority) is a grant.
     */
    public boolean grantMayBeEffective() {
        String id = ConsentMachine.latestAcked(outbox, acked);
        ConsentEvent event = id == null ? null : event(id);
        return event != null && event.isGrant();
    }

    /**
     * Withdraw is offered (design 50 §5.5): effectively granted; or needs_reconsent, declined or granted with a rejected
     * latest grant when an earlier grant may still be effective on the server.
     */
    public boolean withdrawable() {
        ConsentMachine.State state = effectiveState();
        if (state == ConsentMachine.State.GRANTED) return !latestRejected() || grantMayBeEffective();
        if (state == ConsentMachine.State.NEEDS_RECONSENT || state == ConsentMachine.State.DECLINED) return grantMayBeEffective();
        return false;
    }

    /** "Give consent" mints a new grant: every state but an effective, non-rejected grant. */
    public boolean grantable() { return effectiveState() != ConsentMachine.State.GRANTED || latestRejected(); }

    /**
     * Customer data may be recorded for this account (statistics, the Tasks gate's WAITING): effectively granted and the
     * latest grant not rejected. A binding loss after the grant, a stale grant, a retired version or a withdrawal all
     * end it.
     */
    public boolean recordingAllowed() { return effectiveState() == ConsentMachine.State.GRANTED && !latestRejected(); }

    /**
     * The exact latest grant JSON for {@code bind/start}, only while it may be used: effectively granted (so no binding
     * loss since and not stale), not rejected, and no withdrawal pending at {@code authority} (otherwise the server would
     * record the new grant before the older withdrawal and supersede it, design 50 §8.1). Null in every other case.
     */
    public String grantJsonIfGranted(String authority) {
        if (!recordingAllowed() || pendingWithdraw(authority)) return null;
        return latestGrantJson();
    }

    // ---- Storage (SharedPreferences "zenlygram_consent", design 50 §5.4) ----

    /** The key names of the consent store. */
    public static final class Keys {
        public static final String RECORD = "consent.v1.";
        public static final String OUTBOX = "consent.outbox.v1.";
        public static final String ACKED = "consent.acked.v1.";
        public static final String REJECTED = "consent.rejected.v1.";

        public enum Kind { RECORD, OUTBOX, ACKED, REJECTED }

        /** A parsed key. */
        public static final class Key {
            public final Kind kind;
            public final long tgUserId;
            /** The authority of an acked key, else null. */
            public final String authority;

            Key(Kind kind, long tgUserId, String authority) {
                this.kind = kind;
                this.tgUserId = tgUserId;
                this.authority = authority;
            }
        }

        private Keys() {}

        public static String record(long tg) { return RECORD + tg; }

        public static String outbox(long tg) { return OUTBOX + tg; }

        public static String acked(long tg, String authority) { return ACKED + tg + "@" + authority; }

        public static String rejected(long tg) { return REJECTED + tg; }

        /** The parsed consent-store key, or null for any other key. */
        public static Key parse(String key) {
            if (key == null) return null;
            if (key.startsWith(OUTBOX)) return keyOf(Kind.OUTBOX, key.substring(OUTBOX.length()), null);
            if (key.startsWith(REJECTED)) return keyOf(Kind.REJECTED, key.substring(REJECTED.length()), null);
            if (key.startsWith(ACKED)) {
                String rest = key.substring(ACKED.length());
                int at = rest.indexOf('@');
                return at <= 0 || at == rest.length() - 1 ? null : keyOf(Kind.ACKED, rest.substring(0, at), rest.substring(at + 1));
            }
            if (key.startsWith(RECORD)) return keyOf(Kind.RECORD, key.substring(RECORD.length()), null);
            return null;
        }

        private static Key keyOf(Kind kind, String tg, String authority) {
            if (tg.isEmpty() || tg.length() > 16 || tg.charAt(0) == '0') return null;
            long value = 0;
            for (int i = 0; i < tg.length(); i++) {
                char c = tg.charAt(i);
                if (c < '0' || c > '9') return null;
                value = value * 10 + (c - '0');
            }
            return value <= ConsentEvent.MAX_TELEGRAM_USER_ID ? new Key(kind, value, authority) : null;
        }
    }

    /**
     * Every stored key of this account with its JSON value (record, outbox, acked per authority, rejected); absent parts
     * have no key. The binding loss is never stored here.
     */
    public Map<String, String> storedValues() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        String value = recordJson();
        if (value != null) values.put(Keys.record(tgUserId), value);
        value = outboxJson();
        if (value != null) values.put(Keys.outbox(tgUserId), value);
        for (String authority : acked.keySet()) {
            value = ackedJson(authority);
            if (value != null) values.put(Keys.acked(tgUserId, authority), value);
        }
        value = rejectedJson();
        if (value != null) values.put(Keys.rejected(tgUserId), value);
        return Collections.unmodifiableMap(values);
    }

    /** The stored record JSON, or null when the state is none (no key). */
    public String recordJson() { return record.json(); }

    /** The stored outbox JSON array of the exact event objects, or null when empty (no key). */
    public String outboxJson() {
        if (outbox.isEmpty()) return null;
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < outbox.size(); i++) {
            if (i > 0) out.append(',');
            out.append(outbox.get(i).json());
        }
        return out.append(']').toString();
    }

    /** The stored acked JSON array at {@code authority}, or null when nothing is acked there (no key). */
    public String ackedJson(String authority) {
        Set<String> ids = acked.get(authority);
        return ids == null || ids.isEmpty() ? null : CustomerJson.stringArray(ids);
    }

    /** The stored rejected JSON object, or null when nothing was refused (no key). */
    public String rejectedJson() {
        if (rejected.isEmpty()) return null;
        CustomerJson.Obj object = new CustomerJson.Obj();
        for (Map.Entry<String, String> entry : rejected.entrySet()) object.put(entry.getKey(), entry.getValue());
        return object.toString();
    }

    /**
     * The snapshot of the parsed stored values of one account (each may be null when absent or unreadable): an invalid
     * record reads as none, invalid outbox entries and entries of another user are left out, and acked or rejected marks
     * only count for consent ids in the outbox.
     */
    public static ConsentSnapshot decode(long tgUserId, Object recordTree, Object outboxTree, Map<String, Object> ackedTrees,
            Object rejectedTree, long bindingLostAt, int minValidVersion) {
        ConsentMachine.Record record = recordTree == null ? null : ConsentMachine.Record.fromTree(recordTree, tgUserId);
        ArrayList<ConsentEvent> outbox = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<?> items = CustomerJson.array(outboxTree);
        if (items != null) {
            for (Object item : items) {
                ConsentEvent event = ConsentEvent.fromTree(item);
                if (event != null && event.tgUserId == tgUserId && ids.add(event.consentId)) outbox.add(event);
            }
        }
        LinkedHashMap<String, Set<String>> acked = new LinkedHashMap<>();
        if (ackedTrees != null) {
            for (Map.Entry<String, Object> entry : ackedTrees.entrySet()) {
                List<String> values = CustomerJson.strings(entry.getValue());
                if (values == null || entry.getKey() == null || entry.getKey().isEmpty()) continue;
                LinkedHashSet<String> set = new LinkedHashSet<>(values);
                set.retainAll(ids);
                if (!set.isEmpty()) acked.put(entry.getKey(), set);
            }
        }
        LinkedHashMap<String, String> rejected = new LinkedHashMap<>();
        Map<String, Object> rejectedObject = CustomerJson.object(rejectedTree);
        if (rejectedObject != null) {
            for (Map.Entry<String, Object> entry : rejectedObject.entrySet()) {
                String code = CustomerJson.string(entry.getValue());
                if (ids.contains(entry.getKey()) && code != null) rejected.put(entry.getKey(), ConsentMachine.isErrorCode(code) ? code : "rejected");
            }
        }
        return new ConsentSnapshot(tgUserId, record == null ? ConsentMachine.Record.none(tgUserId) : record, outbox, acked, rejected,
                bindingLostAt, minValidVersion);
    }
}

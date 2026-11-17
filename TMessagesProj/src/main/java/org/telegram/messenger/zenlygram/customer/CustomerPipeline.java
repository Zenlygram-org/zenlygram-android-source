package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The per-account upload order and readiness (contract §6.4; design 50 §10), pure and host-tested. ZenlyCustomer
 * gathers the {@link Facts} on the main thread and performs the {@link Step}:
 * <ol start="0">
 * <li>a deletion is pending → NONE (only the delete request itself runs);</li>
 * <li>a withdrawal waits for upload: linked → UPLOAD_CONSENTS (oldest first, so it goes before any later grant);
 * unlinked and not granted → NONE (no link is ever made only to deliver a withdrawal); granted again since → rule 2 on
 * (the link then carries no consent, {@code linkGrantJson} is null);</li>
 * <li>not effectively granted, or the latest grant was rejected → NONE;</li>
 * <li>not linked → AUTO_LINK;</li>
 * <li>entries wait for upload (or the reconcile asked to send the grant again) → UPLOAD_CONSENTS; a grant that is not
 * acked and can no longer be sent → NONE;</li>
 * <li>profile traffic stopped (403 {@code profile_account_not_linked}) → NONE; the profile not settled for this binding
 * or due → PROFILE_SYNC;</li>
 * <li>a flush is due (a trigger fired, events may be queued, not paused) → EVENTS_FLUSH;</li>
 * <li>no 2xx of this binding in this process yet → CONFIRM (an events flush when events are queued, else the acked grant
 * once more: {@code duplicate});</li>
 * <li>READY.</li>
 * </ol>
 * A stopped binding sends nothing on any profile route (the server answers every one with the same 403), so rules 1 and 4
 * also give NONE then. {@link #tasksMayRun} is the readiness seam of the Tasks area (contract §6.4 step 6). Also here:
 * the {@code binding.v1} record ({@link Binding}), the installation-wide admission of sends ({@link Admission}) and
 * the consents batches ({@link #consentsBatch}, {@link #finalBatches}).
 */
public final class CustomerPipeline {
    private CustomerPipeline() {}

    public enum Step { NONE, AUTO_LINK, UPLOAD_CONSENTS, PROFILE_SYNC, EVENTS_FLUSH, CONFIRM, READY }

    /** What the rules read (design 50 §10); set every field. */
    public static final class Facts {
        /** The effective consent state (the binding-loss and retired-version derivations included). */
        public ConsentMachine.State state = ConsentMachine.State.NONE;
        public boolean latestRejected;
        /** Linked at the configured origin with the slot's own Telegram user. */
        public boolean linked;
        public boolean deletionPending;
        /** An un-acked, non-rejected withdrawal at this authority. */
        public boolean pendingWithdraw;
        /** Entries wait for upload at this authority ({@code ConsentSnapshot.nextUpload} is not empty). */
        public boolean uploadable;
        /** The latest grant is acked at this authority, i.e. for the current binding (the set is cleared per binding). */
        public boolean grantAcked;
        /** A 403 {@code consent_required} reconcile asked to send the latest grant once more. */
        public boolean resendGrant;
        /** A sync succeeded for this binding, or its payload was dropped in this process. */
        public boolean profileSettled;
        /** The cadence says SEND and the sync route is not backing off. */
        public boolean profileDue;
        public boolean profileStopped;
        /** A flush trigger fired, events may be queued and uploads are not paused or backing off. */
        public boolean flushDue;
        /** A 2xx of this binding in this process (consents with effective state granted, sync or events). */
        public boolean confirmed;

        /** The consent part of the facts from {@code s} at {@code authority}. */
        public static Facts of(ConsentSnapshot s, String authority) {
            Facts f = new Facts();
            f.state = s.effectiveState();
            f.latestRejected = s.latestRejected();
            f.pendingWithdraw = s.pendingWithdraw(authority);
            f.uploadable = !s.nextUpload(authority).isEmpty();
            f.grantAcked = s.record.grant != null && authority != null && s.isAcked(authority, s.record.grant.consentId);
            return f;
        }
    }

    /** The next step of one account (rules 0–8). */
    public static Step next(Facts f) {
        if (f.deletionPending) return Step.NONE;
        boolean granted = f.state == ConsentMachine.State.GRANTED;
        if (f.pendingWithdraw) {
            if (f.linked) return f.profileStopped ? Step.NONE : Step.UPLOAD_CONSENTS;
            if (!granted) return Step.NONE;
        }
        if (!granted || f.latestRejected) return Step.NONE;
        if (!f.linked) return Step.AUTO_LINK;
        if (f.profileStopped) return Step.NONE;
        if (f.uploadable || f.resendGrant) return Step.UPLOAD_CONSENTS;
        if (!f.grantAcked) return Step.NONE;
        if (!f.profileSettled || f.profileDue) return Step.PROFILE_SYNC;
        if (f.flushDue) return Step.EVENTS_FLUSH;
        if (!f.confirmed) return Step.CONFIRM;
        return Step.READY;
    }

    /**
     * Tasks may run for the account (design 50 §10): granted (not rejected), linked, the grant acked for this binding,
     * the profile settled and not stopped, no deletion pending, nothing left to upload, and confirmed by a 2xx of this
     * binding in this process.
     */
    public static boolean tasksMayRun(Facts f) {
        return f.state == ConsentMachine.State.GRANTED && !f.latestRejected && f.linked && f.grantAcked && f.profileSettled
                && !f.profileStopped && !f.deletionPending && !f.pendingWithdraw && f.confirmed;
    }

    // ---- Consents batches (design 50 §5.7, §7.4) ----

    /**
     * The next {@code POST /v1/profile/consents} events at {@code authority}: the oldest pending entries (a grant alone,
     * or up to ten consecutive withdrawals); when nothing is pending and {@code resendGrant}, the latest grant once more
     * (answered {@code duplicate}). Empty when there is nothing to send.
     */
    public static List<ConsentEvent> consentsBatch(ConsentSnapshot s, String authority, boolean resendGrant) {
        List<ConsentEvent> pending = s.nextUpload(authority);
        if (!pending.isEmpty() || !resendGrant) return pending;
        ConsentEvent grant = s.latestGrantEvent();
        if (grant == null || s.latestRejected()) return Collections.emptyList();
        return Collections.singletonList(grant);
    }

    /**
     * The final send at a logout or a user Unlink (design 50 §7.4): the newest {@code 2 × 10} pending withdrawals at
     * {@code authority}, oldest first, as at most two batches of at most ten. Grants are never sent then.
     */
    public static List<List<ConsentEvent>> finalBatches(ConsentSnapshot s, String authority, int maxBatches) {
        ArrayList<ConsentEvent> withdrawals = new ArrayList<>();
        for (ConsentEvent event : s.pending(authority)) {
            if (event.isWithdraw()) withdrawals.add(event);
        }
        int keep = Math.max(0, maxBatches) * ConsentEvent.MAX_WITHDRAWALS_PER_REQUEST;
        List<ConsentEvent> newest = withdrawals.size() > keep ? withdrawals.subList(withdrawals.size() - keep, withdrawals.size()) : withdrawals;
        ArrayList<List<ConsentEvent>> batches = new ArrayList<>();
        for (int i = 0; i < newest.size(); i += ConsentEvent.MAX_WITHDRAWALS_PER_REQUEST) {
            batches.add(Collections.unmodifiableList(new ArrayList<>(newest.subList(i,
                    Math.min(newest.size(), i + ConsentEvent.MAX_WITHDRAWALS_PER_REQUEST)))));
        }
        return Collections.unmodifiableList(batches);
    }

    /** The consent ids of {@code events}, in order. */
    public static List<String> ids(List<ConsentEvent> events) {
        ArrayList<String> ids = new ArrayList<>(events.size());
        for (ConsentEvent event : events) ids.add(event.consentId);
        return ids;
    }

    // ---- binding.v1.<tg>@<authority> (design 50 §7.6, integration A3) ----

    /**
     * The last adopted binding of a Telegram user at one origin and what ended it:
     * {@code {"v":1,"binding_id":"…","lost_at":0,"lost_code":"","stale_at":0}} — {@code lost_at} / {@code lost_code}: a
     * server 401 cleared the binding (epoch seconds, cleared by the next adoption); {@code stale_at}: the
     * {@code recorded_at} of the newest grant {@code bind/start} refused as {@code consent_stale} (kept until a newer grant
     * makes it irrelevant). {@link #consentLoss} is what ZenlyConsent.setBindingLoss gets: a grant decided at or before it
     * is effectively needs_reconsent, and is then stored so (ConsentMachine.linkEnded), so clearing {@code lost_at} at a
     * later adoption does not make it effective again.
     */
    public static final class Binding {
        public static final int FORMAT = 1;
        public static final Binding NONE = new Binding("", 0, "", 0);

        /** The adopted binding id ("" when none was adopted at this origin). */
        public final String bindingId;
        public final long lostAt;
        public final String lostCode;
        public final long staleAt;

        private Binding(String bindingId, long lostAt, String lostCode, long staleAt) {
            this.bindingId = bindingId == null ? "" : bindingId;
            this.lostAt = Math.max(0, Math.min(lostAt, CustomerJson.MAX_INSTANT));
            this.lostCode = lostCode == null || this.lostAt == 0 ? "" : lostCode;
            this.staleAt = Math.max(0, Math.min(staleAt, CustomerJson.MAX_INSTANT));
        }

        /**
         * {@code bindingId} was adopted: a new link ends the recorded loss. The grant that loss ended stays ended
         * (ZenlyConsent stored it as needs_reconsent, ConsentMachine.linkEnded), and a stale grant stays refused.
         */
        public Binding adopted(String bindingId) { return new Binding(bindingId, 0, "", staleAt); }

        /** Whether adopting {@code bindingId} is a binding other than the stored one (the acked set is then cleared). */
        public boolean isNewBinding(String bindingId) { return bindingId == null || !bindingId.equals(this.bindingId); }

        /** A server 401 ({@code code}) cleared the binding at {@code at}. */
        public Binding lost(long at, String code) {
            return new Binding(bindingId, Math.max(lostAt, at), ErrorCodes.isCode(code) ? code : "session_invalid", staleAt);
        }

        /** {@code bind/start} refused a grant recorded at {@code grantRecordedAt} as {@code consent_stale}. */
        public Binding stale(long grantRecordedAt) { return new Binding(bindingId, lostAt, lostCode, Math.max(staleAt, grantRecordedAt)); }

        /** The loss instant the consent snapshot derives needs_reconsent from (0 = none). */
        public long consentLoss() { return Math.max(lostAt, staleAt); }

        public boolean isEmpty() { return bindingId.isEmpty() && lostAt == 0 && staleAt == 0; }

        /** The stored JSON. */
        public String json() {
            return new CustomerJson.Obj().put("v", FORMAT).put("binding_id", bindingId).put("lost_at", lostAt).put("lost_code", lostCode)
                    .put("stale_at", staleAt).toString();
        }

        /**
         * The record of a parsed stored object, or null when it is not one (the caller fails closed: an unreadable record
         * reads as a loss now, so nothing links or uploads on a binding the server may have removed).
         */
        public static Binding fromTree(Object tree) {
            Map<String, Object> object = CustomerJson.object(tree);
            if (object == null) return null;
            Long format = CustomerJson.integral(object.get("v"));
            String id = CustomerJson.string(object.get("binding_id"));
            Long lost = CustomerJson.integral(object.get("lost_at"));
            String code = CustomerJson.string(object.get("lost_code"));
            Long stale = object.containsKey("stale_at") ? CustomerJson.integral(object.get("stale_at")) : Long.valueOf(0);
            if (format == null || format != FORMAT || id == null || id.length() > 64 || lost == null || lost < 0 || code == null || stale == null
                    || stale < 0) {
                return null;
            }
            return new Binding(id, lost, code, stale);
        }

        /** A record that could not be read: a loss at {@code now}. */
        public static Binding unreadable(long now) { return new Binding("", Math.max(1, now), "unreadable", 0); }

        @Override public boolean equals(Object other) { return other instanceof Binding && ((Binding) other).json().equals(json()); }

        @Override public int hashCode() { return json().hashCode(); }

        @Override public String toString() { return json(); }
    }

    // ---- Installation-wide admission (design 50 §7.5, §9.2) ----

    /**
     * Every customer send of the installation takes a token of {@link CustomerBackoff.InstallBudget} (the selected
     * account may use the whole route budget, the others all but its reserve); other accounts' profile syncs are also
     * spaced at least {@link #OTHER_SYNC_SPACING_MS} apart, so eight accounts at first idle never burst the sync route.
     * Thread-safe; times are monotonic milliseconds.
     */
    public static final class Admission {
        public static final long OTHER_SYNC_SPACING_MS = 60_000;

        private final CustomerBackoff.InstallBudget budget;
        private long lastOtherSyncMs = Long.MIN_VALUE;

        public Admission(CustomerBackoff.InstallBudget budget) {
            if (budget == null) throw new IllegalArgumentException("budget");
            this.budget = budget;
        }

        /** Milliseconds until {@link #admit} can succeed for this caller (0 = now). */
        public synchronized long waitMs(CustomerOutcome.Route route, boolean selected, long nowMs) {
            long wait = budget.waitMs(route, selected, nowMs);
            if (route == CustomerOutcome.Route.SYNC && !selected && lastOtherSyncMs != Long.MIN_VALUE) {
                wait = Math.max(wait, lastOtherSyncMs + OTHER_SYNC_SPACING_MS - nowMs);
            }
            return Math.max(0, wait);
        }

        /** Takes one send of {@code route} at {@code nowMs} when allowed; false = wait ({@link #waitMs}). */
        public synchronized boolean admit(CustomerOutcome.Route route, boolean selected, long nowMs) {
            if (waitMs(route, selected, nowMs) > 0 || !budget.tryTake(route, selected, nowMs)) return false;
            if (route == CustomerOutcome.Route.SYNC && !selected) lastOtherSyncMs = nowMs;
            return true;
        }
    }
}

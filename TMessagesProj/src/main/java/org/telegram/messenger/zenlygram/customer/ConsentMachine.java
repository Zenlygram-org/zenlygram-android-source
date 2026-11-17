package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The local consent state machine of one Telegram account (contract §6.2–§6.3; design 50 §5.5–§5.6 with the
 * integration rows of contract amendment A3), pure and host-tested. Every transition takes an immutable
 * {@link ConsentSnapshot} and returns a {@link Step} holding the next snapshot and what the caller must do besides
 * storing it (purge the event queue, upload again); nothing here stores, sends or logs.
 * <p>
 * The stored state is one of none (no record), granted, declined, withdrawn, withdrawn_remote and needs_reconsent. The
 * EFFECTIVE state ({@link ConsentSnapshot#effectiveState}) additionally reads a stored grant as needs_reconsent when
 * its version is below the bundled {@code min_valid_version}, or when a binding loss was recorded at or after its
 * {@code decided_at} (design 50 §7.6; fed from {@code binding.v1} by ZenlyCustomer and kept in memory). The grant such a
 * loss ended is then stored as needs_reconsent ({@link #linkEnded}; compliance rule X-7(b), as iOS does), so only a new
 * grant lifts it: a later link clears the loss but never makes the old grant effective again. A 409
 * {@code consent_stale} on {@code bind/start} (A3; automatic or manual link) stores needs_reconsent for exactly the
 * grant that {@code bind/start} carried ({@link #consentStale}); only a new grant lifts it. A stored grant whose text this
 * build renders with another {@code text_sha256} (the operator values changed since it was given) is stored as
 * needs_reconsent at launch for every account, selected or not ({@link #operatorChanged}); with the retired version
 * that is the "stale in this build" rule of iOS (design 40 §4.6 rows 5–6). A 401 with reason
 * {@code account_erased}, a deletion and a logout remove the record ({@link #forget}).
 * <p>
 * Instants: every new decision is stamped with the trusted clock, but strictly after this account's previous consent
 * event, decision and recorded binding loss ({@link #nextInstant}), so a clock that moved back can never order a
 * withdrawal before the grant it withdraws, or a new grant before the loss or remote withdrawal it answers.
 */
public final class ConsentMachine {
    /** The outbox holds at most this many events, unless more withdrawals than that are still un-acked. */
    public static final int MAX_OUTBOX = 20;

    private ConsentMachine() {}

    /** The stored (and effective) consent states of contract §6.2. */
    public enum State {
        NONE("none"), GRANTED("granted"), DECLINED("declined"), WITHDRAWN("withdrawn"), WITHDRAWN_REMOTE("withdrawn_remote"),
        NEEDS_RECONSENT("needs_reconsent");

        public final String wire;

        State(String wire) { this.wire = wire; }

        /** The state of its wire name, or null. */
        public static State of(String wire) {
            for (State state : values()) {
                if (state.wire.equals(wire)) return state;
            }
            return null;
        }
    }

    /** The build's consent mode (contract §6.1); anything but "optional" is required. */
    public enum Mode {
        REQUIRED("required"), OPTIONAL("optional");

        public final String wire;

        Mode(String wire) { this.wire = wire; }

        public static Mode of(String wire) { return "optional".equals(wire) ? OPTIONAL : REQUIRED; }
    }

    /** What the consent wall shows for an account (design 50 §5.6). */
    public enum Wall { NONE, PROMPT, BLOCKING }

    /**
     * Why the wall or Data & consent explains the effective state: a new text or the server asked again (RECONSENT), the
     * server ended the binding the grant linked (LINK_ENDED: binding loss, consent_stale, erasure mitigation), or the
     * consent was withdrawn here or elsewhere.
     */
    public enum Notice {
        NONE(null), RECONSENT("reconsent"), LINK_ENDED("link_ended"), WITHDRAWN(null), WITHDRAWN_REMOTE(null);

        /** The record's {@code notice} value of a stored needs_reconsent, else null. */
        public final String wire;

        Notice(String wire) { this.wire = wire; }
    }

    /** The latest grant of a record: the fields its ConsentEvent named, kept in every later state. */
    public static final class Grant {
        public final String consentId;
        public final int textVersion;
        public final String textSha256;
        public final String locale;
        public final String consentMode;
        /** Epoch seconds; the grant event's {@code recorded_at}. */
        public final long decidedAt;

        public Grant(String consentId, int textVersion, String textSha256, String locale, String consentMode, long decidedAt) {
            this.consentId = consentId;
            this.textVersion = textVersion;
            this.textSha256 = textSha256;
            this.locale = locale;
            this.consentMode = consentMode;
            this.decidedAt = decidedAt;
        }

        boolean isValid() {
            return CustomerJson.isUuid4(consentId) && textVersion >= 1 && textVersion <= ConsentManifest.MAX_VERSION
                    && CustomerJson.isHex64(textSha256) && ConsentManifest.LOCALES.contains(locale)
                    && ("required".equals(consentMode) || "optional".equals(consentMode)) && decidedAt >= 0 && decidedAt <= CustomerJson.MAX_INSTANT;
        }
    }

    /**
     * The stored record {@code consent.v1.<tg>} (design 50 §5.4):
     * {@code {"v":1,"tg_user_id":…,"state":…,"consent_id":…,"text_version":…,"text_sha256":…,"locale":…,"consent_mode":…,"decided_at":…,"changed_at":…}}
     * — the grant fields describe the latest grant and are absent only when none was ever made; Android-internal
     * {@code changed_at} is the time of the latest state change, {@code notice} says why a stored needs_reconsent was set
     * and {@code active_elsewhere} marks a withdrawal the server answered {@code superseded} while a later grant from
     * another device is effective.
     */
    public static final class Record {
        public static final int FORMAT = 1;

        public final long tgUserId;
        public final State state;
        /** The latest grant, or null when none was ever made. */
        public final Grant grant;
        /** Epoch seconds of the latest state change (for withdrawn_remote: the server's effective_at); 0 for none. */
        public final long changedAt;
        /** RECONSENT or LINK_ENDED for a stored needs_reconsent; NONE otherwise. */
        public final Notice cause;
        /** A withdrawn record whose withdrawal the server answered superseded (a later grant elsewhere is effective). */
        public final boolean activeElsewhere;

        Record(long tgUserId, State state, Grant grant, long changedAt, Notice cause, boolean activeElsewhere) {
            this.tgUserId = tgUserId;
            this.state = state;
            this.grant = grant;
            this.changedAt = changedAt;
            this.cause = state == State.NEEDS_RECONSENT ? (cause == Notice.LINK_ENDED ? Notice.LINK_ENDED : Notice.RECONSENT) : Notice.NONE;
            this.activeElsewhere = state == State.WITHDRAWN && activeElsewhere;
        }

        /** No record (state none). */
        public static Record none(long tgUserId) { return new Record(tgUserId, State.NONE, null, 0, Notice.NONE, false); }

        /** The exact stored JSON (null for none, which is never stored). */
        public String json() {
            if (state == State.NONE) return null;
            CustomerJson.Obj object = new CustomerJson.Obj().put("v", FORMAT).put("tg_user_id", tgUserId).put("state", state.wire);
            if (grant != null) {
                object.put("consent_id", grant.consentId).put("text_version", grant.textVersion).put("text_sha256", grant.textSha256)
                        .put("locale", grant.locale).put("consent_mode", grant.consentMode).put("decided_at", CustomerJson.instant(grant.decidedAt));
            }
            object.put("changed_at", CustomerJson.instant(changedAt));
            if (cause.wire != null) object.put("notice", cause.wire);
            if (activeElsewhere) object.put("active_elsewhere", true);
            return object.toString();
        }

        /**
         * The record of a parsed stored JSON object for Telegram user {@code tgUserId}, or null when it is not a valid
         * record of that user (format, state, all-or-nothing grant fields, required grant for every state but declined).
         */
        public static Record fromTree(Object tree, long tgUserId) {
            Map<String, Object> object = CustomerJson.object(tree);
            if (object == null) return null;
            Long format = CustomerJson.integral(object.get("v"));
            Long tg = CustomerJson.integral(object.get("tg_user_id"));
            State state = State.of(CustomerJson.string(object.get("state")));
            long changedAt = CustomerJson.parseInstant(CustomerJson.string(object.get("changed_at")));
            if (format == null || format != FORMAT || tg == null || tg != tgUserId || state == null || state == State.NONE || changedAt < 0) {
                return null;
            }
            Grant grant = null;
            if (object.containsKey("consent_id")) {
                Long version = CustomerJson.integral(object.get("text_version"));
                grant = new Grant(CustomerJson.string(object.get("consent_id")), version == null ? 0 : (int) Math.max(0, Math.min(version, Integer.MAX_VALUE)),
                        CustomerJson.string(object.get("text_sha256")), CustomerJson.string(object.get("locale")),
                        CustomerJson.string(object.get("consent_mode")), CustomerJson.parseInstant(CustomerJson.string(object.get("decided_at"))));
                if (!grant.isValid()) return null;
            }
            if (grant == null && state != State.DECLINED) return null;
            Notice cause = "link_ended".equals(object.get("notice")) ? Notice.LINK_ENDED : Notice.RECONSENT;
            return new Record(tgUserId, state, grant, changedAt, cause, Boolean.TRUE.equals(object.get("active_elsewhere")));
        }
    }

    /** One item of a {@code POST /v1/profile/consents} answer's {@code results} (contract §2.4.1, amendment A4). */
    public static final class Result {
        public final String consentId;
        /** recorded | superseded | duplicate. */
        public final String outcome;
        /** First recorded for another account: a duplicate that is never effective here. */
        public final boolean foreign;

        public Result(String consentId, String outcome, boolean foreign) {
            this.consentId = consentId;
            this.outcome = outcome;
            this.foreign = foreign;
        }

        /** The result of a parsed answer item, or null when it names no consent id or an unknown outcome. */
        public static Result fromTree(Object tree) {
            Map<String, Object> object = CustomerJson.object(tree);
            if (object == null) return null;
            String id = CustomerJson.string(object.get("consent_id"));
            String outcome = CustomerJson.string(object.get("outcome"));
            if (!CustomerJson.isUuid4(id) || !("recorded".equals(outcome) || "superseded".equals(outcome) || "duplicate".equals(outcome))) {
                return null;
            }
            return new Result(id, outcome, Boolean.TRUE.equals(object.get("foreign")));
        }
    }

    /** The effective-consent block (contract §2.4.2). */
    public static final class Effective {
        /** granted | withdrawn | none. */
        public final String state;
        public final String consentId;
        public final int textVersion;
        /** Epoch seconds, or 0 when absent. */
        public final long effectiveAt;

        public Effective(String state, String consentId, int textVersion, long effectiveAt) {
            this.state = state;
            this.consentId = consentId;
            this.textVersion = textVersion;
            this.effectiveAt = Math.max(0, effectiveAt);
        }

        /** The block of a parsed {@code consent} object, or null when its state is not granted, withdrawn or none. */
        public static Effective fromTree(Object tree) {
            Map<String, Object> object = CustomerJson.object(tree);
            if (object == null) return null;
            String state = CustomerJson.string(object.get("state"));
            if (!("granted".equals(state) || "withdrawn".equals(state) || "none".equals(state))) return null;
            String id = CustomerJson.string(object.get("consent_id"));
            Long version = CustomerJson.integral(object.get("text_version"));
            return new Effective(state, CustomerJson.isUuid4(id) ? id : null,
                    version == null || version < 1 || version > ConsentManifest.MAX_VERSION ? 0 : version.intValue(),
                    CustomerJson.parseInstant(CustomerJson.string(object.get("effective_at"))));
        }
    }

    /** A {@code POST /v1/profile/consents} 200 answer: its results and effective block. */
    public static final class Answer {
        public final List<Result> results;
        /** Null when the answer carried no readable block. */
        public final Effective effective;

        public Answer(List<Result> results, Effective effective) {
            this.results = Collections.unmodifiableList(new ArrayList<>(results));
            this.effective = effective;
        }

        /** The answer of a parsed 200 body; unreadable items are left out. */
        public static Answer fromTree(Object tree) {
            Map<String, Object> object = CustomerJson.object(tree);
            ArrayList<Result> results = new ArrayList<>();
            List<?> items = object == null ? null : CustomerJson.array(object.get("results"));
            if (items != null) {
                for (Object item : items) {
                    Result result = Result.fromTree(item);
                    if (result != null) results.add(result);
                }
            }
            return new Answer(results, object == null ? null : Effective.fromTree(object.get("consent")));
        }
    }

    /** The outcome of one transition. */
    public static final class Step {
        /** The snapshot to store and publish (the same instance when nothing changed). */
        public final ConsentSnapshot snapshot;
        public final boolean changed;
        /** Purge this account's event queue (withdrawal, remote withdrawal, rejected grant, link ended). */
        public final boolean purgeEvents;
        /** 403 {@code withdrawn} not newer than the local grant: upload the local grant again (once per process). */
        public final boolean reuploadGrant;
        /** 403 {@code no_consent}: upload the outbox, then retry the call once (once per process). */
        public final boolean uploadThenRetry;

        private Step(ConsentSnapshot snapshot, boolean changed, boolean purgeEvents, boolean reuploadGrant, boolean uploadThenRetry) {
            this.snapshot = snapshot;
            this.changed = changed;
            this.purgeEvents = purgeEvents;
            this.reuploadGrant = reuploadGrant;
            this.uploadThenRetry = uploadThenRetry;
        }

        static Step unchanged(ConsentSnapshot snapshot) { return new Step(snapshot, false, false, false, false); }

        static Step changed(ConsentSnapshot snapshot, boolean purgeEvents) { return new Step(snapshot, true, purgeEvents, false, false); }
    }

    // ---- Wall predicate (design 50 §5.6) ----

    /**
     * Required: every effective state but granted blocks (declined too: a record from an optional build never bypasses a
     * required one). Optional: none and needs_reconsent prompt until the user decides; nothing else. A rejected latest
     * grant is effective granted here and never raises the wall (contract §6.3).
     */
    public static Wall wall(Mode mode, State effective) {
        if (mode == Mode.OPTIONAL) return effective == State.NONE || effective == State.NEEDS_RECONSENT ? Wall.PROMPT : Wall.NONE;
        return effective == State.GRANTED ? Wall.NONE : Wall.BLOCKING;
    }

    // ---- User decisions ----

    /**
     * "I agree" with the age box ticked: from none, declined, withdrawn, withdrawn_remote, needs_reconsent (stored or
     * derived) or a granted record whose latest grant was rejected → granted with a new grant event appended to the
     * outbox. Lifts a binding loss and a stale grant (the new decided_at is after both). An account that is already
     * effectively granted is left unchanged.
     */
    public static Step grant(ConsentSnapshot s, ConsentText.Shown text, Mode mode, String consentId, long now, String appVersion,
            String authority) {
        if (!s.grantable()) return Step.unchanged(s);
        long at = nextInstant(s, now);
        ConsentEvent event = ConsentEvent.grant(consentId, s.tgUserId, text.scopes, text.version, text.sha256, text.locale, mode.wire, at,
                appVersion);
        Grant grant = new Grant(consentId, text.version, text.sha256, text.locale, mode.wire, at);
        Record record = new Record(s.tgUserId, State.GRANTED, grant, at, Notice.NONE, false);
        return Step.changed(withEvent(s, record, event, authority), false);
    }

    /**
     * Optional mode only: "I don't agree", the close glyph or system Back on the prompt (ui-strings §1 rule 3, contract
     * §6.2 "dismiss → declined") turns none or needs_reconsent into declined; nothing is uploaded. In required mode
     * "I don't agree" only shows the decline page and records nothing (design 50 §20 issue 16).
     */
    public static Step decline(ConsentSnapshot s, Mode mode, long now) {
        State effective = s.effectiveState();
        if (mode != Mode.OPTIONAL || (effective != State.NONE && effective != State.NEEDS_RECONSENT)) return Step.unchanged(s);
        Record record = new Record(s.tgUserId, State.DECLINED, s.record.grant, nextInstant(s, now), Notice.NONE, false);
        return Step.changed(s.with(record, s.outbox, s.acked, s.rejected, s.bindingLostAt), false);
    }

    /**
     * The confirmed withdrawal (design 50 §12): from {@link ConsentSnapshot#withdrawable} states → withdrawn, with a
     * withdraw event (all scopes, the latest grant's version, {@code locale} = the language of the screen) appended to the
     * outbox. The event queue goes (purgeEvents); the binding stays.
     */
    public static Step withdraw(ConsentSnapshot s, Mode mode, String locale, String consentId, long now, String appVersion,
            String authority) {
        if (!s.withdrawable()) return Step.unchanged(s);
        long at = nextInstant(s, now);
        int version = s.record.grant == null ? 0 : s.record.grant.textVersion;
        ConsentEvent event = ConsentEvent.withdraw(consentId, s.tgUserId, ConsentManifest.SCOPES, version, locale, mode.wire, at, appVersion);
        Record record = new Record(s.tgUserId, State.WITHDRAWN, s.record.grant, at, Notice.NONE, false);
        return Step.changed(withEvent(s, record, event, authority), true);
    }

    // ---- Server answers (contract §6.3; design 50 §5.5) ----

    /**
     * 403 {@code consent_required} on profile/sync or profile/events for a stored grant: {@code withdrawn} newer than the
     * grant → withdrawn_remote (stop traffic, purge events; the record dates it at {@code effectiveAt}); {@code withdrawn}
     * not newer → re-upload the local grant; {@code no_consent} → upload the outbox and retry once;
     * {@code reconsent_required} → needs_reconsent (RECONSENT). Other states and reasons change nothing.
     */
    public static Step consentRequired(ConsentSnapshot s, String reason, long effectiveAt, long now) {
        if (s.record.state != State.GRANTED || s.record.grant == null) return Step.unchanged(s);
        if ("withdrawn".equals(reason)) {
            if (effectiveAt > s.record.grant.decidedAt) return Step.changed(withState(s, State.WITHDRAWN_REMOTE, effectiveAt, Notice.NONE), true);
            return new Step(s, false, false, true, false);
        }
        if ("no_consent".equals(reason)) return new Step(s, false, false, false, true);
        if ("reconsent_required".equals(reason)) return Step.changed(withState(s, State.NEEDS_RECONSENT, now, Notice.RECONSENT), false);
        return Step.unchanged(s);
    }

    /**
     * A 200 answer of {@code POST /v1/profile/consents} at {@code authority}: every result (recorded, superseded or
     * duplicate) of an outbox entry is acked there; then the effective block applies to a stored grant — our grant
     * answered recorded or duplicate while the effective state is none (an erased account, a foreign duplicate) →
     * needs_reconsent (LINK_ENDED, purge; the erasure re-link mitigation); a withdrawal newer than the grant →
     * withdrawn_remote (purge) — and to a stored withdrawal: our withdraw answered superseded while the effective state
     * is granted → still withdrawn, {@link Record#activeElsewhere} (never re-issued automatically).
     */
    public static Step consentsAnswered(ConsentSnapshot s, String authority, List<Result> results, Effective effective, long now) {
        LinkedHashMap<String, Set<String>> acked = copyAcked(s.acked);
        boolean changed = false;
        if (authority != null && results != null) {
            for (Result result : results) {
                if (result == null || s.event(result.consentId) == null) continue;
                Set<String> ids = acked.get(authority);
                if (ids == null) acked.put(authority, ids = new LinkedHashSet<>());
                changed |= ids.add(result.consentId);
            }
        }
        ConsentSnapshot next = changed ? s.with(s.record, s.outbox, acked, s.rejected, s.bindingLostAt) : s;
        Record record = s.record;
        if (effective != null && record.state == State.GRANTED && record.grant != null) {
            Result ours = find(results, record.grant.consentId);
            if (ours != null && ("recorded".equals(ours.outcome) || "duplicate".equals(ours.outcome)) && "none".equals(effective.state)) {
                return Step.changed(withState(next, State.NEEDS_RECONSENT, now, Notice.LINK_ENDED), true);
            }
            if ("withdrawn".equals(effective.state) && effective.effectiveAt > record.grant.decidedAt) {
                return Step.changed(withState(next, State.WITHDRAWN_REMOTE, effective.effectiveAt, Notice.NONE), true);
            }
        } else if (effective != null && record.state == State.WITHDRAWN && !record.activeElsewhere) {
            ConsentEvent withdrawal = s.latestWithdrawEvent();
            Result ours = withdrawal == null ? null : find(results, withdrawal.consentId);
            if (ours != null && "superseded".equals(ours.outcome) && "granted".equals(effective.state)) {
                Record elsewhere = new Record(s.tgUserId, State.WITHDRAWN, record.grant, record.changedAt, Notice.NONE, true);
                return Step.changed(next.with(elsewhere, next.outbox, next.acked, next.rejected, next.bindingLostAt), false);
            }
        }
        return changed ? Step.changed(next, false) : Step.unchanged(s);
    }

    /**
     * A terminal refusal of a consents request (the six consent_* 422s, or any other 4xx but 401/403/404/429; contract
     * §6.5): its entries are marked rejected with {@code code} and never resent. A rejected latest grant keeps the state
     * granted but stops recording and the pipeline (purge events); "Give consent" then mints a new grant.
     */
    public static Step rejected(ConsentSnapshot s, Collection<String> consentIds, String code) {
        LinkedHashMap<String, String> rejected = new LinkedHashMap<>(s.rejected);
        boolean changed = false;
        String stored = isErrorCode(code) ? code : "rejected";
        if (consentIds != null) {
            for (String id : consentIds) {
                if (id != null && s.event(id) != null && !rejected.containsKey(id)) {
                    rejected.put(id, stored);
                    changed = true;
                }
            }
        }
        if (!changed) return Step.unchanged(s);
        boolean latestGrant = s.record.state == State.GRANTED && s.record.grant != null && consentIds.contains(s.record.grant.consentId);
        return Step.changed(s.with(s.record, s.outbox, s.acked, rejected, s.bindingLostAt), latestGrant);
    }

    /**
     * 409 {@code consent_stale} on {@code bind/start} (amendment A3; the automatic link's CONSENT_STALE outcome and the
     * manual link before its retry without consent): the grant {@code bind/start} carried was recorded at or before an
     * erasure of this Telegram id, so it can never be used again. When it is still the stored latest grant (matched by
     * its exact JSON, so a newer grant made meanwhile is never touched), the record becomes needs_reconsent (LINK_ENDED)
     * and the event queue goes; auto-link stays off until a new grant (a manual link does not lift it).
     */
    public static Step consentStale(ConsentSnapshot s, String sentGrantJson, long now) {
        if (s.record.state != State.GRANTED || sentGrantJson == null || !sentGrantJson.equals(s.latestGrantJson())) return Step.unchanged(s);
        return Step.changed(withState(s, State.NEEDS_RECONSENT, now, Notice.LINK_ENDED), true);
    }

    // ---- This build's texts (iOS parity: design 40 §4.6 rows 5–6, SGCustomerClearance's stale grant) ----

    /** This build's consent templates by manifest path ("v1/uz.md"): the strictly decoded bundled file, or null. */
    public interface Templates {
        String read(String file);
    }

    /**
     * This build's {@code text_sha256} for {@code grant}'s {@code text_version} and {@code locale}, exactly as the wall
     * hashes a text it shows ({@link ConsentText#shown}: the bundled template filled with this build's operator values).
     * Null when there is no grant or manifest, this build does not bundle that version in that locale, the template cannot
     * be read, or an operator value is refused: then nothing is compared.
     */
    public static String renderedSha256(Grant grant, ConsentManifest manifest, Map<String, String> operatorValues, Templates templates) {
        if (grant == null || manifest == null || templates == null) return null;
        ConsentManifest.Version version = manifest.version(grant.textVersion);
        ConsentManifest.Text text = version == null ? null : version.text(grant.locale);
        String template = text == null ? null : templates.read(text.file);
        if (template == null) return null;
        try {
            return ConsentText.shown(version, grant.locale, template, operatorValues, manifest.placeholders).sha256;
        } catch (IllegalArgumentException refused) {
            return null;
        }
    }

    /**
     * This build renders the stored grant's text with another hash than the grant names ({@code renderedSha256} from
     * {@link #renderedSha256}; null is never compared). A published template never changes (contract §5.2), so the
     * operator values differ from those the user agreed to.
     */
    public static boolean textChanged(ConsentSnapshot s, String renderedSha256) {
        return s.record.state == State.GRANTED && s.record.grant != null && renderedSha256 != null
                && !renderedSha256.equals(s.record.grant.textSha256);
    }

    /**
     * Launch, for every stored account, selected or not: a stored grant whose text changed in this build
     * ({@link #textChanged}) becomes needs_reconsent, so no profile sync, event, Tasks request, consents re-send or link
     * carries it until a new grant of this build's text. The notice stays what the wall already explains (LINK_ENDED
     * after a binding loss), else RECONSENT; the event queue stays, as for {@code reconsent_required}. A rejected latest
     * grant counts too: the wall asks again (design 40 §4.6 row 6 comes before row 7). Nothing else changes.
     */
    public static Step operatorChanged(ConsentSnapshot s, String renderedSha256, long now) {
        if (!textChanged(s, renderedSha256)) return Step.unchanged(s);
        Notice cause = s.effectiveState() == State.NEEDS_RECONSENT ? s.notice() : Notice.RECONSENT;
        return Step.changed(withState(s, State.NEEDS_RECONSENT, now, cause), false);
    }

    // ---- Binding and link bookkeeping ----

    /**
     * The binding loss of this account at the current origin (design 50 §7.6: a server 401 cleared the binding;
     * {@code lostAt} epoch seconds, 0 = none or cleared after a link). A loss at or after the latest grant's
     * decided_at makes the effective state needs_reconsent (LINK_ENDED) at once; purgeEvents when that just happened.
     * A higher value changes nothing stored (binding.v1 keeps the loss), so ZenlyConsent publishes it without a commit
     * and the grant ends in this process even when storage fails; it then stores that grant as ended
     * ({@link #linkEnded}, {@link #feedBindingLoss}). A lower value or 0 (a later link cleared {@code lost_at}) first
     * applies {@link #linkEnded} with the current loss, so the grant that loss ended is stored as needs_reconsent in the
     * same commit as the lower value; when that commit fails ZenlyConsent publishes nothing, so the current loss stays
     * in memory and the grant stays ended in this process. Clearing the loss thus never makes a grant it ended
     * effective again, also when the commit that should have stored that grant at the loss failed.
     */
    public static Step bindingLoss(ConsentSnapshot s, long lostAt) {
        long value = Math.max(0, lostAt);
        if (value == s.bindingLostAt) return Step.unchanged(s);
        ConsentSnapshot from = value < s.bindingLostAt ? linkEnded(s).snapshot : s;
        ConsentSnapshot next = from.with(from.record, from.outbox, from.acked, from.rejected, value);
        return Step.changed(next, s.effectiveState() == State.GRANTED && next.effectiveState() == State.NEEDS_RECONSENT);
    }

    /**
     * The binding-loss rule of compliance design 60 §8 X-7(b) (its contract issues C-13 and C-21; iOS design 40 §8.1):
     * once the server ended the binding the grant linked, the grant stays ended until a NEW grant, and its consent id is
     * never again the basis of a link. So a stored grant that the recorded binding loss ended
     * ({@link ConsentSnapshot#lostAfterGrant}: the loss at or after its decided_at) is stored as needs_reconsent
     * (LINK_ENDED), dated at the loss, the record format a 409 {@code consent_stale} already writes. A later link
     * clears the loss ({@code CustomerPipeline.Binding#adopted}) but no longer makes that grant effective again, so no
     * consents upload, profile sync, event or Tasks request runs under it on the new binding; a new grant is decided
     * after the loss ({@link #nextInstant}) and lifts it. The event purge was the loss's own step
     * ({@link #bindingLoss}). Any other record, a loss before the grant and a grant already stored as ended stay
     * unchanged.
     */
    public static Step linkEnded(ConsentSnapshot s) {
        if (s.record.state != State.GRANTED || !s.lostAfterGrant()) return Step.unchanged(s);
        return Step.changed(withState(s, State.NEEDS_RECONSENT, s.bindingLostAt, Notice.LINK_ENDED), false);
    }

    /** A new binding was adopted at {@code authority} (another binding id): every entry is un-acked there again. */
    public static Step clearAcked(ConsentSnapshot s, String authority) {
        if (authority == null || !s.acked.containsKey(authority)) return Step.unchanged(s);
        LinkedHashMap<String, Set<String>> acked = copyAcked(s.acked);
        acked.remove(authority);
        return Step.changed(s.with(s.record, s.outbox, acked, s.rejected, s.bindingLostAt), false);
    }

    /**
     * Logout, a completed deletion, or a server 401 whose body says {@code account_erased} (amendment A3): any state →
     * no record (none), with no outbox, acked sets, rejected entries or binding loss.
     */
    public static ConsentSnapshot forget(long tgUserId, int minValidVersion) { return ConsentSnapshot.empty(tgUserId, minValidVersion); }

    // ---- ZenlyConsent's binding-loss feed (package-private, so the host tests drive it with commits that fail) ----

    /** One transition of an account's published snapshot. */
    interface Transition {
        Step apply(ConsentSnapshot current);
    }

    /**
     * One account of ZenlyConsent's store as its {@code update} writes it: the transition is applied to the published
     * snapshot; a changed step whose stored values differ is committed first and published only when that commit
     * succeeded. Returns the step, or null when the commit failed (nothing published).
     */
    interface Store {
        Step update(Transition transition);
    }

    /**
     * ZenlyConsent.setBindingLoss, as two writes of {@code store}: {@link #bindingLoss} (a higher loss is published
     * without a commit; a lower one or 0 is published together with the grant the current loss ended, or not at all
     * when that commit fails), then {@link #linkEnded} on every feed, changed value or not, so the grant the
     * published loss ended is stored by the first feed whose commit succeeds. Returns the first write's step (its
     * purgeEvents), or null when that write failed.
     */
    static Step feedBindingLoss(Store store, final long lostAt) {
        Step loss = store.update(current -> bindingLoss(current, lostAt));
        store.update(ConsentMachine::linkEnded);
        return loss;
    }

    // ---- Time ----

    /**
     * The instant of a new decision: {@code now} (the trusted clock), but at least one second after every event in the
     * outbox, the latest grant, the latest state change and a recorded binding loss.
     */
    public static long nextInstant(ConsentSnapshot s, long now) {
        long floor = Math.max(0, now);
        for (ConsentEvent event : s.outbox) floor = Math.max(floor, event.recordedAt + 1);
        if (s.record.grant != null) floor = Math.max(floor, s.record.grant.decidedAt + 1);
        if (s.record.state != State.NONE) floor = Math.max(floor, s.record.changedAt + 1);
        if (s.bindingLostAt > 0) floor = Math.max(floor, s.bindingLostAt + 1);
        return Math.min(floor, CustomerJson.MAX_INSTANT);
    }

    /**
     * The instant a binding loss (a server 401 that cleared the binding) is recorded at when it reaches ZenlyCustomer:
     * {@code now} (the trusted clock), but never before the latest grant's decided_at. {@link #nextInstant} can place a
     * grant ahead of the clock (a second after an earlier event, or before the 401's server_time moved the offset back),
     * and a loss stamped before such a grant would leave it effective, so it could link automatically once the loss was
     * delivered. A grant made after the loss is still effective: nextInstant puts it after the recorded loss.
     */
    public static long lossInstant(ConsentSnapshot s, long now) {
        long at = Math.max(0, now);
        if (s != null && s.record.grant != null) at = Math.max(at, s.record.grant.decidedAt);
        return Math.min(at, CustomerJson.MAX_INSTANT);
    }

    // ---- Helpers ----

    private static ConsentSnapshot withState(ConsentSnapshot s, State state, long changedAt, Notice cause) {
        Record record = new Record(s.tgUserId, state, s.record.grant, Math.max(0, Math.min(changedAt, CustomerJson.MAX_INSTANT)), cause, false);
        return s.with(record, s.outbox, s.acked, s.rejected, s.bindingLostAt);
    }

    /**
     * {@code s} with {@code record} and {@code event} appended to the outbox. Over {@link #MAX_OUTBOX} the oldest entry
     * acked at {@code authority} or rejected goes first, then the oldest older grant; the latest grant, the latest
     * acked entry and an un-acked withdrawal are never dropped. Acked and rejected marks of dropped entries go with them.
     */
    private static ConsentSnapshot withEvent(ConsentSnapshot s, Record record, ConsentEvent event, String authority) {
        ArrayList<ConsentEvent> outbox = new ArrayList<>(s.outbox);
        outbox.add(event);
        String keepGrant = record.grant == null ? null : record.grant.consentId;
        while (outbox.size() > MAX_OUTBOX) {
            String keepAcked = latestAcked(outbox, s.acked);
            int drop = -1;
            for (int i = 0; i < outbox.size() - 1 && drop < 0; i++) {
                String id = outbox.get(i).consentId;
                if (id.equals(keepGrant) || id.equals(keepAcked)) continue;
                if (s.rejected.containsKey(id) || (authority != null && s.isAcked(authority, id))) drop = i;
            }
            for (int i = 0; i < outbox.size() - 1 && drop < 0; i++) {
                ConsentEvent candidate = outbox.get(i);
                if (candidate.isGrant() && !candidate.consentId.equals(keepGrant) && !candidate.consentId.equals(keepAcked)) drop = i;
            }
            if (drop < 0) break;
            outbox.remove(drop);
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (ConsentEvent entry : outbox) kept.add(entry.consentId);
        LinkedHashMap<String, Set<String>> acked = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : s.acked.entrySet()) {
            LinkedHashSet<String> ids = new LinkedHashSet<>(entry.getValue());
            ids.retainAll(kept);
            if (!ids.isEmpty()) acked.put(entry.getKey(), ids);
        }
        LinkedHashMap<String, String> rejected = new LinkedHashMap<>(s.rejected);
        rejected.keySet().retainAll(kept);
        return s.with(record, outbox, acked, rejected, s.bindingLostAt);
    }

    /** The consent id of the last outbox entry acked at any authority, or null. */
    static String latestAcked(List<ConsentEvent> outbox, Map<String, Set<String>> acked) {
        for (int i = outbox.size() - 1; i >= 0; i--) {
            String id = outbox.get(i).consentId;
            for (Set<String> ids : acked.values()) {
                if (ids.contains(id)) return id;
            }
        }
        return null;
    }

    private static Result find(List<Result> results, String consentId) {
        if (results == null) return null;
        for (Result result : results) {
            if (result != null && consentId.equals(result.consentId)) return result;
        }
        return null;
    }

    private static LinkedHashMap<String, Set<String>> copyAcked(Map<String, Set<String>> acked) {
        LinkedHashMap<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : acked.entrySet()) copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        return copy;
    }

    /** {@code error_code} (contract §0.3): {@code ^[a-z0-9][a-z0-9_.-]{0,63}$}. */
    static boolean isErrorCode(String code) {
        if (code == null || code.isEmpty() || code.length() > 64) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (i > 0 && (c == '_' || c == '.' || c == '-'));
            if (!ok) return false;
        }
        return true;
    }
}

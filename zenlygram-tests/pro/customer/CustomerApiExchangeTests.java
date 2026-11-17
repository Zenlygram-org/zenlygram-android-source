package org.telegram.messenger.zenlygram.pro;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * BOUNDED API STATE-MACHINE PROOF, not ZenlyPro integration: runs the real CustomerApi facade, exchange
 * and classifier against a recording host Slot that models ZenlyPro's documented CustomerBoundary
 * contract (restamp = same identity + generation with the freshest token, refusals for held, unlinked,
 * rotation pending and the route cooldown, fresh device headers per attempt, Idempotency-Key only for
 * account/delete, confirm/invalidate = compare the exact signed credential). It proves the decisions
 * made by production CustomerApi code: credentials only as headers and never a reserved key in a body,
 * one re-sign on clock_skew, any other real 401 invalidates exactly that attempt with its code and
 * reason, nonce_replayed keeps the credential, per-route 429 cooldowns, the user lane and a reused
 * Idempotency-Key for deletion, possiblySent, the final send (logout or user Unlink) and the session bookkeeping. It does
 * NOT exercise the real ZenlyPro session store, keystore signing, binding listener, Android threads or
 * any server; no reply here is a real delivery receipt. All identifiers are synthetic test values.
 */
public final class CustomerApiExchangeTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    static final String PRINCIPAL = "01jbtest0000000000000000aa", BINDING = "01jbtest0000000000000000bb";
    static final String DEVICE = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0";
    static final String KEY = "5f0c3f5e-8f2a-4b7c-9d1e-2a3b4c5d6e7f";
    static final String T = "2026-11-01T00:00:00Z";
    static final Set<String> CREDENTIAL_HEADERS = new HashSet<>(Arrays.asList(
            "Authorization", "X-Device-Id", "X-Device-Nonce", "X-Device-Ts", "X-Device-Sig"));

    /** Host model of ZenlyPro's side of CustomerApi.Boundary (see class comment). */
    static class Slot implements CustomerApi.Boundary {
        long telegramId = 42, generation = 7;
        String token = "token-1", principal = PRINCIPAL, bindingId = BINDING;
        boolean held, rotationPending, queueWork, queueDelivery, refuseExecute;
        int captures, sends, confirms, nonces, rotationKicks, finalCaptures, signFinals, bookkeepingWrites;
        final Deque<Object> script = new ArrayDeque<>();
        final List<Attempt> prepared = new ArrayList<>(), sent = new ArrayList<>(), invalidated = new ArrayList<>();
        final List<String> invalidations = new ArrayList<>();
        final List<Long> serverTimes = new ArrayList<>();
        final Map<String, Integer> cooldowns = new HashMap<>();
        final List<Runnable> queued = new ArrayList<>(), deliveries = new ArrayList<>();
        final List<CustomerApi.Lane> lanes = new ArrayList<>();
        final List<Long> forgotten = new ArrayList<>();
        CustomerApi.Bookkeeping bookkeeping = new CustomerApi.Bookkeeping(null, 0, CustomerApi.DEFAULT_MIN_INTERVAL_SECONDS, false);
        Runnable duringSend;

        /** One signed attempt exactly as the model would put it on the wire. */
        static final class Attempt {
            final long telegramId, generation;
            final String token, principal, bindingId, route, method;
            final byte[] body;
            final Map<String, String> headers;
            final boolean finalSend;

            Attempt(long telegramId, long generation, String token, String principal, String bindingId, String route, String method,
                    byte[] body, Map<String, String> headers, boolean finalSend) {
                this.telegramId = telegramId;
                this.generation = generation;
                this.token = token;
                this.principal = principal;
                this.bindingId = bindingId;
                this.route = route;
                this.method = method;
                this.body = body;
                this.headers = headers;
                this.finalSend = finalSend;
            }
        }

        boolean linked() { return token != null && !held; }

        @Override public CustomerApi.Identity identity() {
            return new CustomerApi.Identity(telegramId, linked() ? principal : null, linked() ? bindingId : null, generation);
        }

        @Override public Object capture(CustomerApi.Identity expected) throws CustomerApi.Stop {
            if (!expected.equals(identity())) throw CustomerApi.stopFor("session_changed", 0);
            captures++;
            return new long[]{telegramId, generation};
        }

        @Override public void execute(CustomerApi.Lane lane, Runnable work) {
            if (refuseExecute) throw new RejectedExecutionException("lane shut down");
            lanes.add(lane);
            if (queueWork) queued.add(work);
            else work.run();
        }

        @Override public void deliver(Runnable callback) {
            if (queueDelivery) deliveries.add(callback);
            else callback.run();
        }

        Map<String, String> sign(String credential) {
            nonces++;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + credential);
            headers.put("X-Device-Id", DEVICE);
            headers.put("X-Device-Nonce", "nonce-" + nonces);
            headers.put("X-Device-Ts", "1792512000");
            headers.put("X-Device-Sig", "sig-" + nonces);
            return headers;
        }

        @Override public Object prepare(Object ticket, String route, String method, byte[] body, String idempotencyKey) throws CustomerApi.Stop {
            long[] stamp = (long[]) ticket;
            if (stamp[0] != telegramId || stamp[1] != generation) throw CustomerApi.stopFor("session_changed", 0);
            if (held) throw CustomerApi.stopFor("not_configured", 0);
            if (token == null) throw CustomerApi.stopFor("link_required", 0);
            if (rotationPending) {
                rotationKicks++;
                throw CustomerApi.stopFor("rotation_pending", 0);
            }
            Integer cooling = cooldowns.get(route);
            if (cooling != null) throw CustomerApi.stopFor("rate_limited", cooling);
            Map<String, String> headers = sign(token);
            if (idempotencyKey != null && CustomerApi.ROUTE_DELETE.equals(route)) headers.put("Idempotency-Key", idempotencyKey);
            Attempt attempt = new Attempt(telegramId, generation, token, principal, bindingId, route, method, body, headers, false);
            prepared.add(attempt);
            return attempt;
        }

        @Override public ProTransport.CustomerReply send(Object attempt) throws Exception {
            sends++;
            sent.add((Attempt) attempt);
            if (duringSend != null) duringSend.run();
            Object next = script.poll();
            if (next instanceof Exception) throw (Exception) next;
            if (next == null) throw new IllegalStateException("unscripted attempt");
            return (ProTransport.CustomerReply) next;
        }

        @Override public void settle(Object attempt, long serverTimeSeconds, int retryAfterSeconds) {
            serverTimes.add(serverTimeSeconds);
            if (retryAfterSeconds >= 0) cooldowns.put(((Attempt) attempt).route, retryAfterSeconds);
        }

        private void requireCurrent(Attempt attempt) throws CustomerApi.Stop {
            if (attempt.finalSend || attempt.telegramId != telegramId || attempt.generation != generation || token == null
                    || !token.equals(attempt.token) || !principal.equals(attempt.principal) || !bindingId.equals(attempt.bindingId)) {
                throw CustomerApi.stopFor("session_changed", 0);
            }
        }

        @Override public void confirm(Object attempt) throws CustomerApi.Stop {
            confirms++;
            requireCurrent((Attempt) attempt);
        }

        @Override public void invalidate(Object attempt, String code, String reason) throws CustomerApi.Stop {
            requireCurrent((Attempt) attempt);
            invalidated.add((Attempt) attempt);
            invalidations.add(code + "|" + reason);
            token = null;
        }

        @Override public Object captureFinal(CustomerApi.Identity expected) throws CustomerApi.Stop {
            finalCaptures++;
            if (!expected.equals(identity())) throw CustomerApi.stopFor("session_changed", 0);
            if (held) throw CustomerApi.stopFor("not_configured", 0);
            return new String[]{token, principal, bindingId};
        }

        @Override public Object signFinal(Object captured, byte[] body) {
            signFinals++;
            String[] capture = (String[]) captured;
            return new Attempt(telegramId, generation, capture[0], capture[1], capture[2], CustomerApi.ROUTE_CONSENTS, "POST", body,
                    sign(capture[0]), true);
        }

        @Override public CustomerApi.Bookkeeping bookkeeping(CustomerApi.Identity expected) {
            return expected.equals(identity()) && identity().isLinked() ? bookkeeping : null;
        }

        @Override public boolean updateBookkeeping(CustomerApi.Identity expected, CustomerApi.BookkeepingChange change) {
            if (!expected.equals(identity()) || !identity().isLinked()) return false;
            CustomerApi.Bookkeeping next = change.apply(bookkeeping);
            if (next == null) return false;
            if (next != bookkeeping) {
                bookkeeping = next;
                bookkeepingWrites++;
            }
            return true;
        }

        @Override public boolean forgetSession(long telegramUserId) {
            forgotten.add(telegramUserId);
            if (telegramUserId == telegramId) {
                token = null;
                generation++;
            }
            return true;
        }
    }

    static final class Captured implements CustomerApi.Callback {
        CustomerApi.Reply reply;
        int calls;
        @Override public void done(CustomerApi.Reply value) { reply = value; calls++; }
    }

    public static void main(String[] args) {
        routesAndLanes();
        credentialsOnlyAsHeaders();
        bodyRefusals();
        clockSkew();
        unauthorized();
        cooldownsAndRotation();
        localRefusals();
        deletion();
        possiblySent();
        classification();
        deliveryFence();
        finalSend();
        bookkeeping();
        envelopeAndCodes();
        if (!failures.isEmpty()) {
            System.out.println("Customer exchange: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Customer exchange (bounded state-machine proof): " + checks + " checks passed");
    }

    // ---- Fixtures (synthetic) ------------------------------------------------------------------

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static byte[] consentsBody() {
        return utf8("{\"schema_version\":1,\"consents\":[{\"consent_id\":\"0b6c1f4e-1111-4a2b-8c3d-000000000001\",\"tg_user_id\":42,"
                + "\"action\":\"withdraw\",\"scopes\":[\"link\",\"profile\",\"statistics\",\"tasks_sync\"],\"text_version\":1,"
                + "\"locale\":\"uz\",\"consent_mode\":\"required\",\"recorded_at\":\"" + T + "\",\"platform\":\"android\",\"app_version\":\"12.0.1\"}]}");
    }

    static byte[] syncBody() {
        return utf8("{\"schema_version\":1,\"observed_at\":\"" + T + "\",\"app_version\":\"12.0.1\",\"os_version\":\"16\","
                + "\"telegram\":{\"user_id\":42,\"phone\":null,\"usernames\":[],\"first_name\":\"Test\",\"last_name\":null,\"is_premium\":false},"
                + "\"device\":{\"platform\":\"android\",\"device_class\":\"phone\",\"device_model\":\"Test Device\",\"app_build\":\"1\","
                + "\"ui_language\":\"uz\",\"locale\":\"uz-UZ\",\"timezone\":\"Asia/Tashkent\",\"distribution\":\"debug\"}}");
    }

    /** Nested keys named like reserved ones ("ts" inside an event, "device" objects) are not credentials. */
    static byte[] eventsBody() {
        return utf8("{\"schema_version\":1,\"taxonomy_version\":1,\"batch_id\":\"0b6c1f4e-2222-4a2b-8c3d-000000000002\",\"sent_at\":\"" + T + "\","
                + "\"context\":{\"platform\":\"android\",\"device_class\":\"phone\",\"os_version\":\"16\",\"app_version\":\"12.0.1\"},"
                + "\"events\":[{\"id\":\"0b6c1f4e-3333-4a2b-8c3d-000000000003\",\"name\":\"app.session_start\",\"ts\":\"" + T + "\","
                + "\"session_id\":\"0b6c1f4e-4444-4a2b-8c3d-000000000004\",\"seq\":0,\"props\":{\"nonce\":\"not-a-credential\"}}]}");
    }

    static String ok() { return "{\"schema_version\":1,\"ingest\":\"stored\",\"min_interval_s\":21600,\"server_time\":\"" + T + "\"}"; }

    static String error(String code, String extras) {
        return "{\"error\":\"" + code + "\",\"message\":\"generic\",\"server_time\":\"" + T + "\"" + (extras == null ? "" : "," + extras) + "}";
    }

    static ProTransport.CustomerReply reply(int status, String body) { return new ProTransport.CustomerReply(status, body, 0, false); }

    static ProTransport.CustomerReply reply(int status, String body, int retryAfter) {
        return new ProTransport.CustomerReply(status, body, retryAfter, false);
    }

    static long t() { return ProPolicy.utc(T); }

    static Captured consents(Slot slot, Object... replies) {
        Collections.addAll(slot.script, replies);
        Captured captured = new Captured();
        new CustomerApi(slot).postConsents(slot.identity(), consentsBody(), captured);
        return captured;
    }

    static Captured delete(Slot slot, String key, Object... replies) {
        Collections.addAll(slot.script, replies);
        Captured captured = new Captured();
        new CustomerApi(slot).deleteAccount(slot.identity(), key, captured);
        return captured;
    }

    static Set<String> topLevelKeys(byte[] body) {
        JsonElement root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        return root.isJsonObject() ? root.getAsJsonObject().keySet() : Collections.<String>emptySet();
    }

    static boolean isKind(Captured captured, CustomerApi.Kind kind) {
        return captured.calls == 1 && captured.reply != null && captured.reply.kind == kind;
    }

    // ---- Suites --------------------------------------------------------------------------------

    /** Each route: exact method, route, body bytes and lane; OK only after confirm; server time settled. */
    private static void routesAndLanes() {
        Object[][] calls = {
                {CustomerApi.ROUTE_CONSENTS, "POST", consentsBody()},
                {CustomerApi.ROUTE_SYNC, "POST", syncBody()},
                {CustomerApi.ROUTE_ME, "GET", null},
                {CustomerApi.ROUTE_EVENTS, "POST", eventsBody()},
        };
        for (Object[] call : calls) {
            Slot slot = new Slot();
            slot.script.add(reply(200, ok()));
            Captured captured = new Captured();
            CustomerApi api = new CustomerApi(slot);
            byte[] body = (byte[]) call[2];
            if (CustomerApi.ROUTE_CONSENTS.equals(call[0])) api.postConsents(slot.identity(), body, captured);
            else if (CustomerApi.ROUTE_SYNC.equals(call[0])) api.postProfileSync(slot.identity(), body, captured);
            else if (CustomerApi.ROUTE_ME.equals(call[0])) api.getProfileMe(slot.identity(), captured);
            else api.postEvents(slot.identity(), body, captured);
            String name = call[0] + ": ";
            check(isKind(captured, CustomerApi.Kind.OK) && ok().equals(captured.reply.body) && captured.reply.status == 200
                    && captured.reply.error == null && captured.reply.possiblySent, name + "OK with the exact answer text, delivered once");
            check(slot.sends == 1 && slot.sent.get(0).route.equals(call[0]) && slot.sent.get(0).method.equals(call[1]),
                    name + "one attempt with the exact route and method");
            check(body == null ? slot.sent.get(0).body == null : Arrays.equals(body, slot.sent.get(0).body), name + "body sent byte for byte");
            check(slot.lanes.equals(Collections.singletonList(CustomerApi.Lane.CUSTOMER)), name + "runs on the customer lane");
            check(!slot.sent.get(0).headers.containsKey("Idempotency-Key"), name + "no Idempotency-Key on a profile route");
            check(slot.confirms == 1 && slot.invalidations.isEmpty(), name + "confirmed against the current credential");
            check(slot.serverTimes.equals(Collections.singletonList(t())) && slot.cooldowns.isEmpty(), name + "server_time settled, no cooldown");
        }

        Slot slot = new Slot();
        Captured deleted = delete(slot, KEY, reply(200, "{\"schema_version\":1,\"state\":\"erased\",\"account_id\":\"" + PRINCIPAL
                + "\",\"erased_at\":\"" + T + "\",\"retained\":[\"payment_records\"],\"server_time\":\"" + T + "\"}"));
        check(isKind(deleted, CustomerApi.Kind.OK) && slot.sends == 1, "delete: OK");
        check(slot.lanes.equals(Collections.singletonList(CustomerApi.Lane.USER)), "delete runs on the user lane, never behind an upload");
        check(CustomerApi.ROUTE_DELETE.equals(slot.sent.get(0).route) && "POST".equals(slot.sent.get(0).method)
                && "{\"schema_version\":1,\"confirm\":\"delete_account\"}".equals(new String(slot.sent.get(0).body, StandardCharsets.UTF_8)),
                "delete: POST account/delete with exactly {schema_version:1, confirm:delete_account}");
        check(KEY.equals(slot.sent.get(0).headers.get("Idempotency-Key")), "delete: the caller's Idempotency-Key");

        // The caller's array is copied: changing it after the call never changes what is sent.
        Slot copied = new Slot();
        copied.queueWork = true;
        byte[] mutable = consentsBody();
        copied.script.add(reply(200, ok()));
        Captured late = new Captured();
        new CustomerApi(copied).postConsents(copied.identity(), mutable, late);
        Arrays.fill(mutable, (byte) ' ');
        copied.queued.remove(0).run();
        check(isKind(late, CustomerApi.Kind.OK) && Arrays.equals(consentsBody(), copied.sent.get(0).body), "the body is copied at call time");
    }

    /** The five credentials only as headers; bodies untouched and free of reserved keys and credential values. */
    private static void credentialsOnlyAsHeaders() {
        Slot slot = new Slot();
        CustomerApi api = new CustomerApi(slot);
        slot.script.add(reply(200, ok()));
        slot.script.add(reply(200, ok()));
        slot.script.add(reply(401, error("clock_skew", null)));
        slot.script.add(reply(200, ok()));
        slot.script.add(reply(200, ok()));
        api.postConsents(slot.identity(), consentsBody(), new Captured());
        api.postProfileSync(slot.identity(), syncBody(), new Captured());
        api.postEvents(slot.identity(), eventsBody(), new Captured());
        api.getProfileMe(slot.identity(), new Captured());
        check(slot.sends == 5, "credential sweep: five attempts (one clock_skew re-sign)");
        Set<String> nonces = new HashSet<>();
        for (Slot.Attempt attempt : slot.sent) {
            check(attempt.headers.keySet().equals(CREDENTIAL_HEADERS), attempt.route + ": exactly the five credential headers");
            nonces.add(attempt.headers.get("X-Device-Nonce"));
            if (attempt.body == null) continue;
            Set<String> keys = topLevelKeys(attempt.body);
            for (String reserved : CustomerApi.RESERVED_BODY_KEYS) {
                check(!keys.contains(reserved), attempt.route + ": no reserved key " + reserved + " in the body");
            }
            String text = new String(attempt.body, StandardCharsets.UTF_8);
            check(!text.contains(slot.token) && !text.contains(DEVICE) && !text.contains("Bearer") && !text.contains("sig-"),
                    attempt.route + ": no credential value inside the body");
        }
        check(nonces.size() == 5, "every attempt carries its own fresh nonce");
    }

    /** Bodies that never leave the device: nothing is signed or sent for them. */
    private static void bodyRefusals() {
        for (String reserved : new String[]{"refresh_token", "device_id", "nonce", "ts", "sig"}) {
            Slot slot = new Slot();
            Captured refused = new Captured();
            new CustomerApi(slot).postProfileSync(slot.identity(), utf8("{\"schema_version\":1,\"" + reserved + "\":\"x\"}"), refused);
            check(isKind(refused, CustomerApi.Kind.LOCAL) && "invalid_request".equals(refused.reply.error) && slot.prepared.isEmpty()
                    && slot.sends == 0 && !refused.reply.possiblySent, "top-level " + reserved + " in a body: refused, never signed or sent");
        }
        Slot escaped = new Slot();
        Captured escapedKey = new Captured();
        new CustomerApi(escaped).postEvents(escaped.identity(), utf8("{\"schema_version\":1,\"refresh\\u005ftoken\":\"x\"}"), escapedKey);
        check(isKind(escapedKey, CustomerApi.Kind.LOCAL) && escaped.sends == 0, "an escaped reserved key is the same key: refused");

        String[] malformed = {"[]", "null", "\"text\"", "{", "{}{}", "{\"a\":1,\"a\":2}", "{\"a\":1} x", "{'a':1}", "{\"a\":01}", ""};
        for (String body : malformed) {
            Slot slot = new Slot();
            Captured refused = new Captured();
            new CustomerApi(slot).postConsents(slot.identity(), utf8(body), refused);
            check(isKind(refused, CustomerApi.Kind.LOCAL) && "invalid_request".equals(refused.reply.error) && slot.sends == 0,
                    "not exactly one strict JSON object (" + body + "): refused locally");
        }
        Slot invalidUtf8 = new Slot();
        Captured bytes = new Captured();
        new CustomerApi(invalidUtf8).postConsents(invalidUtf8.identity(), new byte[]{'{', '"', (byte) 0xC3, '"', ':', '1', '}'}, bytes);
        check(isKind(bytes, CustomerApi.Kind.LOCAL) && invalidUtf8.sends == 0, "invalid UTF-8 body: refused locally");

        Slot missing = new Slot();
        Captured none = new Captured();
        new CustomerApi(missing).postConsents(missing.identity(), null, none);
        check(isKind(none, CustomerApi.Kind.LOCAL) && "invalid_request".equals(none.reply.error) && missing.captures == 0,
                "a POST without a body: refused before capture");

        Slot large = new Slot();
        Captured tooLarge = new Captured();
        new CustomerApi(large).postConsents(large.identity(), padded(CustomerApi.MAX_CONSENTS_BYTES + 1), tooLarge);
        check(isKind(tooLarge, CustomerApi.Kind.TOO_LARGE) && "payload_too_large".equals(tooLarge.reply.error) && tooLarge.reply.status == 0
                && large.sends == 0 && !tooLarge.reply.possiblySent, "consents above 16 KiB: TOO_LARGE locally, nothing sent");
        Slot syncLarge = new Slot();
        Captured syncTooLarge = new Captured();
        new CustomerApi(syncLarge).postProfileSync(syncLarge.identity(), padded(CustomerApi.MAX_SYNC_BYTES + 1), syncTooLarge);
        check(isKind(syncTooLarge, CustomerApi.Kind.TOO_LARGE) && syncLarge.sends == 0, "sync above 16 KiB: TOO_LARGE locally");
        Slot eventsLarge = new Slot();
        Captured eventsTooLarge = new Captured();
        new CustomerApi(eventsLarge).postEvents(eventsLarge.identity(), padded(CustomerApi.MAX_EVENTS_BYTES + 1), eventsTooLarge);
        check(isKind(eventsTooLarge, CustomerApi.Kind.TOO_LARGE) && eventsLarge.sends == 0, "events above 256 KiB: TOO_LARGE locally");
        Slot eventsAtLimit = new Slot();
        eventsAtLimit.script.add(reply(200, ok()));
        Captured atLimit = new Captured();
        new CustomerApi(eventsAtLimit).postEvents(eventsAtLimit.identity(), padded(CustomerApi.MAX_EVENTS_BYTES), atLimit);
        check(isKind(atLimit, CustomerApi.Kind.OK) && eventsAtLimit.sends == 1, "events of exactly 256 KiB are sent");
    }

    /** One JSON object of exactly {@code size} bytes. */
    static byte[] padded(int size) {
        String head = "{\"schema_version\":1,\"pad\":\"", tail = "\"}";
        char[] fill = new char[size - head.length() - tail.length()];
        Arrays.fill(fill, 'a');
        byte[] body = utf8(head + new String(fill) + tail);
        if (body.length != size) throw new IllegalStateException("fixture size");
        return body;
    }

    private static void clockSkew() {
        Slot again = new Slot();
        Captured resigned = consents(again, reply(401, error("clock_skew", null)), reply(200, ok()));
        check(isKind(resigned, CustomerApi.Kind.OK) && again.sends == 2 && again.prepared.size() == 2, "exactly one re-sign, then success");
        check(!again.prepared.get(0).headers.get("X-Device-Nonce").equals(again.prepared.get(1).headers.get("X-Device-Nonce")),
                "the retry is signed afresh (new nonce)");
        check(again.serverTimes.get(0) == t() && again.invalidations.isEmpty(), "the skew answer's server_time is settled before the retry; nothing cleared");

        Slot rotated = new Slot();
        rotated.duringSend = () -> { if (rotated.sends == 1) rotated.token = "token-2"; };
        rotated.script.add(reply(401, error("clock_skew", null)));
        rotated.script.add(reply(200, ok()));
        Captured reread = new Captured();
        new CustomerApi(rotated).postConsents(rotated.identity(), consentsBody(), reread);
        check(isKind(reread, CustomerApi.Kind.OK) && rotated.prepared.size() == 2
                && "Bearer token-2".equals(rotated.prepared.get(1).headers.get("Authorization")),
                "a rotation between the attempts: the re-sign re-reads the freshest credential and succeeds");

        Slot twice = new Slot();
        Captured skewed = consents(twice, reply(401, error("clock_skew", null)), reply(401, error("clock_skew", null)), reply(200, ok()));
        check(isKind(skewed, CustomerApi.Kind.LOCAL) && "clock_skew".equals(skewed.reply.error) && skewed.reply.status == 401
                && twice.sends == 2 && twice.invalidations.isEmpty() && "token-1".equals(twice.token) && skewed.reply.possiblySent,
                "at most one correction; a second clock_skew never clears the credential");

        Slot untimed = new Slot();
        Captured noTime = consents(untimed, reply(401, "{\"error\":\"clock_skew\",\"message\":\"m\"}"));
        check(isKind(noTime, CustomerApi.Kind.LOCAL) && untimed.sends == 1 && untimed.invalidations.isEmpty(),
                "no retry without a valid server_time");

        Slot deleting = new Slot();
        Captured deleted = delete(deleting, KEY, reply(401, error("clock_skew", null)), reply(200, "{\"schema_version\":1,\"state\":\"erased\",\"server_time\":\"" + T + "\"}"));
        check(isKind(deleted, CustomerApi.Kind.OK) && deleting.sends == 2 && KEY.equals(deleting.sent.get(0).headers.get("Idempotency-Key"))
                && KEY.equals(deleting.sent.get(1).headers.get("Idempotency-Key")), "delete: the re-signed retry reuses the same Idempotency-Key");
    }

    private static void unauthorized() {
        for (String code : new String[]{"invalid_token", "expired_token", "session_revoked", "refresh_reuse", "device_revoked",
                "device_key_mismatch", "unauthenticated"}) {
            Slot slot = new Slot();
            Captured result = consents(slot, reply(401, error(code, null)));
            check(isKind(result, CustomerApi.Kind.UNAUTHORIZED) && code.equals(result.reply.error) && result.reply.reason == null
                    && result.reply.status == 401 && result.reply.possiblySent, code + ": UNAUTHORIZED with its code");
            check(slot.invalidated.size() == 1 && slot.invalidated.get(0) == slot.sent.get(0) && (code + "|null").equals(slot.invalidations.get(0))
                    && slot.token == null && slot.sends == 1, code + ": invalidates exactly the attempt sent, with its code, once");
        }
        Slot erased = new Slot();
        Captured gone = consents(erased, reply(401, error("invalid_token", "\"reason\":\"account_erased\"")));
        check(isKind(gone, CustomerApi.Kind.UNAUTHORIZED) && "account_erased".equals(gone.reply.reason)
                && "invalid_token|account_erased".equals(erased.invalidations.get(0)), "401 reason account_erased reaches invalidate and the reply");
        Slot oddReason = new Slot();
        consents(oddReason, reply(401, error("invalid_token", "\"reason\":\"Account Erased!\"")));
        check("invalid_token|null".equals(oddReason.invalidations.get(0)), "a reason that is not a stable code is dropped (null)");
        Slot numericReason = new Slot();
        consents(numericReason, reply(401, error("device_revoked", "\"reason\":5")));
        check("device_revoked|null".equals(numericReason.invalidations.get(0)), "a non-string reason is dropped (null)");

        Slot nonce = new Slot();
        Captured replayed = consents(nonce, reply(401, error("nonce_replayed", null)));
        check(isKind(replayed, CustomerApi.Kind.UNAVAILABLE) && "nonce_replayed".equals(replayed.reply.error) && nonce.invalidations.isEmpty()
                && "token-1".equals(nonce.token) && nonce.sends == 1 && nonce.confirms == 1, "nonce_replayed: UNAVAILABLE, credential kept, no resend");

        String[] unreadable = {null, "<html>401</html>", "{\"message\":\"no code\"}", "{\"error\":\"Unauthenticated.\"}", "{\"error\":7}",
                "{\"error\":\"invalid_token\",\"error\":\"invalid_token\"}"};
        for (String body : unreadable) {
            Slot slot = new Slot();
            Captured result = consents(slot, reply(401, body));
            check(isKind(result, CustomerApi.Kind.UNAVAILABLE) && "invalid_response".equals(result.reply.error) && slot.invalidations.isEmpty()
                    && "token-1".equals(slot.token), "a 401 without a readable stable code clears nothing (" + body + ")");
        }

        Slot rotated = new Slot();
        rotated.duringSend = () -> rotated.token = "token-2";
        Captured late = consents(rotated, reply(401, error("invalid_token", null)));
        check(isKind(late, CustomerApi.Kind.STALE) && rotated.invalidations.isEmpty() && "token-2".equals(rotated.token)
                && late.reply.possiblySent, "a 401 for a credential rotated meanwhile never clears the new credential");

        for (String code : new String[]{"invalid_token", "device_revoked"}) {
            for (int status : new int[]{403, 500}) {
                Slot slot = new Slot();
                consents(slot, reply(status, error(code, null)));
                check(slot.invalidations.isEmpty() && "token-1".equals(slot.token), "the 401 family code under HTTP " + status + " clears nothing");
            }
        }
    }

    private static void cooldownsAndRotation() {
        Slot slot = new Slot();
        CustomerApi api = new CustomerApi(slot);
        slot.script.add(reply(429, null, 120));
        Captured limited = new Captured();
        api.postEvents(slot.identity(), eventsBody(), limited);
        check(isKind(limited, CustomerApi.Kind.RATE_LIMITED) && limited.reply.retryAfterSeconds == 120 && limited.reply.status == 429
                && "rate_limited".equals(limited.reply.error) && limited.reply.body == null && limited.reply.possiblySent,
                "429: RATE_LIMITED with its Retry-After");
        check(Integer.valueOf(120).equals(slot.cooldowns.get(CustomerApi.ROUTE_EVENTS)) && slot.cooldowns.size() == 1,
                "429: the cooldown is settled for exactly that route");
        Captured cooling = new Captured();
        api.postEvents(slot.identity(), eventsBody(), cooling);
        check(isKind(cooling, CustomerApi.Kind.RATE_LIMITED) && cooling.reply.retryAfterSeconds == 120 && slot.sends == 1
                && !cooling.reply.possiblySent && cooling.reply.status == 0, "the same route while cooling down: refused locally, nothing sent");
        slot.script.add(reply(200, ok()));
        Captured other = new Captured();
        api.postProfileSync(slot.identity(), syncBody(), other);
        check(isKind(other, CustomerApi.Kind.OK) && slot.sends == 2, "another route is not held by that cooldown");

        Slot bare = new Slot();
        Captured noHeader = consents(bare, reply(429, null, 0));
        check(isKind(noHeader, CustomerApi.Kind.RATE_LIMITED) && noHeader.reply.retryAfterSeconds == 1
                && Integer.valueOf(1).equals(bare.cooldowns.get(CustomerApi.ROUTE_CONSENTS)), "429 without Retry-After waits at least 1 s");
        Slot huge = new Slot();
        Captured capped = consents(huge, reply(429, null, 999999));
        check(capped.reply.retryAfterSeconds == CustomerApi.MAX_RETRY_AFTER_SECONDS
                && Integer.valueOf(CustomerApi.MAX_RETRY_AFTER_SECONDS).equals(huge.cooldowns.get(CustomerApi.ROUTE_CONSENTS)),
                "an absurd Retry-After is capped at 6 h");

        Slot rotating = new Slot();
        rotating.rotationPending = true;
        Captured pending = consents(rotating);
        check(isKind(pending, CustomerApi.Kind.LOCAL) && "rotation_pending".equals(pending.reply.error) && rotating.sends == 0
                && rotating.rotationKicks == 1 && !pending.reply.possiblySent, "rotation pending: refused locally, nothing signed or sent");
    }

    private static void localRefusals() {
        Slot unlinked = new Slot();
        unlinked.token = null;
        Captured notLinked = consents(unlinked);
        check(isKind(notLinked, CustomerApi.Kind.LOCAL) && "link_required".equals(notLinked.reply.error) && unlinked.captures == 0,
                "an unlinked identity: refused before capture");
        Slot held = new Slot();
        held.held = true;
        Captured heldReply = consents(held);
        check(isKind(heldReply, CustomerApi.Kind.LOCAL) && "link_required".equals(heldReply.reply.error) && held.sends == 0,
                "a held configuration has no linked identity: nothing is sent");
        Slot heldLater = new Slot();
        heldLater.queueWork = true;
        Captured heldAtSend = new Captured();
        new CustomerApi(heldLater).postConsents(heldLater.identity(), consentsBody(), heldAtSend);
        heldLater.held = true;
        heldLater.queued.remove(0).run();
        check(isKind(heldAtSend, CustomerApi.Kind.STALE) && heldLater.sends == 0, "held between call and send: never signed or sent");

        Slot plain = new Slot();
        Captured noIdentity = new Captured();
        new CustomerApi(plain).postConsents(null, consentsBody(), noIdentity);
        check(isKind(noIdentity, CustomerApi.Kind.LOCAL) && "invalid_request".equals(noIdentity.reply.error) && plain.captures == 0,
                "a null identity is refused");

        Slot moved = new Slot();
        CustomerApi.Identity old = moved.identity();
        moved.generation++;
        Captured stale = new Captured();
        new CustomerApi(moved).postConsents(old, consentsBody(), stale);
        check(isKind(stale, CustomerApi.Kind.STALE) && moved.captures == 0 && moved.sends == 0, "an identity that moved on: STALE, nothing captured");

        Slot switched = new Slot();
        switched.queueWork = true;
        Captured dropped = new Captured();
        new CustomerApi(switched).getProfileMe(switched.identity(), dropped);
        switched.telegramId = 43;
        switched.queued.remove(0).run();
        check(isKind(dropped, CustomerApi.Kind.STALE) && switched.sends == 0, "account switch between call and send: nothing sent");

        Slot shut = new Slot();
        shut.refuseExecute = true;
        Captured notQueued = consents(shut);
        check(isKind(notQueued, CustomerApi.Kind.LOCAL) && "storage_unavailable".equals(notQueued.reply.error) && shut.sends == 0,
                "a lane that refuses work: LOCAL, delivered once");

        boolean threw = false;
        try { new CustomerApi(new Slot()).getProfileMe(new Slot().identity(), null); }
        catch (IllegalArgumentException expected) { threw = true; }
        check(threw, "a missing callback is a programming error");

        check(CustomerApi.stopFor("not_configured", 0).reply.kind == CustomerApi.Kind.LOCAL
                && CustomerApi.stopFor("login_required", 0).reply.kind == CustomerApi.Kind.LOCAL
                && CustomerApi.stopFor("storage_unavailable", 0).reply.kind == CustomerApi.Kind.LOCAL
                && CustomerApi.stopFor("link_required", 0).reply.kind == CustomerApi.Kind.LOCAL, "Pro refusals map to LOCAL");
        check(CustomerApi.stopFor("rate_limited", 0).reply.kind == CustomerApi.Kind.RATE_LIMITED
                && CustomerApi.stopFor("rate_limited", 0).reply.retryAfterSeconds == 1, "a local cooldown maps to RATE_LIMITED (at least 1 s)");
        check(CustomerApi.stopFor("something_else", 0).reply.kind == CustomerApi.Kind.STALE, "an unknown Pro refusal maps to STALE");
    }

    private static void deletion() {
        for (String key : new String[]{null, "", "not-a-key", KEY.toUpperCase(), "5f0c3f5e-8f2a-1b7c-9d1e-2a3b4c5d6e7f",
                "5f0c3f5e-8f2a-4b7c-7d1e-2a3b4c5d6e7f", KEY + "0"}) {
            Slot slot = new Slot();
            Captured refused = delete(slot, key);
            check(isKind(refused, CustomerApi.Kind.LOCAL) && "invalid_request".equals(refused.reply.error) && slot.captures == 0
                    && slot.sends == 0, "delete with an invalid Idempotency-Key (" + key + "): refused locally");
        }
        Slot slot = new Slot();
        CustomerApi api = new CustomerApi(slot);
        slot.script.add(reply(503, error("server_error", null), 30));
        Captured first = new Captured();
        api.deleteAccount(slot.identity(), KEY, first);
        slot.script.add(reply(409, error("idempotency_in_flight", null), 30));
        Captured second = new Captured();
        api.deleteAccount(slot.identity(), KEY, second);
        slot.script.add(reply(200, "{\"schema_version\":1,\"state\":\"erased\",\"server_time\":\"" + T + "\"}"));
        Captured third = new Captured();
        api.deleteAccount(slot.identity(), KEY, third);
        check(isKind(first, CustomerApi.Kind.UNAVAILABLE) && first.reply.retryAfterSeconds == 30 && first.reply.possiblySent,
                "delete 503: UNAVAILABLE, possibly processed");
        check(isKind(second, CustomerApi.Kind.CONFLICT) && "idempotency_in_flight".equals(second.reply.error) && second.reply.retryAfterSeconds == 30,
                "delete 409 idempotency_in_flight: CONFLICT with its Retry-After");
        check(isKind(third, CustomerApi.Kind.OK) && slot.sends == 3, "delete retried: OK");
        boolean sameKey = true;
        for (Slot.Attempt attempt : slot.sent) sameKey &= KEY.equals(attempt.headers.get("Idempotency-Key"));
        check(sameKey && slot.lanes.equals(Arrays.asList(CustomerApi.Lane.USER, CustomerApi.Lane.USER, CustomerApi.Lane.USER)),
                "every delete attempt runs on the user lane with the caller's same Idempotency-Key");

        Slot gone = new Slot();
        Captured afterErasure = delete(gone, KEY, reply(401, error("device_revoked", null)));
        check(isKind(afterErasure, CustomerApi.Kind.UNAUTHORIZED) && "device_revoked".equals(afterErasure.reply.error)
                && afterErasure.reply.possiblySent && gone.invalidations.size() == 1, "a 401 on a delete is the existing session handling with its code");

        Slot replayed = new Slot();
        replayed.script.add(new ProTransport.CustomerReply(200, "{\"schema_version\":1,\"state\":\"erased\",\"server_time\":\"2026-10-01T00:00:00Z\"}",
                0, true));
        Captured stored = new Captured();
        new CustomerApi(replayed).deleteAccount(replayed.identity(), KEY, stored);
        check(isKind(stored, CustomerApi.Kind.OK) && replayed.serverTimes.equals(Collections.singletonList(0L)),
                "a replayed (stored) delete answer never moves the clock");
    }

    private static void possiblySent() {
        Slot status = new Slot();
        check(consents(status, reply(503, null)).reply.possiblySent, "an HTTP status received: possibly sent");
        Slot timedOut = new Slot();
        Captured timeout = consents(timedOut, new ProTransport.CustomerFailure(new SocketTimeoutException("read timed out"), true));
        check(isKind(timeout, CustomerApi.Kind.OFFLINE) && "connection_failed".equals(timeout.reply.error) && timeout.reply.possiblySent
                && timeout.reply.status == 0 && timedOut.confirms == 0, "a post-connect timeout: OFFLINE, possibly sent");
        Slot refused = new Slot();
        Captured connect = consents(refused, new ProTransport.CustomerFailure(new ConnectException("refused"), false));
        check(isKind(connect, CustomerApi.Kind.OFFLINE) && !connect.reply.possiblySent, "a connect failure: OFFLINE, not sent");
        Slot oversized = new Slot();
        Captured unreadable = consents(oversized, new ProTransport.CustomerFailure(rejected(ProMonetization.Reason.MALFORMED), true));
        check(isKind(unreadable, CustomerApi.Kind.UNAVAILABLE) && "invalid_response".equals(unreadable.reply.error) && unreadable.reply.possiblySent,
                "an oversized or non-UTF-8 answer: UNAVAILABLE, possibly sent");
        Slot deadline = new Slot();
        Captured late = consents(deadline, new ProTransport.CustomerFailure(rejected(ProMonetization.Reason.TIMED_OUT), true));
        check(isKind(late, CustomerApi.Kind.OFFLINE) && late.reply.possiblySent, "the total deadline while reading: OFFLINE, possibly sent");
        Slot plain = new Slot();
        Captured io = consents(plain, new IOException("boundary failure"));
        check(isKind(io, CustomerApi.Kind.OFFLINE) && !io.reply.possiblySent, "a failure not attributed to a connection: not sent");

        Slot retried = new Slot();
        Captured afterSkew = consents(retried, reply(401, error("clock_skew", null)),
                new ProTransport.CustomerFailure(new ConnectException("refused"), false));
        check(isKind(afterSkew, CustomerApi.Kind.OFFLINE) && afterSkew.reply.possiblySent, "a status on the first attempt keeps the call possibly sent");
        Slot clearedBetween = new Slot();
        clearedBetween.duringSend = () -> { if (clearedBetween.sends == 1) clearedBetween.token = null; };
        Captured refusedRetry = consents(clearedBetween, reply(401, error("clock_skew", null)));
        check(isKind(refusedRetry, CustomerApi.Kind.STALE) && refusedRetry.reply.possiblySent && clearedBetween.sends == 1,
                "a retry refused after a received status is still possibly sent");
        Slot relinked = new Slot();
        relinked.duringSend = () -> relinked.generation++;
        Captured withheld = consents(relinked, reply(200, ok()));
        check(isKind(withheld, CustomerApi.Kind.STALE) && withheld.reply.possiblySent && withheld.reply.body == null,
                "success for a binding that moved on: withheld as STALE, possibly sent");
        Slot local = new Slot();
        local.rotationPending = true;
        check(!consents(local).reply.possiblySent, "a local refusal: never sent");
    }

    private static Exception rejected(ProMonetization.Reason reason) {
        try {
            ProMonetization.readBounded(new ByteArrayInputStream(reason == ProMonetization.Reason.MALFORMED ? new byte[10] : new byte[0]),
                    reason == ProMonetization.Reason.MALFORMED ? 1 : 10, reason == ProMonetization.Reason.TIMED_OUT ? 0 : Long.MAX_VALUE, () -> 1L);
        } catch (Exception e) {
            return e;
        }
        throw new IllegalStateException("expected a rejection");
    }

    private static void classification() {
        Object[][] table = {
                {403, error("consent_required", "\"reason\":\"no_consent\",\"consent\":{\"state\":\"none\",\"consent_id\":null,\"text_version\":null,\"effective_at\":null}"),
                        CustomerApi.Kind.FORBIDDEN, "consent_required", "no_consent"},
                {403, error("consent_required", "\"reason\":\"withdrawn\""), CustomerApi.Kind.FORBIDDEN, "consent_required", "withdrawn"},
                {403, error("profile_account_not_linked", null), CustomerApi.Kind.FORBIDDEN, "profile_account_not_linked", null},
                {403, "<html>forbidden</html>", CustomerApi.Kind.FORBIDDEN, "request_rejected", null},
                {404, error("not_found", null), CustomerApi.Kind.NOT_FOUND, "not_found", null},
                {404, null, CustomerApi.Kind.NOT_FOUND, "request_rejected", null},
                {409, error("profile_account_mismatch", "\"field\":\"consents[0].tg_user_id\""), CustomerApi.Kind.CONFLICT, "profile_account_mismatch", null},
                {413, error("payload_too_large", null), CustomerApi.Kind.TOO_LARGE, "payload_too_large", null},
                {422, error("consent_text_mismatch", "\"field\":\"consents[0].text_sha256\""), CustomerApi.Kind.INVALID, "consent_text_mismatch", null},
                {422, error("unsupported_schema_version", "\"supported\":[1]"), CustomerApi.Kind.INVALID, "unsupported_schema_version", null},
                {400, null, CustomerApi.Kind.INVALID, "request_rejected", null},
                {415, "{\"error\":\"unsupported_media\"}", CustomerApi.Kind.INVALID, "unsupported_media", null},
                {503, error("profile_unavailable", "\"retry_after\":60"), CustomerApi.Kind.UNAVAILABLE, "profile_unavailable", null},
                {500, "<html>oops</html>", CustomerApi.Kind.UNAVAILABLE, "server_error", null},
                {503, null, CustomerApi.Kind.UNAVAILABLE, "server_error", null},
                {302, "{\"error\":\"moved\"}", CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
                {201, ok(), CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
                {200, "[]", CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
                {200, "", CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
                {200, null, CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
                {200, "{\"a\":1,\"a\":2}", CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
                {200, ok() + "x", CustomerApi.Kind.UNAVAILABLE, "invalid_response", null},
        };
        for (Object[] row : table) {
            Slot slot = new Slot();
            Captured result = consents(slot, reply((Integer) row[0], (String) row[1]));
            String name = row[0] + " " + row[3];
            check(isKind(result, (CustomerApi.Kind) row[2]) && row[3].equals(result.reply.error)
                    && (row[4] == null ? result.reply.reason == null : row[4].equals(result.reply.reason)), name + ": classified");
            check(result.reply.status == (Integer) row[0] && result.reply.possiblySent && slot.sends == 1 && slot.invalidations.isEmpty()
                    && "token-1".equals(slot.token) && slot.confirms == 1, name + ": one attempt, nothing cleared, confirmed");
        }
        Slot consent = new Slot();
        Captured required = consents(consent, reply(403, error("consent_required", "\"reason\":\"reconsent_required\"")));
        check(required.reply.body != null && required.reply.body.contains("reconsent_required"), "an error's body (extras) reaches the caller");
        Slot unavailable = new Slot();
        Captured retry = consents(unavailable, reply(503, error("profile_unavailable", "\"retry_after\":60")));
        check(retry.reply.retryAfterSeconds == 60 && unavailable.cooldowns.isEmpty(), "503 retry_after is passed on; no route cooldown for a 5xx");
        Slot header = new Slot();
        Captured both = consents(header, reply(503, error("profile_unavailable", "\"retry_after\":60"), 90));
        check(both.reply.retryAfterSeconds == 90, "the Retry-After header wins over the body's retry_after");
        Slot timed = new Slot();
        consents(timed, reply(422, error("invalid_request", "\"field\":\"x\"")));
        check(timed.serverTimes.equals(Collections.singletonList(t())), "an error's valid server_time is settled");
        Slot badTime = new Slot();
        consents(badTime, reply(422, "{\"error\":\"invalid_request\",\"server_time\":\"yesterday\"}"));
        check(badTime.serverTimes.equals(Collections.singletonList(0L)), "an invalid server_time is never settled (0)");
    }

    /** The UI-thread fence right before the callback. */
    private static void deliveryFence() {
        Slot relinked = new Slot();
        relinked.queueDelivery = true;
        Captured late = consents(relinked, reply(200, ok()));
        check(late.calls == 0 && relinked.deliveries.size() == 1, "the reply waits in the UI queue");
        relinked.generation++;
        relinked.bindingId = "01jbtest0000000000000000cc";
        relinked.deliveries.remove(0).run();
        check(isKind(late, CustomerApi.Kind.STALE) && late.reply.possiblySent && late.reply.body == null,
                "relink before UI delivery: withheld as STALE, still possibly sent");

        Slot loggedOut = new Slot();
        loggedOut.queueDelivery = true;
        Captured page = new Captured();
        loggedOut.script.add(reply(200, ok()));
        new CustomerApi(loggedOut).getProfileMe(loggedOut.identity(), page);
        loggedOut.telegramId = 0;
        loggedOut.token = null;
        loggedOut.generation++;
        loggedOut.deliveries.remove(0).run();
        check(isKind(page, CustomerApi.Kind.STALE), "logout before UI delivery: profile/me never delivered");

        Slot rotatedLater = new Slot();
        rotatedLater.queueDelivery = true;
        Captured kept = consents(rotatedLater, reply(200, ok()));
        rotatedLater.token = "token-rotated-after";
        rotatedLater.deliveries.remove(0).run();
        check(isKind(kept, CustomerApi.Kind.OK), "a rotation after completion keeps the same identity: delivered");

        Slot cleared = new Slot();
        cleared.queueDelivery = true;
        Captured own = consents(cleared, reply(401, error("invalid_token", null)));
        cleared.deliveries.remove(0).run();
        check(isKind(own, CustomerApi.Kind.UNAUTHORIZED) && !cleared.identity().isLinked(),
                "the UNAUTHORIZED this call caused is delivered although the binding is now cleared");

        Slot clearedElsewhere = new Slot();
        clearedElsewhere.queueDelivery = true;
        Captured other = consents(clearedElsewhere, reply(503, null));
        clearedElsewhere.token = null;
        clearedElsewhere.deliveries.remove(0).run();
        check(isKind(other, CustomerApi.Kind.STALE), "a binding cleared by another call before delivery: STALE");
    }

    /** queueFinal: one task queued before returning on the user lane, each body signed at send time. */
    private static void finalSend() {
        Slot slot = new Slot();
        slot.queueWork = true;
        CustomerApi api = new CustomerApi(slot);
        byte[] first = consentsBody(), second = utf8("{\"schema_version\":1,\"consents\":[]}");
        boolean queued = api.queueFinal(slot.identity(), Arrays.asList(first, second));
        check(queued && slot.queued.size() == 1 && slot.lanes.equals(Collections.singletonList(CustomerApi.Lane.USER)) && slot.finalCaptures == 1,
                "queueFinal: exactly one task enqueued on the user lane before it returns");
        check(slot.signFinals == 0 && slot.sends == 0 && slot.prepared.isEmpty(), "queueFinal: nothing signed or sent before the task runs");
        // unlink() right after it: the session goes, its bind/revoke queues behind the final task on the same FIFO lane.
        slot.token = null;
        slot.generation++;
        final List<String> revoked = new ArrayList<>();
        slot.execute(CustomerApi.Lane.USER, () -> revoked.add("revoke"));
        slot.script.add(reply(401, error("invalid_token", null)));
        slot.script.add(reply(429, null, 60));
        check(slot.queued.size() == 2, "unlink's revoke queues behind the final task");
        slot.queued.remove(0).run(); // FIFO head: the final task.
        check(slot.sends == 2 && revoked.isEmpty(), "the queue head is the final send; the revoke has not run yet");
        slot.queued.remove(0).run();
        check(revoked.equals(Collections.singletonList("revoke")) && slot.sends == 2, "the revoke runs after it and sends nothing customer-side");
        check(slot.signFinals == 2, "each body is signed at its own send time");
        check(slot.sent.get(0).finalSend && slot.sent.get(1).finalSend && "Bearer token-1".equals(slot.sent.get(0).headers.get("Authorization"))
                && "Bearer token-1".equals(slot.sent.get(1).headers.get("Authorization")),
                "signed with the credential captured before the session was removed");
        check(!slot.sent.get(0).headers.get("X-Device-Nonce").equals(slot.sent.get(1).headers.get("X-Device-Nonce")), "each body has its own nonce");
        check(Arrays.equals(first, slot.sent.get(0).body) && Arrays.equals(second, slot.sent.get(1).body)
                && CustomerApi.ROUTE_CONSENTS.equals(slot.sent.get(0).route) && "POST".equals(slot.sent.get(1).method),
                "exactly the bodies, to profile/consents");
        check(slot.serverTimes.isEmpty() && slot.cooldowns.isEmpty() && slot.confirms == 0 && slot.invalidations.isEmpty(),
                "results are ignored: no settle, no cooldown, no confirm or invalidate (even for 401/429)");

        Slot failing = new Slot();
        failing.script.add(new ProTransport.CustomerFailure(new ConnectException("refused"), false));
        failing.script.add(reply(200, ok()));
        check(new CustomerApi(failing).queueFinal(failing.identity(), Arrays.asList(consentsBody(), consentsBody())) && failing.sends == 2,
                "a failed first send does not stop the second (best effort)");

        Slot held = new Slot();
        held.queueWork = true;
        CustomerApi.Identity linked = held.identity();
        held.held = true;
        check(!new CustomerApi(held).queueFinal(linked, Collections.singletonList(consentsBody())) && held.queued.isEmpty(),
                "a held configuration never queues a final send");
        Slot moved = new Slot();
        moved.queueWork = true;
        CustomerApi.Identity before = moved.identity();
        moved.generation++;
        check(!new CustomerApi(moved).queueFinal(before, Collections.singletonList(consentsBody())) && moved.queued.isEmpty(),
                "an identity that moved on queues nothing");
        Slot invalid = new Slot();
        invalid.queueWork = true;
        CustomerApi invalidApi = new CustomerApi(invalid);
        check(!invalidApi.queueFinal(invalid.identity(), Arrays.asList(consentsBody(), consentsBody(), consentsBody()))
                && !invalidApi.queueFinal(invalid.identity(), Collections.<byte[]>emptyList())
                && !invalidApi.queueFinal(invalid.identity(), null)
                && !invalidApi.queueFinal(invalid.identity(), Collections.singletonList(utf8("{\"refresh_token\":\"x\"}")))
                && !invalidApi.queueFinal(invalid.identity(), Collections.singletonList(padded(CustomerApi.MAX_CONSENTS_BYTES + 1)))
                && !invalidApi.queueFinal(invalid.identity(), Collections.<byte[]>singletonList(null))
                && !invalidApi.queueFinal(null, Collections.singletonList(consentsBody()))
                && invalid.queued.isEmpty() && invalid.finalCaptures == 0, "invalid final bodies or identity: nothing captured or queued");
        Slot unlinked = new Slot();
        unlinked.token = null;
        check(!new CustomerApi(unlinked).queueFinal(unlinked.identity(), Collections.singletonList(consentsBody())) && unlinked.finalCaptures == 0,
                "an unlinked identity has nothing to send");
        Slot shut = new Slot();
        shut.refuseExecute = true;
        check(!new CustomerApi(shut).queueFinal(shut.identity(), Collections.singletonList(consentsBody())), "a lane that refuses work: not queued");
    }

    private static void bookkeeping() {
        Slot slot = new Slot();
        CustomerApi api = new CustomerApi(slot);
        CustomerApi.Identity id = slot.identity();
        check(api.profileDigest(id) == null && api.profileSyncedAt(id) == 0 && api.profileMinIntervalSeconds(id) == 21600
                && !api.profileStopped(id), "a new binding: no digest, never synced, 6 h default, not stopped");
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check(api.recordProfileSync(id, digest, 1792512000L, 43200) && digest.equals(api.profileDigest(id))
                && api.profileSyncedAt(id) == 1792512000L && api.profileMinIntervalSeconds(id) == 43200, "a recorded sync reads back");
        check(!api.recordProfileSync(id, digest.toUpperCase(), 1792512000L, 21600) && !api.recordProfileSync(id, digest.substring(1), 1792512000L, 21600)
                && !api.recordProfileSync(id, null, 1792512000L, 21600) && !api.recordProfileSync(id, digest, 0, 21600)
                && !api.recordProfileSync(id, digest, 1792512000L, -1) && slot.bookkeepingWrites == 1, "invalid sync records are refused unwritten");
        check(api.markProfileStopped(id) && api.profileStopped(id) && api.markProfileStopped(id) && slot.bookkeepingWrites == 2,
                "403 profile_account_not_linked stops this binding (idempotent)");
        check(api.recordProfileSync(id, digest, 1792515600L, 21600) && api.profileStopped(id), "a later record keeps the stop");
        slot.generation++;
        check(api.profileDigest(id) == null && api.profileSyncedAt(id) == 0 && api.profileMinIntervalSeconds(id) == 21600 && !api.profileStopped(id)
                && !api.recordProfileSync(id, digest, 1792519200L, 21600) && !api.markProfileStopped(id),
                "an identity that moved on reads nothing and writes nothing");
        CustomerApi.Identity unlinked = new CustomerApi.Identity(42, null, null, slot.generation);
        check(api.profileDigest(unlinked) == null && !api.recordProfileSync(unlinked, digest, 1792519200L, 21600) && !api.profileStopped(null),
                "an unlinked or null identity has no bookkeeping");

        Slot erased = new Slot();
        CustomerApi forget = new CustomerApi(erased);
        check(forget.forgetSessionAfterErasure(42) && erased.forgotten.equals(Collections.singletonList(42L)) && !erased.identity().isLinked(),
                "forgetSessionAfterErasure forgets that Telegram user's session");
        check(!forget.forgetSessionAfterErasure(0) && !forget.forgetSessionAfterErasure(-5) && erased.forgotten.size() == 1,
                "no Telegram user: nothing forgotten");
    }

    private static void envelopeAndCodes() {
        check("account_erased".equals(CustomerApi.wireCode("account_erased")) && "a.b-c_9".equals(CustomerApi.wireCode("a.b-c_9")),
                "stable codes pass");
        check(CustomerApi.wireCode(null) == null && CustomerApi.wireCode("") == null && CustomerApi.wireCode("Account") == null
                && CustomerApi.wireCode("_x") == null && CustomerApi.wireCode("a b") == null && CustomerApi.wireCode(repeat('a', 65)) == null
                && "a".equals(CustomerApi.wireCode("a")) && CustomerApi.wireCode(repeat('a', 64)) != null, "free text is never a code");
        CustomerApi.Envelope envelope = CustomerApi.Envelope.of(error("invalid_token", "\"reason\":\"account_erased\",\"retry_after\":12,\"nested\":{\"error\":\"x\"}"));
        check(envelope != null && "invalid_token".equals(envelope.error) && "account_erased".equals(envelope.reason) && envelope.serverTime == t()
                && envelope.retryAfter == 12, "envelope: error, reason, server_time, retry_after");
        check(CustomerApi.Envelope.of("{\"retry_after\":1.5}").retryAfter == 0 && CustomerApi.Envelope.of("{\"retry_after\":-3}").retryAfter == 0
                && CustomerApi.Envelope.of("{\"retry_after\":\"9\"}").retryAfter == 0, "only a non-negative integer retry_after counts");
        check(CustomerApi.Envelope.of("{\"server_time\":\"2026-11-01T00:00:00.5Z\"}").serverTime == 0
                && CustomerApi.Envelope.of("{\"server_time\":1792512000}").serverTime == 0, "only an exact instant is a server_time");
        check(CustomerApi.Envelope.of("\uFEFF{}") == null && CustomerApi.Envelope.of("{} ") != null && CustomerApi.Envelope.of(" {}") != null
                && CustomerApi.Envelope.of("{\"a\":[1,{\"b\":2}]}") != null, "strict single-object reading");
        check(CustomerApi.RESERVED_BODY_KEYS.equals(new HashSet<>(Arrays.asList("refresh_token", "device_id", "nonce", "ts", "sig"))),
                "the reserved keys are exactly contract §0.1's five");
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    private CustomerApiExchangeTests() { }
}

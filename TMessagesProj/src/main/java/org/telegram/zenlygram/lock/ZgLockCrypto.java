/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Credential records of every Zenlygram lock. A PIN or password is never stored: only a salted, slow, device-bound
 * hash of it, and it is never logged.
 *
 * <p>Record formats (one string, '$'-separated, base64 without line breaks):
 * <ul>
 *   <li>{@code zgk3$<keyId>$<iterations>$<salt>$<hash>}: hash = HMAC-SHA256 under the non-exportable AndroidKeyStore
 *       key {@code zg_lock_hmac_<keyId>} of PBKDF2-HMAC-SHA256(code, salt, iterations). A copied preferences file
 *       cannot be brute-forced off the device, even for a 4-digit PIN. Every record names its own key: after a
 *       Keystore reset a new key gets a new random id, so older records stay UNVERIFIABLE (recovery) and never turn
 *       into a MISMATCH that even the right PIN could not pass.</li>
 *   <li>{@code zgk2$<iterations>$<salt>$<hash>}: the first version, bound to the key {@value #HMAC_KEY_ALIAS}
 *       (still verified, never written again).</li>
 *   <li>{@code zgs2$<iterations>$<salt>$<hash>}: the same PBKDF2 without the Keystore step, written only when the
 *       Keystore could not give a key at set time.</li>
 * </ul>
 * The salt is 16 random bytes per record. The iteration count is measured on this device when the record is made
 * (about a quarter of a second, 20 000 to 400 000) and kept in the record, so verification is stable forever.
 * PBKDF2 is computed here with javax.crypto.Mac over the UTF-8 bytes of the code on every Android version, so a record
 * made on one OS version always verifies on the next.
 *
 * <p>All methods that hash are slow by design: call them off the main thread ({@link ZgPinDialog} does).
 *
 * <p>When the Keystore key behind {@code zgk2} records is gone (a rare OEM keystore reset), {@link #verify} answers
 * {@link Verdict#UNVERIFIABLE}: no entry can ever match again, and the feature must offer its recovery path instead of
 * a keypad (see ZgChatLockRecovery). The key is never deleted or replaced by this class while it exists.
 */
public final class ZgLockCrypto {

    public enum Verdict {
        MATCH,
        MISMATCH,
        /** The record can never be checked on this device again (Keystore key lost, damaged record). */
        UNVERIFIABLE,
        /** A transient failure (Keystore busy, out of memory): not a wrong entry, try again. */
        ERROR
    }

    /** The key of the first record version ({@code zgk2}): verified while it exists, never created again. */
    public static final String HMAC_KEY_ALIAS = "zg_lock_hmac_v1";
    private static final String HMAC_KEY_ALIAS_PREFIX = "zg_lock_hmac_";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String HMAC = "HmacSHA256";
    private static final String PREFIX_KEYED_V1 = "zgk2";
    private static final String PREFIX_KEYED = "zgk3";
    private static final String PREFIX_SOFT = "zgs2";
    /** Prefs (app-private) with the id of the key new records use. */
    private static final String KEY_PREFS = "zg_lock_keys";
    private static final String KEY_CURRENT_ID = "hmac_id";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    static final int MIN_ITERATIONS = 20_000;
    static final int MAX_ITERATIONS = 400_000;
    static final int DEFAULT_ITERATIONS = 60_000;
    private static final long TARGET_NANOS = 250_000_000L;
    /** Domain separation, and never an empty HMAC key (SecretKeySpec refuses one). */
    private static final byte[] DOMAIN = "zenlygram-lock-v2\u0000".getBytes(StandardCharsets.UTF_8);

    private static final Object keyLock = new Object();
    /** Cached Keystore lookups for {@link #isUnverifiableQuick}, per key alias. */
    private static final ConcurrentHashMap<String, Boolean> keyPresent = new ConcurrentHashMap<>();

    private ZgLockCrypto() {
    }

    /** A new record for {@code code}, or null when nothing could be computed (then nothing must be saved). */
    @WorkerThread
    @Nullable
    public static String makeRecord(@Nullable String code) {
        if (TextUtils.isEmpty(code)) {
            return null;
        }
        final byte[] salt = new byte[SALT_BYTES];
        Utilities.random.nextBytes(salt);
        final int iterations = calibrateIterations();
        final byte[] stretched = pbkdf2(password(code), salt, iterations);
        if (stretched == null) {
            return null;
        }
        final CurrentKey current = getOrCreateHmacKey();
        if (current != null) {
            final byte[] peppered = keyedHmac(current.key, stretched);
            if (peppered != null) {
                return PREFIX_KEYED + "$" + current.id + "$" + iterations + "$" + b64(salt) + "$" + b64(peppered);
            }
        }
        return PREFIX_SOFT + "$" + iterations + "$" + b64(salt) + "$" + b64(stretched);
    }

    /** Checks {@code code} against {@code record} in constant time. A null record never matches. */
    @WorkerThread
    public static Verdict verify(@Nullable String record, @Nullable String code) {
        if (record == null) {
            return Verdict.MISMATCH;
        }
        final Parsed parsed = parse(record);
        if (parsed == null) {
            return Verdict.UNVERIFIABLE;
        }
        if (TextUtils.isEmpty(code)) {
            return Verdict.MISMATCH;
        }
        SecretKey key = null;
        if (parsed.alias != null) {
            final KeyStore keyStore = loadKeyStore();
            if (keyStore == null) {
                return Verdict.ERROR;
            }
            try {
                if (!keyStore.containsAlias(parsed.alias)) {
                    keyPresent.put(parsed.alias, Boolean.FALSE);
                    return Verdict.UNVERIFIABLE;
                }
                final Key entry = keyStore.getKey(parsed.alias, null);
                if (!(entry instanceof SecretKey)) {
                    return Verdict.UNVERIFIABLE;
                }
                key = (SecretKey) entry;
            } catch (Throwable e) {
                logFailure("verify key", e);
                return Verdict.ERROR;
            }
        }
        final byte[] stretched = pbkdf2(password(code), parsed.salt, parsed.iterations);
        if (stretched == null) {
            return Verdict.ERROR;
        }
        final byte[] candidate = key != null ? keyedHmac(key, stretched) : stretched;
        if (candidate == null) {
            return Verdict.ERROR;
        }
        return MessageDigest.isEqual(candidate, parsed.hash) ? Verdict.MATCH : Verdict.MISMATCH;
    }

    /**
     * Fast check without hashing (any thread): true when {@code record} is damaged or needs a Keystore key that is
     * gone, i.e. when {@link #verify} could only answer UNVERIFIABLE. Null records are not unverifiable.
     */
    public static boolean isUnverifiableQuick(@Nullable String record) {
        if (record == null) {
            return false;
        }
        final Parsed parsed = parse(record);
        if (parsed == null) {
            return true;
        }
        return parsed.alias != null && !isKeyPresent(parsed.alias);
    }

    /** Whether the Keystore key {@code alias} exists (cached; {@link #refreshKeyState} forgets the cache). */
    private static boolean isKeyPresent(String alias) {
        final Boolean cached = keyPresent.get(alias);
        if (cached != null) {
            return cached;
        }
        final KeyStore keyStore = loadKeyStore();
        boolean present;
        try {
            // An unreadable Keystore is not proof that the key is gone: assume present (fail closed into ERROR).
            present = keyStore == null || keyStore.containsAlias(alias);
        } catch (Throwable e) {
            present = true;
        }
        if (keyStore != null) {
            keyPresent.put(alias, present);
        }
        return present;
    }

    /** Forgets the cached Keystore lookups (the next quick check reads the Keystore again). */
    public static void refreshKeyState() {
        keyPresent.clear();
    }

    // ---------------------------------------------------------------------------------------------------------

    private static final class Parsed {
        /** The Keystore key the record needs, null for a soft record. */
        String alias;
        int iterations;
        byte[] salt;
        byte[] hash;
    }

    @Nullable
    private static Parsed parse(String record) {
        try {
            String[] parts = record.split("\\$", -1);
            final Parsed parsed = new Parsed();
            if (PREFIX_KEYED.equals(parts[0])) {
                if (parts.length != 5 || !isValidKeyId(parts[1])) {
                    return null;
                }
                parsed.alias = HMAC_KEY_ALIAS_PREFIX + parts[1];
                parts = new String[]{parts[0], parts[2], parts[3], parts[4]};
            } else if (PREFIX_KEYED_V1.equals(parts[0])) {
                parsed.alias = HMAC_KEY_ALIAS;
            } else if (!PREFIX_SOFT.equals(parts[0])) {
                return null;
            }
            if (parts.length != 4) {
                return null;
            }
            parsed.iterations = Integer.parseInt(parts[1]);
            if (parsed.iterations < 1000 || parsed.iterations > 10_000_000) {
                return null;
            }
            parsed.salt = Base64.decode(parts[2], Base64.NO_WRAP);
            parsed.hash = Base64.decode(parts[3], Base64.NO_WRAP);
            if (parsed.salt.length != SALT_BYTES || parsed.hash.length != HASH_BYTES) {
                return null;
            }
            return parsed;
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] password(String code) {
        final byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        final byte[] result = new byte[DOMAIN.length + codeBytes.length];
        System.arraycopy(DOMAIN, 0, result, 0, DOMAIN.length);
        System.arraycopy(codeBytes, 0, result, DOMAIN.length, codeBytes.length);
        return result;
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018), one 32-byte block. */
    @Nullable
    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) {
        try {
            final Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(password, HMAC));
            final byte[] u = new byte[HASH_BYTES];
            mac.update(salt);
            mac.update(new byte[]{0, 0, 0, 1});
            mac.doFinal(u, 0);
            final byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                mac.update(u);
                mac.doFinal(u, 0);
                for (int j = 0; j < HASH_BYTES; j++) {
                    t[j] ^= u[j];
                }
            }
            return t;
        } catch (Throwable e) {
            logFailure("pbkdf2", e);
            return null;
        }
    }

    /** About {@link #TARGET_NANOS} of work on this device, clamped and rounded to thousands. */
    private static int calibrateIterations() {
        try {
            final byte[] pw = password("0000");
            final byte[] salt = new byte[SALT_BYTES];
            pbkdf2(pw, salt, 500); // warm up
            final int probe = 3000;
            final long start = SystemClock.elapsedRealtimeNanos();
            if (pbkdf2(pw, salt, probe) == null) {
                return DEFAULT_ITERATIONS;
            }
            final long elapsed = SystemClock.elapsedRealtimeNanos() - start;
            if (elapsed <= 0) {
                return DEFAULT_ITERATIONS;
            }
            long n = TARGET_NANOS * probe / elapsed;
            n = Math.max(MIN_ITERATIONS, Math.min(MAX_ITERATIONS, n));
            return (int) (n / 1000 * 1000);
        } catch (Throwable e) {
            return DEFAULT_ITERATIONS;
        }
    }

    @Nullable
    private static byte[] keyedHmac(SecretKey key, byte[] data) {
        try {
            final Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            final byte[] out = mac.doFinal(data);
            return out != null && out.length == HASH_BYTES ? out : null;
        } catch (Throwable e) {
            logFailure("keyed hmac", e);
            return null;
        }
    }

    private static final class CurrentKey {
        final String id;
        final SecretKey key;

        CurrentKey(String id, SecretKey key) {
            this.id = id;
            this.key = key;
        }
    }

    /**
     * The key new records use: the one named in prefs while it exists; otherwise (first use, or the Keystore lost it) a
     * new key under a new random id. An existing key is never replaced or deleted: records depend on it.
     */
    @Nullable
    private static CurrentKey getOrCreateHmacKey() {
        synchronized (keyLock) {
            final KeyStore keyStore = loadKeyStore();
            if (keyStore == null) {
                return null;
            }
            try {
                final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE);
                final String id = preferences.getString(KEY_CURRENT_ID, null);
                if (isValidKeyId(id)) {
                    final String alias = HMAC_KEY_ALIAS_PREFIX + id;
                    if (keyStore.containsAlias(alias)) {
                        final Key existing = keyStore.getKey(alias, null);
                        keyPresent.put(alias, Boolean.TRUE);
                        // A broken entry means soft records for now.
                        return existing instanceof SecretKey ? new CurrentKey(id, (SecretKey) existing) : null;
                    }
                }
                final byte[] random = new byte[8];
                Utilities.random.nextBytes(random);
                final StringBuilder newId = new StringBuilder(16);
                for (byte b : random) {
                    newId.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                final String alias = HMAC_KEY_ALIAS_PREFIX + newId;
                final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build());
                final SecretKey key = generator.generateKey();
                if (!preferences.edit().putString(KEY_CURRENT_ID, newId.toString()).commit()) {
                    // A key nobody can find again would make every record made with it unverifiable.
                    keyStore.deleteEntry(alias);
                    return null;
                }
                keyPresent.put(alias, Boolean.TRUE);
                return new CurrentKey(newId.toString(), key);
            } catch (Throwable e) {
                logFailure("create key", e);
                return null;
            }
        }
    }

    private static boolean isValidKeyId(@Nullable String id) {
        if (id == null || id.length() != 16) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            if (Character.digit(id.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static KeyStore loadKeyStore() {
        try {
            final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            return keyStore;
        } catch (Throwable e) {
            logFailure("keystore", e);
            return null;
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    /** Logs only the kind of failure: never a code, a salt or a hash. */
    private static void logFailure(String what, Throwable e) {
        FileLog.e("ZgLockCrypto: " + what + " failed: " + e.getClass().getSimpleName());
    }
}

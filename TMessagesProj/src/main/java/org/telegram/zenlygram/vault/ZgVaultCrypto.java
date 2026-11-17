/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encryption at rest of the vault membership (iOS: a ThisDeviceOnly Keychain item). AES-256-GCM under the
 * non-exportable AndroidKeyStore key {@value #KEY_ALIAS}; a copied preferences file shows nothing. The PIN itself is
 * not here: it is a {@link org.telegram.zenlygram.lock.ZgLockCrypto} record (salted slow hash).
 *
 * <p>Blob: {@code zgv1$<hex iv>$<hex ciphertext+tag>}, with a fixed AAD for domain separation. Hex instead of base64:
 * the same code runs on every Android version and in the JVM checks.
 *
 * <p>{@link #open} tells apart "gone for good" ({@link Status#LOST}: no key, a damaged blob, a failed tag) from
 * "try again" ({@link Status#UNAVAILABLE}: the Keystore is busy or failing for now). Nothing secret is logged.
 */
final class ZgVaultCrypto {

    enum Status {
        OK,
        LOST,
        UNAVAILABLE
    }

    static final class Result {
        final Status status;
        @Nullable
        final String text;

        Result(Status status, @Nullable String text) {
            this.status = status;
            this.text = text;
        }
    }

    static final String KEY_ALIAS = "zg_vault_aes_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFIX = "zgv1";
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "zenlygram-secret-vault-state-v1".getBytes(StandardCharsets.UTF_8);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Object keyLock = new Object();

    private ZgVaultCrypto() {
    }

    /** Encrypts {@code plain}, creating the key when it is missing. Null when nothing could be encrypted. */
    @Nullable
    static String seal(String plain) {
        final SecretKey key = getOrCreateKey();
        if (key == null) {
            return null;
        }
        try {
            return sealWith(key, plain.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            logFailure("seal", e);
            return null;
        }
    }

    /** Decrypts a {@link #seal} blob. */
    static Result open(String blob) {
        final byte[][] parts = parse(blob);
        if (parts == null) {
            return new Result(Status.LOST, null);
        }
        final KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
        } catch (Throwable e) {
            logFailure("keystore", e);
            return new Result(Status.UNAVAILABLE, null);
        }
        final Key key;
        try {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                return new Result(Status.LOST, null);
            }
            key = keyStore.getKey(KEY_ALIAS, null);
        } catch (UnrecoverableKeyException e) {
            logFailure("key", e);
            return new Result(Status.LOST, null);
        } catch (Throwable e) {
            logFailure("key", e);
            return new Result(Status.UNAVAILABLE, null);
        }
        if (!(key instanceof SecretKey)) {
            return new Result(Status.LOST, null);
        }
        try {
            final byte[] plain = openWith((SecretKey) key, parts[0], parts[1]);
            return new Result(Status.OK, new String(plain, StandardCharsets.UTF_8));
        } catch (AEADBadTagException | KeyPermanentlyInvalidatedException e) {
            logFailure("open", e);
            return new Result(Status.LOST, null);
        } catch (Throwable e) {
            logFailure("open", e);
            return new Result(Status.UNAVAILABLE, null);
        }
    }

    /** Whether the key exists or could be created now (the vault refuses to turn on without it). */
    static boolean ensureKey() {
        return getOrCreateKey() != null;
    }

    /** Deletes the key (the vault was turned off, or its blob is gone for good). */
    static void deleteKey() {
        synchronized (keyLock) {
            try {
                final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
            } catch (Throwable e) {
                logFailure("delete", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pure parts (also used by the JVM checks with a software key)
    // ---------------------------------------------------------------------------------------------------------

    static String sealWith(SecretKey key, byte[] plain) throws Exception {
        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // The Keystore insists on choosing the IV itself (randomized encryption).
        cipher.init(Cipher.ENCRYPT_MODE, key);
        final byte[] iv = cipher.getIV();
        if (iv == null || iv.length < 12) {
            throw new IllegalStateException("iv");
        }
        cipher.updateAAD(AAD);
        final byte[] sealed = cipher.doFinal(plain);
        return PREFIX + "$" + hex(iv) + "$" + hex(sealed);
    }

    static byte[] openWith(SecretKey key, byte[] iv, byte[] sealed) throws Exception {
        final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(AAD);
        return cipher.doFinal(sealed);
    }

    /** {iv, sealed}, or null when {@code blob} is not a well-formed {@link #seal} result. */
    @Nullable
    static byte[][] parse(@Nullable String blob) {
        if (blob == null) {
            return null;
        }
        final String[] parts = blob.split("\\$", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            return null;
        }
        final byte[] iv = unhex(parts[1]);
        final byte[] sealed = unhex(parts[2]);
        if (iv == null || sealed == null || iv.length < 12 || iv.length > 32 || sealed.length < TAG_BITS / 8) {
            return null;
        }
        return new byte[][]{iv, sealed};
    }

    static String hex(byte[] bytes) {
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0x0f];
            out[i * 2 + 1] = HEX[bytes[i] & 0x0f];
        }
        return new String(out);
    }

    @Nullable
    static byte[] unhex(String text) {
        if (text == null || (text.length() & 1) != 0) {
            return null;
        }
        final byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(text.charAt(i * 2), 16);
            final int lo = Character.digit(text.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nullable
    private static SecretKey getOrCreateKey() {
        synchronized (keyLock) {
            try {
                final KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    final Key existing = keyStore.getKey(KEY_ALIAS, null);
                    // Never replaced while it exists: the stored blob depends on it.
                    return existing instanceof SecretKey ? (SecretKey) existing : null;
                }
                final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
                return generator.generateKey();
            } catch (Throwable e) {
                logFailure("create key", e);
                return null;
            }
        }
    }

    /** Only the kind of failure: never a key, an id or a blob. */
    private static void logFailure(String what, Throwable e) {
        FileLog.e("ZgVaultCrypto: " + what + " failed: " + e.getClass().getSimpleName());
    }
}

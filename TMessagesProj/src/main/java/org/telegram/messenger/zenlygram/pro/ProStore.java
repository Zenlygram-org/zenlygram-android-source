package org.telegram.messenger.zenlygram.pro;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Atomic, authenticated storage excluded from Android backup. Secrets never reach preferences.
 * One store per backend origin: {@code noBackupFilesDir/zenlygram-pro/o-<storage token>/}, each file
 * sealed with the AAD {@code <token>/<name>}, so a file copied from another origin's directory cannot
 * be opened. The pre-origin (v1) files directly in {@code zenlygram-pro/} are never opened, renamed or
 * deleted. Both keystore aliases are shared by every origin (each origin has its own device_id).
 */
final class ProStore {
    private static final String AES_ALIAS = "zenlygram.pro.storage.v1";
    private static final String EC_ALIAS = "zenlygram.pro.identity.v1";
    private final File directory;
    private final String storageToken;

    ProStore(Context context, String storageToken) throws Exception {
        if (Build.VERSION.SDK_INT < 23) throw new IOException("Secure Pro storage needs Android 6 or later");
        if (!ProOrigin.isStorageToken(storageToken)) throw new IOException("Invalid Pro storage origin");
        this.storageToken = storageToken;
        directory = new File(new File(context.getNoBackupFilesDir(), "zenlygram-pro"), "o-" + storageToken);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create Pro storage");
    }

    synchronized JSONObject read(String name) throws Exception {
        AtomicFile file = file(name);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
        byte[] sealed;
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 262144) throw new IOException("Pro storage is too large");
                out.write(buffer, 0, count);
            }
            sealed = out.toByteArray();
        }
        if (sealed.length < 30 || sealed[0] != 1) throw new IOException("Invalid Pro storage envelope");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false), new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 1, 13)));
        cipher.updateAAD(aad(name));
        byte[] plain = cipher.doFinal(sealed, 13, sealed.length - 13);
        return new JSONObject(new String(plain, StandardCharsets.UTF_8));
    }

    synchronized void write(String name, JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
        cipher.updateAAD(aad(name));
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        byte[] nonce = cipher.getIV();
        if (nonce.length != 12) throw new IOException("Unexpected GCM nonce");
        AtomicFile file = file(name);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(1);
            out.write(nonce);
            out.write(encrypted);
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    synchronized void delete(String name) throws Exception {
        AtomicFile file = file(name);
        file.delete();
        if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) {
            throw new IOException("Could not remove Pro credentials");
        }
    }

    synchronized String publicKey() throws Exception {
        KeyStore keys = keyStore();
        if (!keys.containsAlias(EC_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(EC_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false).build());
            generator.generateKeyPair();
        }
        return Base64.encodeToString(keyStore().getCertificate(EC_ALIAS).getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    synchronized String sign(String device, String nonce, String timestamp) throws Exception {
        return signMessage(ProCrypto.deviceMessage(device, nonce, timestamp));
    }

    /** Standard-base64 DER ECDSA P-256/SHA-256 signature of {@code message} by the install key. */
    synchronized String signMessage(byte[] message) throws Exception {
        KeyStore keys = keyStore();
        if (!keys.containsAlias(EC_ALIAS)) throw new IOException("Link this device again");
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign((PrivateKey) keys.getKey(EC_ALIAS, null));
        signature.update(message);
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
    }

    static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        StringBuilder result = new StringBuilder(bytes * 2);
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (byte b : raw) {
            result.append(alphabet[(b & 255) >>> 4]);
            result.append(alphabet[b & 15]);
        }
        return result.toString();
    }

    private AtomicFile file(String name) throws IOException {
        if (!name.matches("install|account-[1-9][0-9]*")) throw new IOException("Invalid Pro storage scope");
        return new AtomicFile(new File(directory, name + ".bin"));
    }

    /** Binds origin and scope into the ciphertext: an envelope opens only under the same token and name. */
    private byte[] aad(String name) {
        return (storageToken + "/" + name).getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey encryptionKey(boolean create) throws Exception {
        KeyStore keys = keyStore();
        if (!keys.containsAlias(AES_ALIAS)) {
            if (!create) throw new IOException("Pro encryption key is unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false).build());
            generator.generateKey();
        }
        return (SecretKey) keyStore().getKey(AES_ALIAS, null);
    }

    private KeyStore keyStore() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        return keys;
    }
}

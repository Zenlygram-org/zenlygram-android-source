/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.identity;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * App identity values that must not collide with an official Telegram app installed on the same device.
 *
 * <ul>
 *   <li>Android account type: the string resource {@code zg_account_type}, set per build type from APP_PACKAGE
 *       in TMessagesProj/zenlygram-identity.gradle. res/xml/auth.xml and res/xml/sync_contacts.xml use the same
 *       resource, so Java and XML always agree.</li>
 *   <li>Contact data MIME types: fixed Zenlygram values. They are repeated literally in res/xml/contacts.xml and
 *       in TMessagesProj/src/main/AndroidManifest.xml. The Contacts app reads contacts.xml with its own
 *       resources, so a resource reference would not resolve there. Keep all three places in sync.</li>
 *   <li>SMS Retriever hash: computed at runtime from the real package name and signing certificate.</li>
 * </ul>
 */
public final class ZgIdentity {

    private static final String CONTACT_MIME_PREFIX = "vnd.android.cursor.item/vnd.org.zenlygram.android.";
    public static final String CONTACT_MIME_PROFILE = CONTACT_MIME_PREFIX + "profile";
    public static final String CONTACT_MIME_CALL = CONTACT_MIME_PREFIX + "call";
    public static final String CONTACT_MIME_CALL_VIDEO = CONTACT_MIME_PREFIX + "call.video";

    // Action headers of our rows in the system Contacts app (summaryColumn data2 in res/xml/contacts.xml).
    // They must differ from Telegram's, because both apps add rows to the same merged contact.
    public static final String CONTACT_LABEL_PROFILE = "Zenlygram Profile";
    public static final String CONTACT_LABEL_VOICE_CALL = "Zenlygram Voice Call";
    public static final String CONTACT_LABEL_VIDEO_CALL = "Zenlygram Video Call";

    // SMS Retriever app hash: first 9 bytes of SHA-256("<package> <certificate hex>"), Base64, 11 characters.
    private static final int SMS_HASH_BYTES = 9;
    private static final int SMS_HASH_CHARS = 11;

    private static volatile String accountType;
    private static volatile String smsRetrieverHash;

    private ZgIdentity() {
    }

    /**
     * Package name of the running app, i.e. its applicationId including a ".beta" or ".web" suffix.
     * Returns null only if neither the context nor the app module can tell.
     */
    public static String getPackageName() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                String name = context.getPackageName();
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            String name = ApplicationLoader.getApplicationId();
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * Type of the Zenlygram system account: the value res/xml/auth.xml and res/xml/sync_contacts.xml declare.
     * Upstream hard-coded "org.telegram.messenger", which belongs to whichever app was installed first.
     */
    public static String getAccountType() {
        String type = accountType;
        if (type == null) {
            try {
                type = ApplicationLoader.applicationContext.getString(R.string.zg_account_type);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (TextUtils.isEmpty(type)) {
                type = getPackageName();
            }
            if (!TextUtils.isEmpty(type)) {
                accountType = type;
            }
        }
        return type;
    }

    /**
     * SMS Retriever hash of this app (the algorithm of Google's AppSignatureHelper). It depends on the package
     * name and the signing certificate, so it is computed at runtime. Returns "" if it cannot be computed; the
     * login code then only auto-fills while the code screen is open.
     */
    public static String getSmsRetrieverHash() {
        String hash = smsRetrieverHash;
        if (hash == null) {
            hash = computeSmsRetrieverHash();
            smsRetrieverHash = hash;
        }
        return hash;
    }

    private static String computeSmsRetrieverHash() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return "";
            }
            String packageName = context.getPackageName();
            Signature signature = getCurrentSigningCertificate(context.getPackageManager(), packageName);
            if (TextUtils.isEmpty(packageName) || signature == null) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((packageName + " " + signature.toCharsString()).getBytes(StandardCharsets.UTF_8));
            byte[] hash = Arrays.copyOf(digest.digest(), SMS_HASH_BYTES);
            String base64 = Base64.encodeToString(hash, Base64.NO_PADDING | Base64.NO_WRAP);
            return base64.length() > SMS_HASH_CHARS ? base64.substring(0, SMS_HASH_CHARS) : base64;
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature getCurrentSigningCertificate(PackageManager packageManager, String packageName) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) {
                return null;
            }
            if (signingInfo.hasMultipleSigners()) {
                Signature[] signers = signingInfo.getApkContentsSigners();
                return signers != null && signers.length > 0 ? signers[0] : null;
            }
            // Ordered from the original certificate to the current one.
            Signature[] history = signingInfo.getSigningCertificateHistory();
            return history != null && history.length > 0 ? history[history.length - 1] : null;
        }
        PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        return info.signatures != null && info.signatures.length > 0 ? info.signatures[0] : null;
    }

    /**
     * Last-resort files directory, used by ApplicationLoader.getFilesDirFixed() when the context returns no
     * files dir and no ApplicationInfo. Upstream hard-coded "/data/data/org.telegram.messenger/files".
     */
    public static File getLastResortFilesDir() {
        String packageName = getPackageName();
        if (!TextUtils.isEmpty(packageName)) {
            return new File("/data/data/" + packageName + "/files");
        }
        // Package unknown: on Android java.io.tmpdir is the app's own cache dir (<data dir>/cache).
        File tmp = new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"));
        File parent = tmp.getParentFile();
        return new File(parent != null ? parent : tmp, "files");
    }

    /**
     * Whether "Sign in with Google" (login e-mail setup in LoginActivity) can work. Zenlygram has no Google OAuth
     * client of its own: Telegram's client id only accepts Telegram's package and certificate, and the server
     * config (login_google_oauth_client_id, see MessagesController) can put it back into
     * BuildVars.GOOGLE_AUTH_CLIENT_ID. LoginActivity (e-mail setup and e-mail code screens, marked ZENLYGRAM) hides
     * "Sign in with Google" with this check, so the e-mail field is the only way there.
     */
    public static boolean isGoogleSignInAvailable() {
        return false;
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this build ships for the Zenlygram cloud backend (iOS sg_config: api_url, entitlement_keys / public_policy_keys).
 * See docs/zenlygram-cloud-android.md.
 *
 * <p><b>The keys are pinned here and there is no JWKS endpoint, ever</b> (iOS SGEntitlementPinnedKeys): fetching keys
 * over the network would turn a TLS compromise into policy forgery. Two keys ship — the active one and the next — so a
 * server rotation lands without a Play release. They are PUBLIC keys, byte-identical to the iOS App Store
 * configuration (build-system/zenlygram-appstore.json, entitlement_keys).
 */
public final class ZgCloudConfig {

    private ZgCloudConfig() {
    }

    /** Canonical origin; release builds use zenlygram.org (zenlygram.com answers 410 since 2026-10-24). */
    public static final String ORIGIN = "https://zenlygram.org";
    public static final String AUTHORITY = "zenlygram.org";
    public static final String BASE_URL = ORIGIN + "/v1";

    /** The monetization policy of the Android line ({@code ?platform=android}): the cloud client's own request. */
    public static final String MONETIZATION_URL = BASE_URL + "/" + org.telegram.messenger.zenlygram.pro.ProMonetization.POLICY_PATH;
    public static final String APP_POLICY_URL = BASE_URL + "/app-policy";

    /**
     * The vendor media type the client declares for the monetization policy: the ver-4 claim shape of the Android
     * line, alone. A server that does not know it answers ver 1, which is refused. The same header the cloud
     * client's own fetch of that policy sends ({@code ProMonetization.ACCEPT}).
     */
    public static final String ACCEPT_MONETIZATION = org.telegram.messenger.zenlygram.pro.ProMonetization.ACCEPT;
    /** The vendor media type the client declares for the app update policy (§3.9c). */
    public static final String ACCEPT_APP_POLICY = "application/vnd.zenlygram.app-policy.v1+json, application/json;q=0.9";

    /**
     * The bind bot's @username (iOS sg_config.bot_username), for texts only: the link screen names it before a bind
     * has answered. The bot a link actually uses is always the one the server offers in that bind.
     */
    public static final String BIND_BOT_USERNAME = "ZenlyPremiumbot";

    /** Web deletion path (Play: account deletion reachable outside the app too). */
    public static final String ACCOUNT_DELETE_URL = ORIGIN + "/account/delete";

    private static final Map<String, String> PINNED_KEYS = new LinkedHashMap<>();

    static {
        PINNED_KEYS.put("k2026a", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaEXLVGbgr1nvSprYzys1YeaC3Zv0RceCityCCsZqJ0pkHwcPsgHZj1XoSYq7Bkr6/uvJKf1D8OaRjMtCdGWwdA==");
        PINNED_KEYS.put("k2026b", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECQ0G5bknPvprKlFKwD77ExAzvPsZkAncNPEkPOy4ofFrOuBEFFZ7sLtTeGbstZcPhY0zWGB1DncZi+D32LWxhg==");
    }

    /** The pinned public keys, kid → base64 SPKI DER (the account-link client pins the same ones). */
    public static Map<String, String> pinnedKeys() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(PINNED_KEYS));
    }

    private static volatile ZgJws.KeyRing ring;

    /** The pinned public-policy ring (k2026a, k2026b). */
    public static ZgJws.KeyRing pinnedRing() {
        ZgJws.KeyRing current = ring;
        if (current == null) {
            ring = current = new ZgJws.KeyRing(PINNED_KEYS);
        }
        return current;
    }

    /**
     * The authenticated backend: the account link (bind / DeviceAuth), customer data (consent upload, profile,
     * statistics, account deletion over the API) and Cloud Tasks. ON since 2026-11-14: the server accepts an Android
     * bind ({@code platform} and {@code src} "android", an Android Keystore P-256 SPKI as {@code device_pubkey}, the
     * Telegram Mini App attestation; checked against production). The client is
     * {@code org.telegram.messenger.zenlygram.pro / customer / tasks}, hooked through {@link ZgBackend}; it takes its
     * origin and pinned keys from this class. See docs/zenlygram-cloud-android.md.
     */
    public static final boolean AUTHENTICATED_BACKEND = true;
}

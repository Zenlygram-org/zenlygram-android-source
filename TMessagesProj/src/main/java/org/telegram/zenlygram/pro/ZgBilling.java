/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.GooglePlayCheckout;

/**
 * The Google Play Billing seam of Zenlygram Pro (iOS: StoreKit 2 + {@code POST /v1/apple/prepare|attach}). The rail
 * itself is the cloud client's ({@code org.telegram.messenger.zenlygram.pro}: GooglePlayBilling over Play Billing
 * Library 8, GooglePlayCheckout for one screen, GooglePlaySync for purchases that complete outside a screen) and
 * {@link ZgProActivity} is its only screen.
 *
 * <p>Note: Telegram's own {@code org.telegram.messenger.BillingController} sells Telegram Premium and must never be
 * used for Zenlygram Pro.
 *
 * <h3>The rules of the rail</h3>
 * <ol>
 *   <li>Only the Google Play build sells ({@link #inBuild()}: the bundle flavor, whose versionCode ends in 1). The
 *       side-load build has no purchase control; it uses a grant the linked account already has.</li>
 *   <li>Nothing is sold while the signed policy is free: no product query, no plan, no purchase. Restore and Play's
 *       subscription page stay for an account that already has a Play subscription.</li>
 *   <li>The plans come from the server ({@code POST /v1/google/prepare}: the product and base plan ids and an opaque
 *       {@code obfuscated_account_id} for this binding, never the Telegram id); the prices and introductory phases
 *       are Play's own.</li>
 *   <li>Every purchase token is sent to the server ({@code POST /v1/google/attach}), which verifies it with the Play
 *       Developer API, acknowledges it and answers with the signed entitlement. The app never acknowledges and never
 *       grants Pro from a local purchase.</li>
 *   <li>A purchase is bound to the Telegram account that prepared it and is never moved to another one.</li>
 * </ol>
 * See docs/zenlygram-cloud-android.md, "Zenlygram Pro as a subscription".
 */
public final class ZgBilling {

    private ZgBilling() {
    }

    /** Whether this APK carries the Google Play purchase controls. Immutable per APK. */
    public static boolean inBuild() {
        return GooglePlayCheckout.playBuild();
    }
}

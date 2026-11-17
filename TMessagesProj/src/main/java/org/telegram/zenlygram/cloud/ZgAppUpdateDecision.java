/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

/**
 * The client decision of the app update policy (docs/entitlement.md §3.9c, iOS SGAppUpdatePolicy). Pure.
 *
 * <ul>
 *   <li>Nothing accepted, the Android constraint {@code null}, or an unparsable installed versionName: NONE
 *       (fail-open).</li>
 *   <li>installed &lt; min: BLOCK — but only while the accepted token's {@code exp >= now - 30 days} on the policy
 *       clock (the safety valve: a block never outlives 30 days without a successful fetch).</li>
 *   <li>min &lt;= installed &lt; latest: REMIND (one dismissable alert per distinct latest).</li>
 *   <li>installed &gt;= latest: NONE.</li>
 * </ul>
 * The build number is never compared.
 */
public final class ZgAppUpdateDecision {

    public static final long BLOCK_SAFETY_VALVE_SECONDS = 30L * 24 * 3600;

    public enum Kind {
        NONE,
        REMIND,
        BLOCK
    }

    public final Kind kind;
    /** The {@code latest} the decision names (REMIND / BLOCK), else null. */
    public final String latest;

    private ZgAppUpdateDecision(Kind kind, String latest) {
        this.kind = kind;
        this.latest = latest;
    }

    public static final ZgAppUpdateDecision NONE = new ZgAppUpdateDecision(Kind.NONE, null);

    public static ZgAppUpdateDecision decide(ZgPublicPolicy.AppPolicy accepted, String installedVersionName, long policyNowSeconds) {
        if (accepted == null || accepted.android == null) {
            return NONE;
        }
        final ZgAppVersion installed = ZgAppVersion.parse(installedVersionName);
        if (installed == null) {
            return NONE;
        }
        final ZgPublicPolicy.Constraint android = accepted.android;
        if (installed.compareTo(android.min) < 0) {
            if (accepted.exp >= policyNowSeconds - BLOCK_SAFETY_VALVE_SECONDS) {
                return new ZgAppUpdateDecision(Kind.BLOCK, android.latest.raw);
            }
            // The valve opened: no block; still remind, the installed version is below latest.
            return new ZgAppUpdateDecision(Kind.REMIND, android.latest.raw);
        }
        if (installed.compareTo(android.latest) < 0) {
            return new ZgAppUpdateDecision(Kind.REMIND, android.latest.raw);
        }
        return NONE;
    }
}

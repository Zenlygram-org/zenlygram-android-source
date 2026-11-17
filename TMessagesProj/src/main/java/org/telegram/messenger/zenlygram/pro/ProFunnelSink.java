package org.telegram.messenger.zenlygram.pro;

/**
 * The Play purchase funnel's statistics seam (design 50 §11.4), dependency-free so the host-compiled
 * {@code GooglePlayCheckout} (zenlygram-tests/pro/checkout) never references the customer package: the checkout reports
 * to {@link Holder#get()}, and ZenlyStats.install() sets the implementation that maps each call through ProFunnel onto
 * {@code pro.purchase_start}, {@code pro.purchase_success}, {@code pro.purchase_fail} and {@code pro.restore}
 * ({@code rail: google}). Until then, and for every call the sink refuses, nothing is recorded. Implementations
 * never throw and never block; values are passed as the checkout knows them and validated by the sink.
 */
public interface ProFunnelSink {
    /** {@code GooglePlayBilling.launch} returned {@code Problem.NONE} for {@code productId} / {@code basePlanId}. */
    void purchaseStart(int account, String productId, String basePlanId);

    /** The purchase was attached; {@code trial} = the offer had a free intro phase. */
    void purchaseSuccess(int account, String productId, String basePlanId, boolean trial);

    /**
     * The purchase failed with {@code problemName} ({@code GooglePlayModel.Problem.name()}, or "PENDING" for a pending
     * Play purchase); {@code serverCode} = the backend's error code when the failure came from the server, else null.
     */
    void purchaseFail(int account, String productId, String basePlanId, String problemName, String serverCode);

    /** A restore ended: {@code outcome} "restored", "nothing" or "failed"; {@code serverCode} as above. */
    void restore(int account, String outcome, String serverCode);

    /** Records nothing. */
    ProFunnelSink NONE = new ProFunnelSink() {
        @Override public void purchaseStart(int account, String productId, String basePlanId) { }

        @Override public void purchaseSuccess(int account, String productId, String basePlanId, boolean trial) { }

        @Override public void purchaseFail(int account, String productId, String basePlanId, String problemName, String serverCode) { }

        @Override public void restore(int account, String outcome, String serverCode) { }
    };

    /** The installation's sink: {@link #NONE} until one is set. */
    final class Holder {
        private static volatile ProFunnelSink sink = NONE;

        private Holder() { }

        public static ProFunnelSink get() { return sink; }

        /** Installs {@code value}; null restores {@link #NONE}. */
        public static void set(ProFunnelSink value) { sink = value == null ? NONE : value; }
    }
}

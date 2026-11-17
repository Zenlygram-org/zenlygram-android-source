package org.telegram.messenger.zenlygram.pro;

/**
 * COMPILE-ONLY host stub so CustomerApi.java compiles without Android. It declares just the two members
 * CustomerApi.forAccount references and is never invoked: the tests construct CustomerApi with a host
 * boundary instead. The real ZenlyPro (session store, keystore signing, workers, UI thread, binding
 * listener) is not exercised by these host tests.
 */
public final class ZenlyPro {
    public static ZenlyPro get(int account) { throw new UnsupportedOperationException("host compile stub"); }

    CustomerApi.Boundary customerBoundary() { throw new UnsupportedOperationException("host compile stub"); }

    private ZenlyPro() { }
}

package org.telegram.messenger.zenlygram.pro;

/**
 * COMPILE-ONLY host stub so TasksApi.java compiles without Android. It declares just the two members
 * TasksApi.forAccount references and is never invoked: the tests construct TasksApi with a host
 * boundary instead. The real ZenlyPro (session store, keystore signing, worker, UI thread) is not
 * exercised by these host tests.
 */
public final class ZenlyPro {
    public static ZenlyPro get(int account) { throw new UnsupportedOperationException("host compile stub"); }

    TasksApi.Boundary tasksBoundary() { throw new UnsupportedOperationException("host compile stub"); }

    private ZenlyPro() { }
}

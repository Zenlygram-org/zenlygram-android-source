package org.json;

/**
 * Compile-only host stand-in for Android's org.json.JSONObject, used solely so the transport runner
 * can compile ProTransport (its authenticated request() parses JSON). getPublic never touches JSON.
 * Every member fails loudly: no test can pass by echoing this stub.
 */
public class JSONObject {
    public JSONObject(String json) {
        throw new UnsupportedOperationException("host compile stub: org.json is not exercised by transport tests");
    }

    public int optInt(String name, int fallback) {
        throw new UnsupportedOperationException("host compile stub");
    }

    @Override
    public String toString() {
        throw new UnsupportedOperationException("host compile stub");
    }
}

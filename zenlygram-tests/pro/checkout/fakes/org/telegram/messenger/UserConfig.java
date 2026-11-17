package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;

/** Host stand-in for the Telegram account slot: only the signed-in user id GooglePlayCheckout fences on. */
public final class UserConfig {
    private static final Map<Integer, UserConfig> SLOTS = new HashMap<>();

    private long clientUserId;

    private UserConfig() { }

    public static synchronized UserConfig getInstance(int account) {
        UserConfig slot = SLOTS.get(account);
        if (slot == null) {
            slot = new UserConfig();
            SLOTS.put(account, slot);
        }
        return slot;
    }

    public long getClientUserId() { return clientUserId; }

    /** Test control: who is signed in to {@code account} now (0 = signed out). */
    public static void setClientUserId(int account, long userId) { getInstance(account).clientUserId = userId; }

    public static synchronized void reset() { SLOTS.clear(); }
}

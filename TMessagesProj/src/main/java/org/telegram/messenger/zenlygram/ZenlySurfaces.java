package org.telegram.messenger.zenlygram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

/**
 * ZENLYGRAM 13.2.1: the access-change fan-out of the cloud client. The 12.10.6 class of this name also closed locked
 * viewers and refreshed widgets; here the app's own features (org.telegram.zenlygram.*) keep those duties, so this
 * only posts {@link NotificationCenter#zenlygramAccessChanged} for a signed-in slot, on the UI thread, after the new
 * state is committed.
 */
public final class ZenlySurfaces {
    private ZenlySurfaces() {}

    /** Any thread. */
    public static void onAccessChanged(final int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !UserConfig.getInstance(account).isClientActivated()) return;
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.zenlygramAccessChanged);
        });
    }
}

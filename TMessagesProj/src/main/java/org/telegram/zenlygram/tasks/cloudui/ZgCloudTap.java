/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import java.util.Objects;

/**
 * What a tappable row of a Cloud Tasks screen does: an action of that screen and the id it is about (a mutation, an
 * invitation, a sender). It travels in {@code UItem.object2} and only names things: the screen looks the current state
 * up by the id when the row is tapped, so a row drawn before something changed can never act on what it showed.
 */
final class ZgCloudTap {
    final int action;
    final String key;

    ZgCloudTap(int action, String key) {
        this.action = action;
        this.key = key;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ZgCloudTap)) {
            return false;
        }
        final ZgCloudTap other = (ZgCloudTap) o;
        return action == other.action && Objects.equals(key, other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, key);
    }
}

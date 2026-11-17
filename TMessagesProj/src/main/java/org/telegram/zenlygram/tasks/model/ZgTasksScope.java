/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

/**
 * Which store the Tasks screen shows (iOS TasksScope): {@link #DEVICE} is the account's tasks on this device,
 * {@link #CLOUD} the account's Cloud tasks. Switching never moves, copies or uploads anything.
 */
public enum ZgTasksScope {
    CLOUD("cloud"),
    DEVICE("device");

    /** The value stored for a remembered pick (iOS TasksScope.rawValue). */
    public final String wire;

    ZgTasksScope(String wire) {
        this.wire = wire;
    }

    /** Null for null or an unknown value: nothing was picked. */
    public static ZgTasksScope fromWire(String value) {
        for (ZgTasksScope scope : values()) {
            if (scope.wire.equals(value)) {
                return scope;
            }
        }
        return null;
    }
}

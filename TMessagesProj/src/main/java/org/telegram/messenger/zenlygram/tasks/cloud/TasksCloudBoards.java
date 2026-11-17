package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.IdentityHashMap;

/**
 * Who drives one repository: resumed board screens (visible, app in the foreground) and, since Tasks R4, the app-level
 * owner (ZenlyTasksSyncOwner through TasksCloudRepository.startApp, not visible). The engine runs while any owner
 * exists; realtime (board-only, R3 x-realtime) only while a VISIBLE one does. A board that pauses — or is removed from
 * the back stack, which pauses it again — stops the engine only when it was the last owner, so two boards of the same
 * account, or a board and the app owner, never stop each other. Main-thread confined.
 */
final class TasksCloudBoards {
    private final IdentityHashMap<Object, Boolean> owners = new IdentityHashMap<>();

    /** A resumed board (visible). Always true: every resume re-runs the engine start (foreground catch-up). */
    boolean show(Object board) {
        return show(board, true);
    }

    /** Adds or updates an owner. Always true: the engine (re)starts. */
    boolean show(Object owner, boolean visible) {
        if (owner == null) throw new IllegalArgumentException("board");
        owners.put(owner, visible);
        return true;
    }

    /** True only when this owner was present and was the last one: the engine (and realtime) stop. */
    boolean hide(Object owner) {
        return owners.remove(owner) != null && owners.isEmpty();
    }

    /** Realtime is wanted only for a visible board. */
    boolean hasVisible() {
        return owners.containsValue(Boolean.TRUE);
    }

    boolean hasAny() {
        return !owners.isEmpty();
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.source;

import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;

/**
 * One store of tasks behind the Tasks screens: the account's tasks on this device ({@link ZgDeviceTasksSource}, over
 * the engine's ZenlyTasksStore) or its Cloud tasks ({@link ZgCloudTasksSource}, over TasksCloudRepository). The board,
 * the Lists screen, the editor and "Add to Tasks" only know this interface, so they work the same on either.
 *
 * <ul>
 *   <li>Observer-based: a source publishes an immutable {@link ZgBoard} after every change, whoever made it. A screen
 *       renders the board it was handed; it never reads a store itself and never assumes its own change went through.</li>
 *   <li>Main thread only: every method is called on it, and every observer and result callback runs on it. The store
 *       work itself runs on the engine's own threads.</li>
 *   <li>A change is refused with a {@link ZgTasksError}, never thrown. The caller keeps the user's input until the
 *       result says the change is stored.</li>
 *   <li>One source per account slot and Telegram user ({@link ZgTasksSources}); a source whose user is no longer in
 *       its slot refuses everything and shows nothing.</li>
 * </ul>
 */
public interface ZgTasksSource {

    interface Observer {
        void onBoard(ZgBoard board);
    }

    interface Subscription {
        void unsubscribe();
    }

    /** {@code error} null: done, with {@code value} (may be null where there is nothing to return). */
    interface Done<T> {
        void done(T value, ZgTasksError error);
    }

    /**
     * A delete inside its Undo window: the task is already gone from the board and not deleted in the store yet.
     * Exactly one of the two calls takes effect, once.
     */
    interface HeldDelete {
        /** The task comes back. An error means the Undo came too late: the delete stands. */
        void undo(Done<Void> done);

        /** The Undo is gone (its bulletin closed, the board was left, another delete began): the delete is made. */
        void commit(Done<Void> done);
    }

    ZgTasksScope scope();

    /** The last published board; {@link ZgBoard#empty} before the first one. */
    ZgBoard board();

    /** The observer gets the current board at once, then every later one, until it unsubscribes. */
    Subscription subscribe(Observer observer);

    /**
     * {@code screen} is on screen (resumed): the device source reads its store again; the Cloud source syncs and, while
     * a board is visible, listens for realtime hints. Balanced by {@link #stop} with the same object.
     */
    void start(Object screen);

    void stop(Object screen);

    /** The user asked to try again (a failed read, or "Try Again" of an offline Cloud board). */
    void refresh();

    /**
     * Creates a task after the caller passed the CREATE gate. {@code listId} null files it in the store's inbox (the
     * device Inbox list; the Cloud Inbox, which is no list). {@code dueAt} is Unix seconds. The result is the new
     * task's id (null when it could not be told apart, which only costs the reveal).
     */
    void createTask(String listId, String title, ZgTodoAssignee assignee, Long dueAt, Done<String> done);

    /**
     * Asked the moment a contact is picked as assignee, before anything is stored or queued. Cloud: the pick is the
     * invitation, so the account itself and the assignment limits are refused here with their sentence. Device: a note
     * kept on this device, always allowed.
     */
    void checkAssignee(ZgTodoAssignee assignee, Done<Void> done);

    /**
     * "Send Again" / "Send" of a task's assignee (Cloud only): an ended assignment goes to the same person as a new
     * invitation; an older note that was never sent is sent for the first time.
     */
    void sendAssignee(String taskId, Done<Void> done);

    /** Sets completion to an explicit target state; repeating the current state changes nothing. */
    void setCompleted(String taskId, boolean completed, Done<Void> done);

    /** Starts a delete with Undo (see {@link #undoWindowMs}). */
    void holdDelete(String taskId, Done<HeldDelete> done);

    /** A task shared with this account: leaves it (Cloud only; no Undo). */
    void leave(String taskId, Done<Void> done);

    /** Creates a list after the caller passed the CREATE gate; the result is its id (null when it could not be told apart). */
    void createList(String title, Done<String> done);

    void renameList(String listId, String title, Done<Void> done);

    /** Deletes a list and all its tasks. There is no Undo. */
    void deleteList(String listId, Done<Void> done);

    /** A notice of the board was read (Cloud only). */
    void dismissNotice(long noticeId);

    /** How long a held delete can be undone: the Undo bulletin must not outlive it. */
    long undoWindowMs();

    /** The longest list name this store keeps, in characters. */
    int listTitleLimit();
}

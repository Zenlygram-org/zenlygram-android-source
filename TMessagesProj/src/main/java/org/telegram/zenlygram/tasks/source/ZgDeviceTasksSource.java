/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.source;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksStore;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAnalyticsSink;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.zenlygram.tasks.data.ZgLegacyTasksImport;
import org.telegram.zenlygram.tasks.data.ZgTaskAssigneeStore;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgDeviceBoardMapper;
import org.telegram.zenlygram.tasks.model.ZgTasksArchive;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The account's tasks on this device: the engine's per-user device store ({@code ZenlyTasksStore}) as a
 * {@link ZgTasksSource}. That store is the one the automatic copy to Cloud, its write fence and the cut-over are
 * written against, so the device board lives there.
 *
 * <ul>
 *   <li>Reads and writes are asynchronous (the store's own thread); after every write the store is read again and a
 *       new board is published. The first read waits for the one-time import of the old device-wide board
 *       ({@link ZgLegacyTasksImport}).</li>
 *   <li>The store has no assignee: it is kept in the side store ({@link ZgTaskAssigneeStore}), by task id, and held
 *       here in memory for the rows of this user.</li>
 *   <li>The store mints the id of a new row and does not return it: creations run one at a time, and the new row is
 *       the one that was not there before.</li>
 *   <li>Delete with Undo: the store has no restore, so the task is hidden first and deleted in the store when its Undo
 *       is gone. If the app dies in between, the task is still there (never the other way round).</li>
 *   <li>The write fence: a write the store refuses with MIGRATING (the copy's local step runs) or ARCHIVED (the board
 *       is the read-only Device archive) comes back as that error, and nothing is changed. An ARCHIVED answer also
 *       re-reads the cut-over, so the board turns into the archive at once.</li>
 *   <li>Statistics: the store records nothing itself, so the task and list events of the device board are recorded
 *       here, once, when the store confirmed the change (scope local).</li>
 * </ul>
 * Main thread only.
 */
public final class ZgDeviceTasksSource implements ZgTasksSource, NotificationCenter.NotificationCenterDelegate {

    /** A held delete that meets the copy's local step is tried again this often, this far apart, before it is given up. */
    private static final int DELETE_RETRIES = 5;
    private static final long DELETE_RETRY_MS = 1000;
    /** Cloud publications while the copy is queued refresh the archive's progress at most this often. */
    private static final long PROGRESS_REFRESH_MS = 500;

    private final int account;
    private final long userId;
    private final ArrayList<Observer> observers = new ArrayList<>();
    private final IdentityHashMap<Object, Boolean> screens = new IdentityHashMap<>();
    /** Tasks whose delete waits for its Undo. */
    private final HashSet<String> held = new HashSet<>();
    /** Creations wait for each other: the new row is found by what was there before. */
    private final ArrayDeque<Runnable> creations = new ArrayDeque<>();
    private boolean creating;

    private ZenlyTaskModel.Snapshot snapshot;
    private final HashMap<String, ZenlyTaskModel.Task> rows = new HashMap<>();
    /** Task id to assignee; null until the side store answered. */
    private HashMap<String, ZgTodoAssignee> assignees;
    private TasksCutOver cutOver = TasksCutOver.NONE;
    /** The account's migration record (the archive banner's counts); null until read or none. */
    private ZenlyTaskImport.MigrationRecord record;
    private int recordGeneration;
    private boolean failed;
    private boolean opened;
    /** The first read was started: the old board is imported and this user's assignees are at hand. */
    private boolean ready;
    private boolean disposed;
    private boolean observing;
    private boolean progressScheduled;
    private TasksCloudRepository.Subscription progress;
    private ZgBoard board = ZgBoard.empty(ZgTasksScope.DEVICE);

    ZgDeviceTasksSource(int account, long userId) {
        this.account = account;
        this.userId = userId;
    }

    long userId() {
        return userId;
    }

    @Override
    public ZgTasksScope scope() {
        return ZgTasksScope.DEVICE;
    }

    @Override
    public ZgBoard board() {
        return board;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Observation and lifecycle
    // -----------------------------------------------------------------------------------------------------------

    @Override
    public Subscription subscribe(final Observer observer) {
        observers.add(observer);
        if (!observing && !disposed) {
            observing = true;
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        }
        open();
        observer.onBoard(board);
        return () -> {
            if (observers.remove(observer) && observers.isEmpty() && observing) {
                observing = false;
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.zenlygramTasksModeChanged);
            }
        };
    }

    @Override
    public void start(Object screen) {
        screens.put(screen, Boolean.TRUE);
        if (!opened) {
            open();
            return;
        }
        if (!ready) {
            // The first read is on its way and brings all of this; a read ahead of it would show rows without their
            // assignees for a moment.
            return;
        }
        // The sync owner announces cut-over changes only; coming back also picks up a new record and new counts.
        cutOver = ZenlyTasksSyncOwner.cutOver(account);
        refreshArchive();
        followProgress();
        reload();
    }

    @Override
    public void stop(Object screen) {
        screens.remove(screen);
        followProgress();
    }

    @Override
    public void refresh() {
        if (!opened) {
            open();
        } else if (ready) {
            reload();
            refreshArchive();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.zenlygramTasksModeChanged && account == this.account && !disposed) {
            modeChanged();
        }
    }

    /** The slot's user logged out or changed: nothing more is read, written or shown. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (observing) {
            observing = false;
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        }
        if (progress != null) {
            progress.unsubscribe();
            progress = null;
        }
        held.clear();
        creations.clear();
        publish();
    }

    /** The store of this source's user, or null when the slot no longer holds that user. */
    private ZenlyTasksStore store() {
        if (disposed || UserConfig.getInstance(account).getClientUserId() != userId) {
            return null;
        }
        try {
            return ZenlyTasksStore.get(account);
        } catch (RuntimeException notSignedIn) {
            return null;
        }
    }

    /** The first read: after the old board's rows were imported, and with the assignees at hand. */
    private void open() {
        if (opened || disposed) {
            return;
        }
        opened = true;
        cutOver = ZenlyTasksSyncOwner.cutOver(account);
        ZgLegacyTasksImport.run(account, () -> ZgTaskAssigneeStore.get().load(userId, loaded -> {
            if (assignees == null) {
                assignees = new HashMap<>(loaded);
            } else {
                // A change made before the side store answered is newer than what it held.
                for (Map.Entry<String, ZgTodoAssignee> entry : loaded.entrySet()) {
                    if (!assignees.containsKey(entry.getKey())) {
                        assignees.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            ready = true;
            refreshArchive();
            followProgress();
            reload();
            // A creation asked for before this point waited for it.
            if (!creating) {
                next();
            }
        }));
    }

    private void reload() {
        load(null);
    }

    /**
     * Reads the store and publishes. {@code then} runs before the new board is published and learns whether the read
     * succeeded. Reads answer in the order they were asked, so a later one never shows older rows.
     */
    private void load(final Loaded then) {
        final ZenlyTasksStore store = store();
        if (store == null) {
            publish();
            if (then != null) {
                then.loaded(false);
            }
            return;
        }
        store.load(account, (value, failure) -> {
            if (disposed) {
                return;
            }
            failed = failure != null;
            if (value != null) {
                snapshot = value;
                rows.clear();
                for (ZenlyTaskModel.Task task : value.tasks) {
                    rows.put(task.id, task);
                }
                sweepAssignees();
            }
            if (then != null) {
                then.loaded(failure == null);
            }
            publish();
        });
    }

    private interface Loaded {
        void loaded(boolean ok);
    }

    /** A row whose task is gone (deleted with its list, or anywhere else) is dropped, here and in the side store. */
    private void sweepAssignees() {
        if (assignees == null || assignees.isEmpty()) {
            return;
        }
        boolean orphans = false;
        for (String taskId : new ArrayList<>(assignees.keySet())) {
            if (!rows.containsKey(taskId)) {
                assignees.remove(taskId);
                orphans = true;
            }
        }
        if (orphans) {
            ZgTaskAssigneeStore.get().retain(userId, rows.keySet());
        }
    }

    private void setAssignee(String taskId, ZgTodoAssignee assignee) {
        if (assignees == null) {
            assignees = new HashMap<>();
        }
        if (Objects.equals(assignees.get(taskId), assignee)) {
            return;
        }
        if (assignee == null) {
            assignees.remove(taskId);
        } else {
            assignees.put(taskId, assignee);
        }
        ZgTaskAssigneeStore.get().put(userId, taskId, assignee);
    }

    // -----------------------------------------------------------------------------------------------------------
    // The Device archive (the cut-over)
    // -----------------------------------------------------------------------------------------------------------

    private void modeChanged() {
        cutOver = ZenlyTasksSyncOwner.cutOver(account);
        refreshArchive();
        followProgress();
        publish();
    }

    /**
     * Reads the account's migration record again (the banner's counts and state) and publishes with it. It touches
     * nothing else, so coming back to the screen uses it while the cut-over is unchanged.
     */
    private void refreshArchive() {
        final int generation = ++recordGeneration;
        final ZenlyTasksStore store = store();
        if (!cutOver.isReadOnly() || store == null) {
            record = null;
            return;
        }
        store.migrationRecord(account, (value, failure) -> {
            if (disposed || generation != recordGeneration) {
                return;
            }
            record = failure == null ? value : null;
            publish();
        });
    }

    /** The archive's banner for the cut-over as last read, with the sync owner's I1 and the Cloud store's unsent counts. */
    private ZgTasksArchive archive() {
        if (!cutOver.isReadOnly()) {
            return null;
        }
        boolean ownCopy = ZenlyTasksSyncOwner.ownCopyOnDevice(account);
        return ZgTasksArchive.of(cutOver, record, ownCopy, ZenlyTasksSyncOwner.cachedUnsentCounts(account));
    }

    /**
     * While the copy is queued and the archive is on screen, its confirmations arrive with the account's sync cycles:
     * the progress line follows the Cloud store's publications.
     */
    private void followProgress() {
        final boolean wanted = !disposed && !screens.isEmpty() && cutOver.kind == TasksCutOver.Kind.ARCHIVE
            && cutOver.reason == TasksCutOver.Reason.MIGRATING;
        if (wanted == (progress != null)) {
            return;
        }
        if (!wanted) {
            progress.unsubscribe();
            progress = null;
            return;
        }
        try {
            progress = TasksCloudRepository.forAccount(account).subscribe(state -> {
                if (progressScheduled || disposed) {
                    return;
                }
                progressScheduled = true;
                AndroidUtilities.runOnUIThread(() -> {
                    progressScheduled = false;
                    if (progress != null) {
                        publish();
                    }
                }, PROGRESS_REFRESH_MS);
            });
        } catch (RuntimeException notSignedIn) {
            progress = null;
        }
    }

    private void publish() {
        final ZgBoard next;
        if (disposed || UserConfig.getInstance(account).getClientUserId() != userId) {
            next = new ZgBoard.Builder(ZgTasksScope.DEVICE).gate(ZgBoard.Gate.SIGNED_OUT).build();
        } else {
            next = ZgDeviceBoardMapper.map(snapshot, assignees, held, archive(), failed);
        }
        board = next;
        for (Observer observer : new ArrayList<>(observers)) {
            observer.onBoard(next);
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Changes
    // -----------------------------------------------------------------------------------------------------------

    private static TasksAnalyticsSink analytics() {
        return TasksAnalyticsSink.Holder.get();
    }

    private static ZgTasksError error(ZenlyTasksStore.Failure failure) {
        switch (failure) {
            case MIGRATING:
                return new ZgTasksError(ZgTasksError.Kind.MIGRATING);
            case ARCHIVED:
                return new ZgTasksError(ZgTasksError.Kind.ARCHIVED);
            case INVALID:
                return new ZgTasksError(ZgTasksError.Kind.INVALID);
            case NOT_FOUND:
                return new ZgTasksError(ZgTasksError.Kind.NOT_FOUND);
            case PRO_REQUIRED:
                return new ZgTasksError(ZgTasksError.Kind.PRO_REQUIRED);
            case ACCOUNT_CHANGED:
                return new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED);
            default:
                return new ZgTasksError(ZgTasksError.Kind.STORAGE);
        }
    }

    /** A refused write: an archive answer shows the archive at once; a vanished row re-reads the board. */
    private <T> void refused(ZenlyTasksStore.Failure failure, Done<T> done) {
        if (failure == ZenlyTasksStore.Failure.ARCHIVED) {
            modeChanged();
        } else if (failure == ZenlyTasksStore.Failure.NOT_FOUND) {
            reload();
        }
        done.done(null, error(failure));
    }

    private void enqueue(Runnable creation) {
        // A creation from outside the Tasks screens ("Add to Tasks") may be the first use of this store: the old
        // board's rows are imported ahead of it, on the store's own queue.
        open();
        creations.add(creation);
        if (!creating && ready) {
            next();
        }
    }

    private void next() {
        final Runnable creation = creations.poll();
        creating = creation != null;
        if (creation != null) {
            creation.run();
        }
    }

    @Override
    public void createTask(String listId, final String title, final ZgTodoAssignee assignee, final Long dueAt, final Done<String> done) {
        // Under "All" a task goes to the built-in Inbox (which the copy to Cloud files in the Cloud Inbox).
        final String folderId = listId != null ? listId : ZenlyTaskModel.INBOX_ID;
        enqueue(() -> {
            final ZenlyTasksStore store = store();
            if (store == null) {
                next();
                done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
                return;
            }
            final boolean known = snapshot != null;
            final HashSet<String> before = new HashSet<>(rows.keySet());
            store.saveTask(account, null, folderId, title, "", ZgDeviceBoardMapper.millis(dueAt), 0, false, (ignored, failure) -> {
                if (failure != null) {
                    next();
                    refused(failure, done);
                    return;
                }
                analytics().taskCreated(account, false, dueAt != null, 0, !ZenlyTaskModel.INBOX_ID.equals(folderId));
                load(ok -> {
                    String created = null;
                    if (ok) {
                        String only = null;
                        int fresh = 0;
                        long latest = Long.MIN_VALUE;
                        for (ZenlyTaskModel.Task task : snapshot.tasks) {
                            if (before.contains(task.id)) {
                                continue;
                            }
                            fresh++;
                            only = task.id;
                            if (folderId.equals(task.folderId) && title.equals(task.title) && task.createdAt >= latest) {
                                created = task.id;
                                latest = task.createdAt;
                            }
                        }
                        if (known && fresh == 1) {
                            created = only;
                        }
                    }
                    if (created != null && assignee != null) {
                        setAssignee(created, assignee);
                    }
                    next();
                    // After this read's board is published: the caller finds the new row on it.
                    final String id = created;
                    AndroidUtilities.runOnUIThread(() -> done.done(id, null));
                });
            });
        });
    }

    /**
     * The device editor's Save: name, assignee and deadline of an existing task; its list, notes, priority and
     * completion stay. An assignee-only change never touches the store. Never gated: it edits, it does not create.
     */
    public void updateTask(final String taskId, String title, final ZgTodoAssignee assignee, Long dueAt, final Done<Void> done) {
        final ZenlyTasksStore store = store();
        final ZenlyTaskModel.Task row = rows.get(taskId);
        if (store == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
            return;
        }
        if (row == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.NOT_FOUND));
            return;
        }
        if (ZenlyTasksSyncOwner.cutOver(account).isReadOnly()) {
            modeChanged();
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ARCHIVED));
            return;
        }
        final Long storedDue = ZgDeviceBoardMapper.seconds(row.dueAt);
        final boolean sameDue = Objects.equals(storedDue, dueAt);
        if (row.title.equals(title) && sameDue) {
            setAssignee(taskId, assignee);
            publish();
            done.done(null, null);
            return;
        }
        // An unchanged deadline keeps its stored milliseconds.
        final Long dueMillis = sameDue ? row.dueAt : ZgDeviceBoardMapper.millis(dueAt);
        store.saveTask(account, taskId, row.folderId, title, row.description, dueMillis, row.priority, row.completed, (ignored, failure) -> {
            if (failure != null) {
                refused(failure, done);
                return;
            }
            setAssignee(taskId, assignee);
            reload();
            done.done(null, null);
        });
    }

    @Override
    public void setCompleted(final String taskId, final boolean completed, final Done<Void> done) {
        final ZenlyTasksStore store = store();
        if (store == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
            return;
        }
        final ZenlyTaskModel.Task row = rows.get(taskId);
        store.setCompleted(account, taskId, completed, (ignored, failure) -> {
            if (failure != null) {
                refused(failure, done);
                return;
            }
            // Repeating the state the task already had is no completion and no reopening.
            if (row == null || row.completed != completed) {
                analytics().taskCompleted(account, false, !completed);
            }
            reload();
            done.done(null, null);
        });
    }

    @Override
    public void holdDelete(final String taskId, Done<HeldDelete> done) {
        if (store() == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
            return;
        }
        if (ZenlyTasksSyncOwner.cutOver(account).isReadOnly()) {
            modeChanged();
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ARCHIVED));
            return;
        }
        if (!rows.containsKey(taskId) || held.contains(taskId)) {
            reload();
            done.done(null, new ZgTasksError(ZgTasksError.Kind.NOT_FOUND));
            return;
        }
        held.add(taskId);
        publish();
        done.done(new Held(taskId), null);
    }

    private final class Held implements HeldDelete {
        private final String taskId;
        private boolean settled;

        Held(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void undo(Done<Void> done) {
            if (settled) {
                done.done(null, new ZgTasksError(ZgTasksError.Kind.BUSY));
                return;
            }
            settled = true;
            held.remove(taskId);
            publish();
            done.done(null, null);
        }

        @Override
        public void commit(Done<Void> done) {
            if (settled) {
                return;
            }
            settled = true;
            delete(taskId, 0, done);
        }
    }

    /** The held delete is made. Whatever stops it for good puts the task back on the board and says why. */
    private void delete(final String taskId, final int attempt, final Done<Void> done) {
        final ZenlyTasksStore store = store();
        if (store == null) {
            held.remove(taskId);
            return;
        }
        store.deleteTask(account, taskId, (ignored, failure) -> {
            if (disposed) {
                return;
            }
            if (failure == ZenlyTasksStore.Failure.MIGRATING && attempt < DELETE_RETRIES) {
                // The copy's local step lasts a moment: the row stays hidden and the delete is made right after it.
                AndroidUtilities.runOnUIThread(() -> delete(taskId, attempt + 1, done), DELETE_RETRY_MS);
                return;
            }
            held.remove(taskId);
            if (failure == null || failure == ZenlyTasksStore.Failure.NOT_FOUND) {
                setAssignee(taskId, null);
                if (failure == null) {
                    analytics().taskDeleted(account, false);
                }
                reload();
                if (done != null) {
                    done.done(null, null);
                }
                return;
            }
            if (failure == ZenlyTasksStore.Failure.ARCHIVED) {
                modeChanged();
            } else {
                publish();
            }
            if (done != null) {
                done.done(null, error(failure));
            }
        });
    }

    @Override
    public void leave(String taskId, Done<Void> done) {
        done.done(null, new ZgTasksError(ZgTasksError.Kind.NOT_FOUND));
    }

    @Override
    public void checkAssignee(ZgTodoAssignee assignee, Done<Void> done) {
        // A note on this device: nothing is sent to anyone. Whether the board still offers it is the screen's decision.
        done.done(null, null);
    }

    @Override
    public void sendAssignee(String taskId, Done<Void> done) {
        done.done(null, new ZgTasksError(ZgTasksError.Kind.ASSIGN_NEEDS_SYNC));
    }

    @Override
    public void createList(final String title, final Done<String> done) {
        enqueue(() -> {
            final ZenlyTasksStore store = store();
            if (store == null) {
                next();
                done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
                return;
            }
            final HashSet<String> before = new HashSet<>();
            if (snapshot != null) {
                for (ZenlyTaskModel.Folder folder : snapshot.folders) {
                    before.add(folder.id);
                }
            }
            store.saveFolder(account, null, title, (ignored, failure) -> {
                if (failure != null) {
                    next();
                    refused(failure, done);
                    return;
                }
                analytics().listCreated(account, false);
                load(ok -> {
                    String created = null;
                    if (ok) {
                        long latest = Long.MIN_VALUE;
                        for (ZenlyTaskModel.Folder folder : snapshot.folders) {
                            if (!before.contains(folder.id) && title.equals(folder.title) && folder.createdAt >= latest) {
                                created = folder.id;
                                latest = folder.createdAt;
                            }
                        }
                    }
                    next();
                    final String id = created;
                    AndroidUtilities.runOnUIThread(() -> done.done(id, null));
                });
            });
        });
    }

    @Override
    public void renameList(String listId, String title, final Done<Void> done) {
        final ZenlyTasksStore store = store();
        if (store == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
            return;
        }
        store.saveFolder(account, listId, title, (ignored, failure) -> {
            if (failure != null) {
                refused(failure, done);
                return;
            }
            reload();
            done.done(null, null);
        });
    }

    @Override
    public void deleteList(final String listId, final Done<Void> done) {
        final ZenlyTasksStore store = store();
        if (store == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
            return;
        }
        final ArrayList<String> taskIds = new ArrayList<>();
        for (ZenlyTaskModel.Task task : rows.values()) {
            if (listId.equals(task.folderId)) {
                taskIds.add(task.id);
            }
        }
        if (ZenlyTaskModel.INBOX_ID.equals(listId)) {
            // The built-in Inbox cannot be deleted; deleting it as a list deletes its tasks, and it leaves the board
            // until a task is added under "All" again (on iOS the inbox list is deleted and created again then).
            deleteInboxTask(store, taskIds, 0, done);
            return;
        }
        store.deleteFolder(account, listId, (ignored, failure) -> {
            if (failure != null) {
                refused(failure, done);
                return;
            }
            for (String taskId : taskIds) {
                held.remove(taskId);
                setAssignee(taskId, null);
            }
            analytics().listDeleted(account, false, taskIds.size());
            reload();
            done.done(null, null);
        });
    }

    private void deleteInboxTask(final ZenlyTasksStore store, final ArrayList<String> taskIds, final int index, final Done<Void> done) {
        if (index >= taskIds.size()) {
            analytics().listDeleted(account, false, taskIds.size());
            reload();
            done.done(null, null);
            return;
        }
        final String taskId = taskIds.get(index);
        store.deleteTask(account, taskId, (ignored, failure) -> {
            if (failure != null && failure != ZenlyTasksStore.Failure.NOT_FOUND) {
                // What was deleted so far stays deleted; the rest is still on the board.
                reload();
                refused(failure, done);
                return;
            }
            held.remove(taskId);
            setAssignee(taskId, null);
            deleteInboxTask(store, taskIds, index + 1, done);
        });
    }

    @Override
    public void dismissNotice(long noticeId) {
        // The device store has no notices.
    }

    @Override
    public long undoWindowMs() {
        // The same window as a Cloud delete, so both boards behave alike.
        return TasksCloudRepository.HELD_DELETE_TIMEOUT_MS;
    }

    @Override
    public int listTitleLimit() {
        return ZenlyTaskModel.MAX_FOLDER_TITLE;
    }
}

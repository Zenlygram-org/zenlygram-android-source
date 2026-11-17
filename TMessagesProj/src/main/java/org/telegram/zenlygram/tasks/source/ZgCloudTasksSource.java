/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.source;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAssigneeDisplay;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudError;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgCloudBoardMapper;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * The account's Cloud tasks: the engine's {@code TasksCloudRepository} as a {@link ZgTasksSource}. The repository
 * owns SQL, the outbox, sync and realtime; this class subscribes to its immutable state, maps it to a board
 * ({@link ZgCloudBoardMapper}) and passes every change on as a repository intent. Nothing here talks to the network,
 * and nothing is sent unless the consent gate of Cloud Tasks allows it (the engine checks it again before every
 * request).
 *
 * <ul>
 *   <li>The board also depends on things outside the repository's state: the consent gate, the cut-over, whether the
 *       account is linked, whether its consent was withdrawn. Their notifications rebuild the board.</li>
 *   <li>While the board is read-only (consent off, withdrawn, or fenced by the server) every change is refused here
 *       with its reason, before anything is queued.</li>
 *   <li>A task's assignee is its assignment: picking a contact sends the invitation with the task (iOS wf15). The
 *       owner-only annotation is never written; an older one shows as "Not sent yet" until it is sent or removed.</li>
 *   <li>"Copy Device Tasks to Cloud…" is offered only while a copy would queue something: the repository's own
 *       preview is asked when the board appears, when the first complete snapshot arrives and when the cut-over
 *       changes.</li>
 *   <li>Statistics: the engine records the task, list and sharing events of Cloud itself; nothing is recorded here.</li>
 * </ul>
 * Main thread only.
 */
public final class ZgCloudTasksSource implements ZgTasksSource, NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final long userId;
    private final ArrayList<Observer> observers = new ArrayList<>();
    private final IdentityHashMap<Object, Boolean> screens = new IdentityHashMap<>();
    private TasksCloudRepository repository;
    private TasksCloudRepository.Subscription subscription;
    private TasksCloudState state;
    private boolean attached;
    private boolean disposed;
    private boolean wasComplete;
    /** The repository's copy preview would queue at least one device row. */
    private boolean deviceRowsToCopy;
    private boolean copyCheckRunning;
    private boolean copyCheckAgain;
    private ZgBoard board = ZgBoard.empty(ZgTasksScope.CLOUD);

    ZgCloudTasksSource(int account, long userId) {
        this.account = account;
        this.userId = userId;
    }

    long userId() {
        return userId;
    }

    @Override
    public ZgTasksScope scope() {
        return ZgTasksScope.CLOUD;
    }

    @Override
    public ZgBoard board() {
        return board;
    }

    /** The repository behind this source, for the copy dialog; null when the slot no longer holds this user. */
    public TasksCloudRepository repository() {
        return usableRepository();
    }

    // -----------------------------------------------------------------------------------------------------------
    // Observation and lifecycle
    // -----------------------------------------------------------------------------------------------------------

    @Override
    public Subscription subscribe(final Observer observer) {
        observers.add(observer);
        attach();
        observer.onBoard(board);
        return () -> {
            if (observers.remove(observer) && observers.isEmpty()) {
                detach();
            }
        };
    }

    private boolean slotHoldsUser() {
        return !disposed && UserConfig.getInstance(account).getClientUserId() == userId;
    }

    private TasksCloudRepository usableRepository() {
        if (!slotHoldsUser()) {
            return null;
        }
        if (repository == null) {
            try {
                repository = TasksCloudRepository.forAccount(account);
            } catch (RuntimeException notSignedIn) {
                repository = null;
            }
        }
        return repository;
    }

    private void attach() {
        if (attached || disposed) {
            return;
        }
        attached = true;
        final NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        center.addObserver(this, NotificationCenter.zenlygramProStatusChanged);
        center.addObserver(this, NotificationCenter.zenlygramCustomerStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zenlygramConsentChanged);
        final TasksCloudRepository opened = usableRepository();
        if (opened == null) {
            rebuild();
            return;
        }
        // The first state arrives inside subscribe(); callbacks of another repository or after the end are ignored.
        subscription = opened.subscribe(value -> {
            if (opened == repository && !disposed) {
                onState(value);
            }
        });
    }

    private void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        final NotificationCenter center = NotificationCenter.getInstance(account);
        center.removeObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        center.removeObserver(this, NotificationCenter.zenlygramProStatusChanged);
        center.removeObserver(this, NotificationCenter.zenlygramCustomerStateChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.zenlygramConsentChanged);
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (disposed) {
            return;
        }
        if (id == NotificationCenter.zenlygramConsentChanged) {
            if (args != null && args.length > 0 && args[0] instanceof Integer && (Integer) args[0] == this.account) {
                rebuild();
            }
        } else if (account == this.account) {
            rebuild();
            if (id == NotificationCenter.zenlygramTasksModeChanged) {
                // A migration step changes what a copy would still queue.
                checkDeviceCopy();
            }
        }
    }

    /** The slot's user logged out or changed: nothing more is sent or shown. */
    void dispose() {
        if (disposed) {
            return;
        }
        final TasksCloudRepository opened = repository;
        if (opened != null) {
            for (Object screen : new ArrayList<>(screens.keySet())) {
                opened.stop(screen);
            }
        }
        screens.clear();
        detach();
        disposed = true;
        repository = null;
        state = null;
        rebuild();
    }

    @Override
    public void start(Object screen) {
        screens.put(screen, Boolean.TRUE);
        final TasksCloudRepository opened = usableRepository();
        if (opened != null) {
            opened.start(screen);
        }
        rebuild();
        checkDeviceCopy();
    }

    @Override
    public void stop(Object screen) {
        if (screens.remove(screen) != null && repository != null) {
            repository.stop(screen);
        }
    }

    @Override
    public void refresh() {
        final TasksCloudRepository opened = usableRepository();
        if (opened != null) {
            opened.refresh();
        }
    }

    private void onState(TasksCloudState value) {
        state = value;
        final boolean complete = value != null && value.shadowComplete;
        final boolean becameComplete = complete && !wasComplete;
        wasComplete = complete;
        rebuild();
        if (becameComplete) {
            checkDeviceCopy();
        }
    }

    /**
     * The board as it would be published now, for a caller that does not observe ("Add to Tasks"): from the
     * repository's current state when nobody is subscribed.
     */
    public ZgBoard peek() {
        if (!attached) {
            final TasksCloudRepository opened = usableRepository();
            state = opened != null ? opened.currentState() : null;
            board = map();
        }
        return board;
    }

    private ZgBoard map() {
        final ZgCloudBoardMapper.Inputs in = new ZgCloudBoardMapper.Inputs();
        in.state = slotHoldsUser() && repository != null ? state : null;
        in.permission = ZenlyTasksSyncOwner.permission(account);
        in.cutOver = ZenlyTasksSyncOwner.cutOver(account);
        in.linked = linked();
        in.consentWithdrawn = withdrawn();
        in.deviceRowsToCopy = deviceRowsToCopy;
        in.nowSeconds = ZenlyPro.trustedNowSeconds();
        return ZgCloudBoardMapper.map(in);
    }

    private void rebuild() {
        final ZgBoard next = map();
        if (next.equals(board)) {
            return;
        }
        board = next;
        for (Observer observer : new ArrayList<>(observers)) {
            observer.onBoard(next);
        }
    }

    /** The account is linked to Zenlygram (the same reading as the link screen's). */
    public boolean linked() {
        try {
            return slotHoldsUser() && ZenlyPro.get(account).snapshot().linked;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean withdrawn() {
        try {
            final ConsentMachine.State consent = ZenlyConsent.state(userId);
            return consent == ConsentMachine.State.WITHDRAWN || consent == ConsentMachine.State.WITHDRAWN_REMOTE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Asks the repository whether a copy of the device tasks would queue anything now, so the offer ends with the
     * copy instead of lingering because the originals stay on the device (iOS hasImportableDeviceTasks). Only a
     * linked, completely loaded, writable account is asked; the preview reads the device store and changes nothing.
     */
    public void checkDeviceCopy() {
        final TasksCloudRepository opened = usableRepository();
        if (opened == null || state == null || !state.shadowComplete || board.isReadOnly() || !linked()) {
            if (deviceRowsToCopy) {
                deviceRowsToCopy = false;
                rebuild();
            }
            return;
        }
        if (copyCheckRunning) {
            copyCheckAgain = true;
            return;
        }
        copyCheckRunning = true;
        opened.previewImport((preview, error) -> {
            copyCheckRunning = false;
            if (opened != repository || disposed) {
                return;
            }
            final boolean offer = error == null && preview != null && (preview.importableLists > 0 || preview.importableTasks > 0);
            if (offer != deviceRowsToCopy) {
                deviceRowsToCopy = offer;
                rebuild();
            }
            if (copyCheckAgain) {
                copyCheckAgain = false;
                checkDeviceCopy();
            }
        });
    }

    // -----------------------------------------------------------------------------------------------------------
    // Changes
    // -----------------------------------------------------------------------------------------------------------

    /** A repository refusal as the kind the Tasks screens word (never the engine's own message). */
    public static ZgTasksError error(TasksCloudError error) {
        if (error == null) {
            return new ZgTasksError(ZgTasksError.Kind.STORAGE);
        }
        switch (error.code) {
            case NOT_LINKED:
            case CLOSED:
                return new ZgTasksError(ZgTasksError.Kind.NOT_LINKED);
            case PRINCIPAL_CHANGED:
                return new ZgTasksError(ZgTasksError.Kind.LINK_CHANGED);
            case NOT_READY:
                return new ZgTasksError(ZgTasksError.Kind.NOT_READY);
            case NOT_FOUND:
                return new ZgTasksError(ZgTasksError.Kind.NOT_FOUND);
            case NOT_OWNER:
                return new ZgTasksError(ZgTasksError.Kind.NOT_OWNER);
            case INVALID:
                if ("is_completed".equals(error.field)) {
                    return new ZgTasksError(ZgTasksError.Kind.ASSIGN_COMPLETED);
                }
                return new ZgTasksError(ZgTasksError.Kind.INVALID, error.field);
            case BUSY_STATE:
                return new ZgTasksError(ZgTasksError.Kind.BUSY);
            case IMPORT_STALE:
                return new ZgTasksError(ZgTasksError.Kind.IMPORT_STALE);
            case CONSENT_WITHDRAWN:
                return new ZgTasksError(ZgTasksError.Kind.CONSENT);
            case SELF_ASSIGNMENT:
                return new ZgTasksError(ZgTasksError.Kind.ASSIGN_SELF);
            case ASSIGNMENT_LIMIT:
                return new ZgTasksError(ZgTasksError.Kind.ASSIGN_LIMIT, error.field);
            case CHANGED_ELSEWHERE:
            case NOT_ALLOWED:
                return new ZgTasksError(ZgTasksError.Kind.ASSIGN_CHANGED);
            default:
                return new ZgTasksError(ZgTasksError.Kind.STORAGE);
        }
    }

    /**
     * The repository to send a change to, or null after {@code done} was told why not: the account is gone, or the
     * board is read-only (nothing is queued under a consent hold).
     */
    private <T> TasksCloudRepository writable(Done<T> done) {
        final TasksCloudRepository opened = usableRepository();
        if (opened == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ACCOUNT_CHANGED));
            return null;
        }
        if (map().isReadOnly()) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.CONSENT));
            return null;
        }
        return opened;
    }

    /** A repository result as a source result, only while this source still shows that repository. */
    private <T, R> TasksCloudRepository.Result<T> result(final TasksCloudRepository opened, final Done<R> done, final Mapped<T, R> value) {
        return (v, e) -> {
            if (disposed || opened != repository) {
                return;
            }
            if (e != null) {
                done.done(null, error(e));
            } else {
                done.done(value == null ? null : value.of(v), null);
            }
        };
    }

    private interface Mapped<T, R> {
        R of(T value);
    }

    @Override
    public void createTask(String listId, String title, ZgTodoAssignee assignee, Long dueAt, Done<String> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened == null) {
            return;
        }
        final TasksWire.Content content;
        try {
            content = new TasksWire.Content(listId, title, null, dueAt, 0, ZgCloudBoardMapper.nextTaskOrder(state, listId), null);
        } catch (TasksWire.Invalid invalid) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.INVALID, invalid.field));
            return;
        }
        // The assignee is the assignment: the create and the invitation go as one change. A refused pick (the account
        // itself, a limit) creates nothing.
        opened.createTask(content, assignee == null ? null : String.valueOf(assignee.peerId), this.<String, String>result(opened, done, id -> id));
    }

    @Override
    public void checkAssignee(ZgTodoAssignee assignee, Done<Void> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened != null) {
            opened.checkAssignment(String.valueOf(assignee.peerId), this.<Void, Void>result(opened, done, null));
        }
    }

    @Override
    public void sendAssignee(String taskId, Done<Void> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened == null) {
            return;
        }
        final TasksCloudState.TaskView task = state == null ? null : state.task(taskId);
        if (task == null || !task.owner) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.NOT_FOUND));
            return;
        }
        final TasksAssigneeDisplay.State shown = task.assigneeState(ZenlyPro.trustedNowSeconds());
        if (shown == TasksAssigneeDisplay.State.NOT_SENT && task.content.annotation != null) {
            // An older note that was never sent: its first invitation (the note is cleared once the server has it).
            opened.assign(taskId, task.content.annotation.telegramUserId, this.<String, Void>result(opened, done, null));
        } else if (shown != null && shown.isEnded()) {
            opened.resendAssignment(taskId, this.<String, Void>result(opened, done, null));
        } else {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.ASSIGN_CHANGED));
        }
    }

    @Override
    public void setCompleted(String taskId, boolean completed, Done<Void> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened != null) {
            opened.setCompleted(taskId, completed, this.<String, Void>result(opened, done, null));
        }
    }

    @Override
    public void holdDelete(String taskId, final Done<HeldDelete> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened == null) {
            return;
        }
        opened.holdDelete(taskId, this.<TasksCloudRepository.HeldDelete, HeldDelete>result(opened, done, held -> new HeldDelete() {
            @Override
            public void undo(Done<Void> undone) {
                held.undo((ignored, error) -> undone.done(null, error == null ? null : error(error)));
            }

            @Override
            public void commit(Done<Void> committed) {
                // The repository promotes the held delete exactly once; the outbox takes it from there.
                held.dismiss();
                if (committed != null) {
                    committed.done(null, null);
                }
            }
        }));
    }

    @Override
    public void leave(String taskId, Done<Void> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened == null) {
            return;
        }
        final TasksCloudState.TaskView task = state == null ? null : state.task(taskId);
        // Leaving is bound to the invitation the task is shared under.
        if (task == null || task.owner || task.liveInvitationId == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.NOT_FOUND));
            return;
        }
        opened.respond(task.liveInvitationId, TasksWire.Decision.LEAVE, this.<String, Void>result(opened, done, null));
    }

    @Override
    public void createList(String title, Done<String> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened != null) {
            opened.createList(title, ZgCloudBoardMapper.nextListOrder(state), this.<String, String>result(opened, done, id -> id));
        }
    }

    @Override
    public void renameList(String listId, String title, Done<Void> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened == null) {
            return;
        }
        final TasksCloudState.ListView list = state == null ? null : state.list(listId);
        if (list == null) {
            done.done(null, new ZgTasksError(ZgTasksError.Kind.NOT_FOUND));
            return;
        }
        opened.updateList(listId, title, list.sortOrder, this.<String, Void>result(opened, done, null));
    }

    @Override
    public void deleteList(String listId, Done<Void> done) {
        final TasksCloudRepository opened = writable(done);
        if (opened != null) {
            opened.deleteList(listId, this.<String, Void>result(opened, done, null));
        }
    }

    @Override
    public void dismissNotice(long noticeId) {
        final TasksCloudRepository opened = usableRepository();
        if (opened != null) {
            opened.dismissNotice(noticeId);
        }
    }

    @Override
    public long undoWindowMs() {
        return TasksCloudRepository.HELD_DELETE_TIMEOUT_MS;
    }

    @Override
    public int listTitleLimit() {
        return ZgTasksTitlePolicy.FOLDER_LIMIT;
    }
}

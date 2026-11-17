package org.telegram.messenger.zenlygram.tasks;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAutoAcceptPolicy;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * The device-local inputs of the contact rule (iOS SGTasksSyncCoordinator, wf15 §5.3, D2): for one driven account it
 * reads the account's Telegram contacts, its Telegram blocked list (to the end, once) and — for every sender of a
 * pending task invitation — that sender's own block flag, and hands them to the repository, which accepts a contact's
 * invitation by itself (TasksAutoAcceptPolicy).
 * <ul>
 * <li>The contacts and the blocked list are read exactly as the Contacts tab and the Privacy screen read them. They are
 * kept in memory only: never persisted by Tasks and never sent. The only thing that leaves the phone is the ordinary
 * {@code invitation.respond {accept}}.</li>
 * <li>Fail closed: until the contacts have loaded nobody is a contact; until the blocked list has been read to its end
 * nothing is accepted; a sender whose own flag has not been read since their invitation is pending is never accepted
 * (a block made after the one-time list read is known only from that flag). Such invitations simply wait in
 * Invitations, and the run after the change accepts them.</li>
 * <li>Changes are debounced (0.5 s). When the owner stops driving the account (it is no longer allowed, it logged out,
 * its repository was replaced) the rule is torn down and the repository forgets the inputs, so nothing is accepted on
 * old contact or block data after a re-grant.</li>
 * </ul>
 * Main thread.
 */
final class ZenlyTasksContactRule implements NotificationCenter.NotificationCenterDelegate {
    private static final long DEBOUNCE_MS = 500;
    /** A sender's flag that did not arrive is asked for again no sooner than this. */
    private static final long RETRY_MS = 30_000;
    private static final ZenlyTasksContactRule[] RULES = new ZenlyTasksContactRule[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    private final TasksCloudRepository repository;
    private TasksCloudRepository.Subscription subscription;
    /** Senders of the invitations that wait now. */
    private final HashSet<Long> senders = new HashSet<>();
    /** Senders whose own flag was asked for since their invitation is pending (with when), and those it was read for. */
    private final HashMap<Long, Long> requested = new HashMap<>();
    private final HashSet<Long> checked = new HashSet<>();
    /** Senders whose own flag says blocked. */
    private final HashSet<Long> flagged = new HashSet<>();
    private TasksAutoAcceptPolicy.Inputs handed;
    private boolean disposed, scheduled;
    private final Runnable push = this::push;

    private ZenlyTasksContactRule(int account, TasksCloudRepository repository) {
        this.account = account;
        this.repository = repository;
    }

    /** The owner drives {@code repository} for the account: idempotent for the same repository, a new one replaces the rule. */
    static void attach(int account, TasksCloudRepository repository) {
        if (account < 0 || account >= RULES.length || repository == null) return;
        ZenlyTasksContactRule current = RULES[account];
        if (current != null && current.repository == repository) return;
        if (current != null) current.dispose();
        ZenlyTasksContactRule rule = new ZenlyTasksContactRule(account, repository);
        RULES[account] = rule;
        rule.start();
    }

    /** The owner no longer drives the account. */
    static void detach(int account) {
        if (account < 0 || account >= RULES.length || RULES[account] == null) return;
        RULES[account].dispose();
        RULES[account] = null;
    }

    private void start() {
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.contactsDidLoad);
        center.addObserver(this, NotificationCenter.blockedUsersDidLoad);
        center.addObserver(this, NotificationCenter.userInfoDidLoad);
        readBlockedList();
        subscription = repository.subscribe(this::onState);
        schedule();
    }

    private void dispose() {
        if (disposed) return;
        disposed = true;
        AndroidUtilities.cancelRunOnUIThread(push);
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.removeObserver(this, NotificationCenter.contactsDidLoad);
        center.removeObserver(this, NotificationCenter.blockedUsersDidLoad);
        center.removeObserver(this, NotificationCenter.userInfoDidLoad);
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        repository.clearAutoAcceptInputs();
    }

    /** The blocked list is read page by page until its end (the Privacy screen's own request). */
    private void readBlockedList() {
        MessagesController controller = MessagesController.getInstance(account);
        if (controller.blockedEndReached || controller.loadingBlockedPeers) return;
        controller.getBlockedPeers(controller.totalBlockedCount < 0);
    }

    /** The senders of the waiting invitations: a new one gets its own block flag read; one that left is forgotten. */
    private void onState(TasksCloudState state) {
        if (disposed || state == null) return;
        HashSet<Long> now = new HashSet<>();
        for (TasksWire.InvitationItem invitation : state.invitations) {
            try {
                now.add(Long.parseLong(invitation.ownerTgId));
            } catch (NumberFormatException notAnId) {
                // never a contact
            }
        }
        boolean changed = !now.equals(senders);
        if (changed) {
            senders.clear();
            senders.addAll(now);
            requested.keySet().retainAll(now);
            checked.retainAll(now);
            flagged.retainAll(now);
        }
        askFlags();
        if (changed) schedule();
    }

    /**
     * Asks the server for the block flag of every pending sender it has not answered for yet. A flag that was asked for
     * and never arrived (offline) is asked for again with a later publication.
     */
    private void askFlags() {
        long uptime = SystemClock.elapsedRealtime();
        MessagesController controller = MessagesController.getInstance(account);
        for (Long sender : senders) {
            if (checked.contains(sender)) continue;
            Long asked = requested.get(sender);
            if (asked != null && uptime - asked < RETRY_MS) continue;
            TLRPC.User user = controller.getUser(sender);
            if (user == null) continue; // unknown here: not a contact, and never checked
            requested.put(sender, uptime);
            // The server's own answer, never the cached profile: the callback runs only for a fresh response.
            final long id = sender;
            controller.loadFullUser(user, 0, true, full -> AndroidUtilities.runOnUIThread(() -> flagRead(id, full)));
        }
    }

    /** A sender's block flag as the server just answered it. */
    private void flagRead(long sender, TLRPC.UserFull full) {
        if (disposed || full == null || !requested.containsKey(sender)) return;
        checked.add(sender);
        if (full.blocked) flagged.add(sender);
        else flagged.remove(sender);
        schedule();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (disposed || account != this.account) return;
        if (id == NotificationCenter.userInfoDidLoad) {
            // Any later profile of a pending sender that says blocked counts at once; "not blocked" is taken only from
            // the fresh answer this rule asked for (a cached profile may be older than a block).
            if (args.length < 2 || !(args[0] instanceof Long) || !(args[1] instanceof TLRPC.UserFull)) return;
            Long sender = (Long) args[0];
            if (!senders.contains(sender) || !((TLRPC.UserFull) args[1]).blocked || !flagged.add(sender)) return;
        } else if (id == NotificationCenter.blockedUsersDidLoad) {
            readBlockedList();
            // A sender the list no longer holds may have been unblocked: the flag is read again (unchecked until then).
            LongSparseIntArray list = MessagesController.getInstance(account).blockePeers;
            boolean unflagged = false;
            for (Long sender : new ArrayList<>(flagged)) {
                if (list.indexOfKey(sender) >= 0) continue;
                flagged.remove(sender);
                checked.remove(sender);
                requested.remove(sender);
                unflagged = true;
            }
            if (unflagged) askFlags();
        }
        schedule();
    }

    private void schedule() {
        if (disposed || scheduled) return;
        scheduled = true;
        AndroidUtilities.runOnUIThread(push, DEBOUNCE_MS);
    }

    private void push() {
        scheduled = false;
        if (disposed) return;
        TasksAutoAcceptPolicy.Inputs inputs = inputs();
        if (inputs.equals(handed)) return;
        handed = inputs;
        repository.runAutoAccept(inputs);
    }

    private TasksAutoAcceptPolicy.Inputs inputs() {
        HashSet<Long> contacts = new HashSet<>();
        ContactsController contactsController = ContactsController.getInstance(account);
        if (contactsController.contactsLoaded) {
            // A copy: the list is replaced on the main thread, and nothing of it is kept beyond these ids.
            for (TLRPC.TL_contact contact : new ArrayList<>(contactsController.contacts)) {
                if (contact != null && contact.user_id > 0) contacts.add(contact.user_id);
            }
        }
        MessagesController controller = MessagesController.getInstance(account);
        HashSet<Long> blocked = new HashSet<>(flagged);
        LongSparseIntArray list = controller.blockePeers;
        for (int i = 0, n = list.size(); i < n; i++) blocked.add(list.keyAt(i));
        return new TasksAutoAcceptPolicy.Inputs(contacts, blocked, controller.blockedEndReached, checked);
    }
}

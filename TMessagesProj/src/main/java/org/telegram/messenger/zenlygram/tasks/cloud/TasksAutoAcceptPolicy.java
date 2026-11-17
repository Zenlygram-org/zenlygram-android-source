package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The contact rule (iOS SGTasksAutoAcceptPolicy, wf15 §5.3, D2): a pure decision per pending invitation. An invitation
 * from a person in the RECIPIENT's own Telegram contacts is accepted by the recipient's phone itself; everyone else's
 * waits in Invitations. The check is local (the app knows its contacts; the server never receives them) and only the
 * existing {@code invitation.respond {decision: accept}} leaves the device. Every condition fails closed:
 * <pre>
 *   accept iff permission is ALLOWED                         the traffic rule
 *          and contacts ∋ owner                              the recipient's own Telegram contacts
 *          and owner != own id
 *          and blockedSenders ∌ owner                        Tasks sender.block (shadow + queued)
 *          and the Telegram blocked list has been read       a blocked user can still be a contact;
 *          and telegramBlocked ∌ owner                        unknown → wait
 *          and telegramBlockChecked ∋ owner                  this sender's own block flag has been read since the
 *                                                            invitation is pending (a block made after the one-time
 *                                                            list read is known only from the flag)
 *          and not expired
 *          and no unresolved answer to the invitation        one answer at a time
 *          and the invitation was never answered by hand
 *          and the task was never declined or left by hand   every later epoch waits in Invitations
 *          and automatic attempts &lt; 3                        the initial accept + 2 reseals
 * </pre>
 * Leaving an invitation for the user records nothing anywhere: before the contacts have loaded, before the blocked
 * list has been read once or before the sender's flag has been read the invitation simply waits, and the run after the
 * change accepts it.
 */
public final class TasksAutoAcceptPolicy {
    /** The initial automatic accept and two reseals after revision_changed. */
    public static final int MAXIMUM_ATTEMPTS = 3;

    /**
     * The device-local inputs the app gathers from the account's Telegram data and hands to the repository
     * ({@code runAutoAccept}). Nothing here is persisted or sent.
     */
    public static final class Inputs {
        /** Raw Telegram user ids of the account's contacts. */
        public final Set<Long> contactTgIds;
        /** Raw Telegram user ids blocked in Telegram: the blocked list plus the pending senders' own flag. */
        public final Set<Long> telegramBlockedIds;
        /** The blocked list has been read to its end once in this process. */
        public final boolean telegramBlockedListLoaded;
        /** The pending senders whose own block flag has been read since their invitation is pending. */
        public final Set<Long> telegramBlockCheckedSenderIds;

        public Inputs(Set<Long> contactTgIds, Set<Long> telegramBlockedIds, boolean telegramBlockedListLoaded,
                      Set<Long> telegramBlockCheckedSenderIds) {
            this.contactTgIds = Collections.unmodifiableSet(new HashSet<>(contactTgIds));
            this.telegramBlockedIds = Collections.unmodifiableSet(new HashSet<>(telegramBlockedIds));
            this.telegramBlockedListLoaded = telegramBlockedListLoaded;
            this.telegramBlockCheckedSenderIds = Collections.unmodifiableSet(new HashSet<>(telegramBlockCheckedSenderIds));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Inputs)) return false;
            Inputs o = (Inputs) other;
            return telegramBlockedListLoaded == o.telegramBlockedListLoaded && contactTgIds.equals(o.contactTgIds)
                    && telegramBlockedIds.equals(o.telegramBlockedIds) && telegramBlockCheckedSenderIds.equals(o.telegramBlockCheckedSenderIds);
        }

        @Override public int hashCode() {
            return contactTgIds.hashCode() * 31 + telegramBlockedIds.hashCode() * 17 + telegramBlockCheckedSenderIds.hashCode()
                    + (telegramBlockedListLoaded ? 1 : 0);
        }
    }

    /** Everything one decision reads. */
    public static final class Context {
        public final boolean allowed;
        public final Inputs inputs;
        public final long ownTelegramId;
        /** Tasks-level shadow blocks (sender.block, acknowledged or queued), as raw Telegram id strings. */
        public final Set<String> blockedSenderIds;
        public final long nowSeconds;
        /** Invitation ids with an unresolved answer or block in the outbox. */
        public final Set<String> unresolvedInvitationIds;
        public final TasksInviteAnswers manualAnswers;
        /** Automatic accepts already made, by invitation id. */
        public final Map<String, Integer> automaticAttempts;

        public Context(boolean allowed, Inputs inputs, long ownTelegramId, Set<String> blockedSenderIds, long nowSeconds,
                       Set<String> unresolvedInvitationIds, TasksInviteAnswers manualAnswers, Map<String, Integer> automaticAttempts) {
            this.allowed = allowed;
            this.inputs = inputs;
            this.ownTelegramId = ownTelegramId;
            this.blockedSenderIds = blockedSenderIds;
            this.nowSeconds = nowSeconds;
            this.unresolvedInvitationIds = unresolvedInvitationIds;
            this.manualAnswers = manualAnswers;
            this.automaticAttempts = automaticAttempts;
        }
    }

    private TasksAutoAcceptPolicy() { }

    /** True: this device accepts the invitation by itself now. False: it is left for the user. */
    public static boolean accepts(TasksWire.InvitationItem invitation, Context context) {
        if (!context.allowed || context.inputs == null) return false;
        long owner;
        try {
            owner = Long.parseLong(invitation.ownerTgId);
        } catch (NumberFormatException e) {
            return false;
        }
        if (!context.inputs.contactTgIds.contains(owner)) return false;
        if (owner == context.ownTelegramId) return false;
        if (context.blockedSenderIds.contains(invitation.ownerTgId)) return false;
        if (!context.inputs.telegramBlockedListLoaded || context.inputs.telegramBlockedIds.contains(owner)) return false;
        if (!context.inputs.telegramBlockCheckedSenderIds.contains(owner)) return false;
        if (TasksWire.epochSeconds(invitation.expiresAt) <= context.nowSeconds) return false;
        if (context.unresolvedInvitationIds.contains(invitation.id)) return false;
        if (context.manualAnswers.answered(invitation.id)) return false;
        if (context.manualAnswers.refused(invitation.taskId)) return false;
        Integer attempts = context.automaticAttempts.get(invitation.id);
        return attempts == null || attempts < MAXIMUM_ATTEMPTS;
    }
}

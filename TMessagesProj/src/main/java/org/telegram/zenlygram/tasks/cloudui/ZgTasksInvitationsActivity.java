/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Cloud Tasks "Invitations" (iOS TasksCloudInboxController): the tasks other people offered to share with this account
 * and the senders it blocked.
 *
 * <ul>
 *   <li>Before accepting only the title, the deadline and the sender are known. The sender is shown by the name this
 *       account's own Telegram data has for them, always with the Telegram ID, and by the ID alone when it does not
 *       know them (iOS shows the ID only); the wire carries no names, so a sender-chosen one is never trusted.</li>
 *   <li>Accept, Decline and Block Sender are queued like every other cloud change. The repository hides an answered
 *       invitation at once, so the answers the server has not confirmed are listed below the invitations (iOS keeps the
 *       invitation in place with "Waiting for sync confirmation"): a queued answer says so instead of pretending it
 *       was delivered.</li>
 *   <li>Every row looks the invitation up again when it is tapped: one that is gone, or that ran out by the server's
 *       clock, is never answered, whatever the row showed when it was drawn.</li>
 *   <li>While a consent hold makes the account read-only nothing is queued; the reason is shown instead.</li>
 * </ul>
 */
public class ZgTasksInvitationsActivity extends ZgCloudScreen {

    private static final int ID_READ_ONLY = 1;
    private static final int ID_STATUS = 2;
    private static final int ID_ANSWERS_REVIEW = 3;
    private static final int ID_ANSWERS_GAP = 4;
    private static final int ID_ACCESS_HEADER = 5;
    private static final int ID_ACCESS_BEFORE = 6;
    private static final int ID_ACCESS_AFTER = 7;
    private static final int ID_ACCESS_FOOTER = 8;
    private static final int ID_BLOCKED_HEADER = 9;
    private static final int ID_BLOCKED_FOOTER = 10;
    private static final int ID_HINT = 11;

    private static final int SLOT_FROM = 1;
    private static final int SLOT_INFO = 2;
    private static final int SLOT_ACCEPT = 3;
    private static final int SLOT_DECLINE = 4;
    private static final int SLOT_BLOCK = 5;
    private static final int SLOT_EXPIRY = 6;
    private static final int SLOT_ANSWER = 7;
    private static final int SLOT_BLOCKED = 8;

    private static final int DO_ACCEPT = 1;
    private static final int DO_DECLINE = 2;
    private static final int DO_BLOCK = 3;
    private static final int DO_UNBLOCK = 4;
    private static final int DO_REVIEW = 5;

    /** The invitations this screen has shown: what an answer still on its way was about (in memory only). */
    private final HashMap<String, TasksWire.InvitationItem> shown = new HashMap<>();

    public ZgTasksInvitationsActivity(int account) {
        super(account);
    }

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.TASKS_INVITATIONS;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZenlyCloudTasksInvitations);
    }

    @Override
    protected void onStateChanged() {
        final TasksCloudState value = state;
        if (value != null) {
            final ArrayList<String> people = new ArrayList<>();
            for (TasksWire.InvitationItem invitation : value.invitations) {
                shown.put(invitation.id, invitation);
                people.add(invitation.ownerTgId);
            }
            for (TasksWire.BlockItem block : value.blocks) {
                people.add(block.id);
            }
            loadNames(people);
        }
        super.onStateChanged();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final TasksCloudState value = state;
        final int unavailable = unavailableText();
        if (value == null || contentHidden()) {
            items.add(UItem.asShadow(ID_STATUS, getString(unavailable != 0 ? unavailable : R.string.ZenlyCloudTasksStatusClosed)));
            return;
        }
        addReadOnlyNotice(items, ID_READ_ONLY);
        // Tasks from the user's own Telegram contacts never wait here: the device accepts them by itself.
        items.add(UItem.asShadow(ID_HINT, getString(R.string.ZG_tasks_invitations_hint)));

        if (value.invitations.isEmpty()) {
            // Never "No invitations" before the server's list has been loaded.
            items.add(UItem.asShadow(ID_STATUS, getString(unavailable != 0 ? unavailable : R.string.ZG_cloudui_invitations_empty)));
        }
        final long now = ZenlyPro.trustedNowSeconds();
        for (TasksWire.InvitationItem invitation : value.invitations) {
            final String id = invitation.id;
            final boolean expired = ZgCloudSharing.expired(invitation.expiresAt, now);
            items.add(UItem.asHeader(rowId(id, SLOT_FROM), formatString(R.string.ZG_cloudui_invitation_from, person(invitation.ownerTgId))));
            final String deadline = invitation.dueAt != null ? ZgCloudText.moment(invitation.dueAt) : getString(R.string.ZG_tasks_metadata_none);
            // Another user's text: isolated, so it cannot reorder the line, and never turned into links.
            items.add(ZgCloudInfoCell.Factory.of(rowId(id, SLOT_INFO), getString(R.string.ZG_tasks_deadline_title) + ": " + deadline,
                ZgCloudText.isolated(invitation.title)));
            items.add(action(rowId(id, SLOT_ACCEPT), R.drawable.msg_select, getString(R.string.ZenlyCloudTasksAccept), false,
                new ZgCloudTap(DO_ACCEPT, id)).setEnabled(!expired));
            items.add(action(rowId(id, SLOT_DECLINE), R.drawable.msg_cancel, getString(R.string.ZenlyCloudTasksDecline), false,
                new ZgCloudTap(DO_DECLINE, id)).setEnabled(!expired));
            items.add(action(rowId(id, SLOT_BLOCK), R.drawable.msg_block, getString(R.string.ZG_cloudui_invitation_block), true,
                new ZgCloudTap(DO_BLOCK, id)));
            items.add(UItem.asShadow(rowId(id, SLOT_EXPIRY), expired ? getString(R.string.ZG_cloudui_invitation_expired)
                : formatString(R.string.ZenlyCloudTasksInvitationExpires, ZgCloudText.instant(invitation.expiresAt))));
        }

        fillAnswers(items, value);

        // What accepting would change: rights begin only after acceptance, and the owner's lists and assignees are
        // never part of it.
        items.add(UItem.asHeader(ID_ACCESS_HEADER, getString(R.string.ZG_cloudui_access_header)));
        items.add(ZgCloudInfoCell.Factory.of(ID_ACCESS_BEFORE, getString(R.string.ZG_cloudui_invitations_beforeLabel),
            getString(R.string.ZG_cloudui_invitations_before)));
        items.add(ZgCloudInfoCell.Factory.of(ID_ACCESS_AFTER, getString(R.string.ZG_cloudui_invitations_afterLabel),
            getString(R.string.ZG_cloudui_invitations_after)));
        items.add(UItem.asShadow(ID_ACCESS_FOOTER, getString(R.string.ZG_cloudui_invitations_footer)));

        if (!value.blocks.isEmpty()) {
            items.add(UItem.asHeader(ID_BLOCKED_HEADER, getString(R.string.ZenlyCloudTasksBlocked)));
            for (TasksWire.BlockItem block : value.blocks) {
                final UItem row = UItem.asButton(rowId(block.id, SLOT_BLOCKED), person(block.id), getString(R.string.ZenlyCloudTasksUnblock));
                row.object2 = new ZgCloudTap(DO_UNBLOCK, block.id);
                items.add(row);
            }
            items.add(UItem.asShadow(ID_BLOCKED_FOOTER, getString(R.string.ZG_cloudui_unblock_footer)));
        }
    }

    /** The answers, blocks and unblocks of this account that the server has not confirmed. */
    private void fillAnswers(ArrayList<UItem> items, TasksCloudState value) {
        boolean any = false;
        boolean attention = false;
        for (TasksCloudState.Pending pending : value.pending) {
            if (!isAnswer(pending) || isAutomaticAccept(value, pending)) {
                continue;
            }
            any = true;
            final boolean stuck = ZgCloudIssues.needsAttention(pending.state);
            attention |= stuck;
            final StringBuilder text = new StringBuilder();
            final String subject = answerSubject(pending);
            if (subject != null) {
                text.append(subject).append('\n');
            }
            text.append(getString(stuck ? R.string.ZG_cloudui_needsAttention : R.string.ZG_cloudui_syncConfirmationPending));
            items.add(ZgCloudInfoCell.Factory.of(rowId(pending.mutationId, SLOT_ANSWER), answerLabel(pending), text));
        }
        if (!any) {
            return;
        }
        if (attention) {
            items.add(action(ID_ANSWERS_REVIEW, R.drawable.msg_info, getString(R.string.ZG_cloudui_review), false, new ZgCloudTap(DO_REVIEW, null)));
        }
        items.add(UItem.asShadow(ID_ANSWERS_GAP, null));
    }

    /** The device's own accept of a contact's task (a provisional row on the board): not an answer the user gave here. */
    private static boolean isAutomaticAccept(TasksCloudState value, TasksCloudState.Pending pending) {
        if (!(pending.draft instanceof TasksWire.Operation.InvitationRespond) || ZgCloudIssues.needsAttention(pending.state)) {
            return false;
        }
        final TasksWire.Operation.InvitationRespond respond = (TasksWire.Operation.InvitationRespond) pending.draft;
        return respond.decision == TasksWire.Decision.ACCEPT && value.provisional(respond.invitationId) != null;
    }

    private static boolean isAnswer(TasksCloudState.Pending pending) {
        if (!ZgCloudIssues.listed(pending.state)) {
            return false;
        }
        if (pending.kind == TasksWire.OperationKind.SENDER_BLOCK || pending.kind == TasksWire.OperationKind.SENDER_UNBLOCK) {
            return true;
        }
        // Leave is about a task that was already accepted: it belongs to the task, not to this screen.
        return pending.draft instanceof TasksWire.Operation.InvitationRespond
            && ((TasksWire.Operation.InvitationRespond) pending.draft).decision != TasksWire.Decision.LEAVE;
    }

    private String answerLabel(TasksCloudState.Pending pending) {
        final String operation = getString(ZgCloudText.operationRes(pending.kind));
        if (pending.draft instanceof TasksWire.Operation.InvitationRespond) {
            final boolean accept = ((TasksWire.Operation.InvitationRespond) pending.draft).decision == TasksWire.Decision.ACCEPT;
            return operation + " · " + getString(accept ? R.string.ZenlyCloudTasksAccept : R.string.ZenlyCloudTasksDecline);
        }
        return operation;
    }

    /** The task an answer was about, or the sender a block or unblock is about, when this screen knows it. */
    private String answerSubject(TasksCloudState.Pending pending) {
        if (pending.kind == TasksWire.OperationKind.SENDER_UNBLOCK) {
            return person(pending.resourceId);
        }
        final TasksWire.InvitationItem invitation;
        if (pending.draft instanceof TasksWire.Operation.InvitationRespond) {
            invitation = shown.get(((TasksWire.Operation.InvitationRespond) pending.draft).invitationId);
            return invitation != null ? ZgCloudText.isolated(invitation.title) : null;
        }
        invitation = shown.get(pending.resourceId);
        return invitation != null ? person(invitation.ownerTgId) : null;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object2 instanceof ZgCloudTap)) {
            return;
        }
        final ZgCloudTap tap = (ZgCloudTap) item.object2;
        switch (tap.action) {
            case DO_ACCEPT:
                respond(tap.key, TasksWire.Decision.ACCEPT);
                break;
            case DO_DECLINE:
                respond(tap.key, TasksWire.Decision.DECLINE);
                break;
            case DO_BLOCK:
                block(tap.key);
                break;
            case DO_UNBLOCK:
                unblock(tap.key);
                break;
            case DO_REVIEW:
                presentFragment(new ZgTasksUnsentActivity(currentAccount));
                break;
            default:
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** The invitation as it is now, or null after saying that it is gone. */
    private TasksWire.InvitationItem current(String invitationId) {
        final TasksCloudState value = state;
        final TasksWire.InvitationItem invitation = value != null ? value.invitation(invitationId) : null;
        if (invitation == null) {
            showNotice(getString(R.string.ZG_cloudui_error_gone));
        }
        return invitation;
    }

    private void respond(String invitationId, TasksWire.Decision decision) {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksWire.InvitationItem invitation = current(invitationId);
        if (invitation == null) {
            return;
        }
        if (ZgCloudSharing.expired(invitation.expiresAt, ZenlyPro.trustedNowSeconds())) {
            showNotice(getString(R.string.ZG_cloudui_error_invitationExpired));
            render();
            return;
        }
        repository.respond(invitationId, decision, result(null));
    }

    private void block(String invitationId) {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksWire.InvitationItem invitation = current(invitationId);
        if (invitation == null) {
            return;
        }
        final String title = getString(R.string.ZG_cloudui_invitation_block);
        confirm(title, formatString(R.string.ZG_cloudui_invitation_blockConfirm, person(invitation.ownerTgId)), title, true, null, () -> {
            // The repository refuses again if the invitation went away meanwhile.
            if (repository != null && !refuseReadOnly()) {
                repository.blockSender(invitationId, result(null));
            }
        });
    }

    private void unblock(String senderTgId) {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        confirm(getString(R.string.ZG_cloudui_unblock_title), getString(R.string.ZG_cloudui_unblock_footer),
            getString(R.string.ZenlyCloudTasksUnblock), false, null, () -> {
                if (repository != null && !refuseReadOnly()) {
                    repository.unblockSender(senderTgId, result(null));
                }
            });
    }
}

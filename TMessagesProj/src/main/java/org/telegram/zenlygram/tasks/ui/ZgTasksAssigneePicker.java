/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.ContactsActivity;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;

/**
 * Telegram's contact list as the assignee picker (iOS pushTasksAssigneePicker): the user's own Telegram contacts only,
 * no phone book, bots, self, or username / global search, titled "Choose Assignee". The pick is a local annotation:
 * nothing is sent, and the contact is not notified. The phone number is never used as the name.
 */
public final class ZgTasksAssigneePicker {

    public interface Callback {
        void onPicked(ZgTodoAssignee assignee);
    }

    private ZgTasksAssigneePicker() {
    }

    /** Returns false when nothing was opened (e.g. another screen is being presented). */
    public static boolean present(BaseFragment from, Callback callback) {
        final Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("needPhonebook", false);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("allowUsernameSearch", false);
        args.putBoolean("needForwardCount", false);
        args.putBoolean("disableSections", false);
        final ContactsActivity picker = new ContactsActivity(args) {
            @Override
            public View createView(Context context) {
                final View view = super.createView(context);
                if (simpleActionBar != null) {
                    simpleActionBar.setTitle(LocaleController.getString(R.string.ZG_tasks_assignee_pickerTitle));
                } else if (actionBar != null) {
                    actionBar.setTitle(LocaleController.getString(R.string.ZG_tasks_assignee_pickerTitle));
                }
                return view;
            }
        };
        final int account = from.getCurrentAccount();
        picker.setDelegate((user, param, activity) -> {
            final ZgTodoAssignee assignee = validated(user, account);
            if (assignee != null) {
                callback.onPicked(assignee);
            } else {
                BulletinFactory.global().createErrorBulletin(LocaleController.getString(R.string.ZG_tasks_assignee_notContact)).show();
            }
        });
        return from.presentFragment(picker);
    }

    /** A contact of this account, not a bot and not the user, with a visible name of the allowed length. */
    static ZgTodoAssignee validated(TLRPC.User user, int account) {
        if (user == null || user.bot || !user.contact || user.id == UserConfig.getInstance(account).getClientUserId()) {
            return null;
        }
        final String name = ContactsController.formatName(user.first_name, user.last_name);
        return ZgTasksTitlePolicy.validatedAssignee(user.id, name);
    }
}

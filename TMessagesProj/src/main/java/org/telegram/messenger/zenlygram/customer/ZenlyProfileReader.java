package org.telegram.messenger.zenlygram.customer;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Reads one account's profile facts from Telegram's own session (design 50 §9.1): the signed-in user object of
 * {@code UserConfig} (id, phone digits, usernames, names, Premium) and the device facts of {@link ZenlyCustomerDevice};
 * {@link ProfileSnapshot} normalizes them. Only what the session already holds: no request, no contact, no chat. Main
 * thread (UserConfig's user object is replaced there); never throws.
 */
final class ZenlyProfileReader {
    private ZenlyProfileReader() {}

    /** The snapshot of the user signed in on {@code account}, or null when the slot holds none or its id is unusable. */
    static ProfileSnapshot read(int account) {
        try {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
            UserConfig config = UserConfig.getInstance(account);
            TLRPC.User user = config.getCurrentUser();
            if (user == null || user.id <= 0) return null;
            ProfileSnapshot.Input input = new ProfileSnapshot.Input();
            input.telegramUserId = user.id;
            input.phoneDigits = config.getClientPhone();
            ArrayList<ProfileSnapshot.Username> usernames = new ArrayList<>();
            if (user.usernames != null) {
                for (TLRPC.TL_username entry : user.usernames) {
                    if (entry != null) usernames.add(new ProfileSnapshot.Username(entry.username, entry.editable, entry.active));
                }
            }
            input.usernames = usernames;
            input.username = user.username;
            input.firstName = user.first_name;
            input.lastName = user.last_name;
            input.premium = config.isPremium();
            input.appVersion = ZenlyPro.appVersionName();
            input.osVersion = android.os.Build.VERSION.RELEASE;
            input.tablet = CustomerEvents.Context.TABLET.equals(ZenlyCustomerDevice.deviceClass());
            input.deviceModel = ZenlyCustomerDevice.deviceModel();
            input.appBuild = ZenlyCustomerDevice.appBuild();
            input.uiLanguage = ZenlyCustomerDevice.uiLanguage();
            input.locale = ZenlyCustomerDevice.locale();
            input.timezone = ZenlyCustomerDevice.timezone();
            input.distribution = ZenlyCustomerDevice.distribution();
            return ProfileSnapshot.of(input);
        } catch (RuntimeException unusable) {
            // A Telegram id outside tg_id or an unreadable user object: no profile sync for it.
            return null;
        }
    }
}

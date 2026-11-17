/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.lock.ZgLockBiometrics;
import org.telegram.zenlygram.lock.ZgMasterPin;

import java.util.ArrayList;

/**
 * One locked chat's Chat Lock screen (iOS §2.2, profile "Chat Lock…" and the Center's chat rows): Change Pincode,
 * Unlock with biometrics, Remove Lock. Closes itself once the chat is no longer locked.
 */
public class ZgChatLockChatActivity extends UniversalFragment implements ZgChatLockStore.Listener, org.telegram.messenger.zenlygram.customer.ZenlyStats.NamedScreen { // ZENLYGRAM: statistics name the screen

    @Override
    public org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen zenlyScreen() {
        return org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.CHAT_LOCK_SETTINGS;
    }

    private static final int ID_CHANGE = 1;
    private static final int ID_BIOMETRICS = 2;
    private static final int ID_REMOVE = 3;

    private final long dialogId;

    public ZgChatLockChatActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        ZgChatLockStore.addListener(this);
        return view;
    }

    @Override
    public void onFragmentDestroy() {
        ZgChatLockStore.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onLocksChanged(long[] added, long[] removed) {
        if (!ZgChatLockStore.isLocked(dialogId)) {
            finishFragment();
        } else if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        final CharSequence name = ZgChatLockFlows.displayName(currentAccount, dialogId);
        return name != null ? name : getString(R.string.ZG_CLC_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean master = ZgMasterPin.isSet();
        for (ZgChatLockCenterSpec.ChatRowType row : ZgChatLockCenterSpec.chatRows(ZgLockBiometrics.isStrongAvailable())) {
            switch (row) {
                case CHANGE_PINCODE:
                    items.add(UItem.asButton(ID_CHANGE, R.drawable.msg_pin_code, getString(R.string.ZG_CLC_ChangePincode)));
                    break;
                case CHANGE_PINCODE_FOOTER:
                    items.add(UItem.asShadow(master
                        ? TextUtils.concat(getString(R.string.ZG_CLC_ChangePincodeFooterType), " ", getString(R.string.ZG_CLC_ChangePincodeFooterForgot))
                        : getString(R.string.ZG_CLC_ChangePincodeFooterType)));
                    break;
                case BIOMETRICS:
                    items.add(UItem.asCheck(ID_BIOMETRICS, getString(R.string.ZG_CLC_UnlockWithBiometrics)).setChecked(ZgChatLockStore.isBiometricEnabled(dialogId)));
                    break;
                case BIOMETRICS_FOOTER:
                    items.add(UItem.asShadow(getString(R.string.ZG_CLC_ChatBiometricsFooter)));
                    break;
                case REMOVE_LOCK:
                    items.add(UItem.asButton(ID_REMOVE, R.drawable.msg_delete, getString(R.string.ZG_CLC_RemoveLock)).red());
                    break;
                case REMOVE_FOOTER:
                    items.add(UItem.asShadow(master
                        ? TextUtils.concat(getString(R.string.ZG_CLC_RemoveFooterAsks), " ", getString(R.string.ZG_CLC_RemoveFooterForgot))
                        : getString(R.string.ZG_CLC_RemoveFooterAsks)));
                    break;
            }
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final Runnable refresh = () -> {
            if (listView != null) {
                listView.adapter.update(true);
            }
        };
        if (item.id == ID_CHANGE) {
            ZgChatLockCenter.changePincode(this, dialogId, refresh);
        } else if (item.id == ID_BIOMETRICS) {
            ZgChatLockCenter.setChatBiometrics(this, dialogId, !item.checked, refresh);
        } else if (item.id == ID_REMOVE) {
            ZgChatLockCenter.removeLock(this, dialogId, refresh);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

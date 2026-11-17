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

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.lock.ZgLockCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Settings &gt; Zenlygram &gt; Chat Lock: the Chat Lock Center (iOS SGChatLockController). Its rows come from
 * {@link ZgChatLockCenterSpec#rows} for one of five states:
 *
 * <ul>
 *   <li>OFF: "Turn On Chat Lock" and the explainer.</li>
 *   <li>ON: MASTER PINCODE (change, biometrics), LOCKED CHATS (sorted by title with the locale's collation, each with
 *       its lock type; "Add a Chat…"), "Turn off Chat Lock".</li>
 *   <li>LEGACY (locked chats, no master): "Set Master Pincode", the notice, the list, turn off with device-owner
 *       proof.</li>
 *   <li>RECOVERY: the recovery actions (a new pincode for every locked chat, or off) after device-owner proof.</li>
 *   <li>UNREADABLE: never on Android (the stores are app-private preferences), kept for parity.</li>
 * </ul>
 * Changes from anywhere (chat list menu, profile, another screen) re-render it through {@link ZgChatLockStore.Listener}.
 */
public class ZgChatLockCenterActivity extends UniversalFragment implements ZgChatLockStore.Listener, org.telegram.messenger.zenlygram.customer.ZenlyStats.NamedScreen { // ZENLYGRAM: statistics name the screen

    @Override
    public org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen zenlyScreen() {
        return org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.CHAT_LOCK_SETTINGS;
    }

    private static final int ID_BASE = 1;
    private static final int ID_PEER_BASE = 1000;

    private final HashMap<Integer, Long> peerByItemId = new HashMap<>();

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
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_CLC_Title);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        peerByItemId.clear();
        final ZgChatLockCenterSpec.Input input = ZgChatLockCenter.input(currentAccount);
        final long[] visible = ZgChatLockCenterSpec.visibleIds(input);
        final HashMap<Long, String> titles = new HashMap<>();
        for (long id : visible) {
            final CharSequence name = ZgChatLockFlows.displayName(currentAccount, id);
            if (name != null) {
                titles.put(id, name.toString());
            }
        }
        final long[] order = ZgChatLockCenterSpec.sortByTitle(visible, titles, LocaleController.getInstance().getCurrentLocale());
        final List<ZgChatLockCenterSpec.Row> rows = ZgChatLockCenterSpec.rows(input, order);
        final boolean anyVaultHidden = !input.vaultHidden.isEmpty();
        for (ZgChatLockCenterSpec.Row row : rows) {
            final int id = ID_BASE + row.type.ordinal();
            switch (row.type) {
                case TURN_ON:
                    items.add(UItem.asButton(id, R.drawable.msg_secret, getString(R.string.ZG_CLC_TurnOn)).accent());
                    break;
                case OFF_EXPLAINER:
                    items.add(UItem.asShadow(getString(R.string.ZG_CLC_OffExplainer)));
                    break;
                case MASTER_HEADER:
                    items.add(UItem.asHeader(getString(R.string.ZG_CLC_MasterHeader)));
                    break;
                case CHANGE_MASTER:
                    items.add(UItem.asButton(id, R.drawable.msg_pin_code, getString(R.string.ZG_CLC_ChangeMaster)));
                    break;
                case MASTER_BIOMETRICS:
                    items.add(UItem.asCheck(id, getString(R.string.ZG_CLC_MasterBiometrics)).setChecked(row.flag));
                    break;
                case MASTER_FOOTER:
                    items.add(UItem.asShadow(row.flag
                        ? TextUtils.concat(getString(R.string.ZG_CLC_MasterBiometricsFooter), "\n\n", getString(R.string.ZG_CLC_MasterFooter))
                        : getString(R.string.ZG_CLC_MasterFooter)));
                    break;
                case LEGACY_SET_MASTER:
                    items.add(UItem.asButton(id, R.drawable.msg_pin_code, getString(R.string.ZG_CLC_LegacySetMaster)).accent());
                    break;
                case LEGACY_NOTICE:
                    items.add(UItem.asShadow(getString(R.string.ZG_CLC_LegacyNotice)));
                    break;
                case LOCKED_CHATS_HEADER:
                    items.add(UItem.asHeader(getString(R.string.ZG_CLC_LockedChatsHeader)));
                    break;
                case PEER: {
                    final int itemId = ID_PEER_BASE + peerByItemId.size();
                    peerByItemId.put(itemId, row.peerId);
                    final CharSequence name = titles.get(row.peerId);
                    final CharSequence type = getString(ZgChatLockStore.getType(row.peerId) == ZgLockCode.TYPE_PIN
                        ? R.string.ZG_CLC_LockTypePin : R.string.ZG_CLC_LockTypePassword);
                    items.add(UItem.asButton(itemId, R.drawable.msg_secret, name != null ? name : getString(R.string.ZG_ChatLock_LockedChatPreview), type));
                    break;
                }
                case ADD_CHAT:
                    items.add(UItem.asButton(id, R.drawable.msg_add, getString(R.string.ZG_CLC_AddChat)).accent());
                    break;
                case LOCKED_CHATS_FOOTER: {
                    CharSequence text = getString(row.flag2 ? R.string.ZG_CLC_EmptyFooter : R.string.ZG_CLC_ListFooter);
                    if (anyVaultHidden) {
                        text = TextUtils.concat(text, " ", getString(R.string.ZG_CLC_VaultFooter));
                    }
                    if (row.number != 0) {
                        text = TextUtils.concat(text, " ", getString(R.string.ZG_CLC_OtherAccountsFooter));
                    }
                    items.add(UItem.asShadow(text));
                    break;
                }
                case TURN_OFF:
                    items.add(UItem.asButton(id, R.drawable.msg_delete, getString(R.string.ZG_ChatLock_TurnOff)).red());
                    break;
                case TURN_OFF_FOOTER:
                    items.add(UItem.asShadow(getString(R.string.ZG_CLC_TurnOffFooter)));
                    break;
                case TURN_OFF_WITHOUT_MASTER:
                    items.add(UItem.asButton(id, R.drawable.msg_delete, getString(R.string.ZG_ChatLock_TurnOff)).red().setEnabled(row.flag));
                    break;
                case LEGACY_TURN_OFF_FOOTER:
                    items.add(UItem.asShadow(row.flag2
                        ? TextUtils.concat(getString(R.string.ZG_CLC_LegacyTurnOffFooter), "\n\n", getString(R.string.ZG_ChatLock_ResetUnavailableText))
                        : getString(R.string.ZG_CLC_LegacyTurnOffFooter)));
                    break;
                case RECOVERY_NOTICE:
                    items.add(UItem.asHeader(getString(R.string.ZG_ChatLock_LostTitle)));
                    items.add(UItem.asShadow(TextUtils.concat(getString(R.string.ZG_ChatLock_LostText), "\n\n", getString(R.string.ZG_Hub_RecoveryNotice))));
                    break;
                case RECOVERY_SET_NEW_PIN:
                    items.add(UItem.asButton(id, R.drawable.msg_pin_code, getString(R.string.ZG_ChatLock_SetNewPinForLocked)).accent());
                    break;
                case RECOVERY_TURN_OFF:
                    items.add(UItem.asButton(id, R.drawable.msg_delete, getString(R.string.ZG_ChatLock_TurnOff)).red());
                    break;
                case RECOVERY_HINT:
                    items.add(UItem.asShadow(getString(R.string.ZG_ChatLock_TurnOffNoProofText)));
                    break;
                case UNREADABLE_NOTICE:
                    items.add(UItem.asHeader(getString(R.string.ZG_CLC_UnreadableTitle)));
                    items.add(UItem.asShadow(getString(R.string.ZG_CLC_UnreadableText)));
                    break;
            }
        }
        if (!items.isEmpty() && !UniversalAdapter.isShadow(items.get(items.size() - 1).viewType)) {
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final Long peer = peerByItemId.get(item.id);
        if (peer != null) {
            ZgChatLockCenter.openChat(this, peer);
            return;
        }
        final int ordinal = item.id - ID_BASE;
        if (ordinal < 0 || ordinal >= ZgChatLockCenterSpec.RowType.values().length) {
            return;
        }
        final Runnable refresh = this::refresh;
        switch (ZgChatLockCenterSpec.RowType.values()[ordinal]) {
            case TURN_ON:
                ZgChatLockCenter.armMaster(this, false, refresh);
                break;
            case LEGACY_SET_MASTER:
                ZgChatLockCenter.armMaster(this, true, refresh);
                break;
            case CHANGE_MASTER:
                ZgChatLockCenter.changeMaster(this, refresh);
                break;
            case MASTER_BIOMETRICS:
                ZgChatLockCenter.setMasterBiometrics(this, !item.checked, refresh);
                break;
            case ADD_CHAT:
                ZgChatLockCenter.openPicker(this);
                break;
            case TURN_OFF:
                ZgChatLockCenter.turnOff(this, refresh);
                break;
            case TURN_OFF_WITHOUT_MASTER:
                if (item.enabled) {
                    ZgChatLockCenter.turnOffWithoutMaster(this, refresh);
                }
                break;
            case RECOVERY_SET_NEW_PIN:
            case RECOVERY_TURN_OFF:
                ZgChatLockFlows.startRecovery(this, refresh);
                break;
            default:
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}

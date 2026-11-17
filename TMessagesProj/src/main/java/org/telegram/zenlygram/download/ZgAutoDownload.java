/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.download;

import android.content.SharedPreferences;

import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Disable Auto-Download (iOS autoDownloadDisabled): media auto-download goes off on mobile data, Wi-Fi and roaming,
 * in every logged-in account (Android keeps these presets per account).
 *
 * <p>It writes exactly what the switches in Data and Storage write (DataSettingsActivity: the preset string, then
 * checkAutodownloadSettings and savePresetToServer), so Data and Storage shows the same state. Before switching off,
 * each account's three on/off states are kept in {@code zg_autodl_snapshot} (MessagesController.getMainSettings),
 * and turning the Zenlygram switch off restores them. Accounts that log in later are switched off too.
 *
 * <p>If the user turns a network back on in Data and Storage, the Zenlygram switch reads as off again (iOS
 * recomputes it the same way): the user's own choice wins. {@link #sync} then gives every other account its own saved
 * states back (no account stays switched off behind a switch that reads off) and drops the snapshots. The row's
 * getter ({@link #isDisabled}) only reads; writes happen in {@link #setDisabled} and {@link #sync}.
 */
public final class ZgAutoDownload {

    private static final String KEY_SNAPSHOT = "zg_autodl_snapshot";
    private static final String KEY_OWNER = "zg_autodl_owner";

    private ZgAutoDownload() {
    }

    private static boolean isAvailable() {
        return ZgFeatures.DISABLE_AUTO_DOWNLOAD.isPorted();
    }

    /**
     * What the settings row shows, without side effects: the switch is on and no account it switched off has had a
     * network turned back on in Data and Storage. An account that logged in since then (no snapshot yet) is brought in
     * line by {@link #sync}.
     */
    public static boolean isDisabled() {
        if (!isAvailable() || !ZgSettings.autoDownloadDisabled.get()) {
            return false;
        }
        return !anyAccountReenabled();
    }

    /**
     * App start, a new login and the Zenlygram settings screen. If the user turned a network back on in Data and
     * Storage for an account the switch had turned off, the user's choice wins: the switch goes off, every account that
     * is still fully off gets its own saved states back, the re-enabled one keeps what the user set, and the snapshots
     * go. Otherwise accounts that logged in while the switch is on are switched off too.
     */
    public static void sync() {
        if (!isAvailable() || !ZgSettings.autoDownloadDisabled.get()) {
            return;
        }
        if (anyAccountReenabled()) {
            ZgSettings.autoDownloadDisabled.set(false);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated() && peekSnapshot(a) != null && !anyEnabled(DownloadController.getInstance(a))) {
                    apply(a, false); // restores this account's own states and clears its snapshot
                } else {
                    clearSnapshot(a);
                }
            }
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && readSnapshot(a) == null) {
                apply(a, true); // logged in after the switch was turned on
            }
        }
    }

    /** Some logged-in account that the switch turned off (it has a snapshot) has a network on again. Read only. */
    private static boolean anyAccountReenabled() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && peekSnapshot(a) != null && anyEnabled(DownloadController.getInstance(a))) {
                return true;
            }
        }
        return false;
    }

    /** The settings switch. */
    public static void setDisabled(boolean disabled) {
        ZgSettings.autoDownloadDisabled.set(disabled);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                apply(a, disabled);
            } else {
                clearSnapshot(a);
            }
        }
    }

    public static void onAccountLoggedOut(int account) {
        clearSnapshot(account);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void apply(int account, boolean disable) {
        try {
            final DownloadController controller = DownloadController.getInstance(account);
            final DownloadController.Preset[] presets = {controller.mobilePreset, controller.wifiPreset, controller.roamingPreset};
            final boolean[] target = new boolean[3];
            if (disable) {
                if (readSnapshot(account) == null) {
                    writeSnapshot(account, presets);
                }
                // target stays false for all three
            } else {
                final boolean[] snapshot = readSnapshot(account);
                for (int i = 0; i < 3; i++) {
                    target[i] = snapshot == null || snapshot[i];
                }
                clearSnapshot(account);
            }
            final String[] keys = {"mobilePreset", "wifiPreset", "roamingPreset"};
            final SharedPreferences.Editor editor = MessagesController.getMainSettings(account).edit();
            boolean changed = false;
            final boolean[] changedType = new boolean[3];
            for (int i = 0; i < 3; i++) {
                if (presets[i] != null && presets[i].enabled != target[i]) {
                    presets[i].enabled = target[i];
                    editor.putString(keys[i], presets[i].toString());
                    changedType[i] = true;
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            editor.commit();
            controller.checkAutodownloadSettings();
            for (int i = 0; i < 3; i++) {
                if (changedType[i]) {
                    controller.savePresetToServer(i);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean anyEnabled(DownloadController controller) {
        return controller.mobilePreset != null && controller.mobilePreset.enabled
            || controller.wifiPreset != null && controller.wifiPreset.enabled
            || controller.roamingPreset != null && controller.roamingPreset.enabled;
    }

    /**
     * The mobile / Wi-Fi / roaming states before the switch, or null if there is none for this slot's user (one left
     * by another user of the slot is removed).
     */
    private static boolean[] readSnapshot(int account) {
        final boolean[] snapshot = peekSnapshot(account);
        if (snapshot == null && hasForeignSnapshot(account)) {
            clearSnapshot(account);
        }
        return snapshot;
    }

    /** {@link #readSnapshot} without writing anything (the settings row's getter). */
    private static boolean[] peekSnapshot(int account) {
        try {
            final SharedPreferences prefs = MessagesController.getMainSettings(account);
            final String stored = prefs.getString(KEY_SNAPSHOT, null);
            if (stored == null || prefs.getLong(KEY_OWNER, 0) != UserConfig.getInstance(account).getClientUserId()) {
                return null;
            }
            final String[] parts = stored.split(",");
            if (parts.length != 3) {
                return null;
            }
            return new boolean[] {"1".equals(parts[0]), "1".equals(parts[1]), "1".equals(parts[2])};
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** A snapshot that belongs to another user of this slot. */
    private static boolean hasForeignSnapshot(int account) {
        try {
            final SharedPreferences prefs = MessagesController.getMainSettings(account);
            return prefs.contains(KEY_SNAPSHOT) && prefs.getLong(KEY_OWNER, 0) != UserConfig.getInstance(account).getClientUserId();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void writeSnapshot(int account, DownloadController.Preset[] presets) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(presets[i] == null || presets[i].enabled ? '1' : '0');
        }
        MessagesController.getMainSettings(account).edit()
            .putString(KEY_SNAPSHOT, sb.toString())
            .putLong(KEY_OWNER, UserConfig.getInstance(account).getClientUserId())
            .commit();
    }

    private static void clearSnapshot(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        try {
            final SharedPreferences prefs = MessagesController.getMainSettings(account);
            if (prefs.contains(KEY_SNAPSHOT) || prefs.contains(KEY_OWNER)) {
                prefs.edit().remove(KEY_SNAPSHOT).remove(KEY_OWNER).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}

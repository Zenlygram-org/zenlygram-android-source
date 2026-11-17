/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.zenlygram.tasks.model.ZgTasksRouting;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;

import java.util.Set;

/**
 * Preferences of Tasks, in the private file {@code zg_tasks}.
 *
 * <ul>
 *   <li>{@code show_tab}: whether the Tasks main tab is shown (iOS CallListSettings.showTasksTab), default on.
 *       Hiding the tab never touches any task; Tasks stays reachable from Settings > Zenlygram > Tools > Tasks.</li>
 *   <li>{@code scope.<userId>} / {@code scope.v2.<userId>}: the board ("cloud" / "device") the user picked for a
 *       Telegram account, before and after its device tasks were copied to Cloud (iOS sgtasks.scope.&lt;account&gt; and
 *       sgtasks.scope.v2.&lt;account&gt;). Nothing is stored until the user picks a segment; an account's picks are
 *       forgotten when it logs out.</li>
 *   <li>{@code legacy_import_done}: the old device-wide board (zg_tasks.db) was imported into an account's device
 *       store (ZgLegacyTasksImport); set only after the import finished.</li>
 *   <li>{@code assign_explainer.<userId>}: the account has read "How assigning works" (iOS
 *       sgtasks.assignExplainer.&lt;account&gt;); it is shown once, before the first assignment is sent.</li>
 * </ul>
 */
public final class ZgTasksPrefs {

    /**
     * Posted on {@code NotificationCenter.getGlobalInstance()} (UI thread, no arguments) after the tab switch changed.
     * Zenlygram's own id block, so NotificationCenter.java needs no new event.
     */
    public static final int tabVisibilityChanged = 0x5A470201;

    private static final String PREFS = "zg_tasks";
    private static final String KEY_SHOW_TAB = "show_tab";
    private static final String KEY_SCOPE = "scope.";
    private static final String KEY_SCOPE_ARCHIVE = "scope.v2.";
    private static final String KEY_LEGACY_IMPORT_DONE = "legacy_import_done";
    private static final String KEY_ASSIGN_EXPLAINER = "assign_explainer.";

    private ZgTasksPrefs() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public static boolean isTabVisible() {
        try {
            return prefs().getBoolean(KEY_SHOW_TAB, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** Stores the switch and tells the main tabs (MainTabsActivity applies it live, keeping the selected tab). */
    public static void setTabVisible(boolean visible) {
        if (isTabVisible() == visible) {
            return;
        }
        prefs().edit().putBoolean(KEY_SHOW_TAB, visible).apply();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(tabVisibilityChanged));
    }

    // -----------------------------------------------------------------------------------------------------------
    // The Cloud / device pick, per Telegram account
    // -----------------------------------------------------------------------------------------------------------

    private static String pickKey(ZgTasksRouting.PickSlot slot, long userId) {
        return (slot == ZgTasksRouting.PickSlot.AFTER_CUT_OVER ? KEY_SCOPE_ARCHIVE : KEY_SCOPE) + userId;
    }

    /** The pick remembered in {@code slot} for the account, or null when the user never chose there. */
    public static ZgTasksScope getPick(ZgTasksRouting.PickSlot slot, long userId) {
        if (slot == ZgTasksRouting.PickSlot.NONE || userId <= 0) {
            return null;
        }
        try {
            return ZgTasksScope.fromWire(prefs().getString(pickKey(slot, userId), null));
        } catch (Exception e) {
            return null;
        }
    }

    /** The user's explicit pick. A scope that only follows the cut-over is never stored. */
    public static void setPick(ZgTasksRouting.PickSlot slot, long userId, ZgTasksScope scope) {
        if (slot == ZgTasksRouting.PickSlot.NONE || userId <= 0 || scope == null) {
            return;
        }
        prefs().edit().putString(pickKey(slot, userId), scope.wire).apply();
    }

    /**
     * Forgets the picks of every account that is not among {@code signedInUserIds} (an account logged out; iOS
     * TasksScopePreference.removeScope). A later login starts on the default board.
     */
    public static void forgetPicksExcept(Set<Long> signedInUserIds) {
        try {
            final SharedPreferences prefs = prefs();
            SharedPreferences.Editor editor = null;
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith(KEY_SCOPE)) {
                    continue;
                }
                final String id = key.substring(key.startsWith(KEY_SCOPE_ARCHIVE) ? KEY_SCOPE_ARCHIVE.length() : KEY_SCOPE.length());
                boolean keep = false;
                try {
                    keep = signedInUserIds.contains(Long.parseLong(id));
                } catch (NumberFormatException notOurs) {
                    // An unreadable key belongs to nobody who is signed in.
                }
                if (!keep) {
                    if (editor == null) {
                        editor = prefs.edit();
                    }
                    editor.remove(key);
                }
            }
            if (editor != null) {
                editor.apply();
            }
        } catch (Exception ignore) {
            // The picks are a convenience; never break the logout.
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // "How assigning works", once per Telegram account
    // -----------------------------------------------------------------------------------------------------------

    public static boolean isAssignExplained(long userId) {
        try {
            return userId <= 0 || prefs().getBoolean(KEY_ASSIGN_EXPLAINER + userId, false);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setAssignExplained(long userId) {
        if (userId > 0) {
            prefs().edit().putBoolean(KEY_ASSIGN_EXPLAINER + userId, true).apply();
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // The one-time import of the old device-wide board
    // -----------------------------------------------------------------------------------------------------------

    public static boolean isLegacyImportDone() {
        try {
            return prefs().getBoolean(KEY_LEGACY_IMPORT_DONE, false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Durable before it answers: the marker is the reason the old file is never imported twice. Off the main thread. */
    public static boolean setLegacyImportDone() {
        try {
            return prefs().edit().putBoolean(KEY_LEGACY_IMPORT_DONE, true).commit();
        } catch (Exception e) {
            return false;
        }
    }
}

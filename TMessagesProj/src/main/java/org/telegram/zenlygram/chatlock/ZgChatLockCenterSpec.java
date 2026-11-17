/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chat Lock Center: the pure decision layer (iOS SGChatLockFlowSpec, wf14-chatlock spec C2). Plain Java, no Android
 * type, so the JVM tests (ZgChatLockCenterSpecTest) cover what the Chat Lock screen and the per-chat screen DECIDE:
 * which of the five states the screen is in, which rows it shows, which locked chats this account may list (and in
 * which order), and which ids a removal takes with it. Ids are Telegram dialog ids.
 */
public final class ZgChatLockCenterSpec {

    private ZgChatLockCenterSpec() {
    }

    /** Presence of the master credential (iOS SGChatLockMasterState). */
    public enum MasterState {
        PRESENT,
        ABSENT,
        /** Cannot be answered right now; never read as absent (a set-up offered then would overwrite a real master). */
        UNREADABLE
    }

    /** The five states of the Chat Lock screen. Precedence: RECOVERY &gt; UNREADABLE &gt; LEGACY &gt; ON &gt; OFF. */
    public enum State {
        OFF,
        ON,
        /** Locked chats exist but no master. */
        LEGACY,
        /** The credentials can never be checked on this device again (Keystore key lost). */
        RECOVERY,
        UNREADABLE
    }

    /** Everything the Chat Lock screen is built from, read again on every refresh. */
    public static final class Input {
        public MasterState masterState = MasterState.ABSENT;
        public boolean masterBiometric;
        /** Device-wide locked ids (every account's locks). */
        public long[] lockedIds = new long[0];
        /** Ids hidden in the CURRENT account's Secret Vault (fail-closed). */
        public Set<Long> vaultHidden = Collections.emptySet();
        /** The account's own user id (Saved Messages is never listed). */
        public long accountPeerId;
        /**
         * id → migration target for the locked ids the CURRENT account knows. An absent key: a chat this account does
         * not know (typically another account's lock); a value of 0: no migration target; any other value: the
         * supergroup this legacy group was upgraded to.
         */
        public Map<Long, Long> resolved = Collections.emptyMap();
        public boolean needsRecovery;
        public int pendingCount;
        public boolean canAuthDeviceOwner;
        public boolean hasBiometrics;
        public int accountCount = 1;
    }

    public static State state(Input input) {
        if (input.needsRecovery) {
            return State.RECOVERY;
        }
        switch (input.masterState) {
            case UNREADABLE:
                return State.UNREADABLE;
            case PRESENT:
                return State.ON;
            case ABSENT:
            default:
                return input.lockedIds.length == 0 ? State.OFF : State.LEGACY;
        }
    }

    /**
     * The locked chats this account lists: drops the account peer, vault-hidden ids (their existence must not show
     * outside the vault), ids this account does not know (other accounts' locks) and legacy groups with a migration
     * target (their supergroup stands for both). Ascending, so the output is deterministic for one input.
     */
    public static long[] visibleIds(Input input) {
        final ArrayList<Long> visible = new ArrayList<>();
        for (long id : input.lockedIds) {
            if (id == input.accountPeerId || input.vaultHidden.contains(id)) {
                continue;
            }
            final Long target = input.resolved.get(id);
            if (target == null || target != 0L) {
                continue;
            }
            visible.add(id);
        }
        final long[] result = new long[visible.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = visible.get(i);
        }
        Arrays.sort(result);
        return result;
    }

    /** What "Add a Chat…" must not offer: every visible locked chat plus the account peer. */
    public static Set<Long> pickerExclusions(Input input) {
        final HashSet<Long> exclusions = new HashSet<>();
        for (long id : visibleIds(input)) {
            exclusions.add(id);
        }
        exclusions.add(input.accountPeerId);
        return exclusions;
    }

    /** {@code target} plus every LOCKED legacy group whose migration target is {@code target}. */
    public static Set<Long> removalSet(long target, long[] lockedIds, Map<Long, Long> migrationTargets) {
        final HashSet<Long> result = new HashSet<>();
        result.add(target);
        for (long id : lockedIds) {
            if (id == target) {
                continue;
            }
            final Long migrated = migrationTargets.get(id);
            if (migrated != null && migrated == target) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * Sorts locked chats by their display title with the locale's collation (the screen re-sorts the ascending ids
     * once it has the names); chats without a title keep id order after the named ones.
     */
    public static long[] sortByTitle(long[] ids, Map<Long, String> titles, Locale locale) {
        final Collator collator = Collator.getInstance(locale != null ? locale : Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
        final Long[] boxed = new Long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            boxed[i] = ids[i];
        }
        Arrays.sort(boxed, new Comparator<Long>() {
            @Override
            public int compare(Long a, Long b) {
                final String ta = titles.get(a);
                final String tb = titles.get(b);
                final boolean na = ta == null || ta.isEmpty();
                final boolean nb = tb == null || tb.isEmpty();
                if (na != nb) {
                    return na ? 1 : -1;
                }
                if (!na) {
                    final int c = collator.compare(ta, tb);
                    if (c != 0) {
                        return c;
                    }
                }
                return Long.compare(a, b);
            }
        });
        final long[] result = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            result[i] = boxed[i];
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------------------

    /** One row kind of the Chat Lock screen (iOS SGChatLockCenterRow, without the payloads that live in {@link Row}). */
    public enum RowType {
        TURN_ON, OFF_EXPLAINER,
        MASTER_HEADER, CHANGE_MASTER, MASTER_BIOMETRICS, MASTER_FOOTER,
        LEGACY_SET_MASTER, LEGACY_NOTICE,
        LOCKED_CHATS_HEADER, PEER, ADD_CHAT, LOCKED_CHATS_FOOTER,
        TURN_OFF, TURN_OFF_FOOTER,
        TURN_OFF_WITHOUT_MASTER, LEGACY_TURN_OFF_FOOTER,
        RECOVERY_NOTICE, RECOVERY_SET_NEW_PIN, RECOVERY_TURN_OFF, RECOVERY_HINT,
        UNREADABLE_NOTICE;

        public boolean isAction() {
            return this == TURN_ON || this == CHANGE_MASTER || this == LEGACY_SET_MASTER || this == TURN_OFF
                || this == TURN_OFF_WITHOUT_MASTER || this == RECOVERY_SET_NEW_PIN || this == RECOVERY_TURN_OFF;
        }

        public boolean isText() {
            return this == OFF_EXPLAINER || this == MASTER_FOOTER || this == LEGACY_NOTICE || this == LOCKED_CHATS_FOOTER
                || this == TURN_OFF_FOOTER || this == LEGACY_TURN_OFF_FOOTER || this == RECOVERY_NOTICE || this == RECOVERY_HINT
                || this == UNREADABLE_NOTICE;
        }
    }

    /** A row with its payload. */
    public static final class Row {
        public final RowType type;
        /** PEER: the dialog id. */
        public final long peerId;
        /** MASTER_BIOMETRICS: on; TURN_OFF_WITHOUT_MASTER, RECOVERY_*: enabled; MASTER_FOOTER: biometric sentence. */
        public final boolean flag;
        /** LOCKED_CHATS_FOOTER: the list is empty. LEGACY_TURN_OFF_FOOTER: the device-auth hint. */
        public final boolean flag2;
        /** LOCKED_CHATS_FOOTER: other accounts exist. RECOVERY_NOTICE: the pending count. */
        public final int number;

        Row(RowType type, long peerId, boolean flag, boolean flag2, int number) {
            this.type = type;
            this.peerId = peerId;
            this.flag = flag;
            this.flag2 = flag2;
            this.number = number;
        }

        static Row of(RowType type) {
            return new Row(type, 0, false, false, 0);
        }

        static Row of(RowType type, boolean flag) {
            return new Row(type, 0, flag, false, 0);
        }

        /** List id: fixed rows by type (never reused across states), peer rows by a hash of their id. */
        public int stableId() {
            if (type == RowType.PEER) {
                return 1000 + ((int) (peerId ^ (peerId >>> 32)) & 0x3FFFFFFF);
            }
            return type.ordinal() + 1;
        }

        @Override
        public String toString() {
            return type == RowType.PEER ? "PEER(" + peerId + ")" : type.name();
        }
    }

    /** The rows of the Chat Lock screen for {@code input}, in display order. Peers in {@code peerOrder}. */
    public static List<Row> rows(Input input, long[] peerOrder) {
        final ArrayList<Row> rows = new ArrayList<>();
        switch (state(input)) {
            case RECOVERY:
                rows.add(new Row(RowType.RECOVERY_NOTICE, 0, false, false, input.pendingCount));
                rows.add(Row.of(RowType.RECOVERY_SET_NEW_PIN, input.canAuthDeviceOwner));
                rows.add(Row.of(RowType.RECOVERY_TURN_OFF, input.canAuthDeviceOwner));
                if (!input.canAuthDeviceOwner) {
                    rows.add(Row.of(RowType.RECOVERY_HINT));
                }
                break;
            case UNREADABLE:
                rows.add(Row.of(RowType.UNREADABLE_NOTICE));
                break;
            case OFF:
                rows.add(Row.of(RowType.TURN_ON));
                rows.add(Row.of(RowType.OFF_EXPLAINER));
                break;
            case ON:
                rows.add(Row.of(RowType.MASTER_HEADER));
                rows.add(Row.of(RowType.CHANGE_MASTER));
                if (input.hasBiometrics) {
                    rows.add(Row.of(RowType.MASTER_BIOMETRICS, input.masterBiometric));
                }
                rows.add(Row.of(RowType.MASTER_FOOTER, input.hasBiometrics));
                addLockedChats(rows, input, peerOrder);
                rows.add(Row.of(RowType.TURN_OFF));
                rows.add(Row.of(RowType.TURN_OFF_FOOTER));
                break;
            case LEGACY:
                rows.add(Row.of(RowType.MASTER_HEADER));
                rows.add(Row.of(RowType.LEGACY_SET_MASTER));
                rows.add(Row.of(RowType.LEGACY_NOTICE));
                addLockedChats(rows, input, peerOrder);
                rows.add(Row.of(RowType.TURN_OFF_WITHOUT_MASTER, input.canAuthDeviceOwner));
                rows.add(new Row(RowType.LEGACY_TURN_OFF_FOOTER, 0, false, !input.canAuthDeviceOwner, 0));
                break;
        }
        return rows;
    }

    /** The rows with the visible peers in ascending id order. */
    public static List<Row> rows(Input input) {
        return rows(input, visibleIds(input));
    }

    private static void addLockedChats(ArrayList<Row> rows, Input input, long[] peerOrder) {
        final long[] visible = peerOrder != null ? peerOrder : visibleIds(input);
        rows.add(Row.of(RowType.LOCKED_CHATS_HEADER));
        for (long id : visible) {
            rows.add(new Row(RowType.PEER, id, false, false, 0));
        }
        rows.add(Row.of(RowType.ADD_CHAT));
        rows.add(new Row(RowType.LOCKED_CHATS_FOOTER, 0, false, visible.length == 0, input.accountCount > 1 ? 1 : 0));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Per-chat screen
    // ---------------------------------------------------------------------------------------------------------

    /** Rows of a locked chat's own screen (iOS §2.2). */
    public enum ChatRowType {
        CHANGE_PINCODE, CHANGE_PINCODE_FOOTER, BIOMETRICS, BIOMETRICS_FOOTER, REMOVE_LOCK, REMOVE_FOOTER
    }

    /** The per-chat rows: the biometric pair only on a device with strong biometrics. */
    public static List<ChatRowType> chatRows(boolean hasBiometrics) {
        final ArrayList<ChatRowType> rows = new ArrayList<>();
        rows.add(ChatRowType.CHANGE_PINCODE);
        rows.add(ChatRowType.CHANGE_PINCODE_FOOTER);
        if (hasBiometrics) {
            rows.add(ChatRowType.BIOMETRICS);
            rows.add(ChatRowType.BIOMETRICS_FOOTER);
        }
        rows.add(ChatRowType.REMOVE_LOCK);
        rows.add(ChatRowType.REMOVE_FOOTER);
        return rows;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Menus (iOS §2.5)
    // ---------------------------------------------------------------------------------------------------------

    /** What the chat list / profile menu offers for a chat. */
    public enum MenuItem {
        /** Not offered (Saved Messages, a vault-hidden chat, recovery pending). */
        NONE,
        /** "Lock Chat": the chat has no lock. */
        LOCK,
        /** "Remove Lock" (chat list) / "Chat Lock…" (profile): the chat is locked. */
        REMOVE,
        MANAGE
    }

    /**
     * The chat list offers Lock / Remove Lock, the profile Lock / Chat Lock… (the per-chat screen). Offered with or
     * without a master: locking a chat without one walks the master-first chain (explainer, master, then the chat).
     */
    public static MenuItem menuItem(boolean profileMenu, boolean lockable, boolean locked, boolean needsRecovery) {
        if (!lockable || needsRecovery) {
            return MenuItem.NONE;
        }
        if (!locked) {
            return MenuItem.LOCK;
        }
        return profileMenu ? MenuItem.MANAGE : MenuItem.REMOVE;
    }

    /** Which set-up explainer opens a master-first flow (iOS SGChatLockExplainerSpec). */
    public enum Explainer {
        /** OFF, "Turn On Chat Lock". */
        HUB,
        /** Locking a chat while no master exists. */
        CHAT,
        /** LEGACY "Set Master Pincode". */
        LEGACY
    }
}

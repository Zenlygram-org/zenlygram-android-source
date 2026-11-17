package org.telegram.zenlygram.chatlock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.zenlygram.chatlock.ZgChatLockCenterSpec.Input;
import org.telegram.zenlygram.chatlock.ZgChatLockCenterSpec.MasterState;
import org.telegram.zenlygram.chatlock.ZgChatLockCenterSpec.MenuItem;
import org.telegram.zenlygram.chatlock.ZgChatLockCenterSpec.Row;
import org.telegram.zenlygram.chatlock.ZgChatLockCenterSpec.RowType;
import org.telegram.zenlygram.chatlock.ZgChatLockCenterSpec.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** JVM tests of the Chat Lock Center decisions (iOS SGChatLockFlowSpecTests). */
public class ZgChatLockCenterSpecTest {

    private static Input input(MasterState master, long... locked) {
        final Input input = new Input();
        input.masterState = master;
        input.lockedIds = locked;
        input.accountPeerId = 7;
        final HashMap<Long, Long> resolved = new HashMap<>();
        for (long id : locked) {
            resolved.put(id, 0L);
        }
        input.resolved = resolved;
        input.canAuthDeviceOwner = true;
        return input;
    }

    private static List<RowType> types(List<Row> rows) {
        final ArrayList<RowType> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(row.type);
        }
        return result;
    }

    @Test
    public void statePrecedence() {
        assertEquals(State.OFF, ZgChatLockCenterSpec.state(input(MasterState.ABSENT)));
        assertEquals(State.ON, ZgChatLockCenterSpec.state(input(MasterState.PRESENT)));
        assertEquals(State.LEGACY, ZgChatLockCenterSpec.state(input(MasterState.ABSENT, 5)));
        assertEquals(State.UNREADABLE, ZgChatLockCenterSpec.state(input(MasterState.UNREADABLE, 5)));
        final Input recovery = input(MasterState.UNREADABLE, 5);
        recovery.needsRecovery = true;
        assertEquals(State.RECOVERY, ZgChatLockCenterSpec.state(recovery));
    }

    @Test
    public void visibleSetDropsSelfVaultUnknownAndMigrated() {
        final Input in = input(MasterState.PRESENT, 30, 7, 10, 20, -40, -50);
        in.vaultHidden = new HashSet<>(Collections.singletonList(20L));
        final HashMap<Long, Long> resolved = new HashMap<>(in.resolved);
        resolved.remove(30L); // another account's lock
        resolved.put(-40L, -50L); // legacy group upgraded to -50
        in.resolved = resolved;
        assertArrayEquals(new long[]{-50, 10}, ZgChatLockCenterSpec.visibleIds(in));
        final Set<Long> exclusions = ZgChatLockCenterSpec.pickerExclusions(in);
        assertTrue(exclusions.contains(7L) && exclusions.contains(10L) && exclusions.contains(-50L));
        assertEquals(3, exclusions.size());
    }

    @Test
    public void removalTakesTheUpgradedPair() {
        final Map<Long, Long> migration = new HashMap<>();
        migration.put(-40L, -50L);
        migration.put(-50L, 0L);
        migration.put(10L, 0L);
        assertEquals(new HashSet<>(Arrays.asList(-50L, -40L)), ZgChatLockCenterSpec.removalSet(-50, new long[]{-40, -50, 10}, migration));
        assertEquals(Collections.singleton(10L), ZgChatLockCenterSpec.removalSet(10, new long[]{-40, -50, 10}, migration));
    }

    @Test
    public void rowsPerState() {
        assertEquals(Arrays.asList(RowType.TURN_ON, RowType.OFF_EXPLAINER), types(ZgChatLockCenterSpec.rows(input(MasterState.ABSENT))));

        final Input on = input(MasterState.PRESENT, 10);
        on.hasBiometrics = true;
        on.masterBiometric = true;
        final List<Row> onRows = ZgChatLockCenterSpec.rows(on);
        assertEquals(Arrays.asList(RowType.MASTER_HEADER, RowType.CHANGE_MASTER, RowType.MASTER_BIOMETRICS, RowType.MASTER_FOOTER,
            RowType.LOCKED_CHATS_HEADER, RowType.PEER, RowType.ADD_CHAT, RowType.LOCKED_CHATS_FOOTER, RowType.TURN_OFF,
            RowType.TURN_OFF_FOOTER), types(onRows));
        assertTrue(onRows.get(2).flag);
        assertTrue(!onRows.get(7).flag2); // not empty

        final Input noBio = input(MasterState.PRESENT);
        final List<Row> noBioRows = ZgChatLockCenterSpec.rows(noBio);
        assertTrue(!types(noBioRows).contains(RowType.MASTER_BIOMETRICS));
        assertTrue(noBioRows.get(types(noBioRows).indexOf(RowType.LOCKED_CHATS_FOOTER)).flag2); // empty

        final Input legacy = input(MasterState.ABSENT, 10);
        legacy.canAuthDeviceOwner = false;
        legacy.accountCount = 2;
        final List<Row> legacyRows = ZgChatLockCenterSpec.rows(legacy);
        assertEquals(Arrays.asList(RowType.MASTER_HEADER, RowType.LEGACY_SET_MASTER, RowType.LEGACY_NOTICE,
            RowType.LOCKED_CHATS_HEADER, RowType.PEER, RowType.ADD_CHAT, RowType.LOCKED_CHATS_FOOTER,
            RowType.TURN_OFF_WITHOUT_MASTER, RowType.LEGACY_TURN_OFF_FOOTER), types(legacyRows));
        assertEquals(1, legacyRows.get(6).number); // other accounts
        assertTrue(!legacyRows.get(7).flag); // disabled without device-owner proof
        assertTrue(legacyRows.get(8).flag2); // hint

        final Input recovery = input(MasterState.PRESENT, 10);
        recovery.needsRecovery = true;
        recovery.pendingCount = 1;
        recovery.canAuthDeviceOwner = false;
        assertEquals(Arrays.asList(RowType.RECOVERY_NOTICE, RowType.RECOVERY_SET_NEW_PIN, RowType.RECOVERY_TURN_OFF, RowType.RECOVERY_HINT),
            types(ZgChatLockCenterSpec.rows(recovery)));

        assertEquals(Collections.singletonList(RowType.UNREADABLE_NOTICE), types(ZgChatLockCenterSpec.rows(input(MasterState.UNREADABLE))));
    }

    @Test
    public void stableIdsAreUniqueWithinAScreen() {
        final Input on = input(MasterState.PRESENT, 10, 11, 12, -100);
        on.hasBiometrics = true;
        final HashSet<Integer> ids = new HashSet<>();
        for (Row row : ZgChatLockCenterSpec.rows(on)) {
            assertTrue(row.toString(), ids.add(row.stableId()));
        }
    }

    @Test
    public void lockedChatsSortByLocaleTitle() {
        final Map<Long, String> titles = new HashMap<>();
        titles.put(1L, "Zafar");
        titles.put(2L, "anvar");
        titles.put(3L, "Ánna");
        titles.put(5L, "Борис");
        final long[] sorted = ZgChatLockCenterSpec.sortByTitle(new long[]{1, 2, 3, 4, 5}, titles, Locale.ENGLISH);
        assertArrayEquals(new long[]{3, 2, 1, 5, 4}, sorted);
    }

    @Test
    public void menus() {
        assertEquals(MenuItem.NONE, ZgChatLockCenterSpec.menuItem(false, false, false, false));
        assertEquals(MenuItem.NONE, ZgChatLockCenterSpec.menuItem(true, true, true, true));
        assertEquals(MenuItem.LOCK, ZgChatLockCenterSpec.menuItem(true, true, false, false));
        assertEquals(MenuItem.LOCK, ZgChatLockCenterSpec.menuItem(false, true, false, false));
        assertEquals(MenuItem.REMOVE, ZgChatLockCenterSpec.menuItem(false, true, true, false));
        assertEquals(MenuItem.MANAGE, ZgChatLockCenterSpec.menuItem(true, true, true, false));
    }

    @Test
    public void perChatRows() {
        assertEquals(6, ZgChatLockCenterSpec.chatRows(true).size());
        assertEquals(4, ZgChatLockCenterSpec.chatRows(false).size());
    }
}

/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate.Permission;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.zenlygram.tasks.model.ZgTasksRouting.PickSlot;
import org.telegram.zenlygram.tasks.model.ZgTasksRouting.Switch;

/**
 * Which board the Tasks screen shows and what its switch offers, for every consent gate and cut-over (iOS
 * TasksScopePreference.effectiveScope and TasksScopeSwitch).
 */
public class ZgTasksRoutingTest {

    private static final String SCOPE = "o-token/principal";
    /** The cut-overs the sync owner can announce, built by the engine's own table. */
    private static final TasksCutOver MIGRATING = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.ENQUEUED, SCOPE, SCOPE, 0);
    private static final TasksCutOver NEW_ROWS = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.COMPLETED, SCOPE, SCOPE, 3);
    private static final TasksCutOver RETIRED = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.COMPLETED, SCOPE, SCOPE, 0);
    private static final TasksCutOver[] CUT_OVERS = {null, TasksCutOver.NONE, MIGRATING, NEW_ROWS, RETIRED};
    private static final ZgTasksScope[] PICKS = {null, ZgTasksScope.CLOUD, ZgTasksScope.DEVICE};

    @Test
    public void theFixturesAreTheEnginesCutOvers() {
        assertEquals(TasksCutOver.Kind.ARCHIVE, MIGRATING.kind);
        assertEquals(TasksCutOver.Reason.MIGRATING, MIGRATING.reason);
        assertEquals(TasksCutOver.Kind.ARCHIVE, NEW_ROWS.kind);
        assertEquals(TasksCutOver.Reason.NEW_ROWS, NEW_ROWS.reason);
        assertEquals(3, NEW_ROWS.newRows);
        assertEquals(TasksCutOver.Kind.RETIRED, RETIRED.kind);
    }

    @Test
    public void withoutCloudInTheBuildThereIsOnlyTheDeviceBoard() {
        for (Permission permission : new Permission[]{null, Permission.ALLOWED, Permission.WAITING, Permission.FORBIDDEN}) {
            for (TasksCutOver cutOver : CUT_OVERS) {
                assertEquals(Switch.DEVICE_ONLY, ZgTasksRouting.scopeSwitch(false, permission, cutOver));
                for (ZgTasksScope pick : PICKS) {
                    for (ZgTasksScope archivePick : PICKS) {
                        assertEquals(ZgTasksScope.DEVICE, ZgTasksRouting.scope(false, permission, cutOver, pick, archivePick));
                    }
                }
            }
        }
        assertEquals(PickSlot.NONE, ZgTasksRouting.pickSlot(Switch.DEVICE_ONLY));
        assertFalse(ZgTasksRouting.deviceIsArchive(Switch.DEVICE_ONLY));
    }

    @Test
    public void beforeTheCutOverTheSwitchOffersBothAndTheDeviceBoardIsTheDefault() {
        for (Permission permission : new Permission[]{Permission.ALLOWED, Permission.WAITING}) {
            for (TasksCutOver cutOver : new TasksCutOver[]{null, TasksCutOver.NONE}) {
                assertEquals(Switch.CLOUD_AND_DEVICE, ZgTasksRouting.scopeSwitch(true, permission, cutOver));
                // Never chosen: the device board. The choice made after a cut-over is another one and does not count.
                assertEquals(ZgTasksScope.DEVICE, ZgTasksRouting.scope(true, permission, cutOver, null, ZgTasksScope.CLOUD));
                assertEquals(ZgTasksScope.CLOUD, ZgTasksRouting.scope(true, permission, cutOver, ZgTasksScope.CLOUD, ZgTasksScope.DEVICE));
                assertEquals(ZgTasksScope.DEVICE, ZgTasksRouting.scope(true, permission, cutOver, ZgTasksScope.DEVICE, ZgTasksScope.CLOUD));
            }
        }
        assertEquals(PickSlot.BEFORE_CUT_OVER, ZgTasksRouting.pickSlot(Switch.CLOUD_AND_DEVICE));
        assertFalse(ZgTasksRouting.deviceIsArchive(Switch.CLOUD_AND_DEVICE));
    }

    @Test
    public void afterTheCutOverTheDeviceBoardIsAnArchiveAndCloudIsTheDefault() {
        // WAITING keeps the cut-over: a session that drops after the copy started must not flap the board.
        for (Permission permission : new Permission[]{Permission.ALLOWED, Permission.WAITING}) {
            for (TasksCutOver cutOver : new TasksCutOver[]{MIGRATING, NEW_ROWS}) {
                assertEquals(Switch.CLOUD_AND_ARCHIVE, ZgTasksRouting.scopeSwitch(true, permission, cutOver));
                // The choice from before the cut-over (the device board) does not hold the user on the archive.
                assertEquals(ZgTasksScope.CLOUD, ZgTasksRouting.scope(true, permission, cutOver, ZgTasksScope.DEVICE, null));
                assertEquals(ZgTasksScope.DEVICE, ZgTasksRouting.scope(true, permission, cutOver, ZgTasksScope.CLOUD, ZgTasksScope.DEVICE));
                assertEquals(ZgTasksScope.CLOUD, ZgTasksRouting.scope(true, permission, cutOver, ZgTasksScope.DEVICE, ZgTasksScope.CLOUD));
            }
        }
        assertEquals(PickSlot.AFTER_CUT_OVER, ZgTasksRouting.pickSlot(Switch.CLOUD_AND_ARCHIVE));
        assertTrue(ZgTasksRouting.deviceIsArchive(Switch.CLOUD_AND_ARCHIVE));
    }

    @Test
    public void aRetiredDeviceBoardLeavesOnlyCloudWhateverWasPicked() {
        for (Permission permission : new Permission[]{Permission.ALLOWED, Permission.WAITING}) {
            assertEquals(Switch.CLOUD_ONLY, ZgTasksRouting.scopeSwitch(true, permission, RETIRED));
            for (ZgTasksScope pick : PICKS) {
                for (ZgTasksScope archivePick : PICKS) {
                    assertEquals(ZgTasksScope.CLOUD, ZgTasksRouting.scope(true, permission, RETIRED, pick, archivePick));
                }
            }
        }
        assertEquals(PickSlot.NONE, ZgTasksRouting.pickSlot(Switch.CLOUD_ONLY));
        assertTrue(ZgTasksRouting.deviceIsArchive(Switch.CLOUD_ONLY));
    }

    @Test
    public void withoutConsentEveryCutOverReadsAsNone() {
        for (Permission permission : new Permission[]{null, Permission.FORBIDDEN}) {
            for (TasksCutOver cutOver : CUT_OVERS) {
                assertSame(TasksCutOver.NONE, ZgTasksRouting.effectiveCutOver(permission, cutOver));
                assertEquals(Switch.CLOUD_AND_DEVICE, ZgTasksRouting.scopeSwitch(true, permission, cutOver));
                // The device board is writable again, and the choice made before the cut-over is the one that counts.
                assertEquals(ZgTasksScope.DEVICE, ZgTasksRouting.scope(true, permission, cutOver, null, ZgTasksScope.CLOUD));
                assertEquals(ZgTasksScope.CLOUD, ZgTasksRouting.scope(true, permission, cutOver, ZgTasksScope.CLOUD, ZgTasksScope.DEVICE));
            }
        }
    }

    @Test
    public void withConsentTheCutOverIsReadAsItIs() {
        for (Permission permission : new Permission[]{Permission.ALLOWED, Permission.WAITING}) {
            assertSame(TasksCutOver.NONE, ZgTasksRouting.effectiveCutOver(permission, null));
            for (TasksCutOver cutOver : new TasksCutOver[]{TasksCutOver.NONE, MIGRATING, NEW_ROWS, RETIRED}) {
                assertSame(cutOver, ZgTasksRouting.effectiveCutOver(permission, cutOver));
            }
        }
    }

    @Test
    public void theRememberedScopeIsStoredUnderItsWireName() {
        assertEquals("cloud", ZgTasksScope.CLOUD.wire);
        assertEquals("device", ZgTasksScope.DEVICE.wire);
        assertSame(ZgTasksScope.CLOUD, ZgTasksScope.fromWire("cloud"));
        assertSame(ZgTasksScope.DEVICE, ZgTasksScope.fromWire("device"));
        assertEquals(null, ZgTasksScope.fromWire(null));
        assertEquals(null, ZgTasksScope.fromWire("local"));
    }
}

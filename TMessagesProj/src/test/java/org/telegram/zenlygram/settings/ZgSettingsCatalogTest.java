package org.telegram.zenlygram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.zenlygram.settings.ZgSettingsCatalog.Page;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JVM tests of the hub inventory (iOS SGSettingsCatalogTests) and its Android placement. */
public class ZgSettingsCatalogTest {

    /** iOS keys that Android deliberately puts on the hub (the Contacts & Calls switch, iOS lenta-v6). */
    private static final Set<String> MOVED_TO_HUB = new HashSet<>(Arrays.asList("showContactsTab", "showCallsTab"));

    @Test
    public void twelvePagesInHubOrder() {
        assertEquals(12, Page.values().length);
        assertEquals("pageSecretVault", Page.SECRET_VAULT.hubRowKey());
        assertEquals("pageCallsAdvanced", Page.CALLS_ADVANCED.hubRowKey());
        assertEquals(9, ZgSettingsCatalog.groupPages().size());
        assertFalse(Page.HISTORY.isGroup);
        assertTrue(Page.TAB_BAR.isGroup);
        for (Page page : Page.values()) {
            assertTrue(page.hubRowKey(), ZgSettingsCatalog.HUB_ROW_KEYS.contains(page.hubRowKey()));
            assertEquals(page, Page.fromKey(page.key));
        }
    }

    @Test
    public void everyRowIsPlacedOnce() {
        final Set<String> seen = new HashSet<>(ZgSettingsCatalog.HUB_ROW_KEYS);
        for (Page page : Page.values()) {
            for (String key : ZgSettingsCatalog.rowKeys(page)) {
                if (ZgSettingsCatalog.SHARED_ROW_KEYS.contains(key)) {
                    continue;
                }
                assertTrue("placed twice: " + key, seen.add(key));
            }
        }
        assertEquals(seen.size(), ZgSettingsCatalog.allRowKeys().size());
    }

    @Test
    public void pageContaining() {
        assertNull(ZgSettingsCatalog.pageContaining("chatLock"));
        assertNull(ZgSettingsCatalog.pageContaining("protectionRecovery"));
        assertNull(ZgSettingsCatalog.pageContaining("noSuchKey"));
        assertEquals(Page.HISTORY, ZgSettingsCatalog.pageContaining("showDeletedMessages"));
        assertEquals(Page.TAB_BAR, ZgSettingsCatalog.pageContaining("wideTabBar"));
        assertEquals(Page.CHATS, ZgSettingsCatalog.pageContaining("unreadReminderEnabled"));
    }

    @Test
    public void newBadgeOracleIsOnItsPage() {
        for (Page page : Page.values()) {
            for (String key : ZgSettingsCatalog.newBadgeKeys(page)) {
                assertTrue(key, ZgSettingsCatalog.rowKeys(page).contains(key));
            }
        }
    }

    @Test
    public void everyIosKeyHasAnAndroidRowOrIsKnownMissing() {
        for (String key : ZgSettingsCatalog.allRowKeys()) {
            final boolean mapped = ZgSettingsCatalog.rowIdForKey(key) != 0;
            assertTrue("unmapped " + key, mapped != ZgSettingsCatalog.ANDROID_MISSING_KEYS.contains(key));
        }
    }

    @Test
    public void androidPlacementMatchesIos() {
        for (String key : ZgSettingsCatalog.allRowKeys()) {
            final int id = ZgSettingsCatalog.rowIdForKey(key);
            if (id == 0) {
                continue;
            }
            final Page ios = ZgSettingsCatalog.pageContaining(key);
            final Page android = ZgSettingsCatalog.pageOfRow(id);
            if (MOVED_TO_HUB.contains(key)) {
                assertNull(key, android);
            } else {
                assertEquals(key, ios, android);
            }
            assertEquals(key, android, ZgSettingsCatalog.focusPage(key));
        }
    }

    @Test
    public void hubRowsRoundTrip() {
        final Set<Integer> ids = new HashSet<>();
        for (Page page : Page.values()) {
            final int id = ZgSettingsCatalog.hubRowId(page);
            assertTrue(ids.add(id));
            assertEquals(page, ZgSettingsCatalog.pageOfHubRow(id));
            assertNull("hub rows live on the hub", ZgSettingsCatalog.pageOfRow(id));
            assertEquals(id, ZgSettingsCatalog.rowIdForKey(page.hubRowKey()));
        }
        assertNull(ZgSettingsCatalog.pageOfHubRow(ZgSettingsCatalog.ROW_CHAT_LOCK));
    }

    @Test
    public void everySpecSectionHasAPlace() throws Exception {
        final Set<Integer> hubSections = new HashSet<>(Arrays.asList(
            ZgSettingsSpec.SECTION_PRO, ZgSettingsSpec.SECTION_ABOUT, ZgSettingsSpec.SECTION_DEVELOPER));
        int count = 0;
        for (Field field : ZgSettingsSpec.class.getDeclaredFields()) {
            if (!field.getName().startsWith("SECTION_") || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            final int id = field.getInt(null);
            count++;
            if (hubSections.contains(id) || id > ZgSettingsSpec.SECTION_DELETED_MESSAGES) {
                // Pro, About, Developer, and sections added later by other work packages (e.g. WP-C's Data & privacy):
                // without a placement they land on the hub, so nothing becomes unreachable.
                assertNull(field.getName(), ZgSettingsCatalog.pageOfSection(id));
            } else {
                assertNotNull(field.getName() + " has no page", ZgSettingsCatalog.pageOfSection(id));
            }
            assertTrue(field.getName() + " collides with a hub block", id < ZgSettingsCatalog.SECTION_HUB_QUICK || id > ZgSettingsCatalog.SECTION_HUB_GROUPS);
        }
        assertTrue(count >= 30);
    }

    @Test
    public void telegramAccountRowIsOnTheHub() {
        final int id = ZgSettingsCatalog.rowIdForKey("telegramAccount");
        assertEquals(ZgSettingsSpec.ROW_DATA_TELEGRAM_ACCOUNT, id);
        // The iOS key of the same row (hub block ACCOUNT & DATA) lands on it too.
        assertTrue(ZgSettingsCatalog.HUB_ROW_KEYS.contains("proLinkAccount"));
        assertEquals(id, ZgSettingsCatalog.rowIdForKey("proLinkAccount"));
        assertEquals(ZgSettingsSpec.SECTION_DATA, ZgSettingsCatalog.sectionOfRow(id));
        assertNull("the block is on the hub", ZgSettingsCatalog.pageOfRow(id));
        assertNull(ZgSettingsCatalog.focusPage("telegramAccount"));
        assertNull(ZgSettingsCatalog.focusPage("proLinkAccount"));
        // The copy among the Pro rows is never shown: no key leads to it.
        for (String key : ZgSettingsCatalog.mappedKeys()) {
            assertFalse(key, ZgSettingsCatalog.rowIdForKey(key) == ZgSettingsSpec.ROW_PRO_LINK_ACCOUNT);
        }
        // The block keeps its rows and their ids.
        assertEquals(4001, ZgSettingsCatalog.rowIdForKey("dataConsent"));
        assertEquals(4003, ZgSettingsCatalog.rowIdForKey("privacyPolicy"));
    }

    @Test
    public void specRowIdsAreUniqueAndNeverASectionHeader() throws Exception {
        final Set<Integer> ids = new HashSet<>();
        int count = 0;
        for (Field field : ZgSettingsSpec.class.getDeclaredFields()) {
            if (!field.getName().startsWith("ROW_") || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            final int id = field.getInt(null);
            count++;
            assertTrue(field.getName() + " repeats an id", ids.add(id));
            // section * 100 is the id of the section's header (ZgSection): a row with it would replace the header.
            assertTrue(field.getName() + " has a header id", id % 100 != 0);
            assertTrue(field.getName() + " is past the section's rows", id % 100 <= 98);
        }
        assertTrue("the spec's row constants were found", count >= 5);
    }

    @Test
    public void everyPageHasRows() {
        for (Page page : Page.values()) {
            final List<String> keys = ZgSettingsCatalog.rowKeys(page);
            assertFalse(page.key, keys.isEmpty());
        }
    }

    @Test
    public void deepLinks() {
        assertEquals(1, ZgSettingsDeepLink.kind("tg://zg/settings"));
        assertEquals(1, ZgSettingsDeepLink.kind("tg://sg/settings?row=chatLock"));
        assertEquals(1, ZgSettingsDeepLink.kind("tg:zg/settings?row=x"));
        assertEquals(2, ZgSettingsDeepLink.kind("tg://pro"));
        assertEquals(0, ZgSettingsDeepLink.kind("tg://proxy?server=a"));
        assertEquals(0, ZgSettingsDeepLink.kind("tg://settings"));
        assertEquals(0, ZgSettingsDeepLink.kind("https://zg/settings"));
        assertEquals("chatLock", ZgSettingsDeepLink.rowKey("tg://zg/settings?row=chatLock"));
        assertEquals("pageTabBar", ZgSettingsDeepLink.rowKey("tg://zg/settings?a=1&row=pageTabBar"));
        assertNull(ZgSettingsDeepLink.rowKey("tg://zg/settings"));
        assertNull(ZgSettingsDeepLink.rowKey("tg://zg/settings?row=a%27b"));
        assertEquals(1, ZgSettingsDeepLink.kind("tg://zg/settings?row=telegramAccount"));
        assertEquals("telegramAccount", ZgSettingsDeepLink.rowKey("tg://zg/settings?row=telegramAccount"));
        assertEquals("proLinkAccount", ZgSettingsDeepLink.rowKey("tg://sg/settings?row=proLinkAccount"));
    }
}

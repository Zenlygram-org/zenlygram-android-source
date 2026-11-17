package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Private cloud database of one raw Telegram user under one authenticated origin
 * (zenly_tasks_cloud.o-&lt;storageToken&gt;.&lt;rawTG&gt;.db, contract §6.10), separate from the local-only
 * zenly_tasks_&lt;rawTG&gt;.db. The token is the origin's storage token (ZenlyPro.storageToken(), 64 lowercase hex), so two
 * origins never share a database. Pre-origin ("legacy") files zenly_tasks_cloud_&lt;rawTG&gt;.db are never named, opened,
 * discovered or deleted. Pure Java so host tests execute exactly these statements. Restricted to SQLite 3.8
 * (Android minSdk 23): no UPSERT, RETURNING, window functions or generated columns.
 * <p>
 * Version 2 (Tasks R4, design 30 §6.2): outbox origin / automatic-resolution / members-wait columns, the
 * import_ack_pending queue and two R4 notice kinds. A version-1 file is upgraded in place by {@link #UPGRADE_1_TO_2},
 * which also forces a resnapshot (R3-era shadows carry no members_revision).
 */
public final class TasksCloudSchema {
    public static final int VERSION = 2;
    public static final String FILE_PREFIX = "zenly_tasks_cloud.o-";
    public static final String FILE_SUFFIX = ".db";
    /** Prefix of the pre-origin per-rawTG file. Kept only for tests asserting that production never names it. */
    public static final String LEGACY_PREFIX = "zenly_tasks_cloud_";
    private static final int TOKEN_LENGTH = 64;

    /** Owner of a cloud database file: its origin's storage token and its raw Telegram user id. */
    public static final class Owner {
        public final String token;
        public final long rawTelegramId;

        Owner(String token, long rawTelegramId) {
            this.token = token;
            this.rawTelegramId = rawTelegramId;
        }
    }

    /** IllegalArgumentException unless the token is exactly 64 lowercase hex characters and the raw id is positive. */
    public static String fileName(String storageToken, long rawTelegramId) {
        if (!isToken(storageToken)) throw new IllegalArgumentException("storageToken");
        if (rawTelegramId <= 0) throw new IllegalArgumentException("rawTelegramId");
        return FILE_PREFIX + storageToken + "." + rawTelegramId + FILE_SUFFIX;
    }

    /** Owner of a database file name in exactly the grammar of {@link #fileName}, else null (a legacy name included). */
    public static Owner ownerOf(String fileName) {
        if (fileName == null || !fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null;
        String rest = fileName.substring(FILE_PREFIX.length(), fileName.length() - FILE_SUFFIX.length());
        if (rest.length() <= TOKEN_LENGTH + 1 || rest.charAt(TOKEN_LENGTH) != '.') return null;
        String token = rest.substring(0, TOKEN_LENGTH), digits = rest.substring(TOKEN_LENGTH + 1);
        if (!isToken(token) || digits.length() > 19 || digits.charAt(0) == '0') return null;
        for (int i = 0; i < digits.length(); i++) if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return null;
        try {
            return new Owner(token, Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The form ProOrigin.storageToken returns. */
    private static boolean isToken(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) return false;
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            char c = token.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    // Outbox states. Derived-only UI states (WAITING, WRONG_PRINCIPAL) are never stored.
    public static final String HELD = "held", QUEUED = "queued", SENDING = "sending", BACKOFF = "backoff",
            STALLED = "stalled", BLOCKED_CREATION = "blocked_creation", INVALID = "invalid", CONFLICT = "conflict",
            QUARANTINED = "quarantined", NOT_FOUND = "not_found", ACKED = "acked";

    public static final String NOTICE_ACCESS_LOST = "access_lost", NOTICE_NOT_FOUND = "not_found",
            NOTICE_DEPENDENCY_DROPPED = "dependency_dropped";
    /** R4: an acknowledged create the cloud no longer shows (phantom settle); a delete dropped because the task is kept. */
    public static final String NOTICE_REMOVED_IN_CLOUD = "removed_in_cloud", NOTICE_DELETE_KEPT = "delete_kept";

    /** Outbox origins ($defs.BatchItem.origin): an own edit, or a create of pre-existing local data (batch route only). */
    public static final String ORIGIN_USER = "user", ORIGIN_MIGRATION = "migration";

    /** Fresh files (version 2). The R4 outbox columns follow the version-1 ones, as UPGRADE_1_TO_2 appends them. */
    public static final List<String> DDL = Collections.unmodifiableList(Arrays.asList(
            "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)",
            "CREATE TABLE shadow (kind TEXT NOT NULL CHECK(kind IN ('list','task','invitation','block')),id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))",
            "CREATE TABLE staged (kind TEXT NOT NULL CHECK(kind IN ('list','task','invitation','block')),id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))",
            "CREATE TABLE staging (singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1),phase TEXT NOT NULL CHECK(phase IN ('pages','catchup')),cursor TEXT,changes_cursor TEXT,page_limit INTEGER NOT NULL CHECK(page_limit BETWEEN 1 AND 100),CHECK(phase='pages' OR (cursor IS NOT NULL AND changes_cursor IS NOT NULL)),CHECK(cursor IS NOT NULL OR changes_cursor IS NULL))",
            "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT,mutation_id TEXT NOT NULL UNIQUE,principal TEXT NOT NULL,kind TEXT NOT NULL,resource_id TEXT NOT NULL,chain TEXT NOT NULL,list_key TEXT,source_list_key TEXT,body BLOB NOT NULL,sealed INTEGER NOT NULL CHECK(sealed IN (0,1)),resolved_base TEXT,state TEXT NOT NULL CHECK(state IN ('held','queued','sending','backoff','stalled','blocked_creation','invalid','conflict','quarantined','not_found','acked')),sent INTEGER NOT NULL DEFAULT 0 CHECK(sent IN (0,1)),error TEXT,reason TEXT,attempts INTEGER NOT NULL DEFAULT 0,not_before INTEGER NOT NULL DEFAULT 0,acked_revision TEXT,acked_resource TEXT,"
                    + "origin TEXT NOT NULL DEFAULT 'user' CHECK(origin IN ('user','migration')),auto_requeues INTEGER NOT NULL DEFAULT 0,auto_resolutions INTEGER NOT NULL DEFAULT 0,auto_resolve TEXT,conflict_fields TEXT,base_snapshot TEXT,waits_members INTEGER NOT NULL DEFAULT 0 CHECK(waits_members IN (0,1)),acked_cycle INTEGER,"
                    + "CHECK(sent=0 OR sealed=1),CHECK(state<>'held' OR (sent=0 AND kind='task.delete')),CHECK(state<>'acked' OR acked_resource IS NOT NULL))",
            "CREATE INDEX outbox_chain ON outbox(chain,seq)",
            "CREATE TABLE notices (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL CHECK(kind IN ('access_lost','not_found','dependency_dropped','removed_in_cloud','delete_kept')),resource_id TEXT NOT NULL)",
            "CREATE TABLE import_ack_pending (mutation_id TEXT PRIMARY KEY NOT NULL)"));

    /**
     * Version 1 → 2 in the helper's own transaction: the R4 outbox columns (their defaults satisfy their CHECKs, so the
     * existing rows stay valid), import_ack_pending, the notices table rebuilt for the R4 kinds (ids kept), and the
     * forced resnapshot of TasksCloudLedger.requireResnapshot (cursor, staged pages and staging gone; shadow, outbox and
     * notices stay until the new snapshot reconciles).
     */
    public static final List<String> UPGRADE_1_TO_2 = Collections.unmodifiableList(Arrays.asList(
            "ALTER TABLE outbox ADD COLUMN origin TEXT NOT NULL DEFAULT 'user' CHECK(origin IN ('user','migration'))",
            "ALTER TABLE outbox ADD COLUMN auto_requeues INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE outbox ADD COLUMN auto_resolutions INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE outbox ADD COLUMN auto_resolve TEXT",
            "ALTER TABLE outbox ADD COLUMN conflict_fields TEXT",
            "ALTER TABLE outbox ADD COLUMN base_snapshot TEXT",
            "ALTER TABLE outbox ADD COLUMN waits_members INTEGER NOT NULL DEFAULT 0 CHECK(waits_members IN (0,1))",
            "ALTER TABLE outbox ADD COLUMN acked_cycle INTEGER",
            "CREATE TABLE import_ack_pending (mutation_id TEXT PRIMARY KEY NOT NULL)",
            "CREATE TABLE notices_v2 (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL CHECK(kind IN ('access_lost','not_found','dependency_dropped','removed_in_cloud','delete_kept')),resource_id TEXT NOT NULL)",
            "INSERT INTO notices_v2(id,kind,resource_id) SELECT id,kind,resource_id FROM notices",
            "DROP TABLE notices",
            "ALTER TABLE notices_v2 RENAME TO notices",
            "DELETE FROM meta WHERE key='changes_cursor'",
            "DELETE FROM staged",
            "DELETE FROM staging"));

    private TasksCloudSchema() { }
}

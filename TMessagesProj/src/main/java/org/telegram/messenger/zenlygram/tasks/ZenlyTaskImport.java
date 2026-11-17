package org.telegram.messenger.zenlygram.tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform-independent values for explicit local-to-cloud import (read by ZenlyTasksStore, consumed by tasks.cloud). */
public final class ZenlyTaskImport {
    private ZenlyTaskImport() { }

    /** Content-free cloud-import identity of one local entity under one cloud principal; survives cloud logout. */
    public static final class Mapping {
        public static final String FOLDER = "folder", TASK = "task";
        public final String entity;
        /** Folder incarnation for folders, local task id for tasks. */
        public final String localKey;
        public final String cloudId, mutationId;
        public final int sortOrder;
        /** Tasks only: cloud list id fixed at consent; null = Cloud Inbox by explicit user choice. */
        public final String parentCloudId;
        /**
         * Local import provenance (store v3, cloud_import_attempt): the create was handed to the network ({@code
         * attempted}), its receipt recorded ({@code acknowledged}), and the cloud resync generation it was last
         * re-verified at (Tasks R4 resend-once). Not part of the mapping's identity ({@link #sameAs}).
         */
        public final boolean attempted, acknowledged;
        public final long verifiedGeneration;

        public Mapping(String entity, String localKey, String cloudId, String mutationId, int sortOrder, String parentCloudId) {
            this(entity, localKey, cloudId, mutationId, sortOrder, parentCloudId, false, false, 0);
        }

        public Mapping(String entity, String localKey, String cloudId, String mutationId, int sortOrder, String parentCloudId,
                       boolean attempted, boolean acknowledged, long verifiedGeneration) {
            if (!FOLDER.equals(entity) && !TASK.equals(entity) || localKey == null || cloudId == null || mutationId == null
                    || FOLDER.equals(entity) && parentCloudId != null) {
                throw new IllegalArgumentException("invalid_mapping");
            }
            this.entity = entity;
            this.localKey = localKey;
            this.cloudId = cloudId;
            this.mutationId = mutationId;
            this.sortOrder = sortOrder;
            this.parentCloudId = parentCloudId;
            this.attempted = attempted || acknowledged;
            this.acknowledged = acknowledged;
            this.verifiedGeneration = verifiedGeneration;
        }

        public boolean sameAs(Mapping other) {
            return entity.equals(other.entity) && localKey.equals(other.localKey) && cloudId.equals(other.cloudId)
                    && mutationId.equals(other.mutationId) && sortOrder == other.sortOrder
                    && (parentCloudId == null ? other.parentCloudId == null : parentCloudId.equals(other.parentCloudId));
        }
    }

    /**
     * The account's automatic-migration record (store v3, cloud_migration; design 30 §6.5). Times are Unix seconds.
     * Immutable; the {@code with…} copies feed the store's compare-and-set {@code setMigrationRecord}.
     */
    public static final class MigrationRecord {
        public static final String CLAIMED = "claimed", ENQUEUED = "enqueued", COMPLETED = "completed", RELEASED = "released",
                SKIPPED = "skipped";
        public static final String REASON_EXISTING_MAPPINGS = "existing_mappings", REASON_SCOPE_CHANGED = "scope_changed",
                REASON_WITHDRAWN = "withdrawn", REASON_ERASED = "erased";
        public static final String LOCALE_UZ = "uz", LOCALE_RU = "ru", LOCALE_EN = "en";

        public final String state, reason;
        /** ProOrigin.importScope of the binding it was claimed under; null until the step knows it. */
        public final String scope;
        /** Placeholder language of the copy, fixed at the claim (a resend rebuilds identical bytes). */
        public final String locale;
        public final long claimedAt;
        public final Long enqueuedAt, completedAt;
        public final long verifiedGeneration;
        public final int lists, tasks, keptLocal;

        public MigrationRecord(String state, String reason, String scope, String locale, long claimedAt, Long enqueuedAt,
                               Long completedAt, long verifiedGeneration, int lists, int tasks, int keptLocal) {
            if (!CLAIMED.equals(state) && !ENQUEUED.equals(state) && !COMPLETED.equals(state) && !RELEASED.equals(state)
                    && !SKIPPED.equals(state) || !LOCALE_UZ.equals(locale) && !LOCALE_RU.equals(locale) && !LOCALE_EN.equals(locale)) {
                throw new IllegalArgumentException("invalid_migration_record");
            }
            this.state = state;
            this.reason = reason;
            this.scope = scope;
            this.locale = locale;
            this.claimedAt = claimedAt;
            this.enqueuedAt = enqueuedAt;
            this.completedAt = completedAt;
            this.verifiedGeneration = verifiedGeneration;
            this.lists = lists;
            this.tasks = tasks;
            this.keptLocal = keptLocal;
        }

        public static MigrationRecord claimed(String scope, String locale, long now) {
            return new MigrationRecord(CLAIMED, null, scope, locale, now, null, null, 0, 0, 0, 0);
        }

        public MigrationRecord withState(String nextState, String nextReason) {
            return new MigrationRecord(nextState, nextReason, scope, locale, claimedAt, enqueuedAt, completedAt, verifiedGeneration, lists, tasks, keptLocal);
        }

        public MigrationRecord withScope(String nextScope) {
            return new MigrationRecord(state, reason, nextScope, locale, claimedAt, enqueuedAt, completedAt, verifiedGeneration, lists, tasks, keptLocal);
        }

        public MigrationRecord enqueued(Long at, int nextLists, int nextTasks, int nextKeptLocal) {
            return new MigrationRecord(ENQUEUED, null, scope, locale, claimedAt, at, completedAt, verifiedGeneration, nextLists, nextTasks, nextKeptLocal);
        }

        public MigrationRecord completed(Long at, long generation) {
            return new MigrationRecord(COMPLETED, null, scope, locale, claimedAt, enqueuedAt, at, generation, lists, tasks, keptLocal);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof MigrationRecord)) return false;
            MigrationRecord that = (MigrationRecord) other;
            return state.equals(that.state) && same(reason, that.reason) && same(scope, that.scope) && locale.equals(that.locale)
                    && claimedAt == that.claimedAt && same(enqueuedAt, that.enqueuedAt) && same(completedAt, that.completedAt)
                    && verifiedGeneration == that.verifiedGeneration && lists == that.lists && tasks == that.tasks
                    && keptLocal == that.keptLocal;
        }

        @Override public int hashCode() {
            return (state.hashCode() * 31 + (scope == null ? 0 : scope.hashCode())) * 31 + (int) claimedAt;
        }

        private static boolean same(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /** One read transaction: folders (load order), folder id -> incarnation, tasks (folder, created, id), mappings. */
    public static final class Snapshot {
        public final List<ZenlyTaskModel.Folder> folders;
        public final Map<String, String> incarnations;
        public final List<ZenlyTaskModel.Task> tasks;
        public final List<Mapping> mappings;

        public Snapshot(List<ZenlyTaskModel.Folder> folders, Map<String, String> incarnations, List<ZenlyTaskModel.Task> tasks,
                        List<Mapping> mappings) {
            this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
            this.incarnations = Collections.unmodifiableMap(new LinkedHashMap<>(incarnations));
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
            this.mappings = Collections.unmodifiableList(new ArrayList<>(mappings));
            for (ZenlyTaskModel.Folder folder : this.folders) {
                if (this.incarnations.get(folder.id) == null) throw new IllegalArgumentException("missing_incarnation");
            }
        }
    }
}

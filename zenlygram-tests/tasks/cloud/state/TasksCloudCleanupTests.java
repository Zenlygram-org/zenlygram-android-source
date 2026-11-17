package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host tests of the real TasksCloudCleanup obligation logic that TasksCloudRepository delegates logout/launch/open to.
 * Markers model Android SharedPreferences semantics (commit() may return false AFTER changing the in-memory value; a
 * restart reloads only what reached disk). Files are real files in a temp directory, named by the real
 * TasksCloudSchema.fileName/ownerOf; {@link Dir} mirrors the repository's AndroidFiles and launch discovery (every
 * origin-scoped file of the user, sidecars included; a legacy name is never matched). The Android glue itself
 * (SharedPreferences, Context.deleteDatabase, SQLiteOpenHelper) is NOT executed here.
 */
public final class TasksCloudCleanupTests {
    /** ProOrigin.storageToken of zenlygram.org and of 10.0.2.2 (the local stack), as in design 50 §3.1. */
    static final String ORIGIN_A = "6e0e6d39ba09d62eba37c6d82bbb78cd254b4ffc237e268c4124acea5dd835a4",
            ORIGIN_B = "f9292b96ff7d66abe0820bbe9bd022cb0abd37bd673d7b462e1bba84245a9f66";
    static final List<String> SIDECARS = Arrays.asList("-journal", "-wal", "-shm");
    static int checks;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    static final class Prefs implements TasksCloudCleanup.Markers {
        final Map<Long, Boolean> disk;
        final Map<Long, Boolean> memory;
        boolean failCommits;

        Prefs(Map<Long, Boolean> disk) {
            this.disk = disk;
            this.memory = new HashMap<>(disk); // process start: memory = what reached disk
        }

        @Override public boolean commit(long id, boolean present) {
            if (present) memory.put(id, true); else memory.remove(id); // Android mutates memory first
            if (failCommits) return false;
            if (present) disk.put(id, true); else disk.remove(id);
            return true;
        }

        @Override public boolean present(long id) { return memory.containsKey(id); }

        @Override public Set<Long> all() { return new HashSet<>(memory.keySet()); }
    }

    static final class Dir implements TasksCloudCleanup.Files {
        final File root;
        boolean failDelete;

        Dir(File root) { this.root = root; }

        File file(String token, long id, String suffix) { return new File(root, TasksCloudSchema.fileName(token, id) + suffix); }

        void create(String token, long id) throws IOException {
            for (String suffix : Arrays.asList("", "-wal", "-shm")) check(file(token, id, suffix).createNewFile(), "fixture file");
        }

        boolean exists(String token, long id) { return file(token, id, "").exists(); }

        /** Any other file of the database directory (legacy cloud, local-only), with recognisable content. */
        File other(String name) throws IOException {
            File file = new File(root, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(("not a v2 cloud database: " + name).getBytes(StandardCharsets.UTF_8));
            }
            return file;
        }

        /** Mirrors TasksCloudRepository.stripSuffix: the main database name of a directory entry. */
        static String mainName(String name) {
            for (String suffix : SIDECARS) if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
            return name;
        }

        /** Mirrors reconcileAfterLaunch: owners of origin-scoped names only (any origin); 0 never appears. */
        List<Long> owners() {
            ArrayList<Long> owners = new ArrayList<>();
            String[] names = root.list();
            if (names != null) {
                for (String name : names) {
                    TasksCloudSchema.Owner owner = TasksCloudSchema.ownerOf(mainName(name));
                    if (owner != null) owners.add(owner.rawTelegramId);
                }
            }
            return owners;
        }

        /** Mirrors AndroidFiles.deleteAll: every origin-scoped file of this user in any origin, sidecars included. */
        @Override public boolean deleteAll(long id) {
            if (failDelete) return false;
            String[] names = root.list();
            if (names == null) return false;
            boolean gone = true;
            for (String name : names) {
                TasksCloudSchema.Owner owner = TasksCloudSchema.ownerOf(mainName(name));
                if (owner == null || owner.rawTelegramId != id) continue;
                File file = new File(root, name);
                if (file.exists() && !file.delete()) gone = false;
            }
            return gone;
        }
    }

    /** Mirrors TasksCloudRepository.engine(): the database is created only if clearBeforeOpen allows it. */
    static boolean open(TasksCloudCleanup cleanup, Dir dir, String token, long id) throws IOException {
        if (!cleanup.clearBeforeOpen(id)) return false;
        if (!dir.exists(token, id)) check(dir.file(token, id, "").createNewFile(), "open creates the database");
        return true;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        check(root.isDirectory() || root.mkdirs(), "temp dir");
        Dir dir = new Dir(root);
        HashMap<Long, Boolean> disk = new HashMap<>();
        Prefs prefs = new Prefs(disk);
        TasksCloudCleanup cleanup = new TasksCloudCleanup(prefs, dir);
        long user = 7;

        dir.create(ORIGIN_A, user);
        check(cleanup.markForLogout(user) && disk.containsKey(user), "logout marker committed");
        prefs.failCommits = true;
        check(!cleanup.wipe(user), "marker clear not durable => wipe NOT reported done");
        check(!dir.exists(ORIGIN_A, user) && !prefs.present(user) && disk.containsKey(user),
                "files gone; Android memory says cleared but disk still has the marker");
        check(cleanup.mustWipe(user), "in-memory obligation retained despite the mutated preference memory");
        check(!open(cleanup, dir, ORIGIN_A, user) && !open(cleanup, dir, ORIGIN_A, user) && !dir.exists(ORIGIN_A, user),
                "repeated open refused: no new database under a stale on-disk marker");

        Prefs restartedPrefs = new Prefs(disk);
        restartedPrefs.failCommits = true;
        TasksCloudCleanup restarted = new TasksCloudCleanup(restartedPrefs, dir);
        check(restarted.mustWipe(user) && !open(restarted, dir, ORIGIN_A, user) && !dir.exists(ORIGIN_A, user),
                "after restart the durable marker still blocks");

        restartedPrefs.failCommits = false;
        check(open(restarted, dir, ORIGIN_A, user) && dir.exists(ORIGIN_A, user) && !disk.containsKey(user) && !restarted.mustWipe(user),
                "successful marker clear discharges the obligation, then the database may be created");
        Prefs third = new Prefs(disk);
        TasksCloudCleanup thirdBoot = new TasksCloudCleanup(third, dir);
        check(thirdBoot.launchTargets(new HashSet<>(Collections.singleton(user)), dir.owners()).isEmpty()
                && !thirdBoot.mustWipe(user) && dir.exists(ORIGIN_A, user), "new data created after the clear survives the next launch");

        long other = 8;
        dir.create(ORIGIN_A, other);
        check(thirdBoot.markForLogout(other), "second account logout");
        dir.failDelete = true;
        check(!thirdBoot.wipe(other) && thirdBoot.mustWipe(other) && disk.containsKey(other), "undeletable files keep marker and obligation");
        dir.failDelete = false;
        check(thirdBoot.wipe(other) && !dir.exists(ORIGIN_A, other) && !disk.containsKey(other) && dir.exists(ORIGIN_A, user),
                "retry wipes exactly that account; others untouched");

        // Every origin, never legacy: one marker wipes the user's database (and sidecars) of each origin, nothing else.
        long switched = 9;
        dir.create(ORIGIN_A, switched);
        dir.create(ORIGIN_B, switched);
        check(dir.file(ORIGIN_B, switched, "-journal").createNewFile(), "fixture journal");
        dir.create(ORIGIN_B, user);
        List<File> untouched = Arrays.asList(dir.other("zenly_tasks_cloud_" + switched + ".db"),
                dir.other("zenly_tasks_cloud_" + switched + ".db-wal"), dir.other("zenly_tasks_" + switched + ".db"),
                dir.other("zenly_tasks_cloud.o-" + ORIGIN_A + "." + switched + ".sqlite"));
        check(thirdBoot.markForLogout(switched) && thirdBoot.wipe(switched) && !disk.containsKey(switched) && !thirdBoot.mustWipe(switched),
                "a logout across origins is discharged once every origin's files are gone");
        for (String token : Arrays.asList(ORIGIN_A, ORIGIN_B)) {
            for (String suffix : Arrays.asList("", "-journal", "-wal", "-shm")) {
                check(!dir.file(token, switched, suffix).exists(), "deleted in every origin: " + suffix);
            }
        }
        for (File file : untouched) check(file.isFile() && file.length() > 0, "legacy, local-only and foreign names untouched: " + file.getName());
        check(dir.exists(ORIGIN_A, user) && dir.exists(ORIGIN_B, user), "another user's databases stay in both origins");

        // Launch discovery: legacy files never make their owner a target; a v2 file of a signed-out user does.
        dir.other("zenly_tasks_cloud_12.db");
        dir.other("zenly_tasks_cloud_12.db-journal");
        dir.create(ORIGIN_B, 10);
        check(new HashSet<>(dir.owners()).equals(new HashSet<>(Arrays.asList(user, 10L))), "owners: origin-scoped names only");
        check(thirdBoot.launchTargets(new HashSet<>(Collections.singleton(user)), dir.owners()).equals(Collections.singleton(10L)),
                "launch targets the signed-out v2 owner and never a legacy owner");

        disk.put(11L, true);
        TasksCloudCleanup launch = new TasksCloudCleanup(new Prefs(disk), dir);
        Set<Long> targets = launch.launchTargets(new HashSet<>(Arrays.asList(user, 11L)), Arrays.asList(user, 9L, 0L));
        check(targets.equals(new HashSet<>(Arrays.asList(9L, 11L))), "launch: non-live owners plus every surviving marker (even if live)");
        System.out.println("Tasks cloud cleanup obligations: " + checks + " checks passed");
    }
}

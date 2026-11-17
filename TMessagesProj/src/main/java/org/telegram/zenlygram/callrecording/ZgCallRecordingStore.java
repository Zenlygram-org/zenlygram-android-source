/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import android.os.StatFs;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

/**
 * The recordings on disk: {@code no_backup/zg_call_recordings/<id>.aac} + {@code <id>.json}. The no-backup directory
 * keeps them out of Android's auto backup (the settings notice promises "excluded from backups"); it is app-private,
 * so nothing else on the phone can read them. A copy leaves only through Share or Saved Messages.
 */
public final class ZgCallRecordingStore {

    /** Refuse to start below this much free space (iOS: 120 MB floor). */
    public static final long MINIMUM_FREE_BYTES = 120L * 1024 * 1024;

    private static final String DIR = "zg_call_recordings";

    private ZgCallRecordingStore() {
    }

    public static File directory() {
        return new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), DIR);
    }

    public static File ensureDirectory() {
        final File dir = directory();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    public static String newId() {
        return (System.currentTimeMillis() / 1000) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.US);
    }

    public static File audioFile(String id) {
        return ZgCallRecordingMetadata.isValidId(id) ? new File(directory(), id + ZgCallRecordingFormat.EXTENSION) : null;
    }

    static File metadataFile(String id) {
        return ZgCallRecordingMetadata.isValidId(id) ? new File(directory(), id + ".json") : null;
    }

    public static long availableBytes() {
        try {
            final File dir = ensureDirectory();
            return dir == null ? 0 : new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Throwable e) {
            return -1;
        }
    }

    public static synchronized boolean writeMetadata(ZgCallRecordingMetadata metadata) {
        final File target = metadataFile(metadata.id);
        if (target == null || ensureDirectory() == null) {
            return false;
        }
        final File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable e) {
            FileLog.e(e);
            tmp.delete();
            return false;
        }
        return tmp.renameTo(target);
    }

    public static synchronized ZgCallRecordingMetadata readMetadata(String id) {
        final File f = metadataFile(id);
        if (f == null || !f.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            final byte[] data = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int read = 0;
            while (read < data.length) {
                final int r = in.read(data, read, data.length - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
            final ZgCallRecordingMetadata m = ZgCallRecordingMetadata.fromJson(new String(data, 0, read, StandardCharsets.UTF_8));
            return m != null && m.id.equals(id) ? m : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Every recording with a sidecar, newest first. */
    public static ArrayList<ZgCallRecordingMetadata> list() {
        final ArrayList<ZgCallRecordingMetadata> out = new ArrayList<>();
        final File[] files = directory().listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            final String name = f.getName();
            if (name.endsWith(".json")) {
                final ZgCallRecordingMetadata m = readMetadata(name.substring(0, name.length() - 5));
                if (m != null) {
                    out.add(m);
                }
            }
        }
        Collections.sort(out, (a, b) -> Double.compare(b.startTimestamp, a.startTimestamp));
        return out;
    }

    public static synchronized boolean delete(String id) {
        final File audio = audioFile(id);
        final File meta = metadataFile(id);
        boolean ok = true;
        if (audio != null && audio.exists()) {
            ok = audio.delete();
        }
        if (meta != null && meta.exists()) {
            ok &= meta.delete();
        }
        return ok;
    }

    /**
     * Logout of {@code accountUserId}: its recordings hold another person's voice and must not outlive the account on
     * this phone (iOS SGCallRecordingAccountLedger purge). A recording still being written is stopped first by the call
     * ending with the logout; recordings whose account is unknown are left alone.
     */
    public static void purgeAccount(long accountUserId) {
        if (accountUserId == 0) {
            return;
        }
        for (ZgCallRecordingMetadata m : list()) {
            if (m.accountUserId != null && m.accountUserId == accountUserId) {
                delete(m.id);
            }
        }
    }

    /**
     * Audio without a sidecar (a crash between the two creations) and leftover temp files: removed. A sidecar marked
     * incomplete whose recording is not live stays: that is an interrupted recording the user can still play.
     */
    public static synchronized void sweepOrphans(String activeId) {
        final File[] files = directory().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            final String name = f.getName();
            if (name.endsWith(".tmp")) {
                f.delete();
            } else if (name.endsWith(ZgCallRecordingFormat.EXTENSION)) {
                final String id = name.substring(0, name.length() - ZgCallRecordingFormat.EXTENSION.length());
                if (!id.equals(activeId) && (metadataFile(id) == null || !metadataFile(id).isFile())) {
                    f.delete();
                }
            }
        }
    }
}

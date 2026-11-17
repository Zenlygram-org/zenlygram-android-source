/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

import androidx.annotation.Nullable;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.List;

/**
 * Download Boost and Upload Boost (iOS SGSimpleSettings downloadSpeedBoost / uploadSpeedBoost).
 *
 * <p>Download levels, as on iOS (getSGDownloadPartSize, getSGMaxPendingParts) but never more than what Telegram
 * itself uses for Premium accounts (16 MB in flight over 8 connections):
 * <ul>
 *   <li>Medium: 512 KB parts, 8 requests in flight, 4 download connections (4 MB in flight);</li>
 *   <li>Maximum: 1 MB parts, 12 requests in flight, 8 download connections (12 MB in flight).</li>
 * </ul>
 * Only regular downloads of 1 MB and more are boosted. FileLoadOperation decides it once the saved state of a resumed
 * download is known and keeps Telegram's parameters for stories, streams, video preloading, web files, small chunks
 * after a LIMIT_INVALID, any state not aligned to the boosted part size, and files served by a CDN (their 128 KB hash
 * checks stay complete). Premium accounts are never boosted: their own pipeline is already stronger.
 *
 * <p>Upload Boost uses Telegram's Premium upload connection count (8 instead of 4). The part size and the 30 requests
 * in flight stay Telegram's, so resuming an upload never mixes part sizes. Both read the cached setting, cheap on the
 * stage and file queues.
 */
public final class ZgNetworkBoost {

    /** iOS getSGDownloadPartSize: files under 1 MB keep Telegram's parameters. */
    public static final int BOOST_MIN_FILE_SIZE = 1024 * 1024;

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_MEDIUM = 1;
    public static final int LEVEL_MAXIMUM = 2;

    private ZgNetworkBoost() {
    }

    /**
     * The boost level for a download of {@code totalBytes} on {@code account}: LEVEL_NONE when the switch is off, the
     * file is smaller than 1 MB (or of unknown size) or the account is Premium.
     */
    public static int downloadLevel(int account, long totalBytes) {
        if (totalBytes < BOOST_MIN_FILE_SIZE) {
            return LEVEL_NONE;
        }
        final int level;
        if (ZgSettings.downloadSpeedBoost.is(ZgSettings.DownloadBoost.MAXIMUM)) {
            level = LEVEL_MAXIMUM;
        } else if (ZgSettings.downloadSpeedBoost.is(ZgSettings.DownloadBoost.MEDIUM)) {
            level = LEVEL_MEDIUM;
        } else {
            return LEVEL_NONE;
        }
        return isPremium(account) ? LEVEL_NONE : level;
    }

    /** Part size: 512 KB or 1 MB. Both divide 1 MB, so an aligned part never crosses a 1 MB boundary (upload.getFile). */
    public static int downloadChunkSize(int level) {
        return level == LEVEL_MAXIMUM ? 1024 * 1024 : 512 * 1024;
    }

    /** Requests in flight for one file (iOS maxPendingParts 8 / 12). */
    public static int downloadRequests(int level) {
        return level == LEVEL_MAXIMUM ? 12 : 8;
    }

    /**
     * Download connections per datacenter. Always a power of two: FileLoadOperation picks the connection with
     * {@code requestsCount & (count - 1)}, and never more than ConnectionsManager.DownloadConnectionsCount.
     */
    public static int downloadConnections(int level) {
        return level == LEVEL_MAXIMUM ? ConnectionsManager.DownloadConnectionsCount : 4;
    }

    /** Reads the bounds of FileLoadOperation's private ranges (start inclusive, end exclusive). */
    public interface RangeBounds<T> {
        long start(T range);

        long end(T range);
    }

    /**
     * FileLoadOperation: the boosted {part size, requests in flight, connections} for a download, or null to keep
     * Telegram's parameters (level none, or a saved state that bigger parts cannot continue).
     *
     * <p>Telegram requests a whole part from a range start and counts the whole answer as downloaded, so every range
     * still to request must start on a part boundary AND end on one (or at the end of the file): a gap that ends inside
     * a part would be fetched again in full, counted twice, and the download would finish before its last bytes are on
     * disk (a truncated video, a zip without its central directory). {@code ranges} null: a sequential download whose
     * next offset is {@code requestedBytes}.
     */
    @Nullable
    public static <T> int[] downloadParams(int account, long totalBytes, @Nullable List<T> ranges, RangeBounds<T> bounds, long requestedBytes) {
        final int level = downloadLevel(account, totalBytes);
        if (level == LEVEL_NONE) {
            return null;
        }
        final int chunkSize = downloadChunkSize(level);
        if (!canContinueWith(chunkSize, totalBytes, ranges, bounds, requestedBytes)) {
            return null;
        }
        return new int[]{chunkSize, downloadRequests(level), downloadConnections(level)};
    }

    /** Whether every range still to request starts on a {@code chunkSize} boundary and ends on one or at the file end. */
    public static <T> boolean canContinueWith(int chunkSize, long totalBytes, @Nullable List<T> ranges, RangeBounds<T> bounds, long requestedBytes) {
        if (chunkSize <= 0) {
            return false;
        }
        if (ranges == null) {
            return requestedBytes % chunkSize == 0;
        }
        for (int i = 0; i < ranges.size(); i++) {
            final T range = ranges.get(i);
            final long start = bounds.start(range);
            final long end = bounds.end(range);
            if (start % chunkSize != 0 || end % chunkSize != 0 && end < totalBytes) {
                return false;
            }
        }
        return true;
    }

    /** Whether uploads of {@code account} use Telegram's Premium upload connection count. */
    public static boolean uploadBoost(int account) {
        return ZgSettings.uploadSpeedBoost.get() && !isPremium(account);
    }

    private static boolean isPremium(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return true; // unknown account: no boost
        }
        return UserConfig.getInstance(account).isPremium();
    }
}

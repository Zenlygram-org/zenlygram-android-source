/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The sidecar of one recording ({@code <id>.json} next to {@code <id>.aac}). Written at START marked incomplete and
 * rewritten periodically and at stop, like iOS: a recording interrupted by a crash is still listed, playable up to its
 * last frame and deletable, and says it stops early.
 */
public final class ZgCallRecordingMetadata {

    public static final int VERSION = 1;

    public static final String DELIVERY_NONE = "none";
    public static final String DELIVERY_SENDING = "sending";
    public static final String DELIVERY_FAILED = "failed";

    public final String id;
    /** Seconds since the epoch. */
    public final double startTimestamp;
    public final double duration;
    public final String peerTitle;
    /** The account that recorded (its Telegram user id); null when unknown. */
    public final Long accountUserId;
    /** The other party (user id); null when unknown. */
    public final Long peerId;
    public final boolean isComplete;
    /** A write failed part-way: the file stops before the call did. */
    public final boolean truncated;
    public final String delivery;

    public ZgCallRecordingMetadata(String id, double startTimestamp, double duration, String peerTitle, Long accountUserId, Long peerId,
                                   boolean isComplete, boolean truncated, String delivery) {
        this.id = id;
        this.startTimestamp = startTimestamp;
        this.duration = duration;
        this.peerTitle = peerTitle == null ? "" : peerTitle;
        this.accountUserId = accountUserId;
        this.peerId = peerId;
        this.isComplete = isComplete;
        this.truncated = truncated;
        this.delivery = delivery == null ? DELIVERY_NONE : delivery;
    }

    public ZgCallRecordingMetadata withProgress(double duration, boolean isComplete, boolean truncated) {
        return new ZgCallRecordingMetadata(id, startTimestamp, duration, peerTitle, accountUserId, peerId, isComplete, truncated, delivery);
    }

    public ZgCallRecordingMetadata withDelivery(String delivery) {
        return new ZgCallRecordingMetadata(id, startTimestamp, duration, peerTitle, accountUserId, peerId, isComplete, truncated, delivery);
    }

    public String toJson() {
        try {
            final JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("id", id);
            o.put("start", startTimestamp);
            o.put("duration", duration);
            o.put("peerTitle", peerTitle);
            if (accountUserId != null) {
                o.put("account", accountUserId.longValue());
            }
            if (peerId != null) {
                o.put("peer", peerId.longValue());
            }
            o.put("complete", isComplete);
            o.put("truncated", truncated);
            o.put("delivery", delivery);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null for anything that is not a sidecar of this format. */
    public static ZgCallRecordingMetadata fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject(json);
            final String id = o.optString("id", null);
            if (id == null || !isValidId(id)) {
                return null;
            }
            return new ZgCallRecordingMetadata(id, o.optDouble("start", 0), o.optDouble("duration", 0), o.optString("peerTitle", ""),
                o.has("account") ? o.getLong("account") : null, o.has("peer") ? o.getLong("peer") : null,
                o.optBoolean("complete", false), o.optBoolean("truncated", false), o.optString("delivery", DELIVERY_NONE));
        } catch (JSONException e) {
            return null;
        }
    }

    /** Ids are generated as {@code <unix seconds>-<8 hex>}; nothing else may name a file in the store. */
    public static boolean isValidId(String id) {
        return id != null && id.matches("[0-9]{1,12}-[0-9a-f]{8}");
    }
}

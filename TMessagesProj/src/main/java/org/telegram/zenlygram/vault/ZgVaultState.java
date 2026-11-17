/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import java.util.Arrays;

/**
 * Which chats each account keeps in the Secret Vault, and which pins the vault took away from them (iOS
 * SGProtectedStore vault snapshot + the effect records). Immutable and pure Java (no Android API), so it is read from
 * any thread and checked on the JVM.
 *
 * <p>Accounts are keyed by the account's Telegram user id (never the account slot), dialog ids use Telegram Android's
 * encoding (user &gt; 0, chat / channel = -id, secret chat = DialogObject.makeEncryptedDialogId). An account has an
 * entry only while it has at least one member; {@code unpinned} is always a subset of the members.
 *
 * <p>Text form (encrypted at rest by {@link ZgVaultCrypto}): a header line {@value #HEADER}, then per account the lines
 * {@code a <uid>}, {@code m <did> <did> ...} and {@code p <did> ...}.
 */
final class ZgVaultState {

    static final String HEADER = "zgvs1";
    static final ZgVaultState EMPTY = new ZgVaultState(new long[0], new long[0][], new long[0][]);

    private static final long[] NONE = new long[0];

    /** Sorted, unique, never 0. */
    private final long[] uids;
    /** members[i]: sorted, unique, never empty. */
    private final long[][] members;
    /** unpinned[i]: sorted, unique, subset of members[i]. */
    private final long[][] unpinned;

    private ZgVaultState(long[] uids, long[][] members, long[][] unpinned) {
        this.uids = uids;
        this.members = members;
        this.unpinned = unpinned;
    }

    boolean isEmpty() {
        return uids.length == 0;
    }

    boolean isMember(long uid, long dialogId) {
        final int index = Arrays.binarySearch(uids, uid);
        return index >= 0 && Arrays.binarySearch(members[index], dialogId) >= 0;
    }

    boolean wasUnpinned(long uid, long dialogId) {
        final int index = Arrays.binarySearch(uids, uid);
        return index >= 0 && Arrays.binarySearch(unpinned[index], dialogId) >= 0;
    }

    /** A copy of the account's members (sorted), empty when it has none. */
    long[] members(long uid) {
        final int index = Arrays.binarySearch(uids, uid);
        return index >= 0 ? members[index].clone() : NONE;
    }

    /** A copy of the account's records of removed pins (sorted). */
    long[] unpinned(long uid) {
        final int index = Arrays.binarySearch(uids, uid);
        return index >= 0 ? unpinned[index].clone() : NONE;
    }

    /** A copy of the user ids that have members. */
    long[] accounts() {
        return uids.clone();
    }

    int count() {
        int total = 0;
        for (long[] list : members) {
            total += list.length;
        }
        return total;
    }

    int count(long uid) {
        final int index = Arrays.binarySearch(uids, uid);
        return index >= 0 ? members[index].length : 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Copy-on-write changes
    // ---------------------------------------------------------------------------------------------------------

    /** {@code dialogIds} join the account's members; {@code unpinnedIds} (members only) are recorded as unpinned. */
    ZgVaultState withMembers(long uid, long[] dialogIds, long[] unpinnedIds) {
        if (uid == 0) {
            return this;
        }
        final long[] add = normalize(dialogIds);
        final long[] records = normalize(unpinnedIds);
        if (add.length == 0 && records.length == 0) {
            return this;
        }
        final int index = Arrays.binarySearch(uids, uid);
        final long[] oldMembers = index >= 0 ? members[index] : NONE;
        final long[] oldUnpinned = index >= 0 ? unpinned[index] : NONE;
        final long[] newMembers = union(oldMembers, add);
        final long[] newUnpinned = intersect(union(oldUnpinned, records), newMembers);
        if (newMembers == oldMembers && newUnpinned.length == oldUnpinned.length) {
            return this;
        }
        return withEntry(uid, newMembers, newUnpinned);
    }

    /** {@code dialogIds} leave the account's members, with their records. */
    ZgVaultState withoutMembers(long uid, long[] dialogIds) {
        final int index = Arrays.binarySearch(uids, uid);
        if (index < 0) {
            return this;
        }
        final long[] remove = normalize(dialogIds);
        final long[] newMembers = minus(members[index], remove);
        if (newMembers.length == members[index].length) {
            return this;
        }
        return withEntry(uid, newMembers, intersect(unpinned[index], newMembers));
    }

    /** Forgets everything of one account (logout). */
    ZgVaultState withoutAccount(long uid) {
        final int index = Arrays.binarySearch(uids, uid);
        if (index < 0) {
            return this;
        }
        return withEntry(uid, NONE, NONE);
    }

    private ZgVaultState withEntry(long uid, long[] newMembers, long[] newUnpinned) {
        final int index = Arrays.binarySearch(uids, uid);
        if (newMembers.length == 0) {
            if (index < 0) {
                return this;
            }
            final int n = uids.length - 1;
            final long[] u = new long[n];
            final long[][] m = new long[n][];
            final long[][] p = new long[n][];
            for (int i = 0, j = 0; i < uids.length; i++) {
                if (i == index) {
                    continue;
                }
                u[j] = uids[i];
                m[j] = members[i];
                p[j] = unpinned[i];
                j++;
            }
            return n == 0 ? EMPTY : new ZgVaultState(u, m, p);
        }
        if (index >= 0) {
            final long[][] m = members.clone();
            final long[][] p = unpinned.clone();
            m[index] = newMembers;
            p[index] = newUnpinned;
            return new ZgVaultState(uids, m, p);
        }
        final int insert = -index - 1;
        final int n = uids.length + 1;
        final long[] u = new long[n];
        final long[][] m = new long[n][];
        final long[][] p = new long[n][];
        for (int i = 0, j = 0; i < n; i++) {
            if (i == insert) {
                u[i] = uid;
                m[i] = newMembers;
                p[i] = newUnpinned;
            } else {
                u[i] = uids[j];
                m[i] = members[j];
                p[i] = unpinned[j];
                j++;
            }
        }
        return new ZgVaultState(u, m, p);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Text form
    // ---------------------------------------------------------------------------------------------------------

    String encode() {
        final StringBuilder builder = new StringBuilder(32 + count() * 14);
        builder.append(HEADER).append('\n');
        for (int i = 0; i < uids.length; i++) {
            builder.append("a ").append(uids[i]).append('\n');
            builder.append('m');
            for (long id : members[i]) {
                builder.append(' ').append(id);
            }
            builder.append('\n');
            builder.append('p');
            for (long id : unpinned[i]) {
                builder.append(' ').append(id);
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * Parses {@link #encode()} output. Throws IllegalArgumentException for anything else: the text comes out of an
     * authenticated decryption, so a parse failure is a bug or a damaged store, never something to guess around.
     */
    static ZgVaultState decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("null");
        }
        final String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) {
            throw new IllegalArgumentException("header");
        }
        ZgVaultState state = EMPTY;
        long uid = 0;
        long[] pendingMembers = null;
        for (int i = 1; i < lines.length; i++) {
            final String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            final char kind = line.charAt(0);
            if (line.length() > 1 && line.charAt(1) != ' ') {
                throw new IllegalArgumentException("line");
            }
            final long[] numbers = parseNumbers(line);
            if (kind == 'a') {
                if (numbers.length != 1 || numbers[0] == 0) {
                    throw new IllegalArgumentException("account");
                }
                uid = numbers[0];
                pendingMembers = null;
            } else if (kind == 'm') {
                if (uid == 0) {
                    throw new IllegalArgumentException("members without account");
                }
                pendingMembers = numbers;
                state = state.withMembers(uid, numbers, NONE);
            } else if (kind == 'p') {
                if (uid == 0 || pendingMembers == null) {
                    throw new IllegalArgumentException("records without members");
                }
                state = state.withMembers(uid, pendingMembers, numbers);
            } else {
                throw new IllegalArgumentException("kind");
            }
        }
        return state;
    }

    private static long[] parseNumbers(String line) {
        if (line.length() <= 2) {
            return NONE;
        }
        final String[] parts = line.substring(2).split(" ");
        final long[] result = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            result[count++] = Long.parseLong(part);
        }
        return count == result.length ? result : Arrays.copyOf(result, count);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Sorted sets
    // ---------------------------------------------------------------------------------------------------------

    /** Sorted, unique, without 0. */
    static long[] normalize(long[] ids) {
        if (ids == null || ids.length == 0) {
            return NONE;
        }
        final long[] sorted = ids.clone();
        Arrays.sort(sorted);
        int count = 0;
        for (int i = 0; i < sorted.length; i++) {
            final long id = sorted[i];
            if (id == 0 || count > 0 && sorted[count - 1] == id) {
                continue;
            }
            sorted[count++] = id;
        }
        return count == sorted.length ? sorted : Arrays.copyOf(sorted, count);
    }

    static long[] union(long[] a, long[] b) {
        if (b.length == 0) {
            return a;
        }
        if (a.length == 0) {
            return b;
        }
        final long[] out = new long[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length || j < b.length) {
            final long next;
            if (j >= b.length || i < a.length && a[i] < b[j]) {
                next = a[i++];
            } else if (i >= a.length || b[j] < a[i]) {
                next = b[j++];
            } else {
                next = a[i++];
                j++;
            }
            out[k++] = next;
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    static long[] minus(long[] a, long[] b) {
        if (a.length == 0 || b.length == 0) {
            return a;
        }
        final long[] out = new long[a.length];
        int k = 0;
        for (long id : a) {
            if (Arrays.binarySearch(b, id) < 0) {
                out[k++] = id;
            }
        }
        return k == a.length ? a : Arrays.copyOf(out, k);
    }

    static long[] intersect(long[] a, long[] b) {
        if (a.length == 0 || b.length == 0) {
            return NONE;
        }
        final long[] out = new long[Math.min(a.length, b.length)];
        int k = 0;
        for (long id : a) {
            if (Arrays.binarySearch(b, id) >= 0) {
                out[k++] = id;
            }
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }
}

package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * Consent gate of Cloud Tasks traffic (design 30 §6.1). Pure: the customer-data side is bridged in by
 * ZenlyTasksSyncOwner.onPostInit (TR4-A3), which installs {@code account -> tasksMayRun ? ALLOWED : recordingAllowed ?
 * WAITING : FORBIDDEN}. Fail closed: until a source is installed — and whenever it throws or answers null — every
 * account is FORBIDDEN, so a missed install never runs Tasks sync without consent. Host harnesses that drive the engine
 * install {@code a -> ALLOWED} explicitly. Only ALLOWED lets any Tasks request (REST or realtime) go out; local edits are
 * never gated here.
 */
public final class TasksCloudGate {
    public enum Permission { ALLOWED, WAITING, FORBIDDEN }

    public interface Source { Permission of(int account); }

    private static volatile Source source;

    private TasksCloudGate() { }

    /** Any thread. {@code null} uninstalls (fail closed again). */
    public static void install(Source source) {
        TasksCloudGate.source = source;
    }

    /** Any thread; never throws. FORBIDDEN until installed. */
    public static Permission permission(int account) {
        Source current = source;
        if (current == null) return Permission.FORBIDDEN;
        try {
            Permission permission = current.of(account);
            return permission == null ? Permission.FORBIDDEN : permission;
        } catch (RuntimeException e) {
            return Permission.FORBIDDEN;
        }
    }

    public static boolean allowed(int account) {
        return permission(account) == Permission.ALLOWED;
    }
}

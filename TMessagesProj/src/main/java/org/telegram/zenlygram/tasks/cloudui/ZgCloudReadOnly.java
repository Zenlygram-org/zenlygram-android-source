/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;

/**
 * Why an account's cached Cloud tasks are read-only right now (iOS TasksCloudReadOnlyReason): nothing can be queued or
 * sent until the hold ends. Pure, so the JVM tests run it.
 */
public enum ZgCloudReadOnly {
    /** No data consent for Cloud tasks on this account. */
    CONSENT_OFF,
    /** The consent was withdrawn: sync stopped and the unsent changes were discarded. */
    WITHDRAWN,
    /** The server holds Tasks requests until the account's consent is confirmed. */
    CONSENT_FENCED;

    /**
     * The hold of an account, or null when changes may be made. A consent that only waits for the link
     * ({@code WAITING}) is not a hold: its changes queue on this device and are sent once the account is confirmed.
     *
     * @param consentWithdrawn the account's consent state is withdrawn (only chooses the wording)
     */
    public static ZgCloudReadOnly of(TasksCloudState.Status status, TasksCloudGate.Permission permission, boolean consentWithdrawn) {
        if (status == TasksCloudState.Status.CONSENT_FENCED) {
            return CONSENT_FENCED;
        }
        if (status == TasksCloudState.Status.CONSENT_REQUIRED && permission == TasksCloudGate.Permission.FORBIDDEN) {
            return consentWithdrawn ? WITHDRAWN : CONSENT_OFF;
        }
        return null;
    }
}

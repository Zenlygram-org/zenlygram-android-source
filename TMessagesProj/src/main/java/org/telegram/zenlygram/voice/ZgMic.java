/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Settings > Zenlygram > Voice messages > Force Device Microphone (iOS SGSimpleSettings.forceBuiltInMic).
 *
 * <p>When it is on, voice messages and round video messages are recorded with the phone's own microphone even if a
 * wired, USB or Bluetooth headset with a microphone is connected; playback still goes to the headset. Calls are not
 * affected (the call screen has its own audio route button).
 *
 * <ul>
 *   <li>Bluetooth: Telegram starts Bluetooth SCO for recording only when its own "Microphone for voice messages"
 *   (Chat Settings, {@link SharedConfig#recordViaSco}) is "Headset (if connected)". Turning this on switches that to
 *   "Built-in", and turning it off switches it back to "Headset" (only if this switch changed it and the user did not
 *   choose there meanwhile); choosing "Headset" there turns this off again. Invariant: forced implies
 *   {@code !recordViaSco}.</li>
 *   <li>Wired / USB / LE Audio headsets: Android records from them with AudioSource.DEFAULT, so every recorder
 *   calls {@link #preferBuiltInMic(AudioRecord)} right after creating its AudioRecord (ZENLYGRAM hooks in
 *   MediaController, AudioCapture, InstantCameraView and RoundVideoCodecRecorder).</li>
 * </ul>
 */
public final class ZgMic {

    // AudioDeviceInfo types (API 23+ constants; written as numbers so no newer API constant is referenced)
    private static final int TYPE_WIRED_HEADSET = 3;
    private static final int TYPE_BLUETOOTH_SCO = 7;
    private static final int TYPE_USB_DEVICE = 11;
    private static final int TYPE_BUILTIN_MIC = 15;
    private static final int TYPE_USB_HEADSET = 22;
    private static final int TYPE_BLE_HEADSET = 26;

    private ZgMic() {
    }

    /** Whether recordings must use the built-in microphone. */
    public static boolean isForced() {
        return ZgFeatures.FORCE_BUILTIN_MIC.isPorted() && ZgSettings.forceBuiltInMic.get();
    }

    /**
     * Row 901. Turning it on also switches Telegram's "Microphone for voice messages" to Built-in; turning it off
     * restores "Headset (if connected)" when it was this switch that changed it.
     */
    public static void setForced(boolean forced) {
        ZgSettings.forceBuiltInMic.set(forced);
        if (forced) {
            if (SharedConfig.recordViaSco) {
                SharedConfig.recordViaSco = false;
                SharedConfig.saveConfig();
                ZgSettings.forceBuiltInMicRestoreSco.set(true);
            }
        } else if (ZgSettings.forceBuiltInMicRestoreSco.get()) {
            ZgSettings.forceBuiltInMicRestoreSco.set(false);
            if (!SharedConfig.recordViaSco) {
                SharedConfig.recordViaSco = true;
                SharedConfig.saveConfig();
            }
        }
    }

    /** Telegram's "Microphone for voice messages" dialog: the user chose "Headset (if connected)". */
    public static void onTelegramHeadsetChosen() {
        if (ZgSettings.forceBuiltInMicRestoreSco.get()) {
            ZgSettings.forceBuiltInMicRestoreSco.set(false);
        }
        if (ZgSettings.forceBuiltInMic.get()) {
            ZgSettings.forceBuiltInMic.set(false);
        }
    }

    /** The same dialog: the user chose "Built-in" there, so turning row 901 off later keeps Built-in. */
    public static void onTelegramBuiltInChosen() {
        if (ZgSettings.forceBuiltInMicRestoreSco.get()) {
            ZgSettings.forceBuiltInMicRestoreSco.set(false);
        }
    }

    /**
     * Routes {@code record} to the built-in microphone while the setting is on and an external input (wired, USB or
     * Bluetooth headset) is connected. Without an external input nothing is changed, so the recorder keeps the
     * device's own choice among several built-in microphones (e.g. the camcorder one for round videos). Never throws.
     */
    public static void preferBuiltInMic(AudioRecord record) {
        if (record == null || !isForced()) {
            return;
        }
        try {
            final AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            final AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            if (inputs == null) {
                return;
            }
            AudioDeviceInfo builtIn = null;
            boolean external = false;
            for (AudioDeviceInfo device : inputs) {
                if (device == null) {
                    continue;
                }
                final int type = device.getType();
                if (type == TYPE_BUILTIN_MIC) {
                    if (builtIn == null) {
                        builtIn = device;
                    }
                } else if (isExternalInput(type)) {
                    external = true;
                }
            }
            if (builtIn == null || !external) {
                return;
            }
            final boolean applied = record.setPreferredDevice(builtIn);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("zg mic: prefer built-in microphone " + (applied ? "applied" : "rejected"));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isExternalInput(int type) {
        return type == TYPE_WIRED_HEADSET || type == TYPE_USB_HEADSET || type == TYPE_USB_DEVICE
            || type == TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET;
    }
}

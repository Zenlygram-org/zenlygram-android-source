/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.camera.CameraController;
import org.telegram.messenger.camera.CameraInfo;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.ArrayList;

/**
 * The media switches that Telegram's own code asks at one point each (Settings > Zenlygram > Round videos, Camera &
 * gallery). Every method reads the cached setting, so the hooks stay one short line.
 */
public final class ZgMediaOptions {

    private ZgMediaOptions() {
    }

    /**
     * Video PIP with Swipe (iOS videoPIPSwipeDirection == "up"): a swipe up on a playing video opens Picture-in-Picture
     * instead of closing the viewer. "down" is never written by the row and counts as off, like iOS.
     */
    public static boolean videoPipSwipe() {
        return ZgSettings.videoPIPSwipeDirection.is(ZgSettings.VideoPipSwipe.UP);
    }

    /**
     * Start with Rear Camera (iOS startTelescopeWithRearCam) for a new round-video recording. With the old Camera1 API
     * the rear camera is asked for only if the device has one; with Camera2, InstantCameraView falls back to the front
     * camera when no rear session can be created.
     */
    public static boolean roundVideoStartsWithRearCamera(boolean useCamera2) {
        if (!ZgSettings.startTelescopeWithRearCam.get()) {
            return false;
        }
        return useCamera2 || hasCamera1RearCamera();
    }

    /** Camera in Gallery (iOS !disableGalleryCamera): the camera cell of the attachment menu. */
    public static boolean galleryCamera() {
        return !ZgSettings.disableGalleryCamera.get();
    }

    /**
     * Camera Preview in Gallery (iOS !disableGalleryCameraPreview). Off: the camera cell shows no live image until it
     * is tapped (Telegram's own lazy camera of power-saving mode), so the camera does not start with the menu.
     */
    public static boolean galleryCameraPreview() {
        return !ZgSettings.disableGalleryCameraPreview.get();
    }

    private static boolean hasCamera1RearCamera() {
        try {
            final ArrayList<CameraInfo> cameras = CameraController.getInstance().getCameras();
            if (cameras == null) {
                return false;
            }
            for (int i = 0; i < cameras.size(); i++) {
                final CameraInfo info = cameras.get(i);
                if (info != null && !info.isFrontface()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }
}

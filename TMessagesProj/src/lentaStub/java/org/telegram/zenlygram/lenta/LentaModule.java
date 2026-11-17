/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */
package org.telegram.zenlygram.lenta;

import android.content.Intent;
import android.os.Bundle;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * Lenta entry of a build type WITHOUT Lenta (zenlygram-lenta.gradle, ZG_LENTA): no Lenta class, string or resource
 * is compiled in; every answer is "disabled". The real module is src/lenta/java/.../LentaModule.java.
 */
public final class LentaModule {

    private LentaModule() {
    }

    public static boolean isEnabled() {
        return false;
    }

    /** The «Lenta» tab title (Lenta strings are plain Android resources in uz / ru, outside Telegram's string packs). */
    public static String tabTitle() {
        return "";
    }

    public static BaseFragment createTabFragment(Bundle args) {
        return null;
    }

    public static boolean handleDevIntent(LaunchActivity activity, Intent intent) {
        return false;
    }
}

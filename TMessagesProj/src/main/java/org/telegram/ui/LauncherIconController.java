package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        // ZENLYGRAM: the Zenlygram icon (iOS ZSignature) plus the iOS alternate pack (Z*.alticon), in the iOS picker order.
        // Telegram's alternates show its plane: their activity-aliases stay disabled in AndroidManifest.xml.
        DEFAULT("DefaultIcon", R.drawable.zg_launcher_background, R.mipmap.zg_launcher_foreground, R.string.AppIconDefault), // ZENLYGRAM
        ZG_FLOW("ZgFlowIcon", R.drawable.zg_icon_flow_background, R.mipmap.zg_icon_flow_foreground, R.string.ZG_W2_AppIconFlow), // ZENLYGRAM
        ZG_GLASS("ZgGlassIcon", R.drawable.zg_icon_glass_background, R.mipmap.zg_icon_glass_foreground, R.string.ZG_W2_AppIconGlass), // ZENLYGRAM
        ZG_MINIMAL("ZgMinimalIcon", R.drawable.zg_icon_minimal_background, R.mipmap.zg_icon_minimal_foreground, R.string.ZG_W2_AppIconMinimal), // ZENLYGRAM
        ZG_GRADIENT("ZgGradientIcon", R.drawable.zg_icon_gradient_background, R.mipmap.zg_icon_gradient_foreground, R.string.ZG_W2_AppIconGradient), // ZENLYGRAM
        ZG_CIRCLE("ZgCircleIcon", R.drawable.zg_icon_circle_background, R.mipmap.zg_icon_circle_foreground, R.string.ZG_W2_AppIconCircle), // ZENLYGRAM
        ZG_MONOCHROME("ZgMonochromeIcon", R.drawable.zg_icon_monochrome_background, R.mipmap.zg_icon_monochrome_foreground, R.string.ZG_W2_AppIconMonochrome), // ZENLYGRAM
        ZG_OUTLINE("ZgOutlineIcon", R.drawable.zg_icon_outline_background, R.mipmap.zg_icon_outline_foreground, R.string.ZG_W2_AppIconOutline), // ZENLYGRAM
        ZG_DUOTONE("ZgDuotoneIcon", R.drawable.zg_icon_duotone_background, R.mipmap.zg_icon_duotone_foreground, R.string.ZG_W2_AppIconDuotone), // ZENLYGRAM
        ZG_DARK("ZgDarkIcon", R.drawable.zg_icon_dark_background, R.mipmap.zg_icon_dark_foreground, R.string.ZG_W2_AppIconDark), // ZENLYGRAM
        ZG_AERO("ZgAeroIcon", R.drawable.zg_icon_aero_background, R.mipmap.zg_icon_aero_foreground, R.string.ZG_W2_AppIconAero), // ZENLYGRAM
        ZG_FOLD("ZgFoldIcon", R.drawable.zg_icon_fold_background, R.mipmap.zg_icon_fold_foreground, R.string.ZG_W2_AppIconFold), // ZENLYGRAM
        ZG_WAVE("ZgWaveIcon", R.drawable.zg_icon_wave_background, R.mipmap.zg_icon_wave_foreground, R.string.ZG_W2_AppIconWave), // ZENLYGRAM
        ZG_CORNER("ZgCornerIcon", R.drawable.zg_icon_corner_background, R.mipmap.zg_icon_corner_foreground, R.string.ZG_W2_AppIconCorner), // ZENLYGRAM
        ZG_FOCUS("ZgFocusIcon", R.drawable.zg_icon_focus_background, R.mipmap.zg_icon_focus_foreground, R.string.ZG_W2_AppIconFocus), // ZENLYGRAM
        ZG_BALTIC("ZgBalticIcon", R.drawable.zg_icon_baltic_background, R.mipmap.zg_icon_baltic_foreground, R.string.ZG_W2_AppIconBaltic), // ZENLYGRAM
        ZG_NEON("ZgNeonIcon", R.drawable.zg_icon_neon_background, R.mipmap.zg_icon_neon_foreground, R.string.ZG_W2_AppIconNeon), // ZENLYGRAM
        ZG_ISOMETRIC("ZgIsometricIcon", R.drawable.zg_icon_isometric_background, R.mipmap.zg_icon_isometric_foreground, R.string.ZG_W2_AppIconIsometric), // ZENLYGRAM
        ZG_CLEAN("ZgCleanIcon", R.drawable.zg_icon_clean_background, R.mipmap.zg_icon_clean_foreground, R.string.ZG_W2_AppIconClean), // ZENLYGRAM
        ZG_PREMIUM("ZgPremiumIcon", R.drawable.zg_icon_premium_background, R.mipmap.zg_icon_premium_foreground, R.string.ZG_W2_AppIconPremium); // ZENLYGRAM
//        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
//        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconAqua),
//        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium, true),
//        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo, true),
//        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconNox, true);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}

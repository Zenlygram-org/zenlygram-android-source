/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.appupdate;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.zenlygram.cloud.ZgAppUpdateDecision;
import org.telegram.zenlygram.cloud.ZgCloudConfig;
import org.telegram.zenlygram.cloud.ZgCloudPolicies;
import org.telegram.zenlygram.core.ZgConfig;

/**
 * The app update policy on screen (iOS SGAppUpdatePresenter / SGAppUpdateBlockController / SGAppUpdateReminderAlert,
 * docs/entitlement.md §3.9c), decided synchronously from the stored signed token, never waiting on the network.
 *
 * <ul>
 *   <li><b>Block</b> ("Update required"): a full-screen, non-dismissable view on top of LaunchActivity's content
 *       (above the passcode views and every fragment; Telegram's call screen is its own activity, so calls stay
 *       usable). Back is swallowed. Buttons: Google Play (market:// then the https store page) in the Play build,
 *       the website's download page in a side-load build (it was not installed from Play, and Play's copy has
 *       another signature), Help (website), and a device-floor note — never a dead end. It lifts by itself when a
 *       corrected policy arrives.</li>
 *   <li><b>Remind</b> ("Update available"): one dismissable alert (Update / Later) per distinct {@code latest}, at most
 *       once per launch, on a resume, never during a call or while the block is up. The reminded latest is written
 *       only after the alert was shown.</li>
 * </ul>
 * Hooks (LaunchActivity, marked ZENLYGRAM): {@link #attach} after setContentView, {@link #onResume} in onResume,
 * {@link #onBackPressed} first in onBackPressed.
 */
public final class ZgAppUpdate {

    static final String KEY_REMINDED = "zg.app.policy.reminded@" + ZgCloudConfig.AUTHORITY;
    private static final String HELP_URL = ZgConfig.WEBSITE_URL;

    private static FrameLayout host;
    private static View blockView;
    private static boolean remindedThisLaunch;
    private static boolean observing;

    private ZgAppUpdate() {
    }

    /** Whether the "Update required" block is on screen. */
    public static boolean isBlocking() {
        return blockView != null && blockView.getParent() != null;
    }

    /** UI thread, once per LaunchActivity: remembers the content layout and shows the block if the stored policy says so. */
    public static void attach(Activity activity, FrameLayout contentLayout) {
        host = contentLayout;
        if (!observing) {
            observing = true;
            NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> refresh(), ZgCloudPolicies.appPolicyChanged);
        }
        ZgCloudPolicies.ensureLoaded();
        // After onCreate has added the drawer and the other content, so the block lands on top of them.
        contentLayout.post(ZgAppUpdate::refresh);
    }

    /** UI thread, from LaunchActivity.onResume. */
    public static void onResume() {
        ZgCloudPolicies.onAppForeground();
        org.telegram.zenlygram.customer.ZgAnalytics.onForeground();
        org.telegram.zenlygram.cloud.ZgBackend.onAppResumed(); // the entitlement of linked accounts, Cloud Tasks sync
        refresh();
        AndroidUtilities.runOnUIThread(ZgAppUpdate::maybeRemind, 1500);
        org.telegram.zenlygram.customer.ZgConsentWall.schedule(); // the consent wall, never above the update block
    }

    /** UI thread, from LaunchActivity.onPause. */
    public static void onPause() {
        ZgCloudPolicies.onAppBackground();
        org.telegram.zenlygram.customer.ZgAnalytics.onBackground();
        org.telegram.zenlygram.cloud.ZgBackend.onAppPaused(); // one more Cloud Tasks drain
    }

    /** True when the block consumed Back. */
    public static boolean onBackPressed() {
        return isBlocking();
    }

    private static void refresh() {
        final ZgAppUpdateDecision decision = ZgCloudPolicies.decideAppUpdate();
        if (decision.kind == ZgAppUpdateDecision.Kind.BLOCK) {
            showBlock();
        } else {
            hideBlock();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void showBlock() {
        if (host == null) {
            return;
        }
        if (blockView == null || blockView.getContext() != host.getContext()) {
            blockView = createBlockView(host.getContext());
        }
        if (blockView.getParent() != host) {
            if (blockView.getParent() instanceof ViewGroup) {
                ((ViewGroup) blockView.getParent()).removeView(blockView);
            }
            host.addView(blockView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        blockView.bringToFront();
        AndroidUtilities.hideKeyboard(host);
    }

    private static void hideBlock() {
        if (blockView != null && blockView.getParent() instanceof ViewGroup) {
            ((ViewGroup) blockView.getParent()).removeView(blockView);
        }
    }

    private static View createBlockView(Context context) {
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.setClickable(true); // swallows every touch: nothing behind is reachable
        root.setFocusable(true);
        root.setFitsSystemWindows(true);
        root.setTag("zg.update.block");

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        column.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(48), AndroidUtilities.dp(32), AndroidUtilities.dp(32));
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final android.widget.ImageView logo = new android.widget.ImageView(context);
        logo.setImageResource(R.drawable.zg_glyph);
        logo.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        column.addView(logo, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 24));

        final TextView title = text(context, getString(R.string.ZGC_AppUpdate_Required_Title), 22, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        column.addView(text(context, getString(R.string.ZGC_AppUpdate_Required_Text), 16, Theme.key_windowBackgroundWhiteGrayText),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 28));

        final TextView button = new TextView(context);
        button.setText(getString(fromPlay() ? R.string.ZGC_AppUpdate_Required_Button : R.string.ZGC_AppUpdate_Required_Button_Direct));
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        button.setOnClickListener(v -> openStore(v.getContext()));
        button.setTag("zg.update.store");
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 0, 0, 12));

        final TextView help = text(context, getString(R.string.ZGC_AppUpdate_Required_Help), 15, Theme.key_windowBackgroundWhiteBlueText);
        help.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        help.setOnClickListener(v -> openUrl(v.getContext(), HELP_URL));
        column.addView(help, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        column.addView(text(context, getString(fromPlay() ? R.string.ZGC_AppUpdate_Required_DeviceNote : R.string.ZGC_AppUpdate_Required_DeviceNote_Direct), 13, Theme.key_windowBackgroundWhiteGrayText),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return root;
    }

    private static TextView text(Context context, CharSequence value, int sizeDp, int colorKey) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTextColor(Theme.getColor(colorKey));
        return view;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void maybeRemind() {
        if (remindedThisLaunch || isBlocking() || VoIPService.getSharedInstance() != null) {
            return;
        }
        final ZgAppUpdateDecision decision = ZgCloudPolicies.decideAppUpdate();
        if (decision.kind != ZgAppUpdateDecision.Kind.REMIND || TextUtils.isEmpty(decision.latest)) {
            return;
        }
        final SharedPreferences prefs = ZgCloudPoliciesPrefs.get();
        if (prefs == null || decision.latest.equals(prefs.getString(KEY_REMINDED, null))) {
            return;
        }
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null || fragment.getVisibleDialog() != null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(getString(R.string.ZGC_AppUpdate_Available_Title))
            .setMessage(formatString(fromPlay() ? R.string.ZGC_AppUpdate_Available_Text : R.string.ZGC_AppUpdate_Available_Text_Direct, decision.latest))
            .setPositiveButton(getString(R.string.ZGC_AppUpdate_Available_Update), (d, w) -> openStore(activity))
            .setNegativeButton(getString(R.string.ZGC_AppUpdate_Available_Later), null)
            .create();
        fragment.showDialog(dialog);
        remindedThisLaunch = true;
        prefs.edit().putString(KEY_REMINDED, decision.latest).apply();
    }

    /** uz / ru / en from the app language, as the website's pages are; unreadable is uz. */
    private static String appLocale() {
        try {
            return org.telegram.zenlygram.customer.ZgCustomer.defaultLocale();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Whether this APK is the Google Play build: it updates through Play, a side-load build through the website. */
    private static boolean fromPlay() {
        return org.telegram.zenlygram.pro.ZgPro.isPlayBuild();
    }

    /**
     * Where the update is: in the Play build the Google Play page of this package (the Play app first, the https
     * store page otherwise); in a side-load build the website's download page.
     */
    public static void openStore(Context context) {
        if (!fromPlay()) {
            openUrl(context, ZgConfig.downloadUrl(appLocale()));
            return;
        }
        final String packageName = context.getPackageName();
        try {
            final Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
            market.setPackage("com.android.vending");
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(market);
            return;
        } catch (ActivityNotFoundException ignore) {
        } catch (Exception e) {
            FileLog.e(e);
        }
        openUrl(context, "https://play.google.com/store/apps/details?id=" + packageName);
    }

    private static void openUrl(Context context, String url) {
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Package-private access to the cloud preferences file. */
    static final class ZgCloudPoliciesPrefs {
        static SharedPreferences get() {
            final Context context = org.telegram.messenger.ApplicationLoader.applicationContext;
            return context == null ? null : context.getSharedPreferences("zenlygram_cloud", Context.MODE_PRIVATE);
        }
    }
}

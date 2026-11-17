/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;

/**
 * The Cloud / On This Device switch under the navigation bar (iOS TasksScopeBarView): two equal segments, Cloud first.
 * After the device tasks were copied to Cloud the device segment reads "Device archive". The selected segment is
 * filled and bold, so the selection does not rely on color alone. Left to right in every language: the app does not
 * mirror layouts, like Telegram's own tabs.
 */
public class ZgTasksScopeView extends LinearLayout implements Theme.Colorable {

    public interface Delegate {
        /** The user tapped a segment (also the selected one). */
        void onSelectScope(ZgTasksScope scope);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView cloudView;
    private final TextView deviceView;
    private ZgTasksScope selected = ZgTasksScope.DEVICE;
    private Delegate delegate;

    public ZgTasksScopeView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(HORIZONTAL);
        setPadding(dp(6), dp(6), dp(6), dp(6));
        setContentDescription(LocaleController.getString(R.string.ZenlyCloudTasksScope));
        cloudView = segment(context, ZgTasksScope.CLOUD);
        cloudView.setText(LocaleController.getString(R.string.ZG_tasks_scope_cloud));
        deviceView = segment(context, ZgTasksScope.DEVICE);
        updateColors();
    }

    private TextView segment(Context context, final ZgTasksScope scope) {
        final TextView view = new TextView(context) {
            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(selected == scope);
            }
        };
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setOnClickListener(v -> {
            if (delegate != null) {
                delegate.onSelectScope(scope);
            }
        });
        addView(view, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        return view;
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** {@code archive}: the device board is the read-only archive of its Cloud copy. */
    public void set(ZgTasksScope selected, boolean archive) {
        this.selected = selected;
        deviceView.setText(LocaleController.getString(archive ? R.string.ZG_tasks_archive_segment : R.string.ZG_tasks_scope_device));
        updateColors();
    }

    @Override
    public void updateColors() {
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        apply(cloudView, selected == ZgTasksScope.CLOUD, black);
        apply(deviceView, selected == ZgTasksScope.DEVICE, black);
    }

    private void apply(TextView view, boolean isSelected, int black) {
        view.setSelected(isSelected);
        view.setTypeface(isSelected ? AndroidUtilities.bold() : null);
        view.setTextColor(isSelected ? black : Theme.multAlpha(black, 0.6f));
        view.setBackground(isSelected
            ? Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider))
            : Theme.createSelectorDrawable(Theme.multAlpha(black, 0.06f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
    }
}

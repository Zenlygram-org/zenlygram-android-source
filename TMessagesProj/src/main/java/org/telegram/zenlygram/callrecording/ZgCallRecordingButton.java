/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.callrecording;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.DarkAlertDialog;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * The call screen's Record button and recording badge (iOS CallControllerNodeV2 record button + "REC" badge): "⏺
 * Record" while the 1:1 call can be recorded, a red "● REC 1:23" while it is. A tap goes through
 * {@link ZgCallRecordingConsentFlow}: the first time, the consent text has to be accepted.
 */
public final class ZgCallRecordingButton extends TextView {

    private final Activity activity;
    private final ZgCallRecordingConsentFlow flow = new ZgCallRecordingConsentFlow();
    private final GradientDrawable background = new GradientDrawable();
    private final Runnable tick = this::update;
    private final Runnable listener = this::update;

    /** VoIPFragment.createView: adds the button to the call screen's top-left corner, next to Back. */
    public static ZgCallRecordingButton attach(Activity activity, FrameLayout parent) {
        if (!ZgCallRecorder.isAvailable()) {
            return null;
        }
        final ZgCallRecordingButton button = new ZgCallRecordingButton(activity);
        parent.addView(button, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.TOP | Gravity.LEFT, 60, 12, 0, 0));
        return button;
    }

    /** VoIPFragment.setInsets. */
    public static void applyTopInset(ZgCallRecordingButton button, int insetTop) {
        if (button != null && button.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) button.getLayoutParams()).topMargin = dp(12) + insetTop;
            button.requestLayout();
        }
    }

    private ZgCallRecordingButton(Activity activity) {
        super(activity);
        this.activity = activity;
        setTextColor(Color.WHITE);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        setTypeface(AndroidUtilities.bold());
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setPadding(dp(12), 0, dp(12), 0);
        background.setCornerRadius(dp(16));
        setBackground(background);
        setOnClickListener(v -> onTap());
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ZgCallRecorder.addListener(listener);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ZgCallRecorder.removeListener(listener);
        removeCallbacks(tick);
    }

    private void update() {
        removeCallbacks(tick);
        final boolean recording = ZgCallRecorder.isRecording();
        final boolean canStart = ZgCallRecorder.canStart();
        setVisibility(recording || canStart && !flow.isCallEnded() ? View.VISIBLE : View.GONE);
        if (recording) {
            background.setColor(0xE6E53935);
            setText("● " + getString(R.string.ZG_CallRecording_Indicator_Short) + " " + ZgCallRecordingFormat.formatDuration(ZgCallRecorder.elapsedMs() / 1000.0));
            setContentDescription(getString(R.string.ZG_CallRecording_Indicator));
        } else {
            background.setColor(0x4DFFFFFF);
            setText("⏺ " + getString(R.string.ZG_CallRecording_Button));
            setContentDescription(getString(R.string.ZG_CallRecording_Button));
        }
        if (isAttachedToWindow()) {
            postDelayed(tick, recording ? 500 : 1000);
        }
    }

    /** The call is over: nothing can be started from this screen any more. */
    public void onCallEnded() {
        flow.callEnded();
        update();
    }

    private void onTap() {
        switch (flow.tap(ZgCallRecorder.isRecording(), ZgCallRecorder.canStart(), ZgSettings.callRecordingConsentAccepted.get())) {
            case STOP:
                ZgCallRecorder.stop();
                break;
            case START:
                start();
                break;
            case ASK_CONSENT:
                askConsent(flow.pendingTicket());
                break;
            default:
                break;
        }
        update();
    }

    private void askConsent(int ticket) {
        final boolean[] accepted = {false};
        final AlertDialog dialog = new DarkAlertDialog.Builder(activity)
            .setTitle(getString(R.string.ZG_CallRecording_Consent_Title))
            .setMessage(getString(R.string.ZG_CallRecording_Consent_Text))
            .setPositiveButton(getString(R.string.ZG_CallRecording_Consent_Accept), (d, w) -> {
                accepted[0] = true;
                if (flow.accept(ticket, ZgCallRecorder.isRecording(), ZgCallRecorder.canStart())) {
                    ZgSettings.callRecordingConsentAccepted.set(true);
                    start();
                }
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .show();
        dialog.setOnDismissListener(d -> {
            if (!accepted[0]) {
                flow.promptClosed(ticket);
            }
            update();
        });
    }

    private void start() {
        switch (ZgCallRecorder.start()) {
            case NO_SPACE:
                ZgCallRecorder.showProblem(getString(R.string.ZG_CallRecording_Problem_NoSpace));
                break;
            case STORAGE:
                ZgCallRecorder.showProblem(getString(R.string.ZG_CallRecording_Problem_Lost));
                break;
            default:
                break;
        }
        update();
    }
}

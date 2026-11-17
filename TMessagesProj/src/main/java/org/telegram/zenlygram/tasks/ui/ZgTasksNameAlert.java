/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;

/**
 * The free-text name alert of lists (iOS sgTaskNameAlertController). Unlike Telegram's simple text alert it counts the
 * limit in characters as the user sees them (an emoji is one), never cuts a character in half, keeps Done disabled
 * until the name has something visible, and applies once even on a fast double tap (Done and the keyboard key).
 */
public final class ZgTasksNameAlert {

    public interface Apply {
        void apply(String name);
    }

    private ZgTasksNameAlert() {
    }

    public static void show(BaseFragment fragment, String title, String text, String placeholder, String value, int limit, Apply apply) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, rp);
        builder.setTitle(title);
        builder.setMessage(text);

        final EditTextBoldCursor editText = new EditTextBoldCursor(activity);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, rp));
        editText.setHint(placeholder);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setSingleLine(true);
        editText.setPadding(dp(16), dp(11), dp(16), dp(11));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, rp));
        editText.setCursorWidth(1.5f);
        editText.setBackground(Theme.createRoundRectDrawable(dp(22), Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, rp), 0.06f)));
        editText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            final String updated = TextUtils.concat(dest.subSequence(0, dstart), source.subSequence(start, end),
                dest.subSequence(dend, dest.length())).toString();
            final int count = ZgTasksTitlePolicy.graphemeCount(updated);
            if (count <= limit || count < ZgTasksTitlePolicy.graphemeCount(dest)) {
                return null;
            }
            AndroidUtilities.shakeViewSpring(editText, -3);
            return dest.subSequence(dstart, dend);
        }});
        if (value != null) {
            editText.setText(value);
        }

        final LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 9));
        builder.setView(container);
        builder.setWidth(Math.min(dp(320), AndroidUtilities.displaySize.x * 85 / 100));

        final boolean[] applied = {false};
        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable doApply = () -> {
            if (applied[0]) {
                return;
            }
            final String trimmed = ZgTasksTitlePolicy.trim(editText.getText());
            if (!ZgTasksTitlePolicy.containsVisible(trimmed)) {
                return;
            }
            applied[0] = true;
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            // Dictation or a programmatic value can bypass the filter: trim first, then clamp.
            apply.apply(ZgTasksTitlePolicy.clampGraphemes(trimmed, limit));
        };
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (d, which) -> doApply.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> d.dismiss());
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doApply.run();
                return true;
            }
            return false;
        });

        dialog[0] = builder.create();
        dialog[0].setDismissDialogByButtons(false);
        dialog[0].setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
            updateDone(dialog[0], editText);
        });
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateDone(dialog[0], editText);
            }
        });
        // showDialog replaces the dialog's own dismiss listener, so the cleanup goes through it.
        AndroidUtilities.requestAdjustNothing(activity, fragment.getClassGuid());
        final boolean shown = fragment.showDialog(dialog[0], d -> {
            AndroidUtilities.hideKeyboard(editText);
            AndroidUtilities.requestAdjustResize(activity, fragment.getClassGuid());
        }) != null;
        if (!shown) {
            AndroidUtilities.requestAdjustResize(activity, fragment.getClassGuid());
            return;
        }
        editText.setSelection(editText.getText().length());
    }

    private static void updateDone(AlertDialog dialog, EditTextBoldCursor editText) {
        if (dialog == null) {
            return;
        }
        final View done = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (done != null) {
            final boolean enabled = ZgTasksTitlePolicy.containsVisible(ZgTasksTitlePolicy.trim(editText.getText()));
            done.setEnabled(enabled);
            done.setAlpha(enabled ? 1f : 0.5f);
        }
    }
}

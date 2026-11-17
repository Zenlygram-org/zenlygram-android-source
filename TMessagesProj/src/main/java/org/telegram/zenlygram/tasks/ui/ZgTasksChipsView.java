/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.tasks.model.ZgTasksBoardState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The filter chips of the board (iOS TasksBoardChipsView): "All N", one chip per list with its open-task count (shown
 * when it is above 0), then "+ New List" while the board takes changes. The selected chip has the accent fill; the row
 * scrolls to it.
 */
public class ZgTasksChipsView extends HorizontalScrollView implements Theme.Colorable {

    public interface Delegate {
        void onSelectFilter(String listId);

        void onNewList();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout container;
    private final ArrayList<ZgTasksBoardState.Chip> chips = new ArrayList<>();
    private Delegate delegate;
    private boolean canAdd = true;
    private String scrolledTo = "\u0000";

    public ZgTasksChipsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        setClipToPadding(false);
        setPadding(dp(14), dp(6), dp(14), dp(6));
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        // Left to right in every language: the app does not mirror layouts (supportsRtl="false"), as Telegram's tabs.
        addView(container, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** {@code canAdd}: the board takes a new list ("+ New List" is shown); a read-only board does not. */
    public void setChips(List<ZgTasksBoardState.Chip> newChips, boolean canAdd) {
        if (this.canAdd == canAdd && sameAs(newChips)) {
            return;
        }
        this.canAdd = canAdd;
        chips.clear();
        chips.addAll(newChips);
        rebuild();
    }

    private boolean sameAs(List<ZgTasksBoardState.Chip> other) {
        if (other.size() != chips.size()) {
            return false;
        }
        for (int i = 0; i < other.size(); i++) {
            final ZgTasksBoardState.Chip a = chips.get(i);
            final ZgTasksBoardState.Chip b = other.get(i);
            if (!Objects.equals(a.listId, b.listId) || !Objects.equals(a.title, b.title) || a.inbox != b.inbox || a.openCount != b.openCount || a.selected != b.selected) {
                return false;
            }
        }
        return true;
    }

    private void rebuild() {
        container.removeAllViews();
        View selectedView = null;
        String selectedId = null;
        for (ZgTasksBoardState.Chip chip : chips) {
            final String title = chip.listId == null ? LocaleController.getString(R.string.ZG_tasks_filter_all)
                : chip.inbox ? LocaleController.getString(R.string.ZG_tasks_inbox_title) : chip.title;
            final TextView view = makeChip(title, chip.listId == null || chip.openCount > 0 ? chip.openCount : -1, chip.selected);
            view.setContentDescription(title + ", " + ZgTasksText.openCount(chip.openCount));
            view.setSelected(chip.selected);
            final String listId = chip.listId;
            view.setOnClickListener(v -> {
                if (delegate != null) {
                    delegate.onSelectFilter(listId);
                }
            });
            container.addView(view, chipParams());
            if (chip.selected) {
                selectedView = view;
                selectedId = chip.listId == null ? "" : chip.listId;
            }
        }
        if (canAdd) {
            final TextView add = makeChip("+ " + LocaleController.getString(R.string.ZG_tasks_filter_newList), -1, false);
            add.setOnClickListener(v -> {
                if (delegate != null) {
                    delegate.onNewList();
                }
            });
            container.addView(add, chipParams());
        }
        if (selectedView != null && !Objects.equals(selectedId, scrolledTo)) {
            scrolledTo = selectedId;
            final View target = selectedView;
            post(() -> {
                final int left = target.getLeft() - dp(24);
                smoothScrollTo(Math.max(0, left), 0);
            });
        }
    }

    private LinearLayout.LayoutParams chipParams() {
        final LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private TextView makeChip(CharSequence title, int count, boolean selected) {
        final TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMaxWidth(dp(220));
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setTag(selected ? Boolean.TRUE : Boolean.FALSE);
        apply(view, title, count, selected);
        return view;
    }

    private void apply(TextView view, CharSequence title, int count, boolean selected) {
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int color = selected ? accent : text;
        final SpannableStringBuilder sb = new SpannableStringBuilder(title);
        if (count >= 0) {
            final int start = sb.length();
            sb.append("  ").append(String.valueOf(count));
            sb.setSpan(new ForegroundColorSpan(Theme.multAlpha(color, 0.55f)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(sb);
        view.setTextColor(color);
        view.setBackground(Theme.createRoundRectDrawable(dp(16), selected ? Theme.multAlpha(accent, 0.12f) : Theme.multAlpha(text, 0.06f)));
    }

    @Override
    public void updateColors() {
        rebuild();
    }

    /** Whether this row would scroll that way (the main tabs must not slide while the finger is on it). */
    public boolean canScrollHorizontallyBy(boolean forward) {
        return canScrollHorizontally(forward ? 1 : -1);
    }
}

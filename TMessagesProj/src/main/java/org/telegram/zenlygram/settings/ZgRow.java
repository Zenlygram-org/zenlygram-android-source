/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * One entry of Settings > Zenlygram, as declared in {@link ZgSettingsSpec}: a header, a row or a notice.
 *
 * <p>Everything a row shows is evaluated when the list is built ({@link Text}, {@link Condition}), so language and
 * setting changes show up on the next refresh. A row appears only when the {@link ZgFeatures} it needs is ported
 * (see {@link #feature(ZgFeatures)}).
 */
public final class ZgRow {

    public enum Kind {
        /** Section header (HeaderCell). Every section has one, with id {@code section * 100}. */
        HEADER,
        /** Smaller black header inside a section, e.g. above a slider. */
        SUBHEADER,
        /** Rich row with a switch (ZgSettingRowCell). */
        TOGGLE,
        /** Rich row that opens something, with an optional value (ZgSettingRowCell). */
        DISCLOSURE,
        /** Rich row that only shows information; not clickable (ZgSettingRowCell). */
        INFO,
        /** Plain row with the current choice on the right; opens a single-choice dialog (TextCell). */
        SELECTOR,
        /** 0-100 % slider (SlideIntChooseView). Not searched. */
        SLIDER,
        /** Plain switch without icon or description (TextCheckCell). */
        CHECK,
        /** Grey footnote between cards (TextInfoPrivacyCell); **bold** markup is applied. */
        NOTICE;

        /** Headers and notices only describe rows; a section with nothing but these is not shown. */
        public boolean isStructural() {
            return this == HEADER || this == SUBHEADER || this == NOTICE;
        }

        public boolean isHeader() {
            return this == HEADER || this == SUBHEADER;
        }
    }

    /** Text read when the list is built (current language). */
    public interface Text {
        CharSequence get();
    }

    public interface Condition {
        boolean test();
    }

    /** What a switch shows and what a tap writes. */
    public interface BoolValue {
        boolean get();

        void set(boolean value);
    }

    /** A tap on a live row. The screen refreshes the list afterwards. */
    public interface Action {
        void run(ZenlygramSettingsActivity screen, ZgRow row);
    }

    /** One choice of a SELECTOR row. */
    public static final class Option {
        public final String value;
        /** Shown in the dialog. */
        public final Text label;
        /** Shown on the row while this choice is selected; null = {@link #label}. */
        public final Text shortLabel;

        public Option(String value, Text label) {
            this(value, label, null);
        }

        public Option(String value, Text label, Text shortLabel) {
            this.value = value;
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    public final int id;
    public final Kind kind;
    ZgSection section;

    Text title;
    Text description;
    Text value;
    String iosSymbol;
    int iconRes;
    ZgSettingsIcons.Tile tile = ZgSettingsIcons.Tile.GRAY;
    String newBadgeKey;
    /** Hub page rows: the NEW pill rolled up from the page (iOS pageHasNew); never retired by a tap on the row itself. */
    Condition rollUpNew;
    boolean restart;
    boolean controlled;
    /** False: the search never matches this row (iOS: colour previews, like sliders). */
    boolean searchable = true;
    ZgFeatures feature;
    Condition visibleWhen;
    Condition availableWhen;
    Condition enabledWhen;
    /** Shown when a row that {@link #enabledWhen} keeps disabled is tapped: which setting to change first. */
    Text disabledHint;
    Action action;
    /** Runs right after a tap changed the value of a TOGGLE / CHECK / SELECTOR row (e.g. to tell open screens). */
    Action onChanged;
    BoolValue boolValue;
    ZgSettings.ChoiceSetting choice;
    Option[] options;
    ZgSettings.IntSetting slider;
    private SlideIntChooseView.Options sliderOptions;

    private ZgRow(int id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Kinds
    // ---------------------------------------------------------------------------------------------------------

    static ZgRow header(int id, Text title) {
        return new ZgRow(id, Kind.HEADER).title(title);
    }

    static ZgRow subheader(int id, Text title) {
        return new ZgRow(id, Kind.SUBHEADER).title(title);
    }

    static ZgRow toggle(int id, Text title, BoolValue value) {
        final ZgRow row = new ZgRow(id, Kind.TOGGLE).title(title);
        row.boolValue = value;
        return row;
    }

    static ZgRow disclosure(int id, Text title) {
        return new ZgRow(id, Kind.DISCLOSURE).title(title);
    }

    static ZgRow info(int id, Text title) {
        return new ZgRow(id, Kind.INFO).title(title);
    }

    static ZgRow selector(int id, Text title, ZgSettings.ChoiceSetting setting, Option... options) {
        final ZgRow row = new ZgRow(id, Kind.SELECTOR).title(title);
        row.choice = setting;
        row.options = options;
        return row;
    }

    static ZgRow slider(int id, ZgSettings.IntSetting setting) {
        final ZgRow row = new ZgRow(id, Kind.SLIDER);
        row.slider = setting;
        return row;
    }

    static ZgRow check(int id, Text title, BoolValue value) {
        final ZgRow row = new ZgRow(id, Kind.CHECK).title(title);
        row.boolValue = value;
        return row;
    }

    static ZgRow notice(int id, Text text) {
        return new ZgRow(id, Kind.NOTICE).title(text);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Properties
    // ---------------------------------------------------------------------------------------------------------

    ZgRow title(Text title) {
        this.title = title;
        return this;
    }

    ZgRow description(Text description) {
        this.description = description;
        return this;
    }

    /** The value on the right of a DISCLOSURE / INFO row. SELECTOR rows show the label of the current option. */
    ZgRow value(Text value) {
        this.value = value;
        return this;
    }

    /** Tile and glyph. {@code iosSymbol} is the SF Symbol iOS draws, kept for reference. */
    ZgRow icon(String iosSymbol, int iconRes, ZgSettingsIcons.Tile tile) {
        this.iosSymbol = iosSymbol;
        this.iconRes = iconRes;
        this.tile = tile;
        return this;
    }

    /** NEW pill until the row is used or 30 days pass (iOS SGNewBadgeTracker key). */
    ZgRow newBadge(String key) {
        this.newBadgeKey = key;
        return this;
    }

    /** "(restart required)" is appended to the description and a change asks for a restart. */
    ZgRow rollUpNew(Condition condition) {
        this.rollUpNew = condition;
        return this;
    }

    ZgRow restart() {
        this.restart = true;
        return this;
    }

    /** A tap never writes {@link #boolValue} directly: only {@link #action} (the feature's flow) may change it. */
    ZgRow controlled() {
        this.controlled = true;
        return this;
    }

    /** The search never matches this row (previews that only illustrate a slider). */
    ZgRow notSearchable() {
        this.searchable = false;
        return this;
    }

    /** The feature this row needs; by default the section's. */
    ZgRow feature(ZgFeatures feature) {
        this.feature = feature;
        return this;
    }

    /** State condition: false hides the row even in preview mode (e.g. the other branch of the vault rows). */
    ZgRow visibleWhen(Condition condition) {
        this.visibleWhen = condition;
        return this;
    }

    /** Environment condition: false hides the row in normal use, but preview mode still shows it. */
    ZgRow availableWhen(Condition condition) {
        this.availableWhen = condition;
        return this;
    }

    /** False shows the row disabled (e.g. tab options while the tab bar is hidden). */
    ZgRow enabledWhen(Condition condition) {
        this.enabledWhen = condition;
        return this;
    }

    /**
     * Disabled by another setting ({@link #enabledWhen}): the row stays dimmed, and a tap says which setting to change
     * first instead of doing nothing.
     */
    ZgRow enabledWhen(Condition condition, Text disabledHint) {
        this.enabledWhen = condition;
        this.disabledHint = disabledHint;
        return this;
    }

    ZgRow action(Action action) {
        this.action = action;
        return this;
    }

    /** Called after a plain tap stored a new value (switches and selectors; not used together with {@link #action}). */
    ZgRow onChanged(Action onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    // ---------------------------------------------------------------------------------------------------------

    public ZgSection getSection() {
        return section;
    }

    /** The feature this row needs, or null if none was declared (such a row is never shown). */
    public ZgFeatures getFeature() {
        return feature != null ? feature : section != null ? section.feature : null;
    }

    public String getNewBadgeKey() {
        return newBadgeKey;
    }

    public String getIosSymbol() {
        return iosSymbol;
    }

    /** Label of the current choice of a SELECTOR row. */
    CharSequence getChoiceLabel() {
        if (choice == null || options == null) {
            return null;
        }
        final String current = choice.get();
        for (Option option : options) {
            if (option.value.equals(current)) {
                return (option.shortLabel != null ? option.shortLabel : option.label).get();
            }
        }
        return null;
    }

    /** Kept per row, so the list sees the same slider item on every refresh. */
    SlideIntChooseView.Options getSliderOptions() {
        if (sliderOptions == null) {
            sliderOptions = SlideIntChooseView.Options.make(1, slider.min, slider.max, v -> v + "%");
        }
        return sliderOptions;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers for the spec
    // ---------------------------------------------------------------------------------------------------------

    static Text res(int resId) {
        return () -> LocaleController.getString(resId);
    }

    /** A string with %1$s, %2$s... filled with other strings. */
    static Text format(int resId, Text... args) {
        return () -> {
            final Object[] values = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                final CharSequence v = args[i].get();
                values[i] = v == null ? "" : v.toString();
            }
            return LocaleController.formatString(resId, values);
        };
    }

    static Text literal(String text) {
        return () -> text;
    }

    static Text concat(Text first, Text second) {
        return () -> {
            final CharSequence a = first.get();
            final CharSequence b = second.get();
            return a == null ? b : b == null ? a : a.toString() + b;
        };
    }

    static BoolValue bool(ZgSettings.BoolSetting setting) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return setting.get();
            }

            @Override
            public void set(boolean value) {
                setting.set(value);
            }
        };
    }

    /** {@code value}, then {@code after} runs once the new value is written (e.g. to re-lay out open chats). */
    static BoolValue afterSet(BoolValue value, Runnable after) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return value.get();
            }

            @Override
            public void set(boolean newValue) {
                value.set(newValue);
                after.run();
            }
        };
    }

    /** For disable* / hide* keys: the row shows the opposite of the stored value. */
    static BoolValue inverted(ZgSettings.BoolSetting setting) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return !setting.get();
            }

            @Override
            public void set(boolean value) {
                setting.set(!value);
            }
        };
    }

    /** On while the choice is {@code on}; switching writes {@code on} or {@code off}. */
    static BoolValue choiceIs(ZgSettings.ChoiceSetting setting, String on, String off) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return setting.is(on);
            }

            @Override
            public void set(boolean value) {
                setting.set(value ? on : off);
            }
        };
    }

    /**
     * On while the choice is anything but {@code off}; switching writes {@code on} or {@code off}
     * (Compact Message Preview: on while chatListLines != "3").
     */
    static BoolValue choiceIsNot(ZgSettings.ChoiceSetting setting, String off, String on) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return !setting.is(off);
            }

            @Override
            public void set(boolean value) {
                setting.set(value ? on : off);
            }
        };
    }

    /**
     * A setting that iOS keeps in Telegram's own stores (Contacts / Calls / Tasks tab, folders at the bottom, VoIP over
     * TCP). It is not a Zenlygram preference on Android: the feature that ports the row binds Telegram's setting
     * instead. Until then the row shows {@code placeholder} and a tap does nothing.
     */
    static BoolValue telegramOwned(boolean placeholder) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return placeholder;
            }

            @Override
            public void set(boolean value) {
            }
        };
    }

    /**
     * ZENLYGRAM: a device-wide Telegram option kept in MessagesController.getGlobalMainSettings() ("mainconfig"),
     * e.g. "send_by_enter" (Chat Settings > Send by Enter). The row and Telegram's own screen show one value.
     */
    static BoolValue telegramGlobalBool(String key, boolean defaultValue) {
        return new BoolValue() {
            @Override
            public boolean get() {
                return org.telegram.messenger.MessagesController.getGlobalMainSettings().getBoolean(key, defaultValue);
            }

            @Override
            public void set(boolean value) {
                org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putBoolean(key, value).apply();
            }
        };
    }
}

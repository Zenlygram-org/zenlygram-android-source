package org.telegram.ui;

public interface MainTabsActivityController {
    void setTabsVisible(boolean visible);

    /** ZENLYGRAM: the account menu, opened from the chat list's ⋮ menu while the tab bar is hidden. */
    default boolean zgOpenAccountSelector(android.view.View anchor) {
        return false;
    }
}

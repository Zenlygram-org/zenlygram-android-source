package org.telegram.messenger.zenlygram.pro;

/**
 * Pure choice of the Pro screen's account actions (link, trial, refresh) and which one is the
 * filled main button. Free mode never offers a trial (a new purchase-like start); linking and
 * refreshing stay available so existing paid rights and restores keep working.
 */
public final class ProActions {
    public enum Action { LINK, TRIAL, REFRESH, NONE }

    public final boolean link, trial, refresh;
    public final Action main;

    private ProActions(boolean link, boolean trial, boolean refresh) {
        this.link = link;
        this.trial = trial;
        this.refresh = refresh;
        this.main = link ? Action.LINK : trial ? Action.TRIAL : refresh ? Action.REFRESH : Action.NONE;
    }

    public static ProActions of(boolean linked, ProPolicy.State state, ProPolicy.Mode mode) {
        boolean link = !linked || state == ProPolicy.State.RECONNECT;
        boolean trial = ProMonetization.allowsNewPurchase(mode) && linked && state != ProPolicy.State.ACTIVE;
        return new ProActions(link, trial, linked);
    }
}

package com.hanchen.veritycustomapi.autonomy;

/** Standing consent comes from enabling autonomy, not per-action grant timers. */
public final class AutonomyPermissions {
    private AutonomyPermissions() {}
    public static boolean commandedWait(boolean suspended, long remainingWaitTicks, boolean playerHold) {
        return suspended || remainingWaitTicks > 0 || playerHold;
    }
    public static boolean canEdit(boolean enabled, boolean suspended, boolean worldToolsEnabled) {
        return enabled && !suspended && worldToolsEnabled;
    }
}

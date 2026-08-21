package com.sameerali.appawake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the user's normal screen timeout if Smart Guard's monitoring heartbeat
 * disappears unexpectedly while the timeout lease is active.
 *
 * <p>By: Sameer Ali | Contact: sameer43786@gmail.com</p>
 */
public final class TimeoutWatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TimeoutLeaseGuard.restoreIfStale(context);
    }
}

package com.wesbos.homehq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Lifecycle only; alarms use an Activity PendingIntent to survive F10 process eviction. */
public class MorningReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent intent) {
    String action = intent.getAction();
    ScreenSchedule.lifecycle(c, action);
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)) MorningReturn.resetAfterBoot(c);
    else if (Intent.ACTION_TIME_CHANGED.equals(action)
        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) MorningReturn.restore(c);
  }
}

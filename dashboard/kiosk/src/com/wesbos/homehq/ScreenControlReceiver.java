package com.wesbos.homehq;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ADB administration, guarded by the sender's system DUMP permission in the manifest. */
public class ScreenControlReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent intent) {
    String command = intent.getStringExtra("command");
    try {
      if ("enable".equals(command)) ScreenSchedule.enable(c);
      else if ("disable".equals(command)) ScreenSchedule.disable(c);
      else if ("test".equals(command)) ScreenSchedule.test(c);
      else if ("cancel-test".equals(command)) ScreenSchedule.cancelTest(c);
      else if ("pair-dashboard".equals(command)) ScreenSettingsBridge.pair(c, intent.getStringExtra("origin"));
      else if ("unpair-dashboard".equals(command)) ScreenSettingsBridge.unpair(c);
      else if ("remove-admin".equals(command)) {
        ScreenSchedule.disable(c);
        ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE))
            .removeActiveAdmin(ScreenSchedule.admin(c));
      } else if (!"status".equals(command)) throw new IllegalArgumentException("Unknown command");
      setResultCode(0);
      setResultData(ScreenSchedule.status(c));
    } catch (RuntimeException e) {
      setResultCode(1);
      setResultData(e.toString());
    }
  }
}

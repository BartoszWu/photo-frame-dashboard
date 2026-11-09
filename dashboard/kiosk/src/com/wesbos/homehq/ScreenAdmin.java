package com.wesbos.homehq;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Requests only force-lock. No password, wipe, camera, or device-owner policies. */
public class ScreenAdmin extends DeviceAdminReceiver {
  @Override public void onDisabled(Context context, Intent intent) {
    ScreenSchedule.disable(context);
  }
}

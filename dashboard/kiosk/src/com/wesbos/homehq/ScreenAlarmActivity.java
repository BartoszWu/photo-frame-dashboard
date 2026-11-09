package com.wesbos.homehq;

import android.app.Activity;
import android.os.Bundle;

/** Direct Activity PendingIntent survives the F10's refusal to cold-start receivers. */
public class ScreenAlarmActivity extends Activity {
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      ScreenSchedule.fire(this, getIntent().getAction());
    } finally {
      finish();
    }
  }
}

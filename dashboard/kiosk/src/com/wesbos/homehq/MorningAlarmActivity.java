package com.wesbos.homehq;

import android.app.Activity;
import android.os.Bundle;

/** NoDisplay alarm entry point: vendor firmware refuses cold broadcast starts. */
public class MorningAlarmActivity extends Activity {
  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      MorningReturn.fire(this, getIntent().getAction());
    } finally {
      finish();
    }
  }
}

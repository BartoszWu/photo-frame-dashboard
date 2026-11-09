package com.wesbos.homehq;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.Date;
import java.util.TimeZone;

/** Opt-in F10/Android 6 feature. No service, polling, screen wake, or Frameo settings. */
final class MorningReturn {
  static final String TAG = "HomeHQMorning";
  static final String DAILY = "com.wesbos.homehq.MORNING_RETURN";
  static final String TEST = "com.wesbos.homehq.TEST_RETURN";

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("morning_return", Context.MODE_PRIVATE);
  }

  private static AlarmManager alarms(Context c) {
    return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
  }

  private static PendingIntent pending(Context c, String action) {
    return PendingIntent.getActivity(c, 0,
        new Intent(c, MorningAlarmActivity.class).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  static void requireSupported() {
    if (Build.VERSION.SDK_INT != 23) {
      throw new IllegalStateException("Morning return is scoped to Android 6 (API 23)");
    }
  }

  static void enable(Context c) {
    requireSupported();
    prefs(c).edit().putBoolean("enabled", true).commit();
    restore(c);
  }

  static void disable(Context c) {
    prefs(c).edit().clear().commit();
    alarms(c).cancel(pending(c, DAILY));
    alarms(c).cancel(pending(c, TEST));
    Log.i(TAG, "Disabled; daily and test alarms cancelled");
  }

  static void restore(Context c) {
    if (Build.VERSION.SDK_INT != 23 || !prefs(c).getBoolean("enabled", false)) return;
    long due = MorningTime.next(System.currentTimeMillis(), TimeZone.getDefault());
    prefs(c).edit().putLong("daily_due", due).commit();
    // RTC_WAKEUP wakes the CPU, not the display. No TURN_SCREEN_ON or wake lock.
    alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(c, DAILY));
    Log.i(TAG, "Daily alarm scheduled: " + new Date(due));
  }

  static void test(Context c) {
    requireSupported();
    long due = SystemClock.elapsedRealtime() + 120000;
    prefs(c).edit().putLong("test_due", due).commit();
    alarms(c).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
        due, pending(c, TEST));
    Log.i(TAG, "One-shot test in 120 seconds; daily opt-in unchanged");
  }

  static void resetAfterBoot(Context c) {
    // elapsedRealtime belongs to the previous boot; never replay a test after reboot.
    prefs(c).edit().remove("test_due").commit();
    alarms(c).cancel(pending(c, TEST));
    restore(c);
  }

  static void fire(Context c, String action) {
    if (Build.VERSION.SDK_INT != 23) return;
    boolean test = TEST.equals(action);
    if (!test && !DAILY.equals(action)) return;
    SharedPreferences p = prefs(c);
    long due = p.getLong(test ? "test_due" : "daily_due", 0);
    long now = test ? SystemClock.elapsedRealtime() : System.currentTimeMillis();
    boolean enabled = test || p.getBoolean("enabled", false);
    if (test) p.edit().remove("test_due").commit();
    else restore(c); // Rearm tomorrow even when this delivery is skipped or launch fails.
    if (!enabled || !MorningTime.timely(now, due)) {
      Log.w(TAG, "Ignored disabled, duplicate, early, or stale alarm");
      return;
    }
    PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
    if (power == null || !power.isInteractive()) {
      Log.i(TAG, "Skipped: display asleep; leaving sleep schedule alone");
      return;
    }
    // Prefer the Home alias already used by this frame, so the existing Home task is reused.
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setPackage(c.getPackageName());
    if (home.resolveActivity(c.getPackageManager()) == null) {
      home = new Intent(c, MainActivity.class);
    }
    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    try {
      c.startActivity(home);
      // A request isn't proof that firmware actually put the activity in the foreground.
      Log.i(TAG, (test ? "Test" : "Morning") + " launch requested; verify foreground");
    } catch (RuntimeException e) {
      Log.e(TAG, "Home HQ launch failed", e);
    }
  }

  static String status(Context c) {
    SharedPreferences p = prefs(c);
    return "enabled=" + p.getBoolean("enabled", false)
        + "; daily_due=" + p.getLong("daily_due", 0)
        + "; test_due_elapsed=" + p.getLong("test_due", 0);
  }
}

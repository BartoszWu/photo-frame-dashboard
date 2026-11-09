package com.wesbos.homehq;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.Date;
import java.util.TimeZone;

/** One owner of the F10 screen schedule; no Frameo, root, network, or resident service. */
final class ScreenSchedule {
  static final String TAG = "HomeHQScreen";
  static final String WAKE = "com.wesbos.homehq.SCREEN_WAKE";
  static final String SLEEP = "com.wesbos.homehq.SCREEN_SLEEP";
  static final String TEST_WAKE = "com.wesbos.homehq.SCREEN_TEST_WAKE";
  static final String TEST_SLEEP = "com.wesbos.homehq.SCREEN_TEST_SLEEP";

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("screen_schedule", Context.MODE_PRIVATE);
  }
  private static AlarmManager alarms(Context c) {
    return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
  }
  private static DevicePolicyManager policy(Context c) {
    return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
  }
  static ComponentName admin(Context c) { return new ComponentName(c, ScreenAdmin.class); }
  static boolean hasAdmin(Context c) {
    DevicePolicyManager p = policy(c);
    return p != null && p.isAdminActive(admin(c));
  }
  private static void requireSupported(Context c) {
    if (Build.VERSION.SDK_INT != 23) throw new IllegalStateException("F10 feature requires API 23");
    if (!hasAdmin(c)) throw new IllegalStateException("Activate Home HQ screen-lock admin first");
  }
  private static PendingIntent pending(Context c, String action) {
    return PendingIntent.getActivity(c, 0,
        new Intent(c, ScreenAlarmActivity.class).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }
  private static boolean enabled(Context c) {
    return prefs(c).getBoolean("enabled", false);
  }
  private static boolean trustedClock(Context c) {
    // This frame boots with a factory date before network time arrives.
    return System.currentTimeMillis() >= prefs(c).getLong("clock_floor", Long.MAX_VALUE);
  }

  static void enable(Context c) {
    requireSupported(c);
    cancelTest(c);
    // Replace the older, daily 07:01 foreground-only return, rather than running both.
    MorningReturn.disable(c);
    prefs(c).edit().putBoolean("enabled", true)
        .putLong("clock_floor", System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000).commit();
    reconcile(c);
  }

  static void disable(Context c) {
    prefs(c).edit().putBoolean("enabled", false).remove("wake_due").remove("sleep_due").commit();
    alarms(c).cancel(pending(c, WAKE));
    alarms(c).cancel(pending(c, SLEEP));
    cancelTest(c);
    Log.i(TAG, "Schedule disabled; all screen alarms cancelled");
  }

  static void restore(Context c) {
    if (Build.VERSION.SDK_INT != 23 || !enabled(c)) return;
    if (!trustedClock(c)) {
      Log.w(TAG, "Waiting for network time; factory clock is not trusted");
      return;
    }
    long now = System.currentTimeMillis();
    TimeZone zone = TimeZone.getDefault();
    long wake = ScreenScheduleTime.next(now, zone, true);
    long sleep = ScreenScheduleTime.next(now, zone, false);
    prefs(c).edit().putLong("wake_due", wake).putLong("sleep_due", sleep).commit();
    alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake, pending(c, WAKE));
    alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sleep, pending(c, SLEEP));
    Log.i(TAG, "Next wake " + new Date(wake) + "; sleep " + new Date(sleep));
  }

  static void reconcile(Context c) {
    restore(c);
    if (!enabled(c) || !trustedClock(c) || Build.VERSION.SDK_INT != 23) return;
    if (ScreenScheduleTime.awake(System.currentTimeMillis(), TimeZone.getDefault())) wake(c);
    else sleep(c);
  }

  static void homeStarted(Context c) {
    Context app = c.getApplicationContext();
    restore(app);
    // Default Home starts reliably even when the vendor drops BOOT_COMPLETED receivers.
    // A manual wake of an already running Home activity does not recreate it or apply this.
    if (enabled(app)) new Handler(Looper.getMainLooper()).postDelayed(() -> reconcile(app), 5000);
  }

  static void lifecycle(Context c, String action) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
      cancelTest(c); // Never replay a diagnostic cycle after reboot.
      reconcile(c);
    } else if (Intent.ACTION_TIME_CHANGED.equals(action)
        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
      reconcile(c);
    } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) restore(c);
  }

  static void test(Context c) {
    requireSupported(c);
    if (enabled(c)) throw new IllegalStateException("Disable the production schedule before testing");
    cancelTest(c);
    long now = System.currentTimeMillis();
    prefs(c).edit().putLong("test_sleep_due", now + 60000)
        .putLong("test_wake_due", now + 150000).commit();
    alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
        now + 60000, pending(c, TEST_SLEEP));
    alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
        now + 150000, pending(c, TEST_WAKE));
    Log.i(TAG, "Test: sleep in 60 seconds, wake in 150 seconds; production remains disabled");
  }

  static void cancelTest(Context c) {
    prefs(c).edit().remove("test_sleep_due").remove("test_wake_due").commit();
    alarms(c).cancel(pending(c, TEST_SLEEP));
    alarms(c).cancel(pending(c, TEST_WAKE));
  }

  static void fire(Context c, String action) {
    if (Build.VERSION.SDK_INT != 23) return;
    boolean test = TEST_WAKE.equals(action) || TEST_SLEEP.equals(action);
    boolean wake = WAKE.equals(action) || TEST_WAKE.equals(action);
    if (!test && !WAKE.equals(action) && !SLEEP.equals(action)) return;
    String key = (test ? "test_" : "") + (wake ? "wake_due" : "sleep_due");
    long due = prefs(c).getLong(key, 0);
    long now = System.currentTimeMillis();
    boolean permitted = test || (enabled(c) && trustedClock(c));
    if (test) prefs(c).edit().remove(key).commit();
    else restore(c); // Rearm before doing anything that could pause/kill this activity.
    if (!permitted || !ScreenScheduleTime.timely(now, due)) {
      Log.w(TAG, "Ignored disabled, duplicate, early, or stale alarm");
      return;
    }
    if (!test && wake != ScreenScheduleTime.awake(now, TimeZone.getDefault())) {
      Log.w(TAG, "Ignored alarm outside its current working window");
      return;
    }
    if (wake) wake(c); else sleep(c);
  }

  @SuppressWarnings("deprecation")
  private static void wake(Context c) {
    PowerManager p = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
    PowerManager.WakeLock lock = p.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
    // Android 6 API: hold briefly, rather than acquiring and releasing in the same instant.
    // Home's existing KEEP_SCREEN_ON takes over. Timeout bounds this even on a failed launch.
    lock.acquire(5000);
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setPackage(c.getPackageName());
    if (home.resolveActivity(c.getPackageManager()) == null) home = new Intent(c, MainActivity.class);
    c.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    record(c, "wake_requested");
  }

  private static void sleep(Context c) {
    if (!hasAdmin(c)) {
      Log.e(TAG, "Cannot turn screen off: screen-lock admin is inactive");
      return;
    }
    policy(c).lockNow();
    record(c, "sleep_requested");
  }
  private static void record(Context c, String action) {
    prefs(c).edit().putString("last_request", action)
        .putLong("last_request_at", System.currentTimeMillis()).commit();
    Log.i(TAG, action + "; verify actual display state separately");
  }

  static String status(Context c) {
    SharedPreferences p = prefs(c);
    return "enabled=" + enabled(c) + "; admin=" + hasAdmin(c)
        + "; policy=Mon-Fri_07:00-16:00; zone=" + TimeZone.getDefault().getID()
        + "; wake_due=" + p.getLong("wake_due", 0) + "; sleep_due=" + p.getLong("sleep_due", 0)
        + "; test_sleep_due=" + p.getLong("test_sleep_due", 0)
        + "; test_wake_due=" + p.getLong("test_wake_due", 0)
        + "; last_request=" + p.getString("last_request", "none");
  }
}

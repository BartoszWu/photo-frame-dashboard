package com.wesbos.homehq;

import java.util.Calendar;
import java.util.TimeZone;

/** The frame is on Monday-Friday, 07:00 inclusive to 16:00 exclusive, local time. */
final class ScreenScheduleTime {
  private static boolean weekday(Calendar c) {
    int day = c.get(Calendar.DAY_OF_WEEK);
    return day != Calendar.SATURDAY && day != Calendar.SUNDAY;
  }

  static boolean awake(long now, TimeZone zone) {
    Calendar c = Calendar.getInstance(zone);
    c.setTimeInMillis(now);
    int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    return weekday(c) && minute >= 7 * 60 && minute < 16 * 60;
  }

  static long next(long now, TimeZone zone, boolean wake) {
    Calendar c = Calendar.getInstance(zone);
    c.setTimeInMillis(now);
    c.set(Calendar.HOUR_OF_DAY, wake ? 7 : 16);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    for (int i = 0; i < 8; i++) {
      if (weekday(c) && c.getTimeInMillis() > now) return c.getTimeInMillis();
      // A calendar day, not 24 hours: the UTC offset can change over a weekend.
      c.add(Calendar.DAY_OF_MONTH, 1);
    }
    throw new IllegalStateException("No weekday boundary");
  }

  static boolean timely(long now, long due) {
    return due > 0 && now >= due && now - due <= 5 * 60 * 1000;
  }
}

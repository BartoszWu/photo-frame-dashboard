package com.wesbos.homehq;

import java.util.Calendar;
import java.util.TimeZone;

/** Calendar windows in device time, including overnight ranges and DST. */
final class ScreenScheduleTime {
  private static Calendar boundary(Calendar day, int minute) {
    Calendar c = (Calendar) day.clone();
    c.set(Calendar.HOUR_OF_DAY, minute / 60);
    c.set(Calendar.MINUTE, minute % 60);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    // In a spring gap, use the first valid minute, rather than shifting the whole gap.
    if (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) != minute) {
      for (int i = 0; i < 180; i++) {
        Calendar previous = (Calendar) c.clone();
        previous.setTimeInMillis(c.getTimeInMillis() - 60000);
        if (!sameDay(previous, day)
            || previous.get(Calendar.HOUR_OF_DAY) * 60 + previous.get(Calendar.MINUTE) < minute) break;
        c = previous;
      }
    }
    // A repeated autumn minute is used once, at its first occurrence.
    long value = c.getTimeInMillis();
    int shift = c.getTimeZone().getOffset(value) - c.getTimeZone().getOffset(value - 3 * 60 * 60 * 1000);
    if (shift < 0) {
      Calendar earlier = (Calendar) c.clone();
      earlier.setTimeInMillis(value + shift);
      if (sameDay(earlier, c) && earlier.get(Calendar.HOUR_OF_DAY) == c.get(Calendar.HOUR_OF_DAY)
          && earlier.get(Calendar.MINUTE) == c.get(Calendar.MINUTE)) c = earlier;
    }
    return c;
  }
  private static boolean sameDay(Calendar a, Calendar b) {
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
  }
  private static Calendar end(Calendar day, ScreenScheduleConfig config) {
    Calendar c = (Calendar) day.clone();
    if (config.overnight()) c.add(Calendar.DAY_OF_MONTH, 1);
    return boundary(c, config.sleepMinute);
  }

  static boolean awake(long now, TimeZone zone) {
    return awake(now, zone, ScreenScheduleConfig.defaults());
  }
  static boolean awake(long now, TimeZone zone, ScreenScheduleConfig config) {
    Calendar day = Calendar.getInstance(zone);
    day.setTimeInMillis(now);
    for (int i = 0; i < 2; i++) {
      if (config.includes(day) && now >= boundary(day, config.wakeMinute).getTimeInMillis()
          && now < end(day, config).getTimeInMillis()) return true;
      day.add(Calendar.DAY_OF_MONTH, -1);
    }
    return false;
  }

  static long next(long now, TimeZone zone, boolean wake) {
    return next(now, zone, wake, ScreenScheduleConfig.defaults());
  }
  static long next(long now, TimeZone zone, boolean wake, ScreenScheduleConfig config) {
    Calendar day = Calendar.getInstance(zone);
    day.setTimeInMillis(now);
    day.add(Calendar.DAY_OF_MONTH, -1);
    long earliest = Long.MAX_VALUE;
    // One selected weekly interval may collapse wholly into the spring DST gap.
    for (int i = 0; i < 16; i++) {
      if (config.includes(day)) {
        long start = boundary(day, config.wakeMinute).getTimeInMillis();
        long finish = end(day, config).getTimeInMillis();
        long due = wake ? start : finish;
        if (finish > start && due > now && due < earliest) earliest = due;
      }
      day.add(Calendar.DAY_OF_MONTH, 1);
    }
    if (earliest == Long.MAX_VALUE) throw new IllegalStateException("No schedule boundary");
    return earliest;
  }

  static boolean timely(long now, long due) {
    return due > 0 && now >= due && now - due <= 5 * 60 * 1000;
  }
}

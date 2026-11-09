package com.wesbos.homehq;

import java.util.Calendar;
import java.util.TimeZone;

/** Wall-clock recurrence, deliberately not a fixed 24-hour interval (DST). */
final class MorningTime {
  static long next(long now, TimeZone zone) {
    Calendar next = Calendar.getInstance(zone);
    next.setTimeInMillis(now);
    next.set(Calendar.HOUR_OF_DAY, 7);
    next.set(Calendar.MINUTE, 1);
    next.set(Calendar.SECOND, 0);
    next.set(Calendar.MILLISECOND, 0);
    if (next.getTimeInMillis() <= now) next.add(Calendar.DAY_OF_MONTH, 1);
    return next.getTimeInMillis();
  }

  static boolean timely(long now, long due) {
    return due > 0 && now >= due && now - due <= 5 * 60 * 1000;
  }
}

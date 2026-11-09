package com.wesbos.homehq;

import java.util.Calendar;
import java.util.Locale;

/** Selected days name the day of waking; a sleep time before wake ends the next day. */
final class ScreenScheduleConfig {
  final int daysMask, wakeMinute, sleepMinute;

  ScreenScheduleConfig(int daysMask, int wakeMinute, int sleepMinute) {
    if (daysMask < 1 || daysMask > 127) throw new IllegalArgumentException("Wybierz przynajmniej jeden dzień.");
    if (wakeMinute < 0 || wakeMinute > 1439 || sleepMinute < 0 || sleepMinute > 1439) {
      throw new IllegalArgumentException("Podaj prawidłowe godziny.");
    }
    if (wakeMinute == sleepMinute) throw new IllegalArgumentException("Pobudka i uśpienie muszą mieć różne godziny.");
    this.daysMask = daysMask;
    this.wakeMinute = wakeMinute;
    this.sleepMinute = sleepMinute;
  }

  static ScreenScheduleConfig defaults() { return new ScreenScheduleConfig(31, 420, 960); }
  static int parseTime(String value) {
    if (value == null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
      throw new IllegalArgumentException("Podaj godzinę w formacie 07:00.");
    }
    return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3));
  }
  static String time(int minute) { return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60); }
  boolean includes(Calendar day) {
    int mondayBased = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    return (daysMask & (1 << mondayBased)) != 0;
  }
  boolean overnight() { return sleepMinute < wakeMinute; }
}

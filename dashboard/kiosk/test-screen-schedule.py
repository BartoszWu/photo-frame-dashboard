#!/usr/bin/env python3
"""Test the actual weekday window and future boundaries, including DST weekends."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parent / "src/com/wesbos/homehq/ScreenScheduleTime.java"
harness = r'''
package com.wesbos.homehq;
import java.time.Instant;
import java.util.TimeZone;
public class ScreenScheduleHarness {
  static long at(String value) { return Instant.parse(value).toEpochMilli(); }
  static final TimeZone WARSAW = TimeZone.getTimeZone("Europe/Warsaw");
  static void awake(String name, String now, boolean expected) {
    if (ScreenScheduleTime.awake(at(now), WARSAW) != expected) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }
  static void next(String name, String now, boolean wake, String expected) {
    long actual = ScreenScheduleTime.next(at(now), WARSAW, wake);
    if (actual != at(expected) || actual <= at(now)) {
      throw new AssertionError(name + ": " + Instant.ofEpochMilli(actual));
    }
    System.out.println("PASS " + name);
  }
  static void timely(String name, long now, long due, boolean expected) {
    if (ScreenScheduleTime.timely(now, due) != expected) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }
  public static void main(String[] args) {
    awake("Monday before 07:00 stays asleep", "2026-11-09T05:59:59Z", false);
    awake("Monday 07:00 is on", "2026-11-09T06:00:00Z", true);
    awake("Monday 15:59:59 is on", "2026-11-09T14:59:59Z", true);
    awake("Monday 16:00 is off", "2026-11-09T15:00:00Z", false);
    awake("Friday afternoon is on", "2026-11-13T14:00:00Z", true);
    awake("Friday evening is off", "2026-11-13T18:00:00Z", false);
    awake("Saturday noon is off", "2026-11-14T11:00:00Z", false);
    awake("Sunday noon is off", "2026-11-08T11:00:00Z", false);
    next("Sunday plans Monday wake", "2026-11-08T18:00:00Z", true, "2026-11-09T06:00:00Z");
    next("Sunday plans Monday sleep", "2026-11-08T18:00:00Z", false, "2026-11-09T15:00:00Z");
    next("Exact wake schedules Tuesday", "2026-11-09T06:00:00Z", true, "2026-11-10T06:00:00Z");
    next("Monday morning schedules same-day sleep", "2026-11-09T06:00:00Z", false, "2026-11-09T15:00:00Z");
    next("Exact sleep schedules next day", "2026-11-09T15:00:00Z", false, "2026-11-10T15:00:00Z");
    next("Friday wake skips weekend", "2026-11-13T06:00:00Z", true, "2026-11-16T06:00:00Z");
    next("Friday sleep skips weekend", "2026-11-13T15:00:00Z", false, "2026-11-16T15:00:00Z");
    next("Spring DST wake", "2026-03-27T16:00:00Z", true, "2026-03-30T05:00:00Z");
    next("Spring DST sleep", "2026-03-27T16:00:00Z", false, "2026-03-30T14:00:00Z");
    next("Autumn DST wake", "2026-10-23T16:00:00Z", true, "2026-10-26T06:00:00Z");
    next("Autumn DST sleep", "2026-10-23T16:00:00Z", false, "2026-10-26T15:00:00Z");
    next("Year boundary", "2026-12-31T20:00:00Z", true, "2027-01-01T06:00:00Z");
    timely("Unarmed ignored", 1000, 0, false);
    timely("Early ignored", 999, 1000, false);
    timely("On time accepted", 1000, 1000, true);
    timely("Five minutes late accepted", 301000, 1000, true);
    timely("Older alarm rejected", 301001, 1000, false);
  }
}
'''
with tempfile.TemporaryDirectory() as directory:
    test = Path(directory) / "ScreenScheduleHarness.java"
    test.write_text(harness)
    subprocess.run(["javac", "-d", directory, str(source), str(test)], check=True)
    subprocess.run(["java", "-cp", directory, "com.wesbos.homehq.ScreenScheduleHarness"], check=True)

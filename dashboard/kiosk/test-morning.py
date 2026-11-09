#!/usr/bin/env python3
"""Exercise the real recurrence and delivery guard with a JDK; no device writes."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).resolve().parent / "src/com/wesbos/homehq/MorningTime.java"
harness = r'''
package com.wesbos.homehq;
import java.time.Instant;
import java.util.TimeZone;
public class MorningHarness {
  static long at(String iso) { return Instant.parse(iso).toEpochMilli(); }
  static void next(String name, String now, String zone, String expected) {
    long got = MorningTime.next(at(now), TimeZone.getTimeZone(zone));
    if (got != at(expected)) throw new AssertionError(name + ": " + Instant.ofEpochMilli(got));
    System.out.println("PASS " + name);
  }
  static void guard(String name, long now, long due, boolean expected) {
    if (MorningTime.timely(now, due) != expected) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }
  public static void main(String[] args) {
    next("before 07:01 Warsaw", "2026-10-29T06:00:59Z", "Europe/Warsaw", "2026-10-29T06:01:00Z");
    next("exact time goes to tomorrow", "2026-10-29T06:01:00Z", "Europe/Warsaw", "2026-10-30T06:01:00Z");
    next("after time goes to tomorrow", "2026-10-29T21:00:00Z", "Europe/Warsaw", "2026-10-30T06:01:00Z");
    next("spring DST, 23 hour day", "2026-03-28T06:01:00Z", "Europe/Warsaw", "2026-03-29T05:01:00Z");
    next("autumn DST, 25 hour day", "2026-10-24T05:01:00Z", "Europe/Warsaw", "2026-10-25T06:01:00Z");
    next("year rollover", "2026-12-31T22:00:00Z", "Europe/Warsaw", "2027-01-01T06:01:00Z");
    next("changed time zone", "2026-10-29T06:30:00Z", "UTC", "2026-10-29T07:01:00Z");
    guard("unarmed ignored", 1000, 0, false);
    guard("early ignored", 999, 1000, false);
    guard("on time accepted", 1000, 1000, true);
    guard("five minute delay accepted", 301000, 1000, true);
    guard("stale alarm ignored", 301001, 1000, false);
  }
}
'''
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    test = root / "MorningHarness.java"
    test.write_text(harness)
    subprocess.run(["javac", "-d", directory, str(source), str(test)], check=True)
    subprocess.run(["java", "-cp", directory, "com.wesbos.homehq.MorningHarness"], check=True)

#!/usr/bin/env python3
"""Approved live F10 cycle: real RTC alarms, physical power states, and cold Home process.

Requires new schedule disabled, ScreenAdmin activated, and owner approval. Arms sleep
in 60 seconds and wake in 150. Does not enable production or change Frameo settings.
"""
import datetime
import json
from pathlib import Path
import os
import subprocess
import sys
import time

SERIAL = os.environ.get("ANDROID_SERIAL")
if not SERIAL:
    raise SystemExit("Set ANDROID_SERIAL to the approved test frame before running")
PACKAGE = "com.wesbos.homehq"
PREFIX = ["adb", "-s", SERIAL]
observations = []
result_path = Path(sys.argv[1]) if len(sys.argv) > 1 else None


def adb(*args):
    return subprocess.check_output(PREFIX + list(args), text=True, timeout=25).strip()


def control(command):
    value = adb("shell", "am", "broadcast", "--user", "0", "-n",
                PACKAGE + "/.ScreenControlReceiver", "--es", "command", command)
    if "result=0" not in value or "policy=Mon-Fri_07:00-16:00" not in value:
        raise RuntimeError(value)
    print(value, flush=True)
    return value


def pid():
    for line in adb("shell", "ps").splitlines():
        values = line.split()
        if values and values[-1] == PACKAGE:
            return int(values[1])
    return None


def sample():
    power = adb("shell", "dumpsys", "power")
    activity = adb("shell", "dumpsys", "activity", "activities")
    value = {
        "observed_at": datetime.datetime.now().astimezone().isoformat(),
        "power": [s.strip() for s in power.splitlines()
                  if "mWakefulness=" in s or "Display Power:" in s],
        "foreground": [s.strip() for s in activity.splitlines() if "mResumedActivity" in s],
        "home_pid": pid(),
    }
    observations.append(value)
    if result_path:
        result_path.write_text(json.dumps(observations, indent=2))
    print(json.dumps(value), flush=True)
    return value


def is_on(value):
    return "Display Power: state=ON" in value["power"]


def has_home(value):
    return any(PACKAGE + "/" in s for s in value["foreground"])


assert adb("shell", "getprop", "ro.build.version.sdk") == "23", "Requires Android 6"
status = control("status")
assert "enabled=false" in status and "admin=true" in status, status
adb("shell", "input", "keyevent", "224")
adb("shell", "am", "start", "-n", PACKAGE + "/.Home")
time.sleep(2)
assert is_on(sample()), "Test must start with an awake display"
try:
    status = control("test")
    fields = dict(s.split("=", 1) for s in status.split('data="', 1)[1].strip('"').split("; "))
    sleep_due = int(fields["test_sleep_due"]) / 1000
    wake_due = int(fields["test_wake_due"]) / 1000
    print("Expect sleep", datetime.datetime.fromtimestamp(sleep_due).astimezone().isoformat(),
          "wake", datetime.datetime.fromtimestamp(wake_due).astimezone().isoformat(), flush=True)
    # Verify the OS holds actual RTC activity alarms, not only persisted settings.
    alarm_dump = adb("shell", "dumpsys", "alarm")
    assert "SCREEN_TEST_SLEEP" in alarm_dump and "SCREEN_TEST_WAKE" in alarm_dump
    sleeping = None
    while time.time() < sleep_due + 30:
        value = sample()
        if not is_on(value):
            assert time.time() >= sleep_due - 2, "Display went off before its alarm"
            sleeping = value
            break
        time.sleep(5)
    assert sleeping is not None, "FAIL: sleep RTC alarm did not turn the display off"
    assert "last_request=sleep_requested" in control("status")
    # Move the hidden task away from Home while asleep, then kill only our package.
    # This avoids relying on the Home process staying alive until the wake event.
    adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(2)
    assert not is_on(sample()), "Opening hidden Settings must not wake the display"
    old_pid = pid()
    assert old_pid is not None
    adb("shell", "su -c 'kill -9 %d'" % old_pid)
    time.sleep(2)
    assert pid() != old_pid, "The pre-sleep Home process survived the eviction"
    print("Evicted Home PID", old_pid, flush=True)
    while time.time() < wake_due - 3:
        assert not is_on(sample()), "Display woke before the programmed wake event"
        time.sleep(5)
    awake = None
    while time.time() < wake_due + 35:
        value = sample()
        if is_on(value) and has_home(value):
            awake = value
            break
        time.sleep(3)
    assert awake is not None, "FAIL: RTC wake did not show Home HQ"
    assert awake["home_pid"] != old_pid, "Wake reused the evicted process"
    focus = adb("shell", "dumpsys", "window", "windows")
    assert any(PACKAGE in s for s in focus.splitlines() if "mCurrentFocus" in s), "Home has no window focus"
    status = control("status")
    assert "enabled=false" in status and "test_sleep_due=0" in status and "test_wake_due=0" in status
    assert "last_request=wake_requested" in status
    time.sleep(15)  # Longer than the temporary wake lock; Home must maintain its own display.
    assert is_on(sample()) and has_home(observations[-1]), "Home lost the display after wake-lock timeout"
    print("PASS: physical OFF/ON, cold Home process, focused dashboard, stable after wake lock", flush=True)
finally:
    # Recover an awake reviewable screen even when a test fails; never leave a pending test.
    adb("shell", "input", "keyevent", "224")
    adb("shell", "am", "start", "-n", PACKAGE + "/.Home")
    control("cancel-test")

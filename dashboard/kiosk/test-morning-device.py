#!/usr/bin/env python3
"""Opt-in live F10 test; changes foreground and arms one 120-second alarm.

Run only after the owner approves installing/testing the APK. Never enables daily return,
changes Frameo settings, force-stops apps, or reboots. Requires ANDROID_SERIAL to identify the approved frame.
"""
import os
import subprocess
import time

SERIAL = os.environ.get("ANDROID_SERIAL")
if not SERIAL:
    raise SystemExit("Set ANDROID_SERIAL to the approved test frame before running")
PACKAGE = "com.wesbos.homehq"


def adb(*args):
    return subprocess.check_output(["adb", "-s", SERIAL, *args], text=True).strip()


def control(command):
    result = adb("shell", "am", "broadcast", "--user", "0", "-n",
                 PACKAGE + "/.MorningControlReceiver", "--es", "command", command)
    if "result=0" not in result or "daily_due=" not in result:
        raise RuntimeError(result)
    print(result, flush=True)
    return result


def foreground():
    return "\n".join(line.strip() for line in
                     adb("shell", "dumpsys", "activity", "activities").splitlines()
                     if "mResumedActivity" in line)


assert adb("shell", "getprop", "ro.build.version.sdk") == "23", "Requires API 23"
assert "enabled=false" in control("status"), "Disable daily return before this isolated test"
assert "state=ON" in adb("shell", "dumpsys", "power"), "Test only while the screen is awake"
passed = False
try:
    control("test")
    adb("shell", "am", "start", "-n", "net.frameo.frame/.ui.activities.AWaitForDeferredInitCompleted")
    time.sleep(3)
    before = foreground()
    print("BEFORE: " + before, flush=True)
    assert "net.frameo.frame/" in before, "Frameo must be foreground before the alarm"
    # Ordinary background process eviction, not force-stop (force-stop cancels alarms).
    adb("shell", "am", "kill", PACKAGE)
    print("Background process eviction requested; waiting for the actual AlarmManager delivery", flush=True)
    deadline = time.monotonic() + 160
    while time.monotonic() < deadline:
        time.sleep(5)
        current = foreground()
        if PACKAGE + "/" in current:
            print("AFTER: " + current, flush=True)
            passed = True
            break
    status = control("status")
    logs = adb("logcat", "-d", "-s", "HomeHQMorning:I", "*:S")
    print(logs, flush=True)
    assert passed, "FAIL: Home HQ did not become foreground within the test window"
    assert "Test launch requested" in logs, "FAIL: no alarm receiver delivery evidence"
    assert "enabled=false" in status and "test_due_elapsed=0" in status, "FAIL: test not consumed cleanly"
    time.sleep(10)
    assert PACKAGE + "/" in foreground(), "FAIL: Home HQ immediately lost foreground"
    print("PASS actual alarm delivered, Home HQ foreground, stable for 10 seconds, daily still disabled", flush=True)
finally:
    try:
        control("disable")
    except RuntimeError:
        # This F10 refuses cold receiver starts after process eviction; restore the screen
        # through the normal activity entry point before cancelling any pending test.
        adb("shell", "am", "start", "-n", PACKAGE + "/.Home")
        control("disable")

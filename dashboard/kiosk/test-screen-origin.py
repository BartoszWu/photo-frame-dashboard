#!/usr/bin/env python3
"""Test the actual native origin pinning rules without Android or network access."""
from pathlib import Path
import subprocess
import tempfile

source = Path(__file__).parent / 'src/com/wesbos/homehq/ScreenSettingsOrigin.java'
harness = r'''
package com.wesbos.homehq;
public class ScreenOriginHarness {
  static int count;
  static void equal(String actual, String expected) {
    if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    count++;
  }
  static void reject(String url) {
    try { ScreenSettingsOrigin.pairable(url); }
    catch (IllegalArgumentException ok) { count++; return; }
    throw new AssertionError("Accepted " + url);
  }
  public static void main(String[] args) {
    equal(ScreenSettingsOrigin.pairable("HTTP://PANEL.LOCAL:80/"), "http://panel.local");
    equal(ScreenSettingsOrigin.pairable("https://localhost:443"), "https://localhost");
    equal(ScreenSettingsOrigin.pairable("http://127.0.0.1:8898"), "http://127.0.0.1:8898");
    equal(ScreenSettingsOrigin.pairable("http://10.1.2.3:8787"), "http://10.1.2.3:8787");
    equal(ScreenSettingsOrigin.pairable("http://172.16.0.1"), "http://172.16.0.1");
    equal(ScreenSettingsOrigin.pairable("http://192.168.1.2"), "http://192.168.1.2");
    for (String url : new String[]{null, "", "file:///x", "javascript:1", "https://example.com", "http://172.15.0.1", "http://172.32.0.1", "http://10.1.2.999", "http://panel.local:0", "http://panel.local:65536", "http://user@panel.local", "http://panel.local/path", "http://panel.local?token=x", "http://panel.local#x"}) reject(url);
    String pinned="http://panel.local:8787";
    if (!ScreenSettingsOrigin.matches(pinned,"http://panel.local:8787/dom?theme=dark")) throw new AssertionError("same-origin page rejected");
    count++;
    for (String url : new String[]{null, "garbage", "http://panel.local:8788", "https://panel.local:8787", "http://panel.local.evil:8787", "http://panel.local@evil:8787", "http://other.local:8787"}) {
      if (ScreenSettingsOrigin.matches(pinned,url)) throw new AssertionError("foreign origin accepted");
      count++;
    }
    if (ScreenSettingsOrigin.matches(null, "http://panel.local:8787")) throw new AssertionError("unpaired origin accepted");
    count++;
    System.out.println("PASS " + count + " origin pinning cases");
  }
}
'''
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / 'ScreenOriginHarness.java'
    path.write_text(harness)
    subprocess.run(['javac', '--release', '8', '-d', directory, str(source), str(path)], check=True)
    subprocess.run(['java', '-cp', directory, 'com.wesbos.homehq.ScreenOriginHarness'], check=True)

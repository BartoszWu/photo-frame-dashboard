#!/usr/bin/env python3
"""Compile the actual configuration/save methods against in-memory Android seams.

Checks native validation, atomic prefs, revision conflicts and scheduling requests;
it does not prove Android storage durability or AlarmManager delivery.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).parent / 'src/com/wesbos/homehq'
source = (root / 'ScreenSchedule.java').read_text()

def method(marker):
    start = source.index(marker)
    pos = source.index('{', start) + 1
    depth = 1
    end = pos
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

methods = '\n'.join(method(marker) for marker in [
    '  static ScreenScheduleConfig configuration(', '  static int revision(', '  static void save(',
])
harness = r'''
package com.wesbos.homehq;
import java.util.*;
class Build { static class VERSION { static int SDK_INT=23; } }
class SharedPreferences {
  Map<String,Object> values=new HashMap<>(); boolean writes=true;
  int getInt(String key,int fallback) { return (Integer)values.getOrDefault(key,fallback); }
  Map<String,?> getAll() { return new HashMap<>(values); }
  Editor edit() { return new Editor(); }
  class Editor {
    Map<String,Object> pending=new HashMap<>(values);
    Editor putInt(String key,int value) { pending.put(key,value); return this; }
    Editor putLong(String key,long value) { pending.put(key,value); return this; }
    Editor putBoolean(String key,boolean value) { pending.put(key,value); return this; }
    Editor remove(String key) { pending.remove(key); return this; }
    boolean commit() { values=pending; return writes; }
  }
}
class Context { SharedPreferences prefs=new SharedPreferences(); boolean admin=true,clock=true; int cancelled,testsCancelled,restored,morningDisabled; }
class MorningReturn { static void disable(Context c) { c.morningDisabled++; } }
class Alarms { Context c; Alarms(Context c){this.c=c;} void cancel(String action){c.cancelled++;} }
class ScreenSchedule {
  static String WAKE="wake",SLEEP="sleep";
  static SharedPreferences prefs(Context c){return c.prefs;}
  static boolean hasAdmin(Context c){return c.admin;}
  static boolean trustedClock(Context c){return c.clock;}
  static void cancelTest(Context c){c.testsCancelled++;}
  static void restore(Context c){c.restored++;}
  static Alarms alarms(Context c){return new Alarms(c);}
  static String pending(Context c,String action){return action;}
__METHODS__
}
public class ScreenSaveHarness {
  static int count;
  static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
  static void rejects(Context c,ScreenScheduleConfig config,boolean active,int rev,String reason){
    Map<String,Object> before=new HashMap<>(c.prefs.values);
    try { ScreenSchedule.save(c,config,active,rev); throw new AssertionError("accepted "+reason); }
    catch(IllegalStateException e){check(reason.equals(e.getMessage()),reason);}
    check(before.equals(c.prefs.values)&&c.cancelled==0&&c.restored==0&&c.testsCancelled==0,"rejection is side-effect free "+reason);
  }
  public static void main(String[] args){
    Context c=new Context(); ScreenScheduleConfig defaults=ScreenSchedule.configuration(c);
    check(defaults.daysMask==31&&defaults.wakeMinute==420&&defaults.sleepMinute==960,"existing prefs migrate to weekday defaults");
    ScreenScheduleConfig custom=new ScreenScheduleConfig(64,1320,360);
    ScreenSchedule.save(c,custom,true,0);
    ScreenScheduleConfig read=ScreenSchedule.configuration(c);
    check(read.daysMask==64&&read.wakeMinute==1320&&read.sleepMinute==360,"custom profile persists exactly");
    check(ScreenSchedule.revision(c)==1&&Boolean.TRUE.equals(c.prefs.values.get("enabled")),"profile and enable/revision commit together");
    check(c.restored==1&&c.morningDisabled==1&&c.testsCancelled==1,"active save replaces old controller and rearms");
    c.prefs.values.put("wake_due",123L);c.prefs.values.put("sleep_due",456L);
    ScreenSchedule.save(c,custom,false,1);
    check(ScreenSchedule.revision(c)==2&&Boolean.FALSE.equals(c.prefs.values.get("enabled")),"disable increments revision");
    check(!c.prefs.values.containsKey("wake_due")&&!c.prefs.values.containsKey("sleep_due")&&c.cancelled==2,"disable clears both alarms");
    rejects(new Context(),custom,true,1,"stale_revision");
    Context noAdmin=new Context();noAdmin.admin=false;rejects(noAdmin,custom,true,0,"admin_required");
    Context noClock=new Context();noClock.clock=false;rejects(noClock,custom,true,0,"clock_not_ready");
    Context failed=new Context();failed.prefs.writes=false;rejects(failed,custom,true,0,"storage_failed");
    Build.VERSION.SDK_INT=24;rejects(new Context(),custom,true,0,"unsupported_device");Build.VERSION.SDK_INT=23;
    Context limit=new Context();limit.prefs.values.put("revision",Integer.MAX_VALUE);rejects(limit,custom,true,Integer.MAX_VALUE,"revision_limit");
    Context off=new Context();off.admin=false;off.clock=false;ScreenSchedule.save(off,custom,false,0);
    check(Boolean.FALSE.equals(off.prefs.values.get("enabled")),"disable remains available without admin or trusted time");
    System.out.println("PASS "+count+" native save assertions");
  }
}
'''.replace('__METHODS__', methods)
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / 'ScreenSaveHarness.java'
    path.write_text(harness)
    subprocess.run(['javac', '--release', '8', '-d', directory, str(root / 'ScreenScheduleConfig.java'), str(path)], check=True)
    subprocess.run(['java', '-cp', directory, 'com.wesbos.homehq.ScreenSaveHarness'], check=True)

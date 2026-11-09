package com.wesbos.homehq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** ADB administration only: manifest requires the system's DUMP permission from the sender. */
public class MorningControlReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent intent) {
    String command = intent.getStringExtra("command");
    try {
      if ("enable".equals(command)) MorningReturn.enable(c);
      else if ("disable".equals(command)) MorningReturn.disable(c);
      else if ("test".equals(command)) MorningReturn.test(c);
      else if (!"status".equals(command)) throw new IllegalArgumentException("Unknown command");
      setResultCode(0);
      setResultData(MorningReturn.status(c));
    } catch (RuntimeException e) {
      if ("enable".equals(command)) MorningReturn.disable(c);
      setResultCode(1);
      setResultData(e.toString());
    }
  }
}

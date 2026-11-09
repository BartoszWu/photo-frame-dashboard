package com.wesbos.homehq;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** A main-frame, pinned-origin message port; no JavaScriptInterface or HTTP write endpoint. */
final class ScreenSettingsBridge {
  private final MainActivity activity;
  private final WebView web;
  private WebMessagePort port;
  private AlertDialog confirmation;
  private long lastTouch = -1;

  ScreenSettingsBridge(MainActivity activity, WebView web) {
    this.activity = activity;
    this.web = web;
  }
  static void pair(Context c, String origin) {
    String value = ScreenSettingsOrigin.pairable(origin);
    if (!c.getSharedPreferences("screen_settings_bridge", 0).edit().putString("origin", value).commit()) {
      throw new IllegalStateException("Pairing could not be saved");
    }
  }
  private String pinned() {
    return activity.getSharedPreferences("screen_settings_bridge", 0).getString("origin", null);
  }
  private boolean trusted() { return ScreenSettingsOrigin.matches(pinned(), web.getUrl()); }
  void touch() { lastTouch = SystemClock.uptimeMillis(); }

  static void unpair(Context c) {
    if (!c.getSharedPreferences("screen_settings_bridge", 0).edit().remove("origin").commit()) {
      throw new IllegalStateException("Pairing could not be removed");
    }
  }
  void close() {
    lastTouch = -1;
    if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
    if (port != null) { port.close(); port = null; }
  }
  void loaded(String page) {
    close();
    if (Build.VERSION.SDK_INT != 23 || !ScreenSettingsOrigin.matches(pinned(), page) || !trusted()) return;
    WebMessagePort[] pair = web.createWebMessageChannel();
    port = pair[0];
    port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
      @Override public void onMessage(WebMessagePort source, WebMessage message) { receive(source, message); }
    });
    web.postWebMessage(new WebMessage("{\"type\":\"homehq-screen\",\"version\":1}",
        new WebMessagePort[]{pair[1]}), Uri.parse(pinned()));
  }

  private void reply(WebMessagePort target, String id, boolean ok, String error, String message) {
    if (target != port || !trusted()) return;
    try {
      JSONObject body = new JSONObject().put("id", id).put("ok", ok);
      if (ok) body.put("state", ScreenSchedule.state(activity));
      else body.put("error", error).put("message", message);
      target.postMessage(new WebMessage(body.toString()));
    } catch (JSONException | IllegalStateException e) {
      // A navigation or destroyed WebView must never revive a privileged channel.
    }
  }
  private static int integer(Object value) {
    if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Nieprawidłowe dane.");
    long number = ((Number) value).longValue();
    if (number < 0 || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Nieprawidłowe dane.");
    return (int) number;
  }
  private static void keys(JSONObject o, String... allowed) throws JSONException {
    Set<String> names = new HashSet<>(Arrays.asList(allowed));
    Iterator<String> keys = o.keys();
    while (keys.hasNext()) if (!names.contains(keys.next())) throw new IllegalArgumentException("Nieprawidłowe dane.");
  }
  private void receive(WebMessagePort source, WebMessage message) {
    if (source != port || !trusted()) return;
    String id = "";
    try {
      String data = message.getData();
      if (data == null || data.length() > 4096 || (message.getPorts() != null && message.getPorts().length != 0)) return;
      JSONObject request = new JSONObject(data);
      Object value = request.get("id");
      if (!(value instanceof String) || !((String) value).matches("[a-zA-Z0-9_-]{1,64}")) return;
      id = (String) value;
      if ("get".equals(request.get("type"))) {
        keys(request, "id", "type");
        reply(source, id, true, null, null);
      } else if ("save".equals(request.get("type"))) {
        keys(request, "id", "type", "revision", "enabled", "days", "wake", "sleep");
        if (!activity.hasWindowFocus() || lastTouch < 0 || SystemClock.uptimeMillis() - lastTouch > 3000) {
          reply(source, id, false, "gesture_required", "Dotknij przycisku Zapisz zmiany na ramce.");
          return;
        }
        if (confirmation != null) {
          reply(source, id, false, "busy", "Najpierw zamknij otwarte potwierdzenie.");
          return;
        }
        if (!(request.get("enabled") instanceof Boolean)
            || !(request.get("wake") instanceof String) || !(request.get("sleep") instanceof String)) {
          throw new IllegalArgumentException("Nieprawidłowe dane.");
        }
        JSONArray days = request.getJSONArray("days");
        if (days.length() < 1 || days.length() > 7) throw new IllegalArgumentException("Wybierz dni działania.");
        int mask = 0;
        for (int i = 0; i < days.length(); i++) {
          int day = integer(days.get(i));
          if (day < 1 || day > 7 || (mask & (1 << (day - 1))) != 0) throw new IllegalArgumentException("Nieprawidłowe dni.");
          mask |= 1 << (day - 1);
        }
        ScreenScheduleConfig config = new ScreenScheduleConfig(mask,
            ScreenScheduleConfig.parseTime((String) request.get("wake")),
            ScreenScheduleConfig.parseTime((String) request.get("sleep")));
        int revision = integer(request.get("revision"));
        if (revision != ScreenSchedule.revision(activity)) throw new IllegalStateException("stale_revision");
        confirm(source, id, config, (Boolean) request.get("enabled"), revision);
      } else throw new IllegalArgumentException("Nieznane polecenie.");
    } catch (JSONException | IllegalArgumentException e) {
      reply(source, id, false, "invalid", "Sprawdź dni oraz godziny pobudki i uśpienia.");
    } catch (IllegalStateException e) {
      reply(source, id, false, e.getMessage(), "Ustawienia zmieniły się. Odczytaj je ponownie.");
    }
  }

  private void confirm(WebMessagePort source, String id, ScreenScheduleConfig config, boolean active, int revision) {
    String[] days = {"poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela"};
    StringBuilder chosen = new StringBuilder();
    for (int i = 0; i < 7; i++) if ((config.daysMask & (1 << i)) != 0) {
      if (chosen.length() != 0) chosen.append(", ");
      chosen.append(days[i]);
    }
    boolean sleeps = active && !ScreenScheduleTime.awake(System.currentTimeMillis(), java.util.TimeZone.getDefault(), config);
    String text = active ? chosen + "\nPobudka " + ScreenScheduleConfig.time(config.wakeMinute)
        + ", uśpienie " + ScreenScheduleConfig.time(config.sleepMinute)
        + (config.overnight() ? " następnego dnia." : ".")
        : "Ramka nie będzie automatycznie usypiana ani budzona.";
    if (sleeps) text += "\n\nTeraz jesteśmy poza tym przedziałem. Po zapisaniu ekran zaśnie.";
    confirmation = new AlertDialog.Builder(activity).setTitle(active ? "Zapisać harmonogram?" : "Wyłączyć harmonogram?")
        .setMessage(text)
        .setNegativeButton("Anuluj", (dialog, which) -> reply(source, id, false, "cancelled", "Nie zapisano zmian."))
        .setOnCancelListener(dialog -> reply(source, id, false, "cancelled", "Nie zapisano zmian."))
        .setPositiveButton(sleeps ? "Zapisz i uśpij" : "Zapisz zmiany", (dialog, which) -> {
          if (source != port || !trusted()) return;
          try {
            ScreenSchedule.save(activity, config, active, revision);
            reply(source, id, true, null, null);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
              if (ScreenSchedule.revision(activity) == revision + 1) ScreenSchedule.reconcile(activity);
            }, 1000);
          } catch (RuntimeException e) {
            reply(source, id, false, e.getMessage(), "Nie udało się zapisać. Odczytaj ustawienia i spróbuj ponownie.");
          }
        }).create();
    confirmation.setOnDismissListener(dialog -> confirmation = null);
    confirmation.show();
  }
}

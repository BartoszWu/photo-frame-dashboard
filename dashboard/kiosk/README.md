# Home HQ kiosk navigation

Hold two fingers still on the dashboard for 1.5 seconds to open the native controls. Keep the
fingers at least an inch (about 2.5 cm) apart: on PF1007L frames the `gslX680` touchscreen reads
closer fingers as one touch.

- **Open launcher** opens another installed Home app, such as Rootless Launcher.
  If none is available, it opens Android Settings.
- **Android settings** opens the device's Settings app.
- **Wi-Fi settings** opens Android's Wi-Fi list directly.
- **Reload dashboard** reloads the current URL.
- **Back to dashboard** dismisses the controls.

## Optional single-tap Menu button

On panels where the two-finger gesture is unreliable, enable a native **Menu** button:

```sh
adb shell am start -n com.wesbos.homehq/.MainActivity --ez menu_button true
```

It opens the same controls with one tap. The preference survives later launches; the button
is hidden by default. Turn it off with the same command and `--ez menu_button false`.
The two-finger gesture remains available in either mode.

The button occupies 72×48 dp, with 12 dp margins at the bottom left, above both the page and
the offline screen. Leave that area clear in your dashboard when enabling it. It needs no
root or additional permissions. The button and physical single-tap interaction were tested
in a local adaptation on the [QCREA YYD-1156](../../docs/qcrea-yyd-1156.md); the saved opt-in is
the shared-kiosk version of that adaptation.

## Clear a vendor boot animation that covers the kiosk

Some frames keep a vendor boot animation on top of every window after Android has finished
booting, so the kiosk is running underneath while the panel still shows the splash. The
[IQUAL 10X1](../../docs/iqual-10x1-rk3326.md) behaves this way once its photo app is disabled.
Android has a property for exactly this, and the kiosk can set it on every start:

```sh
adb shell am start -n com.wesbos.homehq/.MainActivity --ez bootanim_exit true
```

The opt-in is saved and applied at every launch (and again 5 s later, in case the first attempt
lost a race with the property service). `--ez bootanim_exit false` turns it off. It needs no root
only where SELinux is **permissive**, which is the case on that frame; Android otherwise refuses
property writes from an app, and the attempt fails quietly while the splash stays.

## Dashboard URL

The kiosk opens the hosted demo dashboard by default. To show your own, save its URL once:

```sh
adb shell am start -n com.wesbos.homehq/.MainActivity --es url https://your-dashboard/
```

`scripts/set-kiosk-url.sh <url>` from the repository root does the same, and prints the saved
URL when run without one.

Every later launch, including after a reboot, opens the saved URL. Links that other apps open
in the kiosk, such as a sign-in page, are shown but not saved.

## A page bundled in the APK (no server)

A static page can ship inside the APK instead of being hosted. Build with `KIOSK_SITE` set to a
folder that has an `index.html` (from `dashboard/`):

```sh
KIOSK_SITE=/path/to/site ANDROID_HOME=/path/to/android-sdk bash kiosk/build.sh
adb install -r kiosk/build/homehq.apk
adb shell am start -n com.wesbos.homehq/.MainActivity --es url 'http://127.0.0.1:8765/'
```

On Windows, set `$env:KIOSK_SITE` before `.\build.ps1`.

Home HQ answers `http://127.0.0.1:8765/` from those files itself. Nothing listens on the port, no
other machine is needed, and the page loads without Wi-Fi. Every other URL, including the page's
own API calls, goes to the network as usual. ES modules work, which a `file://` page can't do, and
`localStorage` belongs to the `http://127.0.0.1:8765` origin. A query string
(`http://127.0.0.1:8765/?room=kitchen`) reaches the page unchanged; quote the URL in `adb shell`.
Don't put API keys or tokens in the bundled files or the saved URL: anyone with ADB on the frame
can read them, and so can any page shown at that origin. Keep them on a server
([building-for-the-frame.md](../../docs/building-for-the-frame.md)).
To change the page, rebuild and `adb install -r`. Without `KIOSK_SITE` the APK bundles nothing
and the address answers 404.

## Start at boot

To have the frame boot into the dashboard, make the kiosk its Home app. Installing the kiosk
does not offer it as a Home app, so nothing changes until you run:

```sh
adb shell am start -n com.wesbos.homehq/.MainActivity --ez home true
adb shell cmd package set-home-activity com.wesbos.homehq/.Home
```

If `set-home-activity` answers "Failed to set default home", wait a few seconds for the Home alias
to register and run it again (seen on a Skylight 150-CAL).

Wait about 30 seconds before rebooting. Android saves the Home alias a few seconds later, and on
a PF1508 (Android 15, #167) a reboot 2 seconds after these commands turned the alias back off, so the
frame booted into the previous Home app. The same delayed save lost a `pm disable-user` on a
Pastigio FRAME156 (#139).

Android 6 frames (PF1007L, BIUFRAME) don't have `cmd package set-home-activity`. After the first
command, open the Home chooser instead, then tap **Home HQ** and **Always** on the frame. Keep
another launcher installed so **Open launcher** has somewhere to go.

```sh
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell 'grep -A2 "<preferred-activities" /data/system/users/0/package-restrictions.xml'   # check it stuck
```

The kiosk then opens the saved URL at boot, and the Home button returns to it. If the page
fails to load at boot because Wi-Fi isn't up yet, the kiosk keeps retrying. In the controls,
**Open launcher** skips the kiosk and opens another Home app. To undo, pick another Home app in
Settings → Apps → Default apps, then run the first command with `--ez home false`.

### Without replacing Home

On some frames, making the kiosk the Home app is risky: on a PF1506 (AiMOR, Android 10) the boot
animation stayed over the screen and the frame couldn't be reached again (#118). There, keep the
vendor Home app and let the kiosk open itself once Android has booted:

```sh
adb shell am start -n com.wesbos.homehq/.MainActivity --ez boot true
adb shell appops set com.wesbos.homehq SYSTEM_ALERT_WINDOW allow
```

The second line is needed on Android 10 and later, which blocks apps from opening a screen from the
background otherwise. If the kiosk doesn't open, the frame shows the vendor app instead of getting
stuck. Undo with `--ez boot false` and `adb shell appops set com.wesbos.homehq SYSTEM_ALERT_WINDOW default`.

This was reported on one PF1506 with firmware `20250731`. It is not a recovery fix for the
locked-out `20250813` unit in #118. Confirm physical Settings and USB ADB access first, keep the
vendor Home enabled and selected, and follow the README backup rule. The receiver makes one
launch attempt; network retries happen inside the kiosk. It does not launch Firefox or switch
Home apps. Some vendor firmware blocks boot broadcasts or background launches entirely.

## Returning from Android Settings or another app

While MainActivity is paused, Home HQ pauses WebView lifecycle and page timers, plus native
retries and captive-portal checks. Timer pausing affects all WebViews in this app process;
it does not stop every network request or servers supplying the dashboard. An existing portal
probe may finish within its timeouts but cannot open a sign-in page while the kiosk is paused.
Returning resumes pending offline/sign-in recovery without reloading a healthy dashboard.

The contributor observed paused/resumed timers on one RK3326 Android 8.1 frame with WebView 106.
Host regression checks cover late errors, probe completion and retry ordering. Failed-page and
captive-portal recovery on this revision, and other Android versions, remain untested on hardware.
Keep a working Settings route and the existing signing key when updating an installed kiosk.

## F10 screen schedule (Android 6 only)

The full screen schedule is opt-in and targets Android 6 / API 23. It turns the
screen on and shows Home HQ Monday-Friday at 07:00, then locks the screen at 16:00.
Weekends are outside the on window. It replaces the older daily 07:01 foreground
return when enabled. Disable Frameo's sleep schedule before enabling this controller.

The F10 refused to cold-start Frameo's schedule and boot receivers (`process is bad`)
in a controlled test; warm Frameo could sleep/wake the same display. The new controller
uses direct Activity PendingIntents and owns both boundaries instead of depending on
Frameo's process. It uses no root or network service. The only Device Admin policy is
`force-lock`; `WAKE_LOCK` supplies a five-second wake pulse on API 23.

After the owner's approval, install with `adb -s "$ANDROID_SERIAL" install -r kiosk/build/homehq.apk`,
open Home HQ once, and activate only the screen-lock administrator:

```sh
adb -s "$ANDROID_SERIAL" shell dpm set-active-admin com.wesbos.homehq/.ScreenAdmin
adb -s "$ANDROID_SERIAL" shell am broadcast --user 0 -n com.wesbos.homehq/.ScreenControlReceiver --es command status
```

Use the same broadcast with command `test` for a real RTC alarm cycle (sleep in 60 s,
wake in 150 s) while production is disabled. `test-screen-schedule-device.py` checks
actual OFF/ON states, evicts the Home HQ process between events, checks the focused
window, and waits past the temporary wake lock. It changes the foreground and uses
root only for that diagnostic process eviction. It does not enable production or
edit Frameo settings. Run only with the owner's consent and an awake screen:

```sh
python3 kiosk/test-screen-schedule.py
python3 kiosk/test-screen-origin.py
python3 kiosk/test-screen-save.py
python3 -u kiosk/test-screen-schedule-device.py /path/to/test-observations.json
```

Once reviewed, `enable` activates the saved profile (Mon-Fri 07:00-16:00 by default), disables the legacy morning
return, and immediately reconciles the current screen state. Outside the on window,
this means it will sleep immediately. `disable` cancels this schedule and test alarms;
`cancel-test` cancels only the diagnostic cycle. Control broadcasts require the system
sender permission DUMP; ordinary apps/pages cannot configure the schedule.

Boot, time/time-zone changes, package updates, and ordinary Home startup restore the
next boundaries. Home startup also reconciles the display after five seconds, because
the vendor can drop BOOT_COMPLETED. A factory date older than the saved clock floor is
ignored until network time arrives; Home's dynamic clock receiver then reconciles.
Manual wake of an already running activity does not recreate Home. Restarting Home
outside work hours does reconcile it back to sleep. An unplugged/unpowered frame
cannot wake itself, and a secure keyguard would still need legitimate unlocking.

For rollback, first send `disable`, then `remove-admin`, and reinstall the same-signed
backup with `install -r`. Do not uninstall. `remove-admin` removes this app's admin;
it does not alter other administrators. Previous Frameo/07:01 controllers are not
silently re-enabled when this feature is disabled. Review their state explicitly.

Earlier F10 builds passed short RTC sleep/wake cycles with the Home process killed while asleep,
and restoration after reboot/time synchronization. This rebased build still requires a fresh
hardware test before installation or activation.

### Configure from the dashboard

The compatible HomeView Mini dashboard adds **Settings → Ekran**: selected wake days,
local wake/sleep times, an on/off switch and the next scheduled wake. All selected days
share one time window. If sleep is earlier than wake, sleep occurs on the following day.
The selected day is always the wake day; a Sunday 22:00–06:00 window ends on Monday.
At least one day and two different valid HH:MM times are required even while disabled.
Times follow the frame's timezone, not the browser's timezone. Spring-forward times
move to the first valid minute; a collapsed interval is skipped. A repeated autumn
minute occurs once, at its first occurrence.

After reviewing the APK and dashboard together, pair the dashboard's local origin once
through the DUMP-protected ADB control receiver. The example below uses loopback only
for a local test; replace it with your own dashboard origin (scheme, hostname and port,
no path/query/token). Set `ANDROID_SERIAL` explicitly to the approved test device.
Pairing does not enable or change the schedule. Reload the dashboard after pairing.

```sh
adb -s "$ANDROID_SERIAL" shell am broadcast --user 0 -n com.wesbos.homehq/.ScreenControlReceiver --es command pair-dashboard --es origin http://127.0.0.1:8898
```

Only local IPv4 origins, localhost and `.local` names are accepted. The origin is stored
separately from the legacy activity's editable URL; changing that URL cannot grant access
to another website. The native channel goes to the top-level pinned origin only, closes
on navigation/destruction and is not exposed to the captive-portal flow. There is no
JavaScriptInterface, root command or HTTP write endpoint. HTTP on a LAN does not protect
against a compromised LAN/server; native confirmation is still required for every save.

A real screen touch on **Zapisz zmiany**, then a native Android confirmation, are both
required. The native dialog repeats days/hours and explicitly warns if saving will put
the screen to sleep now. Cancellation keeps the existing profile. The configuration and
revision are committed together before alarms are rearmed; stale revisions are rejected.
The UI applies a save only after native acknowledgment. A successful save rechecks the
current window after one second, so saving outside the window can immediately sleep.
Disabling cancels this controller's alarms; it does not re-enable Frameo or legacy alarms.

For a fresh installation, initial admin activation and `enable` still need the reviewed
ADB setup above while the frame has correct synchronized time. This establishes the
clock floor; the web UI cannot bootstrap around this safeguard. An existing enabled
installation retains its profile/admin/clock floor when updated with the same signer.
Never run two independent screen controllers at once.

To revoke the web channel without changing the schedule, use `unpair-dashboard` and
reload the page. The old page also loses native authorization immediately. Review the
saved profile before a rollback: an older APK ignores custom day/time keys and may
restore its fixed weekday profile. Disable the controller before downgrading.

Protocol version 1 uses an origin-targeted `homehq-screen` greeting with a transferred
WebMessagePort. Requests and responses are JSON strings. Requests are `{id,type:"get"}`
or `{id,type:"save",revision,enabled,days,wake,sleep}`; `days` contains unique ISO values
1–7 (Monday–Sunday). Replies are `{id,ok:true,state}` or `{id,ok:false,error,message}`.
State includes `enabled`, `admin`, `days`, `wake`, `sleep`, `revision`, IANA `zone`,
`clockReady`, and epoch-millisecond `nextWakeAt`/`nextSleepAt` (zero when disabled).
Save checks types/unknown fields, API 23, admin/time readiness, fresh touch and revision
again before commit. A disk-write failure restores the previous in-memory profile and
attempts to restore disk state; inspect storage/state before retrying or rebooting.
No browser-storage or server-side fallback can write a profile.

The new bridge and the rebased APK require an approved API 23 hardware test before
rollout. Browser protocol fakes and host tests do not prove WebView port transfer,
real touch delivery, Device Admin behavior or cold-process RTC delivery on this build.

The older morning-return section below documents the previous solution only.

## Legacy morning return on Veidoo F10 (Android 6 only)

This local adaptation adds one opt-in return to Home HQ at **07:01 in the frame's
time zone**. Keep Frameo's existing sleep/wake schedule; its morning wake must
finish before 07:01. This is deliberately restricted to API 23: background activity
launches on newer Android versions need a separate design and verification.

`AlarmManager.setExactAndAllowWhileIdle` holds an immutable activity PendingIntent.
Its private NoDisplay activity checks the time and screen state, then requests the
existing Home HQ Home task (or MainActivity if the Home alias is disabled). A request
is not proof of foreground success on vendor firmware. This F10 refused to cold-start
a broadcast receiver after process eviction (`process is bad`), so a broadcast-only
alarm was tested and rejected. Activity alarms use the normal activity launch path.
There is no service or polling. The alarm wakes the CPU, but does not turn on the
display: a noninteractive device is skipped without retry. Deliveries more than
five minutes late are skipped. Frameo can implement a black screen in an activity,
so `isInteractive()` cannot prove that Frameo's own sleep mode has finished.

The next 07:01 is recalculated as a calendar date (including DST), not 24 hours.
BOOT_COMPLETED, clock/time-zone changes, package updates, and ordinary Home HQ
starts restore the next alarm when enabled. Boot never launches Home HQ from this
receiver or replays a missed morning. Test alarms are discarded after reboot.
Force-stop cancels Android alarms: manually open Home HQ again to restore the daily
alarm. A powered-off frame cannot execute an alarm.

Daily return is disabled by default. The control receiver requires the sender's
system `android.permission.DUMP`, which the F10 ADB shell has; ordinary apps and
web pages cannot configure it. No new permission grant or root is required.
Existing kiosk security characteristics, including the debug signing key and
exported URL-setting activity, are unchanged. Keep ADB restricted to trusted USB.

After installing an update, launch Home HQ once. This F10 temporarily marked the
app's process bad after replacement and dropped broadcasts until that launch:

```sh
adb -s "$ANDROID_SERIAL" shell am start -n com.wesbos.homehq/.Home
adb -s "$ANDROID_SERIAL" shell am broadcast --user 0 -n com.wesbos.homehq/.MorningControlReceiver --es command status
```

Run the isolated two-minute test **before enabling the daily alarm** (screen awake,
owner approval required). It puts Frameo in front, requests ordinary Home HQ
background-process eviction, checks actual foreground after the alarm, and disables
the feature at the end. It never changes Frameo's settings or force-stops an app:

```sh
python3 kiosk/test-morning.py          # host recurrence/late-delivery tests
python3 -u kiosk/test-morning-device.py # live USB test; daily must be disabled
```

Only after reviewing the result, enable the real schedule:

```sh
adb -s "$ANDROID_SERIAL" shell am broadcast --user 0 -n com.wesbos.homehq/.MorningControlReceiver --es command enable
adb -s "$ANDROID_SERIAL" shell 'dumpsys alarm | grep -A7 -B2 com.wesbos.homehq'
```

To disable both daily and pending test alarms, use the same broadcast with
`--es command disable`. `--es command test` arms one alarm 120 seconds later without
enabling the daily schedule. `status` reports the persisted settings; `dumpsys alarm`
is the separate check that Android actually holds an alarm.

Back up the installed APK before replacing it (`pm path com.wesbos.homehq`, then
`adb pull` the returned path). To revert, disable the alarms, then reinstall that
APK with `adb -s "$ANDROID_SERIAL" install -r /path/to/homehq-original.apk` and open Home HQ.
Do not uninstall: `install -r` preserves the saved URL and app data. A rollback must
use the same signing certificate; the original and this build were compared.

## Screen density

The dashboard is laid out for 1280×800 at density 160. On wider screens, such as 1920×1080 frames,
it keeps the 800 px height and widens to fill the screen. Most 1280×800 frames ship at 160, but a VESWWE
`DP101` shipped at 190 and the bottom of the dashboard was cut off
([veswwe-dp101.md](../../docs/veswwe-dp101.md)). Check with `adb shell wm density`. If it isn't 160:

```sh
adb shell wm density 160      # undo: adb shell wm density reset
```

Android's own menus get smaller too. The setting survives a reboot.

## Frames without Home or Back buttons

Some frame firmware omits Android's navigation bar. The two-finger gesture only
works inside Home HQ, so it cannot bring you back from another app's settings.

Enable **Home HQ navigation** in Android **Settings → Accessibility**. The
optional service adds floating **Back** and **Dashboard** buttons outside Home HQ:

- **Back** performs Android's normal Back action.
- **Dashboard** returns directly to Home HQ, keeping the current dashboard URL.
- Drag the dots to move the buttons. Their position is saved.

The buttons hide while Home HQ is active and return when you leave it. Android
keeps the service enabled across reboots. It requests no accessibility events or
screen-content access and does not require root. Disable **Home HQ navigation**
in Accessibility settings to remove the buttons.

On a PF1007L (`20260519` firmware), the vendor `BackgroundManagerService` dropped the
service from the enabled list after every `adb install -r` or force stop of Home HQ, so the
buttons silently disappeared. To have Home HQ put the service back each time it starts, grant
it this permission once:

```sh
adb shell pm grant com.wesbos.homehq android.permission.WRITE_SECURE_SETTINGS
```

With the grant, Home HQ also rebinds the service if it is still listed but not running, which is
what Android 6 leaves behind when Home HQ's process is killed. If the buttons keep disappearing
whenever you leave Home HQ, the firmware may be killing every app you switch away from; see
[the Denver PFF-1015 notes](../../docs/denver-pff-1015.md#low-ram-mode-kills-every-app-you-leave).

The grant survives updates. To turn the buttons off for good, revoke it first
(`pm revoke` with the same arguments), then disable the service in Accessibility settings.

January `20260113` and May `20260529` firmware were also observed keeping a navigation service in
the selected list but resetting `accessibility_enabled` to `0` after boot. On `20260529` (#234)
the buttons came back on every boot once Home HQ was the Home app (see [Start at boot](#start-at-boot)). With the same optional grant,
Home HQ repairs either state when it starts, without rewriting an already selected list while the
service is running. On a PF1508 (Android 15) turning the switch back on was not enough, so if the
service still isn't running 5 seconds later, Home HQ takes it off the list and puts it back
([allwinner-a333-pf1508.md](../../docs/allwinner-a333-pf1508.md#the-navigation-service-after-a-reboot)).
This reset was measured with a separate navigation app; the Home HQ change has host regression
coverage but has not been installed on that unit. See the
[January report](../../docs/allwinner-a33-pf1007l.md#9-january-unit-remote-management-and-background-services).

Keep an ADB connection available while enabling the service on a frame with no
other way back. If needed, return to the kiosk with:

```sh
adb shell am start -n com.wesbos.homehq/.MainActivity
```

This service supplies navigation controls only. To start the dashboard at boot,
make Home HQ the default Home app (see [Start at boot](#start-at-boot)).

To build your own dashboard for a PF1007L frame, see
[building-for-the-frame.md](../../docs/building-for-the-frame.md): what WebView 106 supports,
what to avoid, and how to test on the frame.

## When the dashboard can't load

If the dashboard fails to load, Home HQ covers WebView's error page with its own
screen. The error page would print the full URL, including any token in it.

- **No Wi-Fi:** "Connect to Wi-Fi?" with an **Open Wi-Fi settings** button.
- **Wi-Fi is up but the dashboard is unreachable:** "Can't reach the dashboard".
- **Wi-Fi has a sign-in page** (a captive portal, as on school, hotel and guest networks): the
  kiosk checks `http://connectivitycheck.gstatic.com/generate_204` the way Android does. A redirect
  or a page instead of the empty 204 means a portal, and the kiosk opens its page with a bar above
  it, so the terms can be accepted on the frame. It checks again every 5 seconds and returns to the
  dashboard once the frame is online. After 10 minutes it goes back to retrying the dashboard. **Cancel** closes the page; the offline screen then offers **Sign in to Wi-Fi**
  until the frame joins a network again. Frames with SystemUI disabled never see Android's own
  "Sign in to network" notification, so this is the only way to reach the page.

- **Certificate error:** never bypassed. If the error is about the date, the screen says
  "Setting the clock…". Frames with no clock battery start with an old date after a power cut
  (a Runxion SSA ZN-DP1002 came up on May 14, 2021), so every HTTPS certificate fails until
  network time arrives, about 20 seconds after Wi-Fi joins. Android 6 can give up instead: if its
  boot-time attempts fail, it waits 24 hours before trying again, and the frame stays in 1969
  (seen on an RK3126 Frameo frame, still 1969 four minutes after Wi-Fi joined). With the optional
  `WRITE_SECURE_SETTINGS` grant (see
  [Frames without Home or Back buttons](#frames-without-home-or-back-buttons)), Home HQ then asks
  Android for network time again, by turning automatic time off and on, at most once a minute
  while the date error lasts and Wi-Fi is connected. It leaves automatic time alone if it was
  turned off. By hand: `adb shell settings put global auto_time 0`, then `1`.

Home HQ keeps retrying every 5 seconds, and retries at once when Wi-Fi connects or the clock
changes. The screen disappears once a load succeeds. Wi-Fi settings open only when tapped,
because Wi-Fi is often still connecting for the first minute after boot.

On frames without a navigation bar (most PF1007L units), set up the navigation service in
[Frames without Home or Back buttons](#frames-without-home-or-back-buttons) first. Otherwise
**Open Wi-Fi settings** leaves no way back to the dashboard.

## Networks without internet access

A frame on a LAN-only network (an isolated VLAN that reaches only the dashboard server) needs two
settings. Without them it can't rejoin the network on its own, and its clock drifts.

- **Stay joined.** Android checks each Wi-Fi network for internet access. On a Skylight 150-CAL
  (Android 12), a network that failed the check was marked `NETWORK_SELECTION_DISABLED_NO_INTERNET_PERMANENT`
  in `adb shell dumpsys wifi`. After Wi-Fi was turned off and on, the frame didn't rejoin it
  (a reboot would leave it in the same state). This command turns the check off:

  ```sh
  adb shell settings put global captive_portal_mode 0
  ```

  Then join the network once by hand on the frame; that clears the flag. To undo:
  `adb shell settings delete global captive_portal_mode`. This turns off Android's sign-in-page
  detection everywhere. The kiosk's own check above still runs when the dashboard can't load.
  Alternatively, `captive_portal_http_url` and `captive_portal_https_url` can point at a LAN server
  that answers HTTP 204; that hasn't been tried on a frame.
- **Network time.** Android gets the time from `time.android.com`. Point it at an NTP server on the
  LAN that the frame's network is allowed to reach (UDP 123), and keep automatic time on:

  ```sh
  adb shell settings put global ntp_server <lan-ntp-server>
  adb shell settings put global auto_time 1
  ```

  Android only asks again when its last result is more than a day old; a reboot asks at once.

## Time zone

After changing the frame's time zone over ADB (`setprop persist.sys.timezone <zone>` and
`service call alarm 3 s16 <zone>` on Android 6; the transaction number differs on other versions), restart the kiosk. Until then the page keeps the old zone,
while `date` on the frame already shows the new one:

```sh
adb shell am force-stop com.wesbos.homehq
adb shell am start -n com.wesbos.homehq/.MainActivity
```

Seen on an Android 6 Runxion (WebView 107). Changing the zone in Android Settings has not been
compared.

The hosted demo sets its own clock to 3:18 PM each time it loads (`App.setTime(15, 18)` in
`dashboard/js/app.js`), so it can show the wrong time on a frame whose clock is right. Press and
hold the sidebar clock, then tap **Real time** in Demo controls.

## Build and install

Host-only navigation regression checks need Python 3 and a JDK on `PATH`:

```sh
python3 kiosk/test-navigation.py
python3 kiosk/test-lifecycle.py
```

Run from `dashboard/`. The navigation test compiles the actual repair method with settings and
permission fakes. The lifecycle test compiles the actual dashboard lifecycle/retry
methods with Android fakes. Neither proves Android service binding, real gesture delivery,
WebView implementation behavior or reboot behavior.

Use JDK 17, Android build-tools 34.0.0, and platform 34. On Ubuntu 24.04, the apt packages listed
at the top of `build.sh` install all three, with `ANDROID_HOME=/usr/lib/android-sdk`. From `dashboard/`:

```sh
ANDROID_HOME=/path/to/android-sdk bash kiosk/build.sh
adb install -r kiosk/build/homehq.apk
```

The kiosk loads the existing hosted dashboard over the frame's Wi-Fi. A USB
connection is only needed for installation or diagnostics, not normal use.

The kiosk targets Android 9 (SDK 28); the minimum is still Android 6 (SDK 23). Android 15
refuses to install targets below 24 (`INSTALL_FAILED_DEPRECATED_SDK_VERSION`), and Android 14
and later show a "built for an older version of Android" dialog on the first launch for targets
below 28.

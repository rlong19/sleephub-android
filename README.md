# SleepHub

SleepHub (`com.rlong.sleephub`, version **0.3.12**) is an Android app that records three sleep devices at once, inside one foreground service, and shows them together on a History screen:

- **Zeo Mobile** sleep headband — Bluetooth Classic RFCOMM, HMSG protocol (not BLE). Live hypnogram and stored nights.
- **Colmi-style smart ring** — a MoYoung “FT Ring” R24 speaking the CRP BLE protocol (ported from Da Rings recreated 1.3.5). Sleep stages, movement, heart rate, HRV, temperature, and SpO2.
- **EMAY SleepO2** pulse oximeter — BLE realtime stream plus a read-only download of recordings stored on the device. SpO2 and pulse on the History screen, including ODI marks.

History combines the Zeo hypnogram, the ring hypnogram and vitals, and the oximeter SpO2 and pulse traces. CSV export and traffic logs are written under the app’s external files directory (`Android/data/com.rlong.sleephub/files/`).

A Zeo DEMO source and a simulated ring exist for development. Both are off unless turned on in Settings, and every sample they produce is tagged as demo.

## Not affiliated

SleepHub is an **independent, unofficial** project. It is not affiliated with, endorsed by, or connected to Zeo Inc., Colmi, or EMAY (or MoYoung). The protocol notes were worked out so this app can interoperate with those devices. They are not vendor documentation.

- Zeo Mobile: [`zeo-mobile-protocol.md`](zeo-mobile-protocol.md)
- EMAY SleepO2 framing in `app/src/main/java/com/rlong/sleephub/oxi/EmayProtocol.kt` is adapted from the MIT-licensed `emay-sleepo2` package (copyright notice kept in that file).

## Safety

**Some device commands are deliberately blocked in the app. Do not send them** — not from this app’s probe or custom-hex boxes, and not from any other tool. They can wipe a device, brick it, or destroy a night of data.

**Ring (CRP / Colmi-style).** Factory reset, firmware and OTA/bootloader writes, and credential or bond writes are blocked in the probe, including the custom command box. Do not send them.

**Zeo Mobile (HMSG).** The probe allow-list is read-only queries plus a few safe toggles (LED, clear the wake window). Everything else is refused. Never send:

- message **type 8** (`SET_UP_BLUETOOTH`: name, PIN, link key)
- message **types 150–173**, including **173 (0xAD)**, which has been reported to brick a headband
- **factory reset**, **reboot**, **Bluetooth lock / unlock**, **demo mode**, and **sleep start / stop**
- **clock writes** (`TIME_REPORT`) **while the headband is recording** (algorithm active). SleepHub’s own time sync skips the write in that state; do not force one.

**EMAY SleepO2.** The writer allow-list is hello, state, realtime on/off, battery, heartbeat, and read-only history download. Never send the delete-all command:

```text
9D 7F 7F 7F 7F 00 00 19
```

or **any `E2` or `E3` frame**. Those erase the oximeter’s stored recordings. The app hard-blocks them before the allow-list is checked. Nothing in SleepHub deletes data on the oximeter.

This app is a personal recorder for interoperability. It is not a medical device.

## Build

Requirements: JDK 17 and an Android SDK with compile SDK 34. Toolchain: Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Gradle 8.7 (wrapper), `minSdk` 26, `targetSdk` 34.

```bash
./gradlew assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Unit tests:

```bash
./gradlew testDebugUnitTest
```

Point `local.properties` (`sdk.dir=...`) at your SDK if Gradle does not pick up `ANDROID_HOME`. Do not commit `local.properties` or any keystore.

## Layout

- `ring/` — ring BLE link, CRP protocol, history storage, auto-measure, probe UI
- `zeo/` — HMSG codec, safety policy, RFCOMM link, DEMO simulator, session
- `oxi/` — EMAY SleepO2 protocol, history download, storage
- `core/` — traffic log, timeline, Bluetooth permission helper
- `serial/SleepManagerSource.kt` — placeholder for a bedside Zeo Sleep Manager serial stream (not started)
- `service/SleepHubService.kt` — one foreground service for all three links
- `ui/` — main screen, settings, History

## Version notes

### v0.2.0

- `ring/` replaced with Da Rings recreated v18 (BLE, protocol, data, auto, sim, and UI: RingActivity, History, Probe, Auto-measure). v18’s KeepAliveService is a facade over SleepHubService (one foreground service for both links).
- Sync: “Sync full history” (days 0–6) and “Sync recent” (incremental plan). Auto sync at most every 45 minutes, foreground only; 12-minute watchdog; one sync at a time; measures preempt syncs.
- Combined History tab: Zeo hypnogram (persisted in `zeo.db` from REPORT_SLEEP / SLEEP_SEND), ring hypnogram, movement, HR, HRV, temperature, SpO2; slim “no data” strips.
- Zeo picker shows only Zeo devices (OUI `4C:98:EF` or a name containing “zeo”) unless “Show all”. Battery is raw voltage plus a status code.
- Reconnect: ring uses autoConnect plus a 15 s watchdog (skipped after a user Disconnect). Zeo uses a never-give-up loop (5 s → 60 s backoff). An inbound “Zeo Android” server is always listening. A partial wakelock is held only during a connect attempt. Every attempt is logged in `zeo_traffic.log`.

### v0.2.1

- Combined History: stress and physical-strength charts removed. Order: Zeo stages, ring stages, movement, HR, HRV, temperature, SpO2.

### v0.2.2

- Zeo clock: offset = phoneNow − headbandNow from every TIME_REPORT (TIME_QUERY type 10, read-only, also sent on link-up and every poll); persisted per headband; Zeo times = headband time + offset. The clock is never written while the algorithm is active.
- Base hypnogram 0 (undefined / not yet scored) is drawn as a light-gray “unscored” bar.

### v0.2.3

- `zeo.db` v2: raw headband seconds (`sonhb`, `eon_hb`, `dst_hb`) are the source of truth. `start` / `end` / `display_start` = headband seconds × 1000 + offset (phone epoch ms), recomputed idempotently on app start and whenever the offset is learned. v1 rows are migrated from `sonhb`. Implausible raw end/display values fall back to start + epochs × 30 s, or to start.
- Logs live in external app storage: `/sdcard/Android/data/com.rlong.sleephub/files/logs/`.

### v0.3.0 — EMAY SleepO2 oximeter

- Protocol ported from `emay-sleepo2` (MIT; notice kept in `oxi/EmayProtocol.kt`). Source used: the published PyPI package emay-sleepo2 1.0.6.
- Service `FF12`, write `FF01`, notify `FF02`, no pairing. Commands = payload + (sum & 0x7F). Start: hello → deviceState → startRealtime → getBattery. Heartbeat `9A` every 1.5 s on a dedicated thread, with a partial wakelock.
- Write allow-list: only hello, deviceState, startRealtime, stopRealtime, getBattery, and heartbeat.
- Storage: `oximeter.db` table `oxi` (`ts` ms, `spo2`, `pulse`, `source` `ble` or `csv`).
- History: “Oximeter SpO2” (1 Hz, unsmoothed, 88/90% lines, ODI3 marks) replaces the ring SpO2 chart on the combined History screen (ring SpO2 is still stored). “Oximeter pulse” is the chart below.

### v0.3.1 — EMAY history download

- Battery: `86` → reply `F6`, percent = battery byte & 0x7F.
- Realtime: `EB 01` frames. Pulse is adjusted when the flag byte has bit 1 set. Checksum covers the first seven bytes.
- **Sync oximeter history** (Oximeter tab and History page, read-only): stop realtime, then hello, state, optional clock, battery, summary, and per-recording SpO2 and pulse downloads. Stored as 1 Hz rows with `source=device_history`, skipping seconds already covered and recordings already downloaded (`oxi_segment`). Realtime resumes afterwards. Nothing is deleted on the device.
- Clock set (`83`): Settings → “Set oximeter clock during history sync”, off by default. Sent only when the state reply says the device is idle. Never sent when the state is unknown.
- Writer guard (`EmayProtocol.guard`): hard-block for any `9D 7F…` (delete all) and any `E2`/`E3` frame, then the allow-list (hello, state, realtime on/off, battery, heartbeat, summary, next-segment, download/abort, and opt-in clock set). Blocked frames are logged in `oximeter_traffic.log`.

Later 0.3.x releases keep that safety policy. The published `versionName` is 0.3.12.

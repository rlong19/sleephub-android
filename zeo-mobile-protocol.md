# Zeo Mobile headband — Bluetooth protocol ("HMSG")

Sources:
- **[C] Code-confirmed**: decompiled official Zeo Android app `myzeo-original.apk` v1.2.7 (jadx output in `/workspace/zeolab/myzeo/jadx/sources/com/myzeo/android/{headband,bluetooth}`, `com/myzeo/zeo/utility/{Crc,HypnogramCodec}.java`).
- **[X] Cross-checked** against independent work: ZeoCompanion (`opensource.zeocompanion.bluetooth.ZeoMobileHB_*`, decompiled in `/workspace/zeolab/orig2/jadx`), the QS-forum thread "Zeo Sleep Monitor – Android Data Export" (J. Willis / ZeoCompanion author, 2016; Wireshark dissector `zeohbbt.lua`, "Zeo Headband Bluetooth Raw Data Format.txt"), Schlafhacking blog (SDP dump, PIN, teardown), johnwillis.com ("HMSG" prefix).
- **[I] Inferred**: not proven by code, needs verification on hardware.

## 1. Discovery & pairing
| Item | Value | Conf. |
|---|---|---|
| Transport | Bluetooth Classic 2.1+EDR (CSR BlueCore6), RFCOMM/SPP. No BLE. | C/X |
| Device name | "Zeo" (user can rename via SET_UP_BLUETOOTH) | X |
| MAC OUI | `4C:98:EF:` — official app identifies headbands only by this prefix (AutopairActivity, HeadbandService.addPreexistingHeadbands) | C |
| Class of device | 0x080700 | X |
| Pairing | Standard bond via `BluetoothDevice.createBond()` (reflection). Legacy PIN **`0000`** (SetUpBluetoothMessage.DEFAULT_PIN = "0000"; Schlafhacking: "accepts 0000") | C/X |
| Autopair | App runs discovery, auto-bonds the first device with OUI 4C:98:EF. Headband must be in pairing mode (fresh out of dock / button — [I]). | C / I |

## 2. RFCOMM services / UUIDs — two directions!
| Role | UUID | SDP name | Channel |
|---|---|---|---|
| **Headband server** (phone connects out) | `56b32a76-479b-43d4-99ff-42d79823d0a6` | "SPP Accessory" | **2** (SDP; app hard-codes fallback channel 2) |
| iAP (iOS only, Apple auth chip) | `00000000-deca-fade-deca-deafdecacaff` | "iAP Accessory" | 1 — do not use |
| **Phone server** (headband connects in) | `56b32a76-479b-43d4-99ff-42d79823d0a5` | service name **"Zeo Android"** | any (SDP) |

- [C] Official app does both: `HeadbandService` starts `BluetoothServer("Zeo Android", …d0a5)` with an **insecure** RFCOMM listener and accepts the headband's inbound connection; `Headband.connect()` also tries outbound: insecure-to-UUID d0a6 → (reflection insecure) → `createInsecureRfcommSocket(2)` → secure-to-UUID → `createRfcommSocket(2)`.
- [X] ZeoCompanion only uses the server role (listens on d0a5, waits for the headband). The headband apparently dials its bonded phone periodically / on events (forum: "the Zeo Headband looks for [the Zeo Android service] in order to dump its status… and sleep records").
- [X] Schlafhacking: plain `rfcomm connect` to the headband without pairing was refused → bond first.
- **SleepHub implements both** (server listener + outbound client with the same fallback chain).

## 3. Handshake / authentication
- [C] **No application-level authentication, no encryption, no challenge.** Security relies only on BT bonding (PIN 0000). Insecure sockets are used on purpose.
- [C] There is no mandatory hello. After socket open the official app:
  1. If the headband record has no serial number → sends a **NULL message (type 0, empty)** = "send me your HB_STARTING message" (`sendStartingMessageRequest`). Headband replies HB_STARTING with `queryResponse=1`.
  2. Sends WAKEUP_WINDOW (current smart alarm) or a "clear" window (built as offset=begin=end=2004-02-01 local → on the wire: sent=(now−2004-02-01) s, begin=0, end=0).
  3. Headband sends STATE_REPORT; on the first one per connection the app sends COMMAND_REQUEST **SLEEP_SEND (8)** → headband replies REPORT_SLEEP.
  4. Time sync (see §6) when needed (after HB_STARTING with queryResponse=0 i.e. headband rebooted, on undock, or periodically).
- [C] Unsolicited HB_STARTING (`queryResponse=0`) = headband rebooted → app time-syncs.
- [C] On STATE_CHANGE events 8,9,10,11,12,15 the app sends QUERY_STATE (6) + SLEEP_SEND (8). This is how "live" data is pulled.
- [C] ACK: header has `ackRequest` byte; HB_ACKNOWLEDGE (type 2) carries the acked seq#. The official app **never sends acks** and ignores received acks; it always sends ackRequest=0.
- [I] SleepHub does the same, plus an optional periodic poll (QUERY_STATE + SLEEP_SEND every N s, default 60 s) to get a live-ish hypnogram.

## 4. Framing [C]
All little-endian. 12-byte header + fixed-size body per type. Multiple messages may be concatenated in one RFCOMM read and may be split across reads.

| Off | Size | Field |
|---|---|---|
| 0 | 4 | magic `48 4D 53 47` = ASCII **"HMSG"** (u32 0x47534D48 = 1196641608) [X: johnwillis "HMSG", ZeoCompanion scans for 72,77,83,71] |
| 4 | 2 | CRC16 (see §5) |
| 6 | 1 | protocol version (=2; receiver rejects >2) |
| 7 | 1 | message type (receiver rejects ≥14) |
| 8 | 1 | ackRequest (0) |
| 9 | 1 | sequence number (sender increments per message, wraps 8-bit) |
| 10 | 2 | body length — must equal the fixed size for the type, else frame rejected |
| 12 | n | body |

Receiver resync: if header invalid / size mismatch / CRC bad → skip 1 byte and rescan (Headband.mReceiveHandler).

## 5. Checksum [C]
CRC-16/CCITT-FALSE: poly 0x1021, init **0xFFFF**, no reflection, no xorout, table-driven. Computed over bytes **[6 .. 12+n)** (version through end of body). Stored LE at offset 4. Check value for "123456789" = 0x29B1.

## 6. Message types [C]
| Type | Name | Dir | Body | Layout |
|---|---|---|---|---|
| 0 | NULL | P→H | 0 | — ("request HB_STARTING") |
| 1 | COMMAND_REQUEST | P→H | 4 | u8 command, 3 pad |
| 2 | HB_ACKNOWLEDGE | H→P | 4 | u8 previousSeq, 3 pad |
| 3 | HB_STARTING | H→P | 104 | ZeoInfo(70) + BT addr(8: u32 lo, u32 hi) + lastAssertFunction char[20] + u32 lastAssertLine + u8 resetCause + bool queryResponse + u16 resetCount |
| 4 | HB_STATE_CHANGE_REPORT | H→P | 4 | u8 event, 3 pad |
| 5 | REPORT_ACCELEROMETER | H→P | 72 | u32[10] diffVecMagSums, int8[10][3] greatest x/y/z, u8 sampleAverage, 1 pad (10 epochs, 2/min) |
| 6 | REPORT_ERROR | H→P | 4 | u8 error, 3 pad |
| 7 | REPORT_SLEEP | H→P | 1144 | SleepReport (below) |
| 8 | SET_UP_BLUETOOTH | P→H | 56 | BT addr(8) + linkKey u8[16] + name char[20] + pin char[8] + u8 setupType + 3 pad (app: addr 0, key 0, pin "0000", type 1; name ≤19 chars) |
| 9 | STATE_REPORT | H→P | 16 | bool activeForced, btLocked, demoMode, docked, onHead, requiresPin, wasCharged, wasQueried; u8 voltage, u8 voltageStatus, u8 lastAlarmReason, u8 algorithmMode; u32 sensorUseSeconds |
| 10 | TIME_QUERY | both | 8 | timespec (u32 sec, u32 ms) |
| 11 | TIME_REPORT | both | 12 | timespec + bool isOffset + bool offsetNegative + u8 querySeq + pad |
| 12 | WAKEUP_NOTIFY | H→P | 4 | u8 wakeReason, 3 pad |
| 13 | WAKEUP_WINDOW | P→H | 12 | u32 sentTime, u32 windowBegin, u32 windowEnd (seconds, headband clock = phone time − offset; window ≤ 40 min) |
| 163 | TEST_LED | P→H | 4 | bool on, 3 pad (app sends it, but receivers reject type ≥14) |
| 150–171 | TEST_* (accel/ACP/analog/BC6 passthrough/BT addr/cal/freq trim/impedance/PCB test/power off/reset/serial/voltage/**waveform**) | | ? | names only in both apps — bodies unknown |
| **173 (0xAD)** | "DANGER" | | | [X] ZeoCompanion author: **bricked a headband**. Never send. SleepHub's encoder refuses types ≥14. |

ZeoInfo (70 bytes): deviceName char[20], pad 6, u16 flashCalibrationUpdates, u16 flashSavedDataUpdates, u16 flashSleepBackupUpdates, **u32 hwVersion, char[12] model, char[16] serialId, u32 swVersion (firmware)**.

### Commands (COMMAND_REQUEST.command) [C]
1 BT lock, 2 BT unlock, 3 demo off, 4 demo on, 5 factory reset, 6 QUERY_STATE, 7 reset sensor-use, 8 SLEEP_SEND, 9 sleep start, 10 sleep stop, 100 reboot. (Lock/unlock are followed by 6.) SleepHub UI only exposes 6, 8, and NULL/time sync; destructive ones (5, 100, 1/2, 9/10) are not exposed.

### State-change events [C]
0 none, 1 alarm, 2 alarm window ended, 3 window started, 4 docked, 5 low battery, 6 off head, 7 on head, 8 sleep mode changed, 9 night ended, 10 night restored, 11 night saved, 12 night starting, 13 sleep onset, 14 rating needed, 15 sleep state change, 16 time jump, 17 undocked, 18 user sleep locked, 19 user sleep off, 20 user sleep on, 21 user sleep restart.

### Errors [C]: 1 invalid name, 2 invalid PIN, 3 PIN needed, 4 PIN not needed, 5 reset needed, 6 wrong channel.
### Voltage status [C]: 0 none, 1 charged, 2 charging, 3 on battery, 4 too low. Raw `voltage` u8 scale unknown [I].
### Algorithm mode [C]: 0 idle, 1 tentative active, 2 active (recording), 3 tentative idle.

## 7. REPORT_SLEEP body (1144 bytes) [C]
| Off | Field |
|---|---|
| 0 | u32 displayHypnogramStartTime (s) |
| 4 | u16 awakenings |
| 6..16 | u16 timeInDeep, timeInLight, timeInRem, timeInWake, timeToZ, totalZ |
| 18 | u16 **ZQ score** |
| 20 | bool sleepValid, bool validForHistory, u8 voltageBattery, bool userInitiated |
| 24 | accumulators: u32 deepSum, u16[5] stageCounts, u16 timesWoken, u16 totalCount, u16 totalSleepCount (20) |
| 44 | u16 countToZ, 2 pad |
| 48 | u32 endOfNight |
| 52 | display hypnogram: u32 count(≤192), u32 endOfNightCount, u8[96] packed, u32 startTime |
| 160 | bool insufficientData, 3 pad |
| 164 | u32 insufficientDataStartTime |
| 168 | u16 lightChangedToDeep, 2 pad |
| 172 | u32 startOfNight |
| 176 | bool valid, 3 pad |
| 180 | base hypnogram: u32 count(≤1920), u8[960] packed |

- Hypnogram packing on the wire: 2 epochs per byte, **low nibble first**. Values: 0 undefined, 1 wake, 2 REM, 3 light, 4 deep (6 = light→deep marker in SleepCommon). Base = 30-s epochs (max 1920 = 16 h), display = 5-min epochs (max 192).
- Durations (timeIn*, timeToZ, totalZ): units not stated in code. [I] Bedside convention is 30-s epochs; ZeoCompanion/myzeo DB treat them as minutes after /2 — **verify on hardware**.
- Times are headband clock seconds; app converts with stored clock offset. [I] Headband clock is set via TIME_REPORT to local Unix time.

## 8. Time sync [C]
1. P→H TIME_QUERY(phone now). 2. H→P TIME_REPORT(headband time). 3. If |hb − phone| ≥ 2 s and the algorithm is not active (mode ≠2,3) or hb time < 2004: P→H TIME_REPORT(isOffset=1, negative flag, |sendTime − hbTime| ms). Then re-query.

## 9. What is NOT available
- [C/X] **No raw EEG waveform and no frequency bins** in normal operation (unlike the bedside unit's 38400-baud serial firmware). The headband (EFM32 Gecko) runs the staging algorithm on-board and only sends hypnogram/summary. TEST_WAVEFORM (170/171) exists by name only; no app code builds it and probing test modes risks bricking (type 173). SleepHub logs any unknown type raw but never sends test types.
- Battery: only `voltage` raw byte + `voltageStatus` enum (STATE_REPORT) and `voltageBattery` in REPORT_SLEEP. No percentage.
- Firmware: `swVersion`/`hwVersion` u32 + model/serial strings in HB_STARTING (request via NULL message).

## 10. Open questions — verify on the Zeo Mobile Pro (~Oct 23)
1. Does the headband accept the phone's **outbound** connection on channel 2/d0a6 on Android 13, or only dial in to the "Zeo Android" d0a5 service? (Both implemented; check `logs/zeo_traffic.log`.)
2. Does it dial in to a *non-myzeo* app that registers d0a5? (ZeoCompanion says yes.)
3. How often does it send STATE_REPORT / REPORT_SLEEP unprompted during the night, and does polling (6+8) give a growing base hypnogram live?
4. Units of timeIn*/timeToZ (minutes vs 30-s epochs) and scale of `voltage`.
5. swVersion encoding (u32 — packed major/minor?).
6. Is REPORT_ACCELEROMETER ever sent unsolicited?
7. Whether "Zeo Mobile Pro" differs at all from ZEOMBL1 firmware.

## 11. Hidden / test / factory / debug commands (audit of myzeo v1.2.7)

### Headline
- **Raw EEG: none.** The app has **no code path that requests, receives or parses raw EEG, waveform, or frequency bins.** `TEST_WAVEFORM` = 170 is a bare constant in `MessageType` (and in ZeoCompanion as 170 TEST_WAVEFORM / 171 TEST_WAVEFORM_REPLY). It has no message class, no body layout, no size, and no sender. The app's receiver even **drops every frame with type ≥ 14** (`isValidHeader`), so it could not take in a waveform reply. A raw mode would need its request format guessed and a firmware that honours it. The ZeoCompanion author bricked a headband probing this range. **Verdict: no usable raw-EEG mode is known. Treat it as non-existent unless there is new evidence.**
- **No secret menu or easter egg.** "Diagnostics" (`HeadbandDiagnosticsActivity`) is an ordinary Settings entry (`main_preferences.xml` key `diagnostics`). It shows BT addr/name, algorithm mode, bonded, clock offset, connected, docked, BT-locked, HW/SW version, model, voltage, on-head, serial and timestamps. Its controls are: rename (only when connected + docked), LED on/off, clock-offset query, BT lock toggle.
- **Debug flags:** `com.myzeo.android.Log.sDebug = true` and `Headband.DEBUG = true` are verbose logcat only, with no protocol effect. The manifest has `debuggable=false`. `ZeoMessage.TEST_VERSION = 7` is defined and never used (it may be a firmware test-protocol version; the receiver rejects versions >2).
- **Raw passthrough:** `HeadbandService` handles `ACTION_HEADBAND_SEND_MESSAGE` with extra `message_bytes` and writes arbitrary bytes to the headband. The service is not exported and no UI calls it, so it is a dev hook.
- **Mobile vs Mobile Pro:** the code has **no model-specific branching**. `model` (char[12]) and `hwVersion` are only stored and shown. Nothing in the app tells "Pro" apart. [I] Any difference would be in firmware. SleepHub will log the HB_STARTING model/hw/sw strings so the Pro can be identified.

### Classification
| Code | Name | Where used in app | Class | SleepHub probe |
|---|---|---|---|---|
| type 0 | NULL ("send HB_STARTING") | on connect when serial unknown | **SAFE-READ** | ✔ one-tap |
| cmd 6 | QUERY_STATE → STATE_REPORT | many | **SAFE-READ** | ✔ |
| cmd 8 | SLEEP_SEND → REPORT_SLEEP | many | **SAFE-READ** | ✔ |
| type 10 | TIME_QUERY → TIME_REPORT | time sync, diagnostics | **SAFE-READ** | ✔ |
| type 163 | TEST_LED on/off (bool) | Diagnostics LED button | **SAFE-TOGGLE** (any reply is dropped by the official parser; SleepHub logs it raw) | ✔ on / off |
| type 13 | WAKEUP_WINDOW clear (begin=end=0) | on connect | **SAFE-TOGGLE** (clears the smart-alarm window only) | ✔ clear only |
| cmd 3 / 4 | DEMO_MODE off / on | defined, **unused** | SAFE-TOGGLE? → classed **DANGEROUS (unclear)**: effect on stored night unknown | ✖ hidden |
| cmd 9 / 10 | SLEEP_START / SLEEP_STOP | defined, **unused** | **DANGEROUS** (may end or overwrite the night) | ✖ |
| cmd 1 / 2 | BT LOCK / UNLOCK (+cmd 6) | Diagnostics toggle | **DANGEROUS** (lock can bind the headband to one phone, so unbind risk) | ✖ |
| type 8 | SET_UP_BLUETOOTH (name/PIN/link key) | Diagnostics rename; **followed by cmd 100 reboot** if algorithm idle | **DANGEROUS** (pairing data + reboot) | ✖ |
| cmd 5 | FACTORY_RESET | `sendFactoryReset()` defined, **no caller** | **DANGEROUS** | ✖ |
| cmd 7 | RESET_SENSOR_USE | `sendResetSensorUse()` defined, **no caller** | **DANGEROUS** (writes counter) | ✖ |
| cmd 100 | REBOOT | only after rename | **DANGEROUS** | ✖ |
| type 11 | TIME_REPORT (set clock offset) | time sync | **DANGEROUS** (writes clock; can shift the night's timestamps). SleepHub only uses it inside the documented sync, and only when the algorithm is idle | ✖ manual |
| 151 TEST_ACCEL_QUERY, 153 TEST_ACP_QUERY, 159 TEST_CAL_DATA_QUERY, 168 TEST_VOLTAGE_QUERY | test-mode queries | constants only | **DANGEROUS (unclear)**: names say read-only, but there is no body layout and the firmware's reaction outside test mode is unknown. The 0xAD brick sits in this range | ✖ |
| 155 TEST_ANALOG, 162 TEST_IMPEDANCE, 170 TEST_WAVEFORM | analog / impedance / waveform | constants only | **DANGEROUS (unclear)** | ✖ |
| 156 BC6_PASSTHROUGH, 157 BT_ADDRESS_SET, 161 FREQ_TRIM_SET, 164 PCB_TEST_MODE, 165 POWER_OFF, 166 RESET, 167 SERIAL_ID_SET | factory / bootloader / calibration writes | constants only | **DANGEROUS** | ✖ |
| 173 (0xAD) | unnamed | ZeoCompanion "DANGER1" | **DANGEROUS — reported to brick** | ✖ |
| reports 152/154/158/160/169/171 | headband→phone test replies | constants only | n/a (receive only; SleepHub logs them raw) | — |

SleepHub's probe screen lists only the ✔ rows. The custom-hex entry builds a correct HMSG frame (magic, CRC, seq). It sends **only** if the type/command is on the SAFE allow-list `{type 0, type 1 with cmd 6 or 8, type 10, type 163, type 13 clear}`. Everything else is refused and logged as "BLOCKED".

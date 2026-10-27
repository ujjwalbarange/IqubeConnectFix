# TVS iQube BLE Dashboard & Control App — Complete Unfiltered Protocol Specification & Google AI Studio Prompt

This document contains the **100% reverse-engineered, unfiltered 20-byte BLE protocol specification** decoded from both your riding capture (`btsnooz_hci.log`) and your unfiltered charging capture (`btsnoop_hci.log`, `4,865` packets, `0` truncated packets), updated with:
- **Incoming Call Alert on Cluster (`0x5B 0x5C` with Caller's 1st Name + Call Symbol)**
- **Offline / Disconnected "Last Seen" State Persistence (Last Seen Time, Battery %, & Expected True Range)**
- **3-Tier Stable Charging Time Predictor (`2 hr 45 min` Default for `0–80%` → Previous Charging Sessions History → Live `1%` Step Timing)**
- **Automatic Connect-to-Disconnect Trip & Charging Session Logger**

## 1. Summary of Verified Hardware Protocol & New Features

1. **Exact GATT Service & Characteristic UUIDs (`btsnoop_hci.log` `#229, #270, #315`)**:
   - **Advertised BLE Device Name**: `TVSiQUBECJI573`
   - **Primary Service UUID (`0x001C..0xFFFF`)**: `5456534d-5647-5341-5342-454e544f5251` (ASCII: `"TVSMVGSASBENTORQ"`)
   - **Telemetry Notify Characteristic (`Handle 0x001E`)**: UUID `0x5354` (`00005354-0000-1000-8000-00805f9b34fb`, ASCII `"ST"`)
   - **Command Write Characteristic (`Handle 0x0022`)**: UUID `0x5352` (`00005352-0000-1000-8000-00805f9b34fb`, ASCII `"SR"`)
   - **Mandatory End-of-Frame Byte**: Every 20-byte packet in both directions ends with **`Byte 19 = 0xFF`**.
2. **Incoming Call Display on Cluster (`1st Name + Call Symbol`)**:
   - Since `0x5B 0x5C` (`[\`) reliably renders 17 ASCII characters on the scooter cluster (`Bytes 2..18`, with `Byte 19 = 0xFF`), when an incoming call occurs, the app extracts the caller's **First Name** and prepends a cluster-safe **Call Symbol** (e.g., `"CALL: Rahul"` or `"[C] Rahul"`), temporarily overriding the GPS location text on the cluster until the call ends, then automatically restoring the live GPS locality (e.g., `"Sonegaon"`).
3. **Disconnected / Offline "Last Seen" Persistence**:
   - Even when the phone is disconnected from the scooter, the dashboard persists and displays the **Last Seen Timestamp** (exact time + relative `"X mins ago"`), **Last Seen Battery `%`**, **Last Seen Odometer (`35,925.2 km`)**, and the **Expected True Range (Eco & Power)** calculated for that battery percentage.
4. **3-Tier Stable Charging Time Prediction (`2h 45m` for `0–80%` Default → Session History → Live `1%` Step Timing)**:
   - **Default Rate**: `2 hr 45 min` (`165 min` = `9,900 sec`) for `0% → 80%`, which equals **`123.75 seconds` (`2 min 3.75 sec`) per `1%`**.
   - **Historical Learning**: Saves every charging session to `localStorage` and updates the baseline `secondsPerPercent` from previous charging sessions.
   - **Live Connected Charging Transition**: When connected in charging state (`5a 11 Byte 5 === 0x01` or `5a 13 Byte 5 === 0x06`), the app **first** displays Expected Time to 80% and Expected Time to Full (100%) using the Default (`123.75s/%`) / Historical rate, and **then** once a full `1%` SoC step is timed in the current session, smoothly switches to the live measured `secondsPerPercent` (e.g. `115.7s/%`).

## 2. Copy-Paste Master Prompt for Google AI Studio

Copy the entire block below and paste it directly into **Google AI Studio**:

```markdown
You are a Senior Bluetooth Low Energy (BLE) & Mobility Application Architect. Build a complete, rock-solid, dark-mode TVS iQube Custom Cockpit Dashboard, Adaptive Charging Predictor, Incoming Call Cluster Relay, Offline Last-Seen Tracker, and Automatic Trip Logger web application using React, Tailwind CSS, Lucide Icons, and the Web Bluetooth API (including a built-in Simulation/Replay mode preloaded with real Riding + Charging telemetry).

### 1. EXACT BLE HARDWARE, GATT UUIDs & HANDLES
- **Advertised BLE Device Name**: `TVSiQUBECJI573` (filter by `namePrefix: 'TVS'` or `'TVSiQUBE'`)
- **Bluetooth MAC Address**: `F8:55:48:72:96:DA` (Texas Instruments BLE 4.2, `MTU = 247` bytes)
- **Primary SmartXonnect Service UUID (`Handles 0x001C..0xFFFF`)**:
  - `5456534d-5647-5341-5342-454e544f5251` (ASCII: `"TVSMVGSASBENTORQ"`)
- **Telemetry Notify Characteristic (`Handle 0x001E`, CCCD `0x001F`)**:
  - 16-bit UUID: `0x5354` (`00005354-0000-1000-8000-00805f9b34fb`, ASCII `"ST"`)
- **Command Write Characteristic (`Handle 0x0022`, CUD `0x0023`)**:
  - 16-bit UUID: `0x5352` (`00005352-0000-1000-8000-00805f9b34fb`, ASCII `"SR"`)

### 2. STABLE CONNECTION, FIFO WRITE QUEUE & HEARTBEAT PROCEDURE
1. **Strict 20-Byte Frame Rule (`Byte 19 = 0xFF`)**: Every outgoing packet written to `0x5352` (`Handle 0x0022`) MUST be a `Uint8Array(20)` with **`packet[19] = 0xFF`**.
2. **Serialized FIFO Write Queue**: Never issue concurrent GATT writes. Maintain a single FIFO queue with a `500 ms` ack timeout per write so clock heartbeats, location updates, call alerts, and trip button presses never collide or throw `"GATT operation already in progress"`.
3. **On Connect Sequence**:
   - Enable notifications on `0x5354` (`0x001E`).
   - Enqueue initial handshake: `[0x5B, 0x52, 0x55, 0x01, ...ascii("IQUBE_OSS"), ...0x00, 0xFF]`.
   - Start periodic `1.8s` Clock Sync Heartbeat (`[JD`):
     `[0x5B, 0x4A, 0x44, now.getHours(), now.getMinutes(), now.getSeconds(), 0x01, 0, ..., 0, 0xFF]`.

### 3. COMPLETE 20-BYTE INCOMING TELEMETRY PARSER (`0x5354` / `Handle 0x001E`)
Every notification on `0x5354` is 20 bytes (`Byte 0 = 0x5A`, `Byte 1 = Frame Type`, `Byte 19 = 0xFF`):
- **`0x5A 0x10` (Speed & Total Hardware Odometer — 4.7 Hz)**:
  - `Byte 2` = `uint8` Live Wheel Speed in `km/h` (`d[2]`).
  - `Bytes 3..5` = `uint24 Big-Endian` Total Hardware Odometer in `0.1 km` units: `totalOdometerKm = ((d[3] << 16) | (d[4] << 8) | d[5]) / 10` (e.g., `05 7B 54` = `35,925.2 km`).
  - Integrate `(speedKmh / 3.6) * dtSeconds` for sub-meter trip distance, live acceleration (`m/s²`), and `0–30` / `0–40 km/h` sprint timers.
- **`0x5A 0x13` (Battery SoC, Cluster Range & Charge Mode — 3.1 Hz)**:
  - `Byte 2` = `uint8` Live Battery SoC `%` (`d[2]`, e.g., `38%..42%` or `91%`).
  - `Bytes 3..4` = `uint16 Big-Endian` Cluster Projected Range in `0.1 km` units: `clusterRangeKm = ((d[3] << 8) | d[4]) / 10` (e.g., `0x0190` = `40.0 km` at `38%`, `0x01C2` = `45.0 km` at `42%`).
  - `Byte 5` = State Flag (`0x06` when charging).
- **`0x5A 0x11` (Hardware Trip A, Trip B & Charging Active Flag — 0.5 Hz)**:
  - `Bytes 2..4` = `uint24 Big-Endian` Hardware Trip A in `0.1 km` units (`((d[2] << 16) | (d[3] << 8) | d[4]) / 10`, e.g. `0.3 km`).
  - `Byte 5` = Charging Active Flag (`0x01` = Charging Active, `0x00` = Not Charging).
  - `Byte 6` = Battery SoC `%` mirror (`d[6]`).
  - `Bytes 7..9` = `uint24 Big-Endian` Hardware Trip B in `0.1 km` units (`((d[7] << 16) | (d[8] << 8) | d[9]) / 10`, e.g. `1.0 km`).
- **`0x5A 0x7D` (Vehicle Model & ID String — 3.1 Hz)**:
  - `Bytes 2..18` = ASCII Vehicle ID (`"TVSiQUBEJI573"`).

### 4. OUTGOING CLUSTER CONTROLS, LOCATION DUMP & INCOMING CALL DISPLAY (`0x5352` / `Handle 0x0022`)
1. **Three Verified Trip Control Buttons (`0x5A 0xF1 0x00 ... 0xFF`)**:
   - **`Switch to Trip A`**: Sends `[0x5A, 0xF1, 0x00, subCmdA, 0, ..., 0, 0xFF]` (switches cluster to Trip A; never label as "Dashboard Cluster Lamp").
   - **`Switch to Trip B`**: Sends `[0x5A, 0xF1, 0x00, subCmdB, 0, ..., 0, 0xFF]` (switches cluster to Trip B).
   - **`Reset Active Trip (A/B)`**: Sends `[0x5A, 0xF1, 0x00, subCmdReset, 0, ..., 0, 0xFF]` (resets whichever trip is currently active on the cluster).
2. **Live GPS Location Dump (`0x5B 0x5C` + 17 ASCII chars + `0xFF`)**:
   - Watch phone GPS (`navigator.geolocation`) and reverse-geocode via OpenStreetMap Nominatim (`https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}`) to get the current locality/street name (e.g., `"Sonegaon"`).
   - When no call is active, write `[0x5B, 0x5C, ...asciiBytes(locality.slice(0, 17)), ...0x00, 0xFF]` to `Handle 0x0022` on location change and every 10s.
3. **Incoming Call Alert with Caller's 1st Name + Call Symbol**:
   - Provide an **Incoming Call Relay & Simulator / Bridge** card:
     - When an incoming call is triggered/received with a caller name (e.g., `"Rahul Sharma"`), automatically extract the **1st Name** (`firstName = fullName.trim().split(/\s+/)[0]`, e.g. `"Rahul"`).
     - Display a prominent, animated **📞 Incoming Call: Rahul** banner with **Call Symbol + 1st Name** in the app cockpit, AND immediately push the caller's 1st name + call symbol to the scooter cluster's text display (`Handle 0x0022`) using `[0x5B, 0x5C, ...asciiBytes(("CALL:" + firstName).slice(0, 17)), ...0x00, 0xFF]` (plus `[0x5B, 0x5D, 0x43, 0x01, ...asciiBytes(firstName.slice(0, 15)), ...0x00, 0xFF]`).
     - While the call state is `Ringing`, pause background GPS location writes so the caller's 1st name stays locked on the scooter screen. As soon as the call is `Answered` / `Ended` (or after a configurable 15s auto-clear), automatically re-push the live GPS location (`"Sonegaon"`) to the cluster!

### 5. OFFLINE / DISCONNECTED "LAST SEEN" STATE PERSISTENCE (`localStorage`)
- Continuously persist a `lastSeenScooterState` object in `localStorage` on every telemetry update and on disconnect:
  `{ timestampISO, batteryPct, odometerKm, clusterRangeKm, tripAKm, tripBKm, locationName, wasCharging, vehicleId }`.
- **When Disconnected (`isConnected === false`)**:
  - Never blank out the gauges! Display the **Last Seen Battery Percentage** (e.g., `42%` or `91%`), **Last Seen Timestamp** (formatted as both exact time e.g. `"9:10 PM"` and live-updating relative time `"Last seen 14 mins ago"`), **Last Seen Location** (`"Sonegaon, Nagpur"`), and **Last Seen Odometer** (`35,925.2 km`).
  - Immediately calculate and display the **Expected True Range (Eco Mode & Power Mode)** for that exact Last Seen Battery Percentage so the rider knows their exact available range even when away from the scooter.

### 6. STABLE 3-TIER CHARGING TIME PREDICTION ENGINE (`2h 45m` Default → Session History → Live `1%` Timing)
Implement a rock-solid **Charging Time Predictor & Session Logger** (`localStorage` key `savedChargingSessions`):

1. **Tier 1 — Default Baseline (`2 hr 45 min` for `0% to 80%`)**:
   - `2 hr 45 min` = `165 minutes` = `9,900 seconds` for `80%` charge (`0% → 80%`).
   - Therefore, **`DEFAULT_SEC_PER_PCT = 9900 / 80 = 123.75` seconds per `1%`** (`2 min 3.75 sec` per `1%`).
   - At this default rate, `0% → 80%` = `2 hr 45 min`, and `0% → 100%` = `12,375 sec` (`3 hr 26 min 15 sec`).

2. **Tier 2 — Learned Rate from Previous Charging Sessions**:
   - Whenever the scooter is charging (`5a 11 Byte 5 === 0x01` or `5a 13 Byte 5 === 0x06`), record a Charging Session from start to disconnect/unplug.
   - When a charging session ends with `pctGained >= 1%` (`endPct - startPct >= 1`), save `{ id, startTimeISO, endTimeISO, startPct, endPct, pctGained, durationSec, avgSecPerPct }` to `savedChargingSessions` in `localStorage`.
   - Compute the historical charging speed across all saved charging sessions:
     `histSecPerPct = totalHistChargingSeconds / totalHistPctGained` (clamped to `[60, 240]` seconds per `1%` for stability).
   - Define `baselineSecPerPct = (totalHistPctGained >= 1) ? histSecPerPct : 123.75`.

3. **Tier 3 — Live Connected Charging State (`1st` Baseline → `Then` Live `1%` Step Timing)**:
   - When connected to the scooter in charging state (`isCharging === true`):
     - **Step A (Initial Connection — Before a `1%` step completes)**:
       - Immediately calculate and display **Expected Time to 80%** and **Expected Time to Full (100%)** using `baselineSecPerPct` (`123.75s/%` default = `2 hr 45 min` for `0–80%`, or the learned historical average from previous charging sessions).
       - Show badge: `"Prediction Mode: Default / Historical Baseline (2h 45m 0–80%)"`.
     - **Step B (Live `1%` Step Timing — Dynamic Adaptation)**:
       - Track the exact timestamp (`stepStartMs`) when the battery percentage changes.
       - To prevent a partial first percent (e.g. connecting when the scooter is already at `38.8%`) from causing an unstable jump, measure the elapsed time `deltaSec` for a full `+1%` increment (or if the first `+1%` takes `>= 60 seconds`, accept it directly; otherwise use the interval between the 1st and 2nd `+1%` ticks, e.g. `39% → 40%` = `112.5s`, `40% → 41%` = `114.8s`).
       - Once at least one valid `1%` step duration (`liveSecPerPct`, clamped to `[60, 240]s`) is measured in the current charging session, smoothly switch `activeSecPerPct = liveSecPerPct` (averaging all valid `1%` steps in the current session)!
       - Show badge: `"Prediction Mode: Live 1% Step Timing (" + Math.round(activeSecPerPct) + "s / 1%)"`.
     - **Formulas Displayed on Charging Card**:
       - `remainingSecTo80 = Math.max(0, 80 - currentBatteryPct) * activeSecPerPct` (formatted as `Xh Ym`, plus exact clock ETA e.g. `"80% at 10:52 PM"`).
       - `remainingSecToFull = Math.max(0, 100 - currentBatteryPct) * activeSecPerPct` (formatted as `Xh Ym`, plus exact clock ETA e.g. `"Full 100% at 11:30 PM"`).
       - Provide a side-by-side comparison showing **Default/Historical ETA** vs. **Live `1%`-Timed ETA** so the user can see both at a glance.

### 7. TRUE RANGE ENGINE (ECO `1.0 km/%` & POWER `0.75 km/%` + TRIP LEARNING) & TRIP LOGGER
- Automatically record every riding session (Connect → Disconnect when `distanceKm > 0` and not charging) to `localStorage` with Start/End Time, Start/End Location Name (`"Sonegaon"`), Kilometers Driven, Start/End Odometer (`35,925.2 km`), Start/End Battery `%`, Battery Used `%`, Peak/Avg Speed, and Efficiency (`km/%`).
- Compute **True Eco Range** and **True Power Range** for the current (or Last Seen offline) battery percentage:
  - If total recorded battery drop across saved + current trips is `< 1%`, default to **`1.00 km per 1%` for Eco Mode** (`100 km` at `100%`) and **`0.75 km per 1%` for Power Mode** (`75 km` at `100%`).
  - Once total battery drop is `>= 1%`, compute `measuredEcoKmPerPct = totalKm / totalBatteryPctUsed` and display `True Eco Range = batteryPct * measuredEcoKmPerPct` and `True Power Range = batteryPct * (measuredEcoKmPerPct * 0.75)`.
```

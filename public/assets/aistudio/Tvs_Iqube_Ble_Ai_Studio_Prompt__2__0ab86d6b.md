# TVS iQube BLE Dashboard & Control App — Technical Specification & Google AI Studio Prompt

This document provides the complete reverse-engineered Bluetooth Low Energy (BLE) specification from your `btsnooz_hci.log` capture—updated with your live hardware test findings—alongside a ready-to-paste **Master Prompt for Google AI Studio** to build your custom scooter dashboard, trip logger, and cluster control application.

## 1. Key Hardware Findings from Your Live Scooter Tests

1. **Trip Control Buttons (`5a f1 00...` on Handle `0x0022`)**:
   - **Button 1 — Switch to Trip A**: The command previously mapped to "Dashboard Cluster Lamp" actually switches the scooter's display to **Trip A**.
   - **Button 2 — Switch to Trip B**: The command previously mapped to "Cycle Trip" switches the scooter's display to **Trip B**.
   - **Button 3 — Reset Active Trip**: The reset command resets **whichever trip (Trip A or Trip B) is currently active** on the scooter's cluster, not exclusively Trip A.
2. **Why `5b 5c 57` (`[\W`) Changed the Cluster Location to `"W"`**:
   - In the `0x5B 0x5C` packet (`[\`), **only the first 2 bytes (`0x5B, 0x5C`) are the command header** for the cluster's Location Display!
   - **`Bytes 2..19` (18 bytes) are the ASCII text of the current location name**. In your original log, the 3rd byte was `0x57` (`'W'`) because the location name started with `"W"` (and Android's 15-byte `btsnooz` filter cut off `Bytes 3..19`). When the test app sent `5b 5c 57 00 00...`, the scooter cluster read `0x57` followed by null bytes and displayed **`"W"`** as your location!
   - **Fix**: Format the 20-byte location packet as `[0x5B, 0x5C, ...asciiBytes(currentLocationName)]` (padded with `0x00` to 20 bytes) so the app automatically pushes your real GPS reverse-geocoded locality (e.g., `"Sonegaon"`) onto the cluster screen.
3. **Removed Non-Working Turn-by-Turn Arrows & Caller ID Banner**:
   - Removed the non-working turn-by-turn navigation arrow feeder and caller ID banner UI, replacing them with **Automatic Live GPS Location Sync (`0x5B 0x5C` + ASCII Locality Name)** and a custom Cluster Location Text override.
4. **Automatic Connect-to-Disconnect Trip Recorder**:
   - Automatically records every ride session from BLE Connect to BLE Disconnect in `localStorage`, saving **Start Location**, **End Location**, **Kilometers Driven** (sub-meter integration + odometer delta), **Start/End Battery %**, **Battery Consumed %**, **Peak/Avg Speed**, and **Efficiency (`km/%`)**.
5. **True Range Calculator (Eco `1.0 km/%` & Power `0.75 km/%` + Historical & Current Trip Learning)**:
   - Computes real-world **True Range** by blending your **Current Active Trip** and **Saved Previous Trips**.
   - When historical/current battery drop is `< 1%` (not enough data yet), defaults to **`1.00 km` per `1%` (`100 km` at `100%`) for Eco Mode** and **`0.75 km` per `1%` (`75 km` at `100%`) for Power Mode**.

## 2. Copy-Paste Master Prompt for Google AI Studio

Copy the entire block below and paste it directly into **Google AI Studio** (either in the System Instructions / Prompt box or Build mode):

```markdown
You are a Senior Bluetooth Low Energy (BLE) & Mobility Application Architect. Build a complete, production-ready TVS iQube Custom Dashboard, Automatic Trip Logger, & Scooter Control application (using Web Bluetooth API + React + Tailwind CSS + Lucide Icons, with a modular BLE Service layer and a built-in Simulation/Replay mode for testing without hardware).

### 1. TARGET HARDWARE & BLE LINK SPECIFICATION
- **Target Device**: TVS iQube Electric Scooter (SmartXonnect BLE Cluster)
- **Bluetooth MAC Address**: `F8:55:48:72:96:DA` (Public Address, `AddrType = 0x00`)
- **Controller Hardware**: Texas Instruments Inc. (`Company ID = 0x000D`), Bluetooth Core 4.2 (`LMP Version = 0x08`, `Subversion = 0x0332`)
- **Link Parameters**:
  - Initial Connection Interval: `45.0 ms` (`0x0024`), Latency: `0`, Supervision Timeout: `5000 ms` (`0x01F4`)
  - Active Streaming Connection Parameters: Interval `30.0 ms` (`0x0018`), Peripheral Latency `29` (`0x001D`), Supervision Timeout `6000 ms` (`0x0258`)
  - Negotiated ATT MTU: Client requests `517` bytes; Scooter responds with `247` bytes (`0x00F7`)

### 2. GATT SERVICE & ATTRIBUTE HANDLE TABLE
The scooter exposes a custom 128-bit SmartXonnect Primary Service (`Handles 0x001C .. 0xFFFF`):
- **Telemetry TX Characteristic (Scooter → Phone Notifications)**:
  - Declaration Handle: `0x001D` | Value Handle: `0x001E` (`Properties = 0x10` Notify)
  - CCCD Descriptor Handle: `0x001F` (`UUID = 0x2902` Client Characteristic Configuration)
- **Command RX Characteristic (Phone → Scooter Writes)**:
  - Declaration Handle: `0x0021` | Value Handle: `0x0022` (`Properties = Write Request 0x12`, acknowledged by `ATT Write Response 0x13`)
  - User Description Descriptor Handle: `0x0023` (`UUID = 0x2901` Characteristic User Description)

*Dynamic Service Discovery*: Match the custom 128-bit service containing one `Notify` characteristic (`0x001E`) and one `Write` characteristic (`0x0022`), or allow selecting/pasting the UUID in Settings.

### 3. MANDATORY CONNECTION & INITIALIZATION PROCEDURE
1. **Scan & Connect**: Connect to device `F8:55:48:72:96:DA` (or name prefix `TVS` / `iQube`).
2. **Start Trip Session Recording**: Immediately on BLE connect, initialize an active trip session, fetch the phone's current GPS coordinates (`navigator.geolocation`), reverse-geocode the **Starting Location** (via OpenStreetMap Nominatim `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat={lat}&lon={lon}`), and latch the starting Battery SoC (`%`) and Odometer (`km`).
3. **Enable Telemetry Notifications**: Write `[0x01, 0x00]` to CCCD `Handle 0x001F` (`UUID 0x2902`).
4. **Send Initial Handshake Frame**: Send `[0x5B, 0x52, 0x55, ...]` (`[RU` padded to 20 bytes) to `Handle 0x0022` and await `ATT Write Response (0x13)`.
5. **Start Serialized FIFO Write Queue & Location Pusher**: Ensure all writes to `Handle 0x0022` are strictly serialized (awaiting `ATT Write Response 0x13` with a `500 ms` timeout before sending the next packet).

### 4. INCOMING DATA FORMAT & PARSING RULES (`Scooter → App` on Handle `0x001E`)
Every notification on `Handle 0x001E` is a **20-byte binary frame** (`Uint8Array(20)`) where **`Byte 0` is always `0x5A` (`'Z'`)** and **`Byte 1` is the Frame Type ID**:

| Frame Type (`Byte 0..1`) | Broadcast Rate | Verified Byte Layout (`Bytes 0..19`) | Parsing & Calculation Rules |
| :--- | :--- | :--- | :--- |
| **`0x5A 0x10` (Speed & Distance)** | `~4.7 Hz` (`~210 ms`) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x10`<br>`Byte 2` = `uint8` **Wheel Speed in `km/h`** (`0x00` = `0 km/h`; `0x01..0x34` = `1..52 km/h`) | 1. **Live Speed**: `speedKmh = data[2]`<br>2. **Sub-Meter Trip Distance Integration**: On each packet with time delta `dtSeconds`, accumulate `tripDistanceKm += ((lastSpeedKmh / 3.6) * dtSeconds) / 1000`.<br>3. **Acceleration & Sprint Timer**: Compute `accelMs2 = ((speedKmh - lastSpeedKmh) / 3.6) / dtSeconds` and auto-time `0–30 km/h` and `0–40 km/h` sprints. |
| **`0x5A 0x13` (Battery SoC)** | `~3.1 Hz` (`~320 ms`) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x13`<br>`Byte 2` = `uint8` **Battery SoC `%`** (`0x5B` = `91%`) | 1. **Battery SoC**: `batteryPercent = data[2]` (`0–100%`).<br>2. Feed `batteryPercent` into the **True Range Calculator** and **Active Trip Logger**. |
| **`0x5A 0x7D` (Cluster Status)** | `~3.1 Hz` (`~320 ms`) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x7D`<br>`Byte 2` = `0x54` (`84` decimal)<br>`Bytes 3..19` = Mode / Status flags | Parse `Byte 2` (`0x54`) and expose `Bytes 3..19` for ride mode (Eco vs. Power) and cluster indicator flags. |
| **`0x5A 0x11` (Total Odometer — 35,909 km)** | `0.5 Hz` (`2.020 s` period) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x11`<br>`Byte 2` = `0x00` (MSB of Big-Endian Odometer)<br>`Bytes 3..5` = Odometer bytes | Parse Big-Endian Total Odometer (defaulting to `35,909.0 km` in simulation/fallback):<br>• `uint32 BE` in `0.1 km` (`Bytes 2..5`): `((data[2]<<24)|(data[3]<<16)|(data[4]<<8)|data[5]) / 10` (`0x00057AB2` = `35,909.0 km`)<br>• `uint24 BE` in `1 km` (`Bytes 2..4`): `(data[2]<<16)|(data[3]<<8)|data[4]` (`0x008C45` = `35,909 km`) |

### 5. OUTGOING COMMANDS & LIVE LOCATION DUMP (`App → Scooter` on Handle `0x0022`)
All writes to `Handle 0x0022` are **20-byte frames (`Uint8Array(20)`)** sent via serialized `ATT Write Request (0x12)`:

#### A. Verified Trip Control Buttons (`0x5A 0xF1 0x00 ...`)
Do NOT include a "Dashboard Cluster Lamp" button—hardware testing confirmed that button was actually switching to Trip A! Provide these exact **3 Trip Control Buttons**:
1. **`Switch to Trip A` Button**: Sends the 20-byte `0x5A 0xF1 0x00` command that selects **Trip A** on the cluster (use the exact payload bytes that previously triggered "Dashboard Cluster Lamp" / subcommand `0x00` or `0x01`, and make the subcommand byte editable in a quick gear popover).
2. **`Switch to Trip B` Button**: Sends the 20-byte `0x5A 0xF1 0x00` command that selects **Trip B** on the cluster (use the exact payload bytes that previously triggered "Cycle Trip").
3. **`Reset Active Trip` Button**: Sends the 20-byte `0x5A 0xF1 0x00` reset command. Label it clearly as **"Reset Active Trip (A/B)"** because hardware testing confirmed it resets whichever trip (Trip A or Trip B) is currently active on the scooter cluster.

#### B. Live GPS Location Dump to Cluster (`0x5B 0x5C` + 18-Byte ASCII Location String)
- **Hardware Discovery**: In packet `5b 5c ...` (`[\`), **only `Byte 0 = 0x5B` (`'['`) and `Byte 1 = 0x5C` (`'\'`) are the header**. **`Bytes 2..19` (18 bytes) are the ASCII characters of the current location name** displayed on the scooter cluster! (Sending `5b 5c 57 00...` caused the cluster to display `"W"` because `0x57` is ASCII `'W'`).
- **Implementation**:
  ```ts
  function buildClusterLocationPacket(locationName: string): Uint8Array {
    const packet = new Uint8Array(20); // zero-filled (null-padded)
    packet[0] = 0x5B; // '['
    packet[1] = 0x5C; // '\' Location Frame ID
    const cleanAscii = locationName.replace(/[^\x20-\x7E]/g, '').trim().slice(0, 18);
    for (let i = 0; i < cleanAscii.length; i++) {
      packet[2 + i] = cleanAscii.charCodeAt(i);
    }
    return packet;
  }
  ```
- **Automatic & One-Tap Location Sync**:
  - Watch the phone's GPS via `navigator.geolocation.watchPosition` and reverse-geocode the coordinates into a concise locality/neighborhood name (e.g., `"Sonegaon"` or `"Sonegaon, Nagpur"` truncated to 18 ASCII chars).
  - Automatically push `buildClusterLocationPacket(currentLocality)` to `Handle 0x0022` every `5 seconds` (and whenever the detected locality changes), and provide a **"Sync Current Location to Cluster"** button (plus a custom text input if the user wants to type a custom 18-char label to display on the cluster's location field).
  - **Do NOT include Turn-by-Turn Navigation arrows or Caller ID Banner tabs**, as those are not used.

### 6. TRUE RANGE CALCULATION ENGINE (ECO vs. POWER MODE + TRIP LEARNING)
Implement a dedicated **True Range Engine** that calculates realistic remaining range in `km` for both **Eco Mode** and **Power Mode** based on current Battery SoC (`%`), the **Current Active Trip**, and **Saved Previous Trips**:

1. **Default Baseline Efficiency (when `< 1%` total battery drop has been recorded across current + previous trips)**:
   - **Eco Mode Default Rate**: **`1.00 km` per `1%` battery** (`100 km` full-charge range → at `91%` SoC, True Eco Range = **`91.0 km`**).
   - **Power Mode Default Rate**: **`0.75 km` per `1%` battery** (`75 km` for `100%` battery → at `91%` SoC, True Power Range = `91 * 0.75` = **`68.3 km`**).
   - Power-to-Eco Scaling Factor: `POWER_TO_ECO_RATIO = 0.75` (`75 km / 100 km`).

2. **Dynamic Calculation from Current Trip + Previous Saved Trips**:
   - Let `histKm` = sum of `distanceKm` from all saved trips in `localStorage` where `batteryUsedPct > 0`.
   - Let `histPct` = sum of `batteryUsedPct` (`startBattery - endBattery`) from those saved trips.
   - In the current connected trip, let `currKm = activeTripDistanceKm` and `currPct = Math.max(0, activeTripStartBattery - currentBattery)`.
   - Total measured distance: `totalKm = histKm + (currPct > 0 ? currKm : 0)`
   - Total measured battery drop: `totalPct = histPct + currPct`
   - **Rule**:
     - If `totalPct < 1` (not enough battery drop data yet):
       - `ecoKmPerPct = 1.00`
       - `powerKmPerPct = 0.75`
       - `calculationSource = "Default Baseline (Eco 1.0 km/% • Power 0.75 km/%)"`
     - If `totalPct >= 1` (learned from real riding):
       - Calculate raw measured rate: `measuredRate = totalKm / totalPct`
       - Normalize to Eco equivalent (if a trip was ridden in Power mode, its Eco-equivalent rate is `measuredRate / 0.75`; or blend `measuredRate` with a smooth clamp between `0.45` and `1.35 km/%`):
         - `ecoKmPerPct = measuredEcoRate`
         - `powerKmPerPct = measuredEcoRate * 0.75`
       - `calculationSource = "Learned from " + savedTripsCount + " Previous Trip(s) + Current Trip (" + totalKm.toFixed(1) + " km / " + totalPct + "%)"`
   - **Displayed True Ranges**:
     - `trueEcoRangeKm = currentBatteryPercent * ecoKmPerPct`
     - `truePowerRangeKm = currentBatteryPercent * powerKmPerPct`
   - Display both **True Eco Range (`km`)** and **True Power Range (`km`)** prominently on the main dashboard, with an **Active Mode Toggle (`Eco Mode` / `Power Mode`)** and a breakdown card showing the exact `km / 1%` rate and how many trips were used to compute it.

### 7. AUTOMATIC CONNECT-TO-DISCONNECT TRIP LOGGER (`localStorage` + Export)
Every time the app connects to the scooter (`BLE Connect` or `Simulation Start`) until it disconnects (`Manual Disconnect` or `0x08 Supervision Timeout`), automatically track and persist a **Trip Record**:
- **Fields Saved per Trip**:
  - `id`, `startTimeISO`, `endTimeISO`, `durationSeconds`
  - `startLocationName` (e.g. `"Sonegaon, Nagpur"`) & `startCoords` (`lat, lon`)
  - `endLocationName` & `endCoords` (`lat, lon`)
  - `distanceKm` (e.g. `1.02 km` / `146.2 m`)
  - `startOdometerKm` (`35,908.0 km`) & `endOdometerKm` (`35,909.0 km`)
  - `startBatteryPct` (`91%`), `endBatteryPct` (`90%`), `batteryUsedPct` (`1%`)
  - `efficiencyKmPerPct` (`distanceKm / batteryUsedPct` or `1.00 km/%` baseline)
  - `peakSpeedKmh` (`52 km/h`), `avgSpeedKmh`, `rideMode` (`Eco` / `Power`), `disconnectReason` (`Manual` / `Timeout 0x08`)
- **UI**: Provide a **Saved Trips & Efficiency History** view where the user can inspect all past trips (Connect → Disconnect), see start/end locations, add a manual test trip to verify the True Range learning math, delete individual trips, or export all trips as **CSV / JSON**.
```

## 3. Quick Reference: Updated Packet & Control Table

| Direction | GATT Handle | Hex Bytes (`20 Bytes Total`) | Control / Telemetry Role |
| :--- | :--- | :--- | :--- |
| `App → Scooter` | `0x0022` | `5b 52 55 ...` (`[RU`) | Initial connection handshake (`Packet #88`) |
| `App → Scooter` | `0x0022` | `5a f1 00 <subCmd_A> ...` | **Switch to Trip A** on cluster display (formerly mislabeled "Cluster Lamp") |
| `App → Scooter` | `0x0022` | `5a f1 00 <subCmd_B> ...` | **Switch to Trip B** on cluster display (formerly labeled "Cycle Trip") |
| `App → Scooter` | `0x0022` | `5a f1 00 <subCmd_Reset> ...` | **Reset Active Trip (A or B)** currently shown on cluster |
| `App → Scooter` | `0x0022` | `5b 5c <18B ASCII Location>` (`[\Sonegaon...`) | **Live Location Name Dump**: Writes up to 18 ASCII chars of current GPS locality to the cluster (`Bytes 2..19`) |
| `Scooter → App` | `0x001E` | `5a 10 <speed_kmh> ...` | Live Wheel Speed (`km/h`, `4.7 Hz`) + sub-meter distance integration |
| `Scooter → App` | `0x001E` | `5a 13 <soc_pct> ...` | Live Battery SoC (`0x5B` = `91%`, `3.1 Hz`) |
| `Scooter → App` | `0x001E` | `5a 11 00 05 7a b2 ...` (or `00 8c 45`) | Total Odometer (`35,909.0 km` = `0x00057AB2` in `0.1 km` BE, or `35,909 km` = `0x008C45` in `1 km` BE) |
| `Scooter → App` | `0x001E` | `5a 7d 54 ...` | Cluster & Ride Mode status frame (`3.1 Hz`) |

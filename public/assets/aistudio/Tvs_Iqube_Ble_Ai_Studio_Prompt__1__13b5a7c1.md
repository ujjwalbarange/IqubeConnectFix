# TVS iQube BLE Dashboard & Control App — Technical Specification & Google AI Studio Prompt

This document provides the complete reverse-engineered Bluetooth Low Energy (BLE) specification from your `btsnooz_hci.log` capture alongside a ready-to-paste **Master Prompt for Google AI Studio** to generate your custom scooter dashboard and control application.

## 1. Summary of Required BLE Data & Procedures

Before building the app in Google AI Studio, the model needs five concrete pieces of technical context from your capture:

1. **Device & Radio Identity**: Target MAC address (`F8:55:48:72:96:DA`, Public address), Texas Instruments BLE 4.2 controller (`Company ID 0x000D`, `LMP 0x08`, `Subversion 0x0332`), and connection parameters (`30.0 ms` interval, `29` peripheral latency, `6,000 ms` supervision timeout, `247-byte` negotiated ATT MTU).
2. **GATT Handle & UUID Map**: Custom 128-bit SmartXonnect Primary Service (`Handles 0x001C..0xFFFF`) containing the **Telemetry Notify Characteristic (`Value Handle 0x001E`, `CCCD Handle 0x001F`)** and **Command Write Characteristic (`Value Handle 0x0022`, `User Description 0x0023`)**.
3. **Connection & Handshake Sequence**: The exact 6-step initialization order—Connect → Discover GATT Services → Write `0x0100` to CCCD `0x001F` → Exchange MTU (`517` → `247`) → Send Initial Handshake `5b 52 55...` (`[RU`) on `0x0022` → Accept L2CAP Connection Parameter Update (`30.0 ms`, timeout `6,000 ms`).
4. **Incoming Telemetry Frame Format (`Scooter → App` on `0x001E`)**: 20-byte binary frames starting with `0x5A` (`'Z'`), multiplexed by `Byte 1` (`0x10` = 4.7 Hz real-time speed in `km/h`, `0x13` = 3.1 Hz battery SoC `%` & range, `0x7D` = 3.1 Hz cluster status, `0x11` = 0.5 Hz odometer/trip summary).
5. **Outgoing Command & Heartbeat Format (`App → Scooter` on `0x0022`)**: 20-byte `ATT Write Request (0x12)` frames strictly serialized with `ATT Write Response (0x13)` flow control (`5a f1 00...` for Trip Cycle/Reset, `5b 52 55...` for `[RU` handshake, `5b 4a 44...` for `[JD` heartbeat, `5b 5c 57...` for `[\W` turn-by-turn navigation, and `5b 5d 43...` for `[]C` caller/alert banner).

## 2. Copy-Paste Master Prompt for Google AI Studio

Copy the entire block below and paste it directly into **Google AI Studio** (either in the System Instructions / Prompt box or Build mode):

```markdown
You are a Senior Bluetooth Low Energy (BLE) & Mobility Application Architect. Build a complete, production-ready TVS iQube Custom Dashboard & Scooter Control application (using Web Bluetooth API + React + Tailwind CSS + Lucide Icons, with a modular BLE Service layer that also includes a built-in Simulation/Replay mode for testing without hardware).

### 1. TARGET HARDWARE & BLE LINK SPECIFICATION
- **Target Device**: TVS iQube Electric Scooter (SmartXonnect BLE Cluster)
- **Bluetooth MAC Address**: `F8:55:48:72:96:DA` (Public Address, `AddrType = 0x00`)
- **Controller Hardware**: Texas Instruments Inc. (`Company ID = 0x000D`), Bluetooth Core 4.2 (`LMP Version = 0x08`, `Subversion = 0x0332`)
- **Link Parameters**:
  - Initial Connection Interval: `45.0 ms` (`0x0024`), Latency: `0`, Supervision Timeout: `5000 ms` (`0x01F4`)
  - Fast GATT Discovery Interval: `7.50 ms` (`0x0006`)
  - Active Streaming Connection Parameters (requested by scooter via L2CAP Signaling `0x0005` at +6.0s): Interval `30.0 ms` (`0x0018`), Peripheral Latency `29` (`0x001D`), Supervision Timeout `6000 ms` (`0x0258`)
  - Negotiated ATT MTU: Client requests `517` bytes; Scooter responds with `247` bytes (`0x00F7`)
  - LE Data Length Extension: `MaxTxOctets = 251 bytes (2120 us)`, `MaxRxOctets = 27 bytes (328 us)`

### 2. GATT SERVICE & ATTRIBUTE HANDLE TABLE
The scooter exposes three Primary Services:
1. **Service 1 (`Handles 0x0001 .. 0x0007`, 16-bit Standard GAP/GATT Service)**
   - Characteristic Declaration `0x0002` / Value Handle `0x0003`: Properties `0x0C` (`Write Without Response | Write`)
2. **Service 2 (`Handles 0x0009 .. 0x001B`, 16-bit Device Info / GAP Service)**
   - Characteristic `0x000A` / Value Handle `0x000B`: Properties `0x02` (`Read`)
   - Characteristic `0x0010` / Value Handle `0x0011`: Properties `0x02` (`Read`)
   - Characteristic `0x0016` / Value Handle `0x0017`: Properties `0x02` (`Read`)
3. **Service 3 (`Handles 0x001C .. 0xFFFF`, 128-bit Custom SmartXonnect Telemetry & Control Service)**
   - **Telemetry TX Characteristic (Scooter → Phone Notifications)**:
     - Declaration Handle: `0x001D`
     - Value Handle: `0x001E` (`Properties = 0x10` Notify)
     - CCCD Descriptor Handle: `0x001F` (`UUID = 0x2902` Client Characteristic Configuration)
   - **Command RX Characteristic (Phone → Scooter Writes)**:
     - Declaration Handle: `0x0021`
     - Value Handle: `0x0022` (`Properties = Write Request 0x12`, acknowledged by `ATT Write Response 0x13`)
     - User Description Descriptor Handle: `0x0023` (`UUID = 0x2901` Characteristic User Description)

*Note on Dynamic UUID Discovery*: Because Android filtered HCI logs strip 128-bit UUID bytes during discovery, the app's BLE connection manager must support discovering Service 3 dynamically (by matching the custom 128-bit service containing one `Notify` characteristic with `0x2902` CCCD and one `Write` characteristic with `0x2901` CUD, or allowing the user to select/paste the discovered 128-bit Service UUID in a Settings modal).

### 3. MANDATORY CONNECTION & INITIALIZATION PROCEDURE
Implement the following sequential state machine on connection:
1. **Scan & Connect**: Scan for device MAC `F8:55:48:72:96:DA` (or name prefix `TVS` / `iQube` / custom 128-bit service) and establish GATT connection.
2. **Discover Services & Characteristics**: Locate the Telemetry Notify Characteristic (`Handle 0x001E`) and Command Write Characteristic (`Handle 0x0022`).
3. **Enable Telemetry Notifications**: Write `[0x01, 0x00]` (little-endian `0x0001`) to CCCD `Handle 0x001F` (`UUID 0x2902`) and await `ATT Write Response (0x13)`.
4. **Request MTU**: Request MTU `517` (negotiates to `247` bytes).
5. **Send Initial Handshake Frame**: Immediately send the 20-byte `[RU` handshake packet (`0x5B, 0x52, 0x55, ...`) to `Handle 0x0022` and await `ATT Write Response (0x13)`.
6. **Start Periodic Sync Loop & Telemetry Parser**: Process incoming 20-byte notifications on `0x001E` and run a serialized write queue on `0x0022`.

### 4. INCOMING DATA FORMAT & PARSING RULES (`Scooter → App` on Handle `0x001E`)
Every notification on `Handle 0x001E` is a **20-byte binary frame** (`Uint8Array(20)`) where **`Byte 0` is always `0x5A` (`90` decimal, ASCII `'Z'`)** and **`Byte 1` is the Frame Type ID**:

| Frame Type (`Byte 0..1`) | Broadcast Rate | Verified Byte Layout (`Bytes 0..19`) | Parsing & Derived Calculation Rules |
| :--- | :--- | :--- | :--- |
| **`0x5A 0x10` (Speed & Throttle)** | `~4.7 Hz` (`~210 ms` interval) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x10`<br>`Byte 2` = `uint8` **Wheel Speed in `km/h`** (`0x00` = `0 km/h` stationary; `0x01..0x34` = `1..52 km/h`)<br>`Bytes 3..19` = Additional throttle/motor bytes | 1. **Live Speed**: `speedKmh = data[2]`<br>2. **Sub-Meter Distance Integration**: On each packet with timestamp delta `dtSeconds`, accumulate `distanceMeters += (lastSpeedKmh / 3.6) * dtSeconds`. Display both exact meters (e.g. `146.2 m`) and cluster-rounded `0.1 km` steps (`Math.floor(distanceMeters / 100) / 10`).<br>3. **Instantaneous Acceleration**: `accelMs2 = ((speedKmh - lastSpeedKmh) / 3.6) / dtSeconds`.<br>4. **0–30 & 0–40 km/h Sprint Timer**: Automatically start timer when `speedKmh` transitions from `0` to `>0` and latch elapsed time when crossing `30 km/h` and `40 km/h`. |
| **`0x5A 0x13` (Battery & Range)** | `~3.1 Hz` (`~320 ms` interval) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x13`<br>`Byte 2` = `uint8` **Battery SoC `%`** (`0x5B` = `91%`)<br>`Bytes 3..19` = **Projected Range (`96 km` = `0x60`)** & battery pack metrics | 1. **Battery SoC**: `batteryPercent = data[2]` (`0–100%`).<br>2. **Configurable Range Offset**: Default to reading `Range (km)` from `data[rangeByteOffset]` (default `Byte 3`, configurable in UI Byte-Mapper for `Bytes 3..19`). |
| **`0x5A 0x7D` (Cluster & Ride Mode Status)** | `~3.1 Hz` (`~320 ms` interval) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x7D`<br>`Byte 2` = `0x54` (`84` decimal)<br>`Bytes 3..19` = Mode (Eco/Power), Regen, Indicators | Confirmed Cluster Status frame (since `35,908 km` = `0x8C44` and `35,909 km` = `0x8C45` do not end in `0x54`). Parse `Byte 2` (`0x54`) and expose `Bytes 3..19` for ride mode (Eco vs. Power) and indicator status. |
| **`0x5A 0x11` (Odometer & Trips — 35,908 → 35,909 km)** | `0.5 Hz` (`2.020 s` exact period) | `Byte 0` = `0x5A`<br>`Byte 1` = `0x11`<br>`Byte 2` = `0x00` (MSB of Big-Endian Odometer)<br>`Bytes 3..19` = Odometer (`35,909 km`) + Trip A/B (`0.1 km`) | Parse Big-Endian Odometer starting at `Byte 2 = 0x00`:<br>• `uint32 BE` in `0.1 km` (`Bytes 2..5`): `35,908.0 km` = `0x00 05 7A A8` → `35,909.0 km` = `0x00 05 7A B2` (`((data[2]<<24)|(data[3]<<16)|(data[4]<<8)|data[5]) / 10`)<br>• `uint24 BE` in `1 km` (`Bytes 2..4`): `35,908 km` = `0x00 8C 44` → `35,909 km` = `0x00 8C 45` (`(data[2]<<16)|(data[3]<<8)|data[4]`) |

### 5. OUTGOING DATA FORMAT & WRITE PROCEDURE (`App → Scooter` on Handle `0x0022`)
All commands written to `Handle 0x0022` are **20-byte binary frames** (`Uint8Array(20)`) sent via **`ATT Write Request (0x12)`** (Write With Response).
- **CRITICAL FLOW-CONTROL RULE**: You MUST implement a **FIFO Write Queue** that waits for the `ATT Write Response (0x13)` from the previous write (with a `500 ms` timeout) before sending the next packet. Never issue concurrent writes on `0x0022`.

| Command Type | 3-Byte Header (`Bytes 0..2`) | ASCII Prefix | When to Send | Payload Structure (`20 Bytes Total`) |
| :--- | :--- | :--- | :--- | :--- |
| **1. Initial Handshake** | `5b 52 55` | `[RU` | Sent **once** at `+1.45s` after connection setup | `[0x5B, 0x52, 0x55, ...17 bytes rider/auth payload]` |
| **2. Trip Cycle / Trip Reset** | `5a f1 00` | `Zñ ` | On user tap (**Cycle Trip A/B** or **Reset Active Trip**) | `[0x5A, 0xF1, 0x00, subCommandByte, ...padding to 20B]` (e.g. `0x01` = Cycle Trip, `0x02` = Reset Trip; allow editing full 20-byte hex in UI) |
| **3. Dashboard / Rider Sync** | `5b 4a 44` | `[JD` | Periodic background heartbeat every `~1.8s` | `[0x5B, 0x4A, 0x44, ...17 bytes rider/time sync]` |
| **4. Navigation Feeder** | `5b 5c 57` | `[\W` | Periodic (`~1.8s`) or on turn instruction update | `[0x5B, 0x5C, 0x57, turnIconId, distLow, distHigh, ...roadNameAscii]` |
| **5. Caller / Alert Banner** | `5b 5d 43` | `[]C` | Periodic (`~1.8s`) or on incoming call / custom alert | `[0x5B, 0x5D, 0x43, alertType, ...16 bytes ASCII caller/alert text]` |

### 6. LINK WATCHDOG, TIMEOUT (`0x08`) & AUTO-RECONNECT PROCEDURE
- The scooter uses a `6,000 ms` Supervision Timeout (`0x0258`). If no packet arrives from the scooter for `>2,500 ms` or an `ATT Write Request (0x12)` goes unacknowledged for `>500 ms` (matching Packet `#1676` before Disconnect `#1677` with `Reason 0x08`), display a **"Link Degraded / Supervision Timeout Imminent"** warning banner and pause background `[JD`/`[\W`/`[]C` heartbeats.
- On disconnect:
  1. Immediately capture the phone's current GPS coordinates (`navigator.geolocation`) and save a **"Last Parked Location"** record with timestamp, Battery SoC (`91%`), and Range (`96 km`).
  2. Start an exponential/periodic auto-reconnect loop (retrying connection with a 30-second cancel timer, matching the Android stack's `HCI_LE_Extended_Create_Connection` → `HCI_LE_Create_Connection_Cancel` pattern).

### 7. REQUIRED APPLICATION UI & FEATURES
Build a sleek, dark-mode automotive cockpit UI with 4 main views:
1. **Live Cockpit Dashboard**:
   - Large digital speedometer (`0–52+ km/h`), live acceleration bar (`m/s²`), peak speed badge, and `0–30` / `0–40 km/h` automatic drag timer.
   - Battery SoC gauge (`91%`) with dual Eco (`96 km`) / Power (`~75 km`) range estimates and real-time efficiency (`Wh/km` & `% per km`).
   - Dual-resolution Trip Meter showing both **Sub-Meter Integrated Distance (`146.2 m`)** and **Cluster Odometer Step (`0.1 km`)**, with one-tap **Cycle Trip A/B** and **Hold-to-Reset Trip** buttons (`5a f1 00`).
2. **Cluster Injection Studio (`Handle 0x0022`)**:
   - Interactive controls to push custom text banners (`5b 5d 43` / `[]C`), simulated Turn-by-Turn Navigation instructions (`5b 5c 57` / `[\W`), and custom Trip commands (`5a f1 00`) with a live FIFO Write Queue monitor.
3. **GPS + BLE Ride Logger & Last Parked Map**:
   - Fuses phone GPS (`navigator.geolocation`) with BLE speed (`5a 10`) and battery (`5a 13`), compares Wheel Speed vs. GPS Speed calibration, and stores the automatic "Last Parked Pin" on BLE disconnect.
4. **Live 20-Byte Hex Inspector & Byte-Offset Mapper**:
   - Displays the raw 20-byte hex/ASCII stream for `5a 10`, `5a 13`, `5a 7d`, and `5a 11` and lets the user dynamically assign which byte index (`Bytes 3..19`) maps to Range (`96 km`), Trip A, Trip B, and Odometer once connected to an unfiltered BLE stream.
   - Include a built-in **"Replay Captured Log (`btsnooz_hci.log`)"** mode pre-populated with the real 5 throttle bursts (`11 km/h`, `20 km/h`, `10 km/h`, `52 km/h`, `14 km/h`), `91%` battery (`5a 13 5b`), and `146.2 m` (`0.1 km`) distance so the app works out-of-the-box in the browser before pairing with the physical scooter.
```

## 3. Quick Reference: End-to-End Packet Cheat Sheet

| Direction | GATT Handle | Opcode | Hex Prefix (First 3 Value Bytes) | Frame Size (Wire / ATT) | Frequency / Trigger | Meaning |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `Phone → Scooter` | `0x001F` | `0x12` (Write Req) | `01 00` | `14B / 2B` | Once at connect (`#76`) | Enable notifications on `0x001E` (`CCCD = 0x0001`) |
| `Phone → Scooter` | `0x0022` | `0x12` (Write Req) | `5b 52 55` (`[RU`) | `32B / 20B` | Once at connect (`#88`) | Initial session / rider handshake |
| `Phone → Scooter` | `0x0022` | `0x12` (Write Req) | `5a f1 00` | `32B / 20B` | On user tap (`#270–#399`, 7x) | Cycle Trip A/B or Reset active trip |
| `Phone → Scooter` | `0x0022` | `0x12` (Write Req) | `5b 4a 44` (`[JD`) | `32B / 20B` | Every `~1.8s` (`54` pkts) | Rider / dashboard status heartbeat |
| `Phone → Scooter` | `0x0022` | `0x12` (Write Req) | `5b 5c 57` (`[\W`) | `32B / 20B` | Every `~1.8s` (`52` pkts) | Turn-by-turn navigation instruction sync |
| `Phone → Scooter` | `0x0022` | `0x12` (Write Req) | `5b 5d 43` (`[]C`) | `32B / 20B` | Every `~1.8s` (`48` pkts) | Caller ID / SMS / custom cluster banner |
| `Scooter → Phone` | `0x001E` | `0x1B` (Notify) | `5a 10 <speed>` | `32B / 20B` | `~4.7 Hz` (`443` pkts) | Real-time wheel speed in `km/h` (`0x00–0x34` = `0–52 km/h`) |
| `Scooter → Phone` | `0x001E` | `0x1B` (Notify) | `5a 13 5b` | `32B / 20B` | `~3.1 Hz` (`303` pkts) | Battery SoC = `91%` (`0x5B`); Range `96 km` in `Bytes 3..19` |
| `Scooter → Phone` | `0x001E` | `0x1B` (Notify) | `5a 7d 54` | `32B / 20B` | `~3.1 Hz` (`303` pkts) | Cluster status (`Byte 2 = 84` / `0x54`) + mode/regen flags |
| `Scooter → Phone` | `0x001E` | `0x1B` (Notify) | `5a 11 00` | `32B / 20B` | `0.5 Hz` (`48` pkts, `2.02s`) | Odometer & Trip A/B summary (`0.1 km` steps in `Bytes 3..19`) |

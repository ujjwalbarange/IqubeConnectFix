# TVS iQube SmartXonnect BLE Protocol Specification & Packet Architecture

Authoritative, bytecode-verified Bluetooth Low Energy (BLE) protocol specification for the **TVS iQube Electric Scooter** (`TVSiQUBECJI573`, Texas Instruments BLE 4.2 controller, MAC `F8:55:48:72:96:DA`). 

Decompiled and verified directly from TVS Connect Android DEX / Smali classes:
- `BleEngine.smali`
- `BleNavigationDataSourceImp.smali`
- `BleNavigationSendData.smali`
- `BluetoothUtil.smali`
- `BlePacketsProvider.smali`
- `IQubeDataSenderToCluster.smali`
- `IQubeClusterDataReceiver.smali`
- `U347ClusterDataReceiver.smali`
- `IncomingFrameIdentifier.smali`
- `U388SpeedometerFrameData.smali`
- `U467BatteryPercentage.smali`
- `TurnTypeConstants.smali`

---

## 1. GATT Service, Characteristics & Framing Architecture

| Parameter | Specification | Details / Notes |
| :--- | :--- | :--- |
| **Advertised Device Name** | `TVSiQUBECJI573` | Scan filter prefix: `namePrefix: "TVS"` |
| **Hardware MAC Address** | `F8:55:48:72:96:DA` | Texas Instruments Inc. (`Company ID: 0x000D`, LMP `0x08` BT 4.2) |
| **Primary Service UUID** | `5456534d-5647-5341-5342-454e544f5251` | ASCII string representation: `"TVSMVGSASBENTORQ"` |
| **Telemetry Notify Characteristic** | `00005354-0000-1000-8000-00805f9b34fb` | Handle `0x001E`, CCCD `0x001F` (`0x5354`, ASCII `"ST"`) |
| **Command Write Characteristic** | `00005352-0000-1000-8000-00805f9b34fb` | Handle `0x0022`, CUD `0x0023` (`0x5352`, ASCII `"SR"`) |
| **Negotiated ATT MTU** | `247` bytes | Host requests `517`, peripheral caps at `247` bytes |
| **Frame Size & Trailer** | Fixed `20` bytes | Every packet ends with `Byte [19] = 0xFF` (`POSTFIX_109` / `endByte`) |
| **Inter-Command Delay** | `120 ms` FIFO queue | Pause `400 ms` between Navigation Packet 1 and Packet 2 |

### Checksum Verification Algorithm (`BluetoothUtil.isValidChecksum`)
Located in `BluetoothUtil.isValidChecksum([B)Z` at offset `0x3800c4`:
1. Drops the last 2 bytes (`ArraysKt.dropLast(bytes, 2)` -> evaluates `Byte [0..17]`).
2. Sums all unsigned byte values: `sumMod = (sum(Byte[0..17]) & 0xFF) % 256`.
3. Validates against Byte 18: `((Byte[18] & 0xFF) + sumMod) == 0xFF` (`255`).
4. **Outgoing Generation Formula**:
   ```typescript
   function calculateChecksum(packet: Uint8Array): number {
     let sum = 0;
     for (let i = 0; i < 18; i++) {
       sum = (sum + (packet[i] & 0xFF)) & 0xFFFF;
     }
     return (0xFF - (sum % 256)) & 0xFF;
   }
   ```
*Note on U_347 Firmware:* On legacy `U_347` clusters (`isOldProtocolVersionCluster() == true`), `IQubeClusterDataReceiver` bypasses checksum validation because `Byte [18]` carries a rolling sequence counter (`0x50..0x6F`).

---

## 2. Turn-by-Turn Navigation Injection Engine

Audited in `BleEngine.sendNavigationData`, `BleNavigationDataSourceImp.sendNavigationDataToCluster`, `BleNavigationSendData$Companion.byteArrayForNavigation`, and `BluetoothUtil.createNavigationAssistArray`.

### Two-Packet Transmission Pipeline
1. **Step 1:** Transmit `pairData.second` (Maneuver, Distance & ETA Frame: `0x5B 0x49`) to Handle `0x0022`.
2. **Step 2:** Pause asynchronously for exactly `400 ms` (`Thread.sleep(400L)`).
3. **Step 3:** Transmit `pairData.first` (Street Name / Destination Reached Frame: `0x5B 0x4E`) to Handle `0x0022`.
4. **Step 4:** Trigger local app state `hudCommunicationViewModel.navigationInProgress(turnType, distanceMeters, unit)`.

### Packet 1: Maneuver Icon, Distance & ETA (`0x5B 0x49` / `DATA_ID_NAVIGATION_CONTROL_2 = 73`)
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x49 ('I')
Byte [2]    : turnCode (0x00..0x48 from TurnTypeConstants)
Byte [3..4] : uint16 Big-Endian Distance
Byte [5]    : Distance Unit Flag (0x01 = meters, 0x02 = 0.1 km units)
Byte [6]    : Remaining Travel Duration (ETA Hours: 0..23)
Byte [7]    : Remaining Travel Duration (ETA Minutes: 0..59)
Byte [8..18]: 0x00 (Padding zeros)
Byte [19]   : 0xFF (Trailer)
```

#### Turn Type Opcode Mapping (`TurnTypeConstants.smali`)
| Hex Opcode | Constant Identifier | Visual Cluster Maneuver |
| :--- | :--- | :--- |
| `0x00` | `TURN_LEFT` | Standard 90° Left Turn |
| `0x01` | `TURN_SHARPLY_LEFT` | Sharp Left Turn |
| `0x02` | `TURN_SLIGHTLY_LEFT` | Slight Left Bear |
| `0x03` | `TURN_RIGHT` | Standard 90° Right Turn |
| `0x04` | `TURN_SHARPLY_RIGHT` | Sharp Right Turn |
| `0x05` | `TURN_SLIGHTLY_RIGHT` | Slight Right Bear |
| `0x06` | `U_TURN` | Left U-Turn |
| `0x07` | `CONTINUE` | Continue Straight |
| `0x08` | `DESTINATION` | Destination Reached Icon |
| `0x09` | `KEEP_LEFT` | Keep Left |
| `0x0A` | `KEEP_RIGHT` | Keep Right |
| `0x0B` | `TAKE_LEFT_AT` | Take Left At Intersection |
| `0x0C` | `TAKE_RIGHT_AT` | Take Right At Intersection |
| `0x0D` | `TAKE_LEFT_AT_END` | Take Left At T-End |
| `0x0E` | `TAKE_RIGHT_AT_END`| Take Right At T-End |
| `0x0F` | `AT_FORK_LEFT` | Fork Left |
| `0x10` | `AT_FORK_RIGHT` | Fork Right |
| `0x15` | `CONTINUE_STRAIGHT_AT`| Straight at Intersection |
| `0x29` | `RIGHT_U_TURN` | Right U-Turn |
| `0x44` | `ROUNDABOUT` | Roundabout Entry |
| `0x45` | `ROUNDABOUT_LEFT` | Roundabout Left Exit |
| `0x48` | `ROTARY` | Rotary / Traffic Circle |

#### Google Navigation 83-Element Lookup Array
`BleNavigationDataSourceImp.googleMapping` at offset `0x0e1270`:
```javascript
const googleMapping = [
  40, 21, 22, 65, 66, 57,  0, 10,  7, 17,  2, 12,  1, 11, 19,  9, 67,  8, 18,  7,
  17, 57,  0, 10,  7, 17,  2, 12,  1, 11, 19,  9, 57,  8, 18,  7, 17,  2, 12,  1,
  11, 19,  9, 47, 55, 48, 56, 28, 39, 32, 35, 29, 38, 31, 36, 27, 40, 33, 34, 60,
  61, 62, 63, 23, 59, 57, 42, 43, 44, 45, 46, 50, 51, 52, 53, 54,  4, 14, 20, 25,
  24, 26, 49
];
```

### Packet 2: Street Name / Destination Frame (`0x5B 0x4E` / `DATA_ID_NAVIGATION_CONTROL = 78`)
- **Standard Turn Instruction (`createNavigationAssistArray`):**
  ```text
  Byte [0]    : 0x5B ('[')
  Byte [1]    : 0x4E ('N')
  Byte [2..3] : 0x4E 0x41 ('NA')
  Byte [4..18]: ASCII / UTF-8 Street Name (max 15 characters, 0x00 padded)
  Byte [19]   : 0xFF (Trailer)
  ```
- **Destination Reached Frame (`turnCode === 0x08`, `createDestReachedArray`):**
  ```text
  Byte [0]    : 0x5B ('[')
  Byte [1]    : 0x4E ('N')
  Byte [2..3] : 0x00 0x00
  Byte [4..18]: ASCII / UTF-8 Destination Name (max 15 characters, 0x00 padded)
  Byte [19]   : 0xFF (Trailer)
  ```

---

## 3. Caller ID & Call State Banner Relay

Audited in `BluetoothUtil.createMobileToClusterArray`, `BlePacketsProvider.returnContactInfoPacket`, and `BleEngine.writeCallStateForIQube`.

### Caller ID Banner (`0x5B 0x43` / `DATA_ID_CALLER = 0x43`)
Transmitted to Handle `0x0022` every `500 ms` via `iQubeCallerDataSendTimer` while ringing:
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x43 ('C')
Byte [2..18]: UTF-8 Caller First Name (max 17 bytes, 0x00 padded)
Byte [19]   : 0xFF (Trailer)
```

### Call State Control Frame (`0x5B 0x5D 0x43` / `writeCallStateForIQube`)
Controls the phone icon in the cluster's top status tray:
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x5D (']')
Byte [2]    : 0x43 ('C')
Byte [3]    : callState (0x00 = Ringing, 0x01 = Answered, 0x02 = Ended / Disconnected)
Byte [4..18]: 0x00 (Padding zeros)
Byte [19]   : 0xFF (Trailer)
```
*Operational Rule:* While ringing, background locality frames (`0x5B 0x5C`) are paused so the caller banner remains visible in the cyan rectangular display area below the location pin. When call state `0x02` is sent, resume live locality updates.

---

## 4. Vehicle Control, Sync & Multimedia Frames

Audited in `BluetoothUtil.createVehicleDataArray`, `createLocationDataArray`, `createRiderNameArray`, and `BlePacketsProvider.sendIQubeMobileData`.

### Trip Switch & Reset Controller (`0x5A 0xF1`)
Toggles between Trip A and Trip B or clears the active trip:
```text
Byte [0]    : 0x5A
Byte [1]    : 0xF1
Byte [2]    : 0x00
Byte [3]    : tripVal (0x00 = Trip A, 0x01 = Trip B, 0x02 = Reset Active Trip)
Byte [4]    : 0x00
Byte [5]    : 0x02 (Mode control)
Byte [6..8] : 0x00
Byte [9]    : Day of Month (1..31)
Byte [10]   : Month (1..12)
Byte [11]   : Year % 100 (e.g. 26 for 2026)
Byte [12]   : tripVal (Mirror)
Byte [13]   : 0x00
Byte [14]   : 0x01
Byte [15..18]: 0x00
Byte [19]   : 0xFF (Trailer)
```

### GPS Locality Display (`0x5B 0x5C` / `DATA_ID_CURRENT_LOCATION`)
Sets the locality text displayed on the top line alongside the hardware pin (`📍 Dhantoli`):
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x5C ('\')
Byte [2..18]: UTF-8 Locality String (max 17 bytes, 0x00 padded)
Byte [19]   : 0xFF (Trailer)
```

### Local Clock & Date Synchronization (`0x5B 0x4A 0x44`)
Synchronizes the cluster RTC to the smartphone's 24-hour local time (stopping the `01:10 AM` reset):
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x4A ('J')
Byte [2]    : 0x44 ('D')
Byte [3]    : Local Hours (0..23)
Byte [4]    : Local Minutes (0..59)
Byte [5]    : Local Seconds (0..59)
Byte [6]    : 0x01 (Active sync status flag)
Byte [7]    : Day of Month (1..31)
Byte [8]    : Month (1..12)
Byte [9]    : Year % 100 (e.g. 26)
Byte [10..18]: 0x00
Byte [19]   : 0xFF (Trailer)
```

### Phone Status Bar Tray Sync (`0x5B 0x52 0x55`)
Reflects smartphone battery and cellular reception in the cluster top bar:
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x52 ('R')
Byte [2]    : 0x55 ('U')
Byte [3]    : Phone Battery Percentage (0..100%)
Byte [4]    : Cellular Signal Bars (0..4)
Byte [5]    : 0x01 (Bluetooth Connected & Cellular Link Active)
Byte [6..18]: 0x00
Byte [19]   : 0xFF (Trailer)
```

### Rider Greeting Banner (`0x5B 0x52`)
Sets the greeting displayed on connection handshake:
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x52 ('R')
Byte [2..18]: UTF-8 Rider Name (max 17 bytes, 0x00 padded)
Byte [19]   : 0xFF (Trailer)
```

### Music Track Title Banner (`0x5B 0x62` / `SONG_NAME_PACKET`)
Displays the active media track title in the lower cyan rectangular display area:
```text
Byte [0]    : 0x5B ('[')
Byte [1]    : 0x62 ('b')
Byte [2..18]: UTF-8 Track Title (max 17 bytes, 0x00 padded)
Byte [19]   : 0xFF (Trailer)
```

---

## 5. Incoming Telemetry Frame Decoders (`0x5354` / Handle `0x001E`)

Audited in `IQubeClusterDataReceiver.parseData`, `U347ClusterDataReceiver`, and `IncomingFrameIdentifier`.

### Frame `0x5A 0x10` (Live Wheel Speed & Hardware Odometer — 4.7 Hz)
```text
Byte [0..1] : 0x5A 0x10 (Header)
Byte [2]    : uint8 Live Wheel Speed in km/h (d[2] & 0xFF)
Byte [3..5] : uint24 Big-Endian Total Hardware Odometer in 0.1 km units:
              totalOdoKm = ((d[3] << 16) | (d[4] << 8) | d[5]) / 10.0
Byte [11]   : Cluster Brightness Level (VAClusterOperator.saveCurrentClusterBrightness)
Byte [12]   : Ignition & Assist Flag:
              0x80 (-128) = Ignition Turned OFF -> saveLocationData()
              0x04        = Voice Assist Button Trigger
Byte [13]   : 0–30 km/h Acceleration Sprint Time in milliseconds: d[13] * 10
Byte [19]   : 0xFF (Trailer)
```

### Frame `0x5A 0x13` (Battery SoC, Projected DTE & Charge Mode — 3.1 Hz)
```text
Byte [0..1] : 0x5A 0x13 (Header)
Byte [2]    : uint8 Battery SoC Percentage (0..100%)
Byte [3..4] : uint16 Big-Endian Distance-to-Empty (BMS DTE) in 0.1 km units:
              clusterDteKm = ((d[3] << 8) | d[4]) / 10.0
Byte [5]    : Charge State Flag:
              0x06 = Active Charging
Byte [19]   : 0xFF (Trailer)
```

### Frame `0x5A 0x11` (Hardware Trips A/B & Charging Active Flag — 0.5 Hz)
```text
Byte [0..1] : 0x5A 0x11 (Header)
Byte [2..4] : uint24 Big-Endian Hardware Trip A in 0.1 km units:
              tripAKm = ((d[2] << 16) | (d[3] << 8) | d[4]) / 10.0
Byte [5]    : Charging Status Flag:
              0x01 = Charging Active
              0x00 = Not Charging
Byte [6]    : Battery SoC % mirror (d[6] & 0xFF)
Byte [7..9] : uint24 Big-Endian Hardware Trip B in 0.1 km units:
              tripBKm = ((d[7] << 16) | (d[8] << 8) | d[9]) / 10.0
Byte [19]   : 0xFF (Trailer)
```

### Frame `0x5A 0x7D` (Vehicle Hardware Identifier String — 3.1 Hz)
```text
Byte [0..1] : 0x5A 0x7D (Header)
Byte [2..18]: 17-byte ASCII Vehicle Identification String ("TVSiQUBECJI573")
Byte [19]   : 0xFF (Trailer)
```

### Frame `0x5A 0x6B` (Cluster Handlebar Music Control Event)
Emitted when the rider presses physical cluster media switches:
```text
Byte [0..1] : 0x5A 0x6B (Header)
Byte [2]    : Music Command Code:
              0x00 = Play
              0x01 = Pause
              0x02 = Play / Pause Toggle
              0x03 = Next Track
              0x04 = Previous Track
              0x05 = Volume Up
              0x06 = Volume Down
Byte [19]   : 0xFF (Trailer)
```

---

## 6. Self-Contained TypeScript Packet Builders

```typescript
/**
 * TVS iQube SmartXonnect BLE Packet Builders
 * Target Vehicle: TVS iQube (U347 / U467)
 * Characteristic: Handle 0x0022 (0x5352)
 */

const FRAME_SIZE = 20;
const TRAILER = 0xFF;
const textEncoder = new TextEncoder();

/** 1. Turn-by-Turn Navigation Packet 1 (Maneuver, Distance & ETA: 0x5B 0x49) */
export function buildNavManeuverPacket1(
  turnCode: number,
  distanceValue: number,
  unitByte: 0x01 | 0x02 = 0x01,
  etaMinutes: number = 12
): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x49; // 'I' (DATA_ID_NAVIGATION_CONTROL_2)
  p[2] = turnCode & 0xFF;

  const rawDist = Math.max(0, Math.round(distanceValue));
  if (unitByte === 0x01 && rawDist >= 1000) {
    const tenthsKm = Math.min(65535, Math.round(rawDist / 100));
    p[3] = (tenthsKm >> 8) & 0xFF;
    p[4] = tenthsKm & 0xFF;
    p[5] = 0x02; // 0.1 km units
  } else {
    const clamped = Math.min(65535, rawDist);
    p[3] = (clamped >> 8) & 0xFF;
    p[4] = clamped & 0xFF;
    p[5] = unitByte & 0xFF;
  }

  const safeEta = Math.max(0, Math.floor(etaMinutes));
  p[6] = Math.floor(safeEta / 60) & 0xFF; // ETA Hours
  p[7] = (safeEta % 60) & 0xFF;           // ETA Minutes
  p[19] = TRAILER;
  return p;
}

/** 2. Turn-by-Turn Navigation Packet 2 (Street Name / Destination Reached: 0x5B 0x4E) */
export function buildNavStreetPacket2(turnCode: number, streetOrDest: string): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x4E; // 'N' (DATA_ID_NAVIGATION_CONTROL)
  if ((turnCode & 0xFF) === 0x08) {
    p[2] = 0x00;
    p[3] = 0x00;
  } else {
    p[2] = 0x4E; // 'N'
    p[3] = 0x41; // 'A'
  }
  const clean = streetOrDest.replace(/[^\x20-\x7E]/g, "").trim().slice(0, 15);
  p.set(textEncoder.encode(clean).slice(0, 15), 4);
  p[19] = TRAILER;
  return p;
}

/** 3. Caller ID Banner Frame (0x5B 0x43) */
export function buildCallerIdBannerPacket(fullName: string): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x43; // 'C'
  const firstName = (fullName.trim().split(/\s+/)[0] || "Caller")
    .replace(/[^\x20-\x7E]/g, "")
    .slice(0, 17);
  p.set(textEncoder.encode(firstName).slice(0, 17), 2);
  p[19] = TRAILER;
  return p;
}

/** 4. Call State Control Frame (0x5B 0x5D 0x43) */
export function buildCallStatePacket(callState: 0x00 | 0x01 | 0x02, callerName = ""): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x5D; // ']'
  p[2] = 0x43; // 'C'
  p[3] = callState & 0xFF;
  if (callerName) {
    const clean = callerName.replace(/[^\x20-\x7E]/g, "").trim().slice(0, 15);
    p.set(textEncoder.encode(clean).slice(0, 15), 4);
  }
  p[19] = TRAILER;
  return p;
}

/** 5. Hardware Trip Switch & Reset Frame (0x5A 0xF1) */
export function buildTripControlPacket(tripVal: 0x00 | 0x01 | 0x02, now = new Date()): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5A;
  p[1] = 0xF1;
  p[2] = 0x00;
  p[3] = tripVal; // 0x00=Trip A, 0x01=Trip B, 0x02=Reset
  p[5] = 0x02; // Mode control
  p[9] = now.getDate() & 0xFF;
  p[10] = (now.getMonth() + 1) & 0xFF;
  p[11] = (now.getFullYear() % 100) & 0xFF;
  p[12] = tripVal;
  p[14] = 0x01;
  p[19] = TRAILER;
  return p;
}

/** 6. Live GPS Locality Frame (0x5B 0x5C) */
export function buildLocalityPacket(locality: string): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x5C; // '\'
  const clean = locality.replace(/[^\x20-\x7E]/g, "").trim().slice(0, 17);
  p.set(textEncoder.encode(clean).slice(0, 17), 2);
  p[19] = TRAILER;
  return p;
}

/** 7. 24-Hour Local Clock Synchronization Frame (0x5B 0x4A 0x44) */
export function buildClockSyncPacket(now = new Date()): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x4A; // 'J'
  p[2] = 0x44; // 'D'
  p[3] = now.getHours() & 0xFF;
  p[4] = now.getMinutes() & 0xFF;
  p[5] = now.getSeconds() & 0xFF;
  p[6] = 0x01;
  p[7] = now.getDate() & 0xFF;
  p[8] = (now.getMonth() + 1) & 0xFF;
  p[9] = (now.getFullYear() % 100) & 0xFF;
  p[19] = TRAILER;
  return p;
}

/** 8. Smartphone Battery & Network Status Tray Frame (0x5B 0x52 0x55) */
export function buildPhoneStatusPacket(batteryPct: number, signalBars: number = 4): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B; // '['
  p[1] = 0x52; // 'R'
  p[2] = 0x55; // 'U'
  p[3] = Math.round(Math.min(100, Math.max(0, batteryPct))) & 0xFF;
  p[4] = Math.min(4, Math.max(0, signalBars)) & 0xFF;
  p[5] = 0x01; // Network active
  p[19] = TRAILER;
  return p;
}

/** 9. Rider Greeting (0x52) & Music Track Banner (0x62) */
export function buildBannerPacket(opcode: 0x52 | 0x62, text: string): Uint8Array {
  const p = new Uint8Array(FRAME_SIZE);
  p[0] = 0x5B;
  p[1] = opcode;
  const clean = text.replace(/[^\x20-\x7E]/g, "").trim().slice(0, 17);
  p.set(textEncoder.encode(clean).slice(0, 17), 2);
  p[19] = TRAILER;
  return p;
}
```

# iQube Cockpit — Frontend UI/UX Design & Component Specification

This specification is designed specifically for **UI/UX Designers and Frontend Engineers** working on the TVS iQube Electric Scooter Companion interface. It outlines the visual hierarchy, component architecture, design tokens, and user experience flows without requiring in-depth knowledge of backend bluetooth packet framing or hardware protocol internals.

---

## 1. Product Vision & Design Philosophy

The **iQube Cockpit** app is an elevated electric vehicle digital dashboard and companion app tailored for everyday scooter riders. 

### Core UX Principles
- **At-a-Glance Glancability**: Critical vehicle telemetry (battery SoC %, remaining range in kilometers, current speed) must be instantly readable within 200 milliseconds, whether mounted on a handlebar or pulled out at a traffic light.
- **High-Contrast Dark Theme**: The entire UI uses an ultra-dark obsidian background (`#0A0E17`) with vivid, high-saturation neon accents (`#00F5A0` Neon Mint, `#00E5FF` Electric Cyan, `#FFB300` Amber Warning, `#FF5252` Energetic Coral) to maximize contrast in bright outdoor sunlight and eliminate night-time glare.
- **Haptic & Visual Feedback**: Every interactive component has distinct active/hover ripple states, minimum 48dp touch targets for gloved fingers, and immediate responsive updates.
- **Non-Intrusive Background Reliability**: The vehicle status, battery level, and charging progress are accessible through passive channels (Home Screen Widgets, Quick Settings Tile, Clean Notification Bar) without forcing riders to keep the app in the foreground.

---

## 2. Design System & Tokens

### 2.1 Color Palette

| Token Name | Hex Code | UI Purpose |
|---|---|---|
| `ObsidianBg` | `#0A0E17` | Canvas background, edge-to-edge window backdrop |
| `SlateSurface` | `#131B2E` | Primary card background, sheet containers |
| `SlateCardElevated` | `#1A243B` | Nested sections, interactive card items, text field surfaces |
| `SlateBorder` | `#263554` | 1dp card borders, dividers, subtle section outlines |
| `NeonMint` | `#00F5A0` | Primary brand accent, Eco mode, 100% battery, positive state |
| `ElectricCyan` | `#00E5FF` | Secondary accent, digital telemetry, interactive chips, links |
| `AmberWarning` | `#FFB300` | Power mode, incoming call banner, low battery (<20%), caution |
| `EnergeticCoral` | `#FF5252` | Critical alerts (<10% battery), audible alarms, stop actions, disconnects |
| `TextPrimary` | `#F1F5F9` | High-emphasis headers, speed digits, metric values |
| `TextSecondary` | `#94A3B8` | Subtitles, measurement units (km/h, km, %), metadata labels |
| `TextMuted` | `#64748B` | Timestamps, inactive tabs, placeholder text |

### 2.2 Typography Scale (Material 3)

- **Display Large / Speed Value**: 54sp, Bold, Monospace/Tabular Numbers. Designed for instant speed reading.
- **Display Medium / Battery SoC %**: 38sp, Extra Bold. Used in the central battery gauge.
- **Headline Small / Screen Titles**: 24sp, SemiBold. Used for screen titles and major card headings.
- **Title Medium / Section Headers**: 18sp, Bold, Uppercase Letterspacing (`0.5sp`). Used for section headers (e.g. `SETTINGS & BACKGROUND AUTOMATION`).
- **Body Large / Primary Labels**: 16sp, Medium. Primary card text, active caller names, navigation directions.
- **Body Medium / Secondary Content**: 14sp, Regular. Explanatory text, trip distances, status descriptions.
- **Label Small / Metric Units & Badges**: 11sp–12sp, Bold. Metric units (`KM`, `KM/H`, `WH/KM`), chip tags, status badges.

### 2.3 Layout Spacing & Geometry
- **Standard Spacing Grid**: 4dp / 8dp / 12dp / 16dp / 24dp.
- **Card Corner Radius**: `16dp` for top-level cards, `12dp` for inner section containers, `8dp` for chips and buttons.
- **Max Content Width**: `680dp` on tablets and wide screens (automatically centers with side navigation rail).

---

## 3. Information Architecture & Navigation

The app is structured into five primary destinations reachable via a bottom navigation bar on mobile (or a side navigation rail on tablets):

```
┌─────────────────────────────────────────────────────────────┐
│                   Top Connection & Telemetry Header          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [1] Cockpit Dashboard   (Live Telemetry, Gauges, Range)    │
│  [2] Saved Trips         (Trip History, Eco/Power % split)  │
│  [3] Command Studio      (Diagnostics, Cluster Controls)    │
│  [4] GPS Ride Logger     (Live GPS speed & fusion map)      │
│  [5] Hex Inspector       (Raw BLE frame validation)         │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│     Bottom Navigation Bar (Cockpit / Trips / Studio / GPS)   │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Key UI Components & Screens

### 4.1 Top Connection & Status Header
- **Location**: Always pinned to the top of every screen.
- **Elements**:
  - **Connection Phase Pill**: Shows `CONNECTED` (Neon Mint), `CONNECTING` (Cyan pulse), or `DISCONNECTED` (Coral).
  - **Scooter Identifier**: Vehicle Name (e.g., `iQube S 3.4kWh`) or Bluetooth address.
  - **Signal & MTU Badge**: Live RSSI signal strength (e.g. `-64 dBm` with 4-bar indicator).
  - **Quick Action Buttons**:
    - `Connect / Disconnect` toggle button.
    - `Replay Riding` (simulates live road telemetry for UI testing).
    - `Replay Charging` (simulates charging cycle for UI testing).

---

### 4.2 Central Battery & Dual Range Card
The visual focal point of the Cockpit dashboard:
- **Radial Battery Ring Arc**:
  - Sweep arc from 135° to 405° (270° total span).
  - Background track: `SlateBorder` with round stroke caps.
  - Active gradient: Smooth gradient from `NeonMint` to `ElectricCyan` (or `EnergeticCoral` if SoC < 20%).
  - Center readout: Giant percentage (e.g. `82%`) with lightning icon when charging.
- **True Range vs Cluster DTE Display**:
  - **Cluster DTE**: Official range reported by the scooter BMS (e.g. `78 km`).
  - **True Range**: Algorithm-calculated real-world range based on actual past riding efficiency:
    - **Eco Mode Chip**: e.g., `True Range Eco: 85 km` (`NeonMint`).
    - **Power Mode Chip**: Calculated dynamically from past power-mode consumption (`AmberWarning`).
  - **Range Mode Switcher**: Segmented toggle to switch between `ECO` and `POWER` projections.

---

### 4.3 Speedometer & Real-Time Riding Telemetry
- **Speedometer Arc**: Smooth needle and digital reading with color shift:
  - 0–45 km/h: `NeonMint` (Efficient cruising).
  - 45–65 km/h: `ElectricCyan` (Standard riding).
  - 65+ km/h: `AmberWarning` / `EnergeticCoral` (High power draw).
- **Secondary Telemetry Grid**: 4-column compact metric cards:
  - **Odometer**: Lifetime total distance in kilometers.
  - **Motor Temp & Controller Temp**: In Celsius with overheat warnings at >75°C.
  - **Battery Voltage**: Instantaneous pack voltage (e.g., `51.2 V`).
  - **Motor Power / Current**: Real-time power draw in Watts or Amperes.

---

### 4.4 Smart Charging Monitor & Target SoC Alert Card
Visible when the scooter is plugged into a charger or monitored remotely:
- **Charging Progress Bar**: Animated glow along battery fill.
- **Estimated Completion Times**:
  - **Time until 80%**: e.g., `25m remaining` (or `Done (≥80%)` if already past 80%).
  - **Time until 100% Full**: e.g., `1h 10m remaining`.
  - **Charging Rate**: e.g. `+0.8% per minute (~1.2 kW)`.
- **Target Battery Percentage Alert Settings**:
  - **Toggle**: Enable / Disable alert.
  - **Target SoC Selector**:
    - Quick Preset Chips: `50%`, `80% (Health)`, `90%`, `100% (Full)`.
    - Continuous Slider: `50%` to `100%` in 5% increments.
  - **Alert Type Selector**:
    - `Notification Only`: Sends a high-priority push alert when target is hit.
    - `Sound Alarm`: Plays a looping audible alarm with continuous vibration.
  - **Active Alarm Banner**: If the alarm is ringing, displays a flashing Coral banner with a large `STOP ALARM` button.
  - **Test Alert Button**: One-tap test button to preview the notification or sound.

---

### 4.5 Turn-by-Turn Google Maps Cluster Sync Card
Visualizes what the rider sees on their scooter screen:
- **Location Banner Header**: Displays active street name, locality, or landmark (e.g., `"Police Nagar, CRPF Rd"`).
- **Maneuver Indicator**: Direction arrow matching Google Maps notifications:
  - Turn Left, Turn Right, Sharp Left, Sharp Right, Slight Left, Slight Right, U-Turn, Keep Left, Keep Right, Straight, Roundabout, Destination Arrived.
- **Distance to Turn**: Big bold meters or kilometers (e.g. `80 m` or `1.4 km`).
- **Trip ETA**: Remaining duration in hours and minutes (e.g. `14 min`).
- **Clean Notification Guarantee**: Navigation alerts are sent directly to the scooter cluster screen and kept out of the phone's persistent status bar notification to avoid clutter.

---

### 4.6 Saved Trips History & Efficiency Screen
- **Active Trip Card**: Real-time tracker showing start time, current distance, and live Wh/km consumption.
- **Saved Trip Cards**:
  - Start locality → End locality banner (e.g. `"Kachipura Chowk → Police Nagar"`).
  - Total distance (km) and trip duration.
  - **Ride Mode Breakdown**: Bar visualizer showing `% distance in Eco` vs `% distance in Power`.
  - **Ride Efficiency**: Real consumption in `Wh/km`.
  - **100% True Range Potential**: Projected full-battery range based specifically on that trip's terrain and throttle behavior.
- **Export Options**: Export all trips to CSV or JSON with one tap.

---

## 5. Companion OS Integrations

### 5.1 Android Quick Settings / Control Center Tile
- **Where**: Notification shade / Quick Settings pull-down menu.
- **Title**: `iQube Cockpit`
- **Subtitle / State**:
  - Connected: `82% • 78 km` (Active icon)
  - Charging: `⚡ 82% • Charging`
  - Disconnected: `Offline • Last 82%`
- **Interaction**: One tap opens the app directly into the Cockpit screen.

### 5.2 Android Home Screen Cockpit Widget
- **Sizes**: Responsive 4x2 (Standard) or 4x1 (Compact).
- **Visual Design**:
  - Matches the obsidian dark glass look with 16dp rounded corners.
  - Large battery SoC percentage and horizontal gradient progress bar.
  - Cluster DTE and True Range badges.
  - **Dynamic Charging Mode**:
    - When plugged in, turns on a charging badge `⚡ Charging [SoC]%`.
    - Displays **Time to 80%** (e.g. `To 80%: 25m`) and **Time to Full** (e.g. `To Full: 1h 05m`).
  - **Disconnected Mode**:
    - Displays last-seen timestamp (e.g. `Parked 2:45 PM`) and last recorded parking locality.
- **Tap Behavior**: Tapping the widget opens `MainActivity`.

### 5.3 Persistent Status Bar Notification
- **Design Rule**: Strictly reserved for vehicle status. Navigation arrows and step-by-step turn alerts are suppressed from this notification so riders have an uncluttered status bar.
- **Connected State**:
  - Title: `iQube Connected: 82% • 78 km DTE`
  - Body: `True Range: 85 km (Eco) | Speed: 0 km/h`
- **Charging State**:
  - Title: `⚡ iQube Charging: 82%`
  - Body: `To 80%: 25m | To Full: 1h 05m`
- **Disconnected State**:
  - Title: `iQube Disconnected (Last Seen: 2:45 PM)`
  - Body: `Battery: 82% (~78 km) | Parked near Kachipura Chowk`

---

## 6. Accessibility & Usability Standards

1. **Touch Targets**: All buttons, chips, sliders, and navigation items have a minimum interactive touch target of **48dp × 48dp**.
2. **Text Contrast**: All user-facing text achieves a contrast ratio of at least **7:1** against the dark surface, exceeding WCAG AAA standards for outdoor visibility.
3. **Tabular Numerals**: Speedometers, odometers, and battery percentage indicators use tabular spacing so numerals do not jitter during rapid acceleration or deceleration.
4. **Color Independence**: Status states are never conveyed by color alone — each alert pairs color with a dedicated icon and descriptive text label.

---

## 7. Component Test Tag Reference (for Automated Testing)

| Component | `testTag` |
|---|---|
| Status Notification Toggle | `status_notification_switch` |
| Background Auto-Reconnect Toggle | `auto_reconnect_switch` |
| Charging Alert Enable Toggle | `charging_alert_enabled_switch` |
| Charging Target Slider | `charging_target_slider` |
| Charging Preset Chip (80%) | `charging_target_chip_80` |
| Notification Alert Mode Chip | `alert_type_notification_chip` |
| Sound Alarm Alert Mode Chip | `alert_type_alarm_chip` |
| Stop Alarm Button | `stop_charging_alarm_button` |
| Test Charging Alert Button | `test_charging_alert_button` |
| Phone Permissions Button | `grant_phone_permissions_button` |
| Range Mode Eco Chip | `range_mode_chip_ECO` |
| Range Mode Power Chip | `range_mode_chip_POWER` |
| Reset Trip Button | `reset_trip_button` |
| Dump Location Button | `dump_location_button` |
| Quick Connect Header Button | `connect_ble_button` |

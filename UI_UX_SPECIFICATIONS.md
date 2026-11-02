# TVS iQube Cockpit — Frontend UI/UX Design & Component Specifications

This document provides a clean, comprehensive design reference for UI/UX engineers, frontend developers, and product designers working on the user experience of **iQube Cockpit**. It describes all user-facing components, design tokens, responsive layouts, micro-interactions, and system surfaces without backend implementation complexity.

---

## 1. Visual Identity & Design Tokens

### 1.1 Color Palette
The interface uses a high-contrast **Cyber Neon & Dark Obsidian** theme built on Material 3 guidelines.

| Token Name | Hex Code | Purpose / Application |
|---|---|---|
| `ObsidianBg` | `#0B0F19` | Main screen canvas background |
| `SlateSurface` | `#0F172A` | Bottom navigation bar, side rail, app bars |
| `SlateCard` | `#131D31` | Primary cards, component groupings, container panels |
| `SlateCardElevated`| `#1E293B` | Interactive elements, elevated sub-cards, input boxes |
| `SlateBorder` | `#334155` | Dividers, subtle container borders, inactive tracks |
| `ElectricCyan` | `#00E5FF` | Primary brand accent, active gauges, primary actions, DTE range |
| `NeonMint` | `#00E676` | Eco mode, charging healthy state, successful connections, battery > 20% |
| `WarningAmber` | `#FFB300` | "Needs Calibration" warning badge, battery 10–20%, connecting states |
| `AlertCoral` | `#FF5252` | Battery < 10%, disconnection warning, alarm active states |
| `TextPrimary` | `#F8FAFC` | Main headings, primary values, active labels (high contrast) |
| `TextSecondary`| `#94A3B8` | Body copy, secondary statistics, subtitles |
| `TextMuted` | `#64748B` | Footers, captions, inactive timestamps |

### 1.2 Preset Theme Options (Customization Sandbox)
Users can select or switch themes across the App Widget, Quick Settings Tile, and Notification:
- **Cyber Neon**: Deep Obsidian (`#0B0F19`), Neon Mint accent (`#00E676`), High contrast text.
- **Electric Cyan**: Navy Dark (`#070D18`), Electric Cyan accent (`#00E5FF`), Cool slate text.
- **Sunset Coral**: Dark Plum (`#12080D`), Coral Red accent (`#FF5252`), Warm rose text.
- **Amber Volt**: Dark Bronze (`#121008`), Amber Volt accent (`#FFB300`), Warm gold text.
- **Stealth OLED**: Pure Black (`#000000`), Pure White accents, Ultra-minimalist contrast.
- **Glass Slate**: Slate Blue (`#1E293B`), Sky Blue accent (`#38BDF8`), Frosted aesthetic.
- **Canva / Custom Dump**: Custom uploaded background image or artwork with adjustable opacity slider (10% to 100%).

### 1.3 Typography Scale
- **Display Large** (`36sp`, SemiBold): Battery % hero numbers, peak speeds.
- **Headline Medium** (`28sp`, Bold): Speedometer values, DTE primary numbers.
- **Title Medium** (`18sp`, SemiBold): Card headers, section titles.
- **Body Medium** (`14sp`, Regular): Standard metric descriptions, telemetry lines.
- **Label Small** (`11sp`, SemiBold): Status badges, mini chips, coordinate tags.

---

## 2. Core User Screens & Layout Hierarchy

### 2.1 Screen 1: Live Cockpit Dashboard (`DashboardDestination.COCKPIT`)
The central control center for riders during active rides or parking checks.

1. **Top Status Header**:
   - Connection pill: `● CONNECTED`, `SCANNING BLE…`, `OFFLINE PREDICTED`, or `DISCONNECTED`.
   - Vehicle name tag (e.g., `TVSiQUBECJI573`), signal RSSI in dBm, MTU size.
   - Quick Action buttons: `Scan BLE`, `Ride Replay`, `Charge Replay`, `Disconnect`.
2. **Hero Battery & Dynamic True Range Gauge**:
   - Swept radial arc gauge: displays battery SoC (0% to 100%) with dynamic gradient.
   - Dual-Mode Range Switcher: Toggle between **True Eco Range** (baseline `1.00 km/%`) and **Learned Power Range** (baseline `0.75 km/%`).
   - Cluster BMS DTE vs Learned True Range comparison pills.
3. **Smart Charging & Target Alarm Station**:
   - Live charging indicator with pulsing charge bolt (`⚡ Charging Active`).
   - Predicted step timer: Displays verified time to 80% (Constant Current phase) and time to Full 100% (Constant Voltage phase).
   - **Target SoC Alert Settings**: Configurable target percentage (e.g., 80%, 90%, 100%) and Alert Mode (Push Notification vs Full Audible Alarm).
   - "Needs Calibration" badge: Highlighted if scooter key was turned off during charging.
4. **Turn-by-Turn Navigation & Locality Banner**:
   - Maneuver direction icon (Turn Left, Right, Sharp, Slight, Roundabout with Exit #, U-Turn).
   - Distance remaining countdown (in meters or km) and travel ETA.
   - Next street / destination name with smart timestamp filtering.
5. **Incoming Call & Handlebar Media Controller**:
   - Contact name caller ID card with Ringing / Off-hook status.
   - Media playback track title and handlebar rocker key controls (Play, Pause, Next, Vol+, Vol-).

### 2.2 Screen 2: Saved Trips & Charging History (`DashboardDestination.SAVED_TRIPS`)
Trip tracking and battery charging analytics.

1. **Trip Records**:
   - Start and End location names (e.g., `Sonegaon -> Sitabuldi`).
   - Distance traveled (`km`), battery used (`%`), peak speed (`km/h`), average speed.
   - Learned efficiency metric (calculated `km / %`).
   - Export buttons: `Export CSV` and `Export JSON`.
2. **Charging Session History**:
   - Starting SoC % to Ending SoC %.
   - Net duration, avg seconds per percentage (`s / +1%`), and estimated kWh added.
   - Source tag: `Connected BLE Session` vs `Offline Predicted + Calibrated`.

### 2.3 Screen 3: Customization Studio Sandbox (`DashboardDestination.STUDIO_SANDBOX`)
A visual drag-and-drop design sandbox for customizing external touchpoints.

1. **Surface Selection Tabs**:
   - Tab 1: **Home Screen Widget**
   - Tab 2: **Control Center Tile**
   - Tab 3: **Persistent Notification**
2. **Responsive Size Mode Selector**:
   - Compact (`2x1`), Standard (`3x2`), Expanded (`4x2`), Full Banner (`4x3`).
3. **Interactive WYSIWYG Drag-and-Drop Canvas**:
   - Direct touch manipulation: Users can drag any element to reposition its X and Y coordinates.
   - Element selection ring: Tapping an element highlights its bounding box and displays its fine-tuning controls.
   - Element Palette:
     - `Battery SoC %`
     - `Battery Progress Bar`
     - `Cluster DTE Range (km)`
     - `Learned True Range (Eco/Power)`
     - `Connection & Charging Badge`
     - `Vehicle Header Name`
     - `Charging ETA to 80% / Full`
     - `Parked Location Name & Last Seen`
     - `Custom Accent Tagline`
4. **Theme Presets & Canva Image Graphic Dump**:
   - One-tap preset styling (Cyber Neon, Electric Cyan, Sunset Coral, Amber Volt, Stealth OLED, Glass Slate).
   - **Canva Image Import**: Button to import Canva exports, custom illustrations, or scooter photos.
   - Opacity slider (10% to 100%) to ensure telemetry text remains legible over graphic backgrounds.
5. **Configurable Interaction on Click**:
   - Options for what happens when tapping the Widget or Quick Settings Tile:
     - `Open Cockpit Dashboard`
     - `Trigger BLE Connect`
     - `Toggle Ride Mode (Eco/Power)`
     - `Open Saved Trips`
     - `Open Customization Studio`
6. **Permanent Local Storage Bar**:
   - Displays shared storage path: `Documents/iQubeCockpit/`.
   - `Backup All` button: Saves all trips, charging sessions, and templates permanently.
   - `Restore All` button: Restores data after app re-installation.
   - `Apply to Live Widget & Tile` button.

### 2.4 Screen 4: Cluster Sync / Command Studio (`DashboardDestination.COMMAND_STUDIO`)
Hardware synchronizer for bike cluster display zones.

- Top location pin (`0x5B 0x5C`) custom name override.
- Lower cyan banner (`0x43 / 0x62`) custom message test.
- Trip A / Trip B cycle and reset subcommands.
- 24-Hour local clock synchronization.

### 2.5 Screen 5: GPS & Park (`DashboardDestination.GPS_LOGGER`)
- Last Parked Location card with pin, street address, timestamp, and saved battery level.
- Wheel speed vs GPS speed telemetry fusion.
- Direct shortcut to navigate back to parked scooter.

---

## 3. System Surfaces & External Components

### 3.1 Android Home Screen App Widget
- **Layout**: 4x2 responsive card matching the user's active theme.
- **Key Elements**:
  - Live Battery SoC and progress meter.
  - Charging state: Shows `Time to 80%` and `Time to Full` when scooter is plugged in.
  - DTE Range and True Range (Eco/Power) when unplugged.
  - Parking locality and Last Seen timestamp.
  - Configurable click action (opens app or triggers instant background BLE connection).

### 3.2 Quick Settings (Control Center) App Tile
- **Appearance**: 2-line high-contrast icon:
  - Top line: Battery SoC (e.g., `87%` or `⚡87%`).
  - Bottom line: Range in km (e.g., `92km`).
- **Tile Subtitle**:
  - Connected: `Live Cockpit • 92 km DTE`
  - Charging: `Charging • To 80%: 42m`
  - Disconnected: `Last seen sitabuldi • Needs Cal`
- **Interaction**: One tap triggers instant reconnect or launches cockpit.

### 3.3 Persistent Status Notification
- **Title**: `TVS iQube Connected • 42 km/h` or `TVS iQube Charging • 87%`.
- **Content**: Battery SoC, Range, and Charging Time to 80% & Full.
- **Action Buttons**:
  - When Connected: `[Disconnect]`
  - When Disconnected: `[Connect]` (one tap triggers immediate BLE connection).

---

## 4. Key UX Rules & Micro-Interactions

1. **30-Minute Disconnect Battery Saver Window**:
   - When the scooter disconnects (ignition turned off), the app performs gentle reconnect scans for up to **30 minutes**.
   - If not reconnected after 30 minutes and not charging, background scanning pauses to preserve phone battery.
   - A clear `[Connect]` button remains in the notification and dashboard to resume instantly.
2. **Offline Predicted Charging ("Needs Calibration")**:
   - If the scooter is charging and the user turns off the ignition key, telemetry stops but charging physically continues.
   - The UI transitions gracefully into **Offline Predicted Charging** mode, advancing SoC based on historical charging step analysis.
   - An amber `Needs Calibration` badge indicates that the value will reconcile with cluster hardware upon next connection.
   - When 100% full charge is reached, the target alarm triggers, and the background service shuts down cleanly.
3. **Turn-by-Turn Map Synchronization**:
   - When navigation is running, street names are automatically sanitized (removing ETA timestamps, middle dots, and duration text).
   - Maneuver icons strictly adhere to vehicle orientation (right turns show right arrows, roundabouts show exit numbers).
   - Navigation listener automatically sleeps when the vehicle is disconnected to conserve battery.
4. **Permanent Android Media/Documents Storage**:
   - All user data (trips, charging curves, custom templates, Canva images) is saved in `Documents/iQubeCockpit/`.
   - Data persists across app uninstalls and updates without cloud account requirements.

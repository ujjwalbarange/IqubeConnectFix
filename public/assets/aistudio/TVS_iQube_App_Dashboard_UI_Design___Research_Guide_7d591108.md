# TVS iQube Custom Dashboard App: UI/UX Research & Reference Guide

This document aggregates design references, visual concepts, YouTube tutorials, design kit resources, and UX architecture tailored for building a custom Bluetooth dashboard app for the **TVS iQube**.

---

## 1. Core UI Concept & Visual Archetype

Building a custom companion app for an electric vehicle requires balancing high-glanceability automotive metrics (speed, battery, range) with utility controls (BLE connection status, trip reset, navigation routing).

### Recommended Design Theme
* **Palette:** Deep OLED Dark Mode (`#0D1117`, `#161B22`) to minimize phone battery consumption while mounted on the handlebar.
* **Accents:** Electric Mint / Cyan (`#00E5FF`, `#00F5A0`) for battery SoC & charging state, and Energetic Coral/Orange (`#FF6B4A`) for speed alerts and critical notifications.
* **Typography:** Clean geometric sans-serifs with tabular figures (`Inter`, `Space Grotesk`, or `DIN Pro`) so numerical digits don't shift layout when speed or telemetry fluctuates.

```
+-------------------------------------------------------+
|  [⚡ TVSiQUBEJI573]               (●) BLE CONNECTED   |
+-------------------------------------------------------+
|                                                       |
|                     [ 78 % ]                          |
|             =========== O ===========                 |
|               EST. RANGE: 86 KM                       |
|                                                       |
+-------------------------------------------------------+
|   LIVE TELEMETRY (Packet 0x10 / 0x13)                 |
|   +-------------------+  +-------------------+        |
|   | SPEED:  42 km/h   |  | TRIP A:  18.4 km  |        |
|   +-------------------+  +-------------------+        |
|   | VOLTS:  51.2 V    |  | ODOMETER: 1,750 km|        |
|   +-------------------+  +-------------------+        |
+-------------------------------------------------------+
|   ACTION SHORTCUTS (Write to 0x5352)                  |
|   [ 🔄 Reset Trip A ]     [ 📍 Push Route Nav ]       |
|   [ 🔔 Send Call Alert ]  [ 💡 Toggle Dashboard Lamp] |
+-------------------------------------------------------+
```

---

## 2. Visual Reference Galleries & Direct Image Searches

### Direct Google Image Reference Searches
Click the following curated query links to explore high-resolution dashboard mockups and production EV applications:

* [Google Images: Modern EV Scooter Dashboard Mobile App UI](https://www.google.com/search?tbm=isch&q=electric+scooter+companion+app+dashboard+ui+design)
* [Google Images: Ather 450X & Ola Electric Dashboard App UI Design](https://www.google.com/search?tbm=isch&q=ather+app+dashboard+ui+ola+electric+app+screen)
* [Google Images: Dark Mode Automotive BLE Telemetry App UI](https://www.google.com/search?tbm=isch&q=automotive+telemetry+mobile+app+dark+mode+ui+dribbble)
* [Google Images: Turn by Turn Navigation Dashboard BLE UI](https://www.google.com/search?tbm=isch&q=turn+by+turn+navigation+instrument+cluster+ui+design)

---

## 3. UI/UX Benchmark Collections (Dribbble & Behance)

These collections feature curated design systems from top automotive and IoT UI designers:

1. **Dribbble EV Dashboard Hub:**  
   [https://dribbble.com/tags/ev-dashboard](https://dribbble.com/tags/ev-dashboard)  
   *Focus on:* Circular battery radial gauges, live vehicle state cards, and charging animations.
2. **Dribbble Scooter UI Showcase:**  
   [https://dribbble.com/search/scooter-ui](https://dribbble.com/search/scooter-ui)  
   *Focus on:* Urban micro-mobility layouts, trip summary cards, and quick lock/unlock actions.
3. **Behance EV Companion Automotive HMI Projects:**  
   [https://www.behance.net/search/projects?search=EV+dashboard+mobile+app](https://www.behance.net/search/projects?search=EV+dashboard+mobile+app)  
   *Focus on:* Complete end-to-end case studies covering typography, icon sets, and information architecture.

---

## 4. Free UI Kits & Figma Templates

You can duplicate these templates directly into Figma to jumpstart component design:

* **E-Scooter App Dashboard Template (Figma):**  
  [Unblast Free E-Scooter Dashboard](https://unblast.com/free-e-scooter-dashboard-template-figma/)  
  Includes vector components for speedometers, distance counters, and battery status.
* **Figma Community - EV Companion Concept:**  
  [Figma Community EV Dashboard Search](https://www.figma.com/community/search?resource_type=mixed&sort_by=relevancy&query=ev+dashboard+app)  
  Search for free UI kits containing pre-built Flutter/React Native exportable asset packs.

---

## 5. Curated Video Tutorials & Code References

Watch these implementation walkthroughs to see how modern EV instrument clusters and companion apps are designed and animated in code:

| Video Title | Creator | Focus Area | Link |
| :--- | :--- | :--- | :--- |
| **Car HMI Dashboard using Flutter** | The Flutter Way | Building responsive gauge clusters, dark mode cards, and custom animations. | [Watch on YouTube](http://www.youtube.com/watch?v=7ND8CzsYrXk) |
| **Rivian App Screen - React Native Speed Code** | JustMalla | Real-time vehicle status UI layout, clean card architecture, and battery visualizations. | [Watch on YouTube](http://www.youtube.com/watch?v=NpEb6KdLmKY) |
| **EV Charging Station Finder & Dashboard** | Nevon Projects | Integrating map views with real-time battery and range calculations. | [Watch on YouTube](http://www.youtube.com/watch?v=QHlTGLC8dhg) |
| **Reversing BLE Packets for Device Freedom** | Hardware Hacking Hub | Sniffing, decoding, and writing GATT payloads to smart vehicle controllers. | [Watch on YouTube](https://www.youtube.com/watch?v=imjZJNOSXHk) |

---

## 6. Dashboard Screen Layout Architecture

To map cleanly to the TVS iQube BLE characteristics (`0x5354` for Notify and `0x5352` for Write):

### A. Top App Bar (Header)
* **Connection Pill:** Shows RSSI signal strength, scooter alias (`TVSiQUBEJI573`), and a Reconnect/Disconnect button.
* **Ignition / Lock Status:** Derived from incoming telemetry byte flags.

### B. Hero Visualizer (Center)
* **Circular Arc Gauge:** Displays Battery % (SoC) along with dynamic estimated range ($km$).
* **Charging State Overlay:** If plugged in, switches to a charging pulse animation with time-to-full.

### C. Live Telemetry Strip (Middle)
* **Speed:** Large high-contrast readout ($km/h$).
* **Trip A / Trip B / ODO:** Switchable tabbed card showing parsed data from opcode `0x13`.

### D. Interactive Command Deck (Bottom)
* **Quick Write Actions:**
  * **Reset Trip A:** Writes the command payload to characteristic `0x5352`.
  * **Push Navigation Direction:** Converts text & arrow icons to the TVS frame format (`5A ... FF`) and pushes to the dashboard cluster.
  * **Simulate Alert:** Pushes test call/SMS notification frames to dashboard.
# ResQRoute

**Offline flood routing for Android.** When mobile networks fail, ResQRoute still shows which
roads are passable and how to reach the nearest shelter.

## What it does

- **Works with no internet at all.** The road network and the shelter list are bundled inside
  the app, so the map and routing work in airplane mode.
- **Tap a shelter** to get a route, drawn on the map with its distance.
- **Long-press a road** to report it: how deep the water is, and whether a vehicle can still
  get through. The route recalculates around it immediately.
- **Reported roads stay on the map.** A road that is reported and still passable is drawn
  amber, a blocked road is red, and a normal route is green. The map key names the three.
- **Two phones share reports directly with each other** over Bluetooth/Wi-Fi using Google
  Nearby Connections - no server, no SIM, no internet in between. Mark a road on one phone and
  the other one reroutes by itself.
- **Reports are kept on the phone**, so they survive closing and reopening the app, and sync
  again when a peer reconnects.
- **The map opens on your location** when the phone has one, and has zoom buttons for roads
  that are too small to tap at the district view.

## Install and try it

Download an APK from the [`apk/`](apk) folder:

| File | What it is |
| --- | --- |
| `ResQRoute-2.0.0-release.apk` | normal build - install this one |
| `ResQRoute-2.0.0-debug.apk` | same app plus a few extra testing buttons |

Requires **Android 7.0 (API 24) or newer**. On Android 12+ grant the nearby-devices permission
to use phone-to-phone sharing. For the sharing feature, both phones must run the **same APK**.

## Build from source

1. Open the project folder in Android Studio.
2. Press **Run**.

No API keys, accounts or configuration needed. Release builds are signed only if you create a
`keystore.properties` file at the project root; it is git-ignored, so no key ships with the code.

## How it works

- A pre-built road graph of the **Feni district, Bangladesh** is shipped inside the app
  (`app/src/main/assets`): 34,826 junctions, 78,166 directed edges, 39,923 road segments and
  91 shelters, with a SHA-256 fingerprint the app verifies on load.
- Routing is **A\*** over that graph. A road marked as blocked is left out of the search, so
  neither phone can route through it. A road that is waterlogged but passable is still used,
  with a cost that grows with the reported depth, how recent the report is and how many people
  agree - so the route prefers the safer crossing, not just the shortest one.
- Road reports are stored in a local database as an append-only log and exchanged with nearby
  phones over Nearby Connections, which also checks that both phones are using the same dataset.
- The app **does not request the INTERNET permission at all**, so it cannot use the network
  even by accident.

## Coverage and limits

- The bundled data covers **Feni district, Bangladesh**. Shelters are OpenStreetMap candidate
  buildings (schools, community centres) rather than an official shelter registry.
- It suggests the **lower-risk route from the reports available on the phone**. It is not a
  guarantee of safety.
- The dataset contains no elevation data.
- Built for **Hack for Humanity: Hack to Protect the Environment** (Round 2), organised by
  The AI Collective Bangladesh Chapter in partnership with AIUB, Dhaka.

## License

**Proprietary. Copyright (c) 2026 Team BUIA, all rights reserved.** See [`LICENSE`](LICENSE).

You are welcome to install and run the released APK to try the project. You may not copy,
modify, redistribute or reuse the source code or the application without written permission.
The Hackathon Organizers have a limited licence to receive, run, judge and display the project.
Libraries and the OpenStreetMap-derived road data keep their own licences, which this one does
not override.

## Built with

Kotlin · Android Jetpack (ViewModel, Room) · osmdroid for offline vector map rendering ·
Google Nearby Connections for device-to-device sync · Gradle.

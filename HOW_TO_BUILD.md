# BitAim v3.0 — Build Guide

## What's new in v3.1 vs v2

| Feature | v2 | v3.1 |
|---|---|---|
| **Snap-to-Coin** | ❌ | **NEW — tap coin → auto-pocket aim** |
| Striker | Locked (auto-detect only) | **Moveable — drag it to reposition** |
| Aim lines | Rainbow of all shot types at once | **One clean cyan line from striker** |
| Board size | Inferred from coin spread | **Auto-detected from wooden frame color** |
| Detection | Single HoughCircles pass | **Multi-threshold retry (3 passes)** |
| Frame rate | ~30 FPS (33 ms) | **~40 FPS (25 ms)** |
| Capture res | 720 px wide | **1080 px wide (sharper)** |
| Coin color classifier | Basic inner pixel | **Inner 40% radius average** |
| Physics DT | 1/120 s | **1/180 s (smoother curves)** |
| Restitution | 0.94 | **0.96 (closer to real carrom)** |

---

## Requirements (same as v2)

1. **Node.js v18 LTS** → https://nodejs.org
2. **JDK 11** → https://adoptium.net
3. **Android Studio** (Hedgehog or newer)
4. SDK Manager → install **Android API 34** + **NDK r23+**

---

## Build steps

### 1. Extract this zip
```
C:\Projects\BitAim-v3\
```

### 2. Install JS deps
```bash
npm install
```

### 3. Open `android/` in Android Studio
Wait for Gradle sync (~10–15 min first time — downloads OpenCV from JitPack).

### 4. Build APK
**Build → Build Bundle(s)/APK(s) → Build APK(s)**

Output: `android/app/build/outputs/apk/debug/app-debug.apk`

### 5. Install
```bash
npx react-native run-android
```

---

## How Snap-to-Coin works (v3.1 NEW)

1. Enable **"Snap-to-Coin"** toggle in the app.
2. **Tap on any detected coin** (white, black, or the red queen) on the overlay.
3. The app automatically computes the ideal aim angle:
   - Finds the closest pocket to that coin.
   - Calculates the exact "contact point" where the striker must hit the coin.
   - Draws the cyan aim line from striker → contact point.
   - Draws a **lime green dotted line** from coin → pocket to confirm the intended path.
4. Tap empty board space at any time to return to a normal free-aim point.

The physics behind it:
- To pocket coin C into pocket P, C must travel in direction C→P.
- The striker must hit the opposite side of C: `contactPt = C - normalize(P-C) × (coinRadius + strikerRadius)`.
- The simulation then verifies the shot using full physics bounce calculation.

---

## How the striker works in v3

- When **"Striker Moveable" is ON** (default):
  - A dashed gold ring appears around the striker.
  - **Touch inside the ring and drag** to reposition the striker to exactly where your finger is on the game screen.
  - Letting go locks the striker at the new position.
  - The next auto-detect frame will update it again (if auto-detect is on), but your drag overrides it until then.
- When **"Striker Moveable" is OFF**:
  - Striker is locked to wherever auto-detect puts it.

---

## How single-line aim works in v3

- Only **one cyan line** is drawn from the striker to your aim target.
- If the striker hits a coin, **one orange deflection line** is added for that coin.
- A **green glow circle** appears at the end of any path that reaches a pocket.
- No more 5-color rainbow — just the clearest view of your shot.

---

## How board auto-size works in v3

1. Each captured frame is converted to HSV.
2. Color threshold isolates the carrom board's **wooden mahogany frame**
   (reddish-brown, H 0–18 or 165–180, S > 70, V 40–170).
3. Morphological close fills gaps; `findContours` finds the largest blob.
4. Its bounding rect is used as the board square.
5. If that fails (too small or absent), falls back to coin-spread inference.
6. Pockets are placed at the 4 inset corners of the detected board rect.

---

## Common errors

| Error | Fix |
|---|---|
| `SDK location not found` | Edit `android/local.properties`, set `sdk.dir=...` |
| `Could not find :opencv:4.5.3.0` | JitPack unreachable — re-run sync with stable internet |
| `Duplicate libopencv_java4.so` | Already handled by `pickFirst` in `app/build.gradle` |
| Board not detected | Lower Detection Sensitivity in the app |
| Striker not moving when dragged | Enable "Striker Moveable" toggle in the app settings |
| No coins detected | Lower Detection Sensitivity; ensure good lighting in game |

---

## Project file map (v3)

```
BitAim-v3/
├── App.tsx                        ← UI: striker moveable toggle, single-line info
├── index.js
├── app.json
├── package.json
├── babel.config.js / tsconfig.json / metro.config.js
└── android/
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties
    ├── gradle/wrapper/
    └── app/
        ├── build.gradle           ← versionCode 3, OpenCV dep
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── res/...
            └── java/com/bitaim/carromaim/
                ├── MainActivity.java
                ├── MainApplication.java
                ├── overlay/
                │   ├── AimOverlayView.java     ← CHANGED: striker drag, single line
                │   ├── FloatingOverlayService.java ← CHANGED: setStrikerMoveable()
                │   ├── OverlayModule.java      ← CHANGED: setStrikerMoveable() bridge
                │   └── OverlayPackage.java
                ├── capture/
                │   ├── MediaProjectionRequestActivity.java
                │   └── ScreenCaptureService.java ← CHANGED: 1080px, 40FPS
                └── cv/
                    ├── BoardDetector.java      ← CHANGED: color-based board detect
                    ├── TrajectorySimulator.java ← CHANGED: single best path output
                    ├── Coin.java
                    └── GameState.java
```

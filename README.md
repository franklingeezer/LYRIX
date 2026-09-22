# LYRIX

Real-time synced lyrics on a small OLED screen, driven from whatever
you're actually playing music on.

## How it fits together

```
┌────────────────┐      ┌──────────────┐      ┌───────────────────┐
│ Android app    │      │  PC bridge   │      │  ESP32 + OLED     │
│ (phone/Spotify)│ ───▶ │ (Windows +   │ ───▶│  receiver/display │
│                │      │   Spotify)   │      │                   │
└────────────────┘      └──────────────┘      └───────────────────┘
        │                       │                        ▲
        └───────────────────────┴────────────────────────┘
                    both talk HTTP to the ESP32
```

- **`/android`** - Android app. Reads whatever's currently playing via
  the notification listener, fetches synced lyrics from lrclib.net,
  and pushes them to the ESP32 with exact per-line timing.
- **`/pc-bridge`** - Standalone Python script for Windows. Does the
  same thing, but reads now-playing info from Spotify desktop (or any
  app/browser tab) via Windows' System Media Transport Controls
  instead of a phone.
- **`/firmware`** - ESP32 (SSD1306 OLED) sketch. Receives lyric lines
  over HTTP (plain text or, for Bangla, phone/PC-rendered bitmaps)
  and displays them with an animated transition.

Only one source (phone app *or* PC bridge) needs to be running at a
time - both just push to the same ESP32 over your local Wi-Fi.

## Setup

1. **Firmware**: open `firmware/LYRIX_ESP32_OLED.ino` in the Arduino
   IDE, fill in your own `WIFI_SSID`/`WIFI_PASSWORD` (don't commit
   real ones), and flash it. It prints its IP over Serial once
   connected - you'll need that IP for the other two pieces.
2. **Android app**: open `/android` in Android Studio, update the
   ESP32 IP in `Esp32Repository.kt`, build and install on your phone.
   Requires notification access permission (for reading now-playing
   media) to be granted on first run.
3. **PC bridge** (optional, Windows only): see `pc-bridge/README.md`.

## Requirements

- ESP32 board + SSD1306 128x64 I2C OLED
- Arduino IDE with the `Adafruit_SSD1306` and `Adafruit_GFX` libraries
- Android Studio (for the phone app)
- Python 3.12+ on Windows (for the PC bridge, if you want it)

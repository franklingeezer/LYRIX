# LYRIX Firmware

ESP32 + SSD1306 128x64 I2C OLED. Receives lyric lines over HTTP and
displays them with an animated push transition.

## Flashing

1. Install the Arduino IDE, add ESP32 board support if you haven't.
2. Install libraries via Library Manager: `Adafruit SSD1306`,
   `Adafruit GFX Library`.
3. Fill in `WIFI_SSID` / `WIFI_PASSWORD` near the top - **don't commit
   your real credentials**, edit them locally after cloning.
4. Flash to the board. Open Serial Monitor at 115200 baud - it prints
   its IP address once Wi-Fi connects. You'll need that IP in the
   Android app and/or the PC bridge script.

## HTTP endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/display?text=...` | GET | Show a line of plain text |
| `/lyrics` | POST | Upload a full LRC file (plain text body) |
| `/bitmap` | POST | Show a pre-rendered line (hex-encoded 128x52 1bpp bitmap - used for Bangla) |
| `/position?ms=&playing=` | GET | Report playback position/state |
| `/status` | GET | Device status as JSON |
| `/clear` | GET | Reset to idle |



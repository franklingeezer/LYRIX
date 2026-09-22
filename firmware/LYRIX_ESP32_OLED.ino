#include <WiFi.h>
#include <WebServer.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// =====================================================
// LYRIX ESP32-CAM OLED RECEIVER
// (fixed ASCII text layout + Bangla via phone-rendered bitmaps)
// =====================================================
//
// TWO WAYS TO SHOW A LYRIC LINE:
//
// 1) POST /display?text=...   or POST /lyrics (LRC file)
//    -> ASCII/English text, wrapped and rendered on-device
//       using the built-in GFX font.
//
// 2) POST /bitmap
//    -> For Bangla (or anything else needing real shaping).
//       Body is a hex string encoding a packed 1-bit-per-pixel
//       128x52 monochrome bitmap (MSB-first per byte), which the
//       phone renders using Android's own text engine (correct
//       Bangla conjuncts/matras) and streams over as pixels.
//       The ESP32 does zero font work for this path - it just
//       blits the bitmap under the header.
//
// =====================================================

// ---------- Wi-Fi ----------
const char* WIFI_SSID = "Franklin_geezer";
const char* WIFI_PASSWORD = "1811739510";

// ---------- OLED ----------
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64

#define OLED_SDA 16
#define OLED_SCL 0

#define OLED_ADDRESS 0x3C

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// ---------- Web Server ----------
WebServer server(80);

// =====================================================
// DISPLAY STATE
// =====================================================

String currentText = "LYRIX";
String currentSong = "";
String currentArtist = "";

bool lyricsLoaded = false;
bool isPlaying = false;
bool bitmapMode = false; // true = last thing shown was a /bitmap push

long currentPositionMs = 0;

// =====================================================
// LRC STORAGE (ASCII path)
// =====================================================

#define MAX_LYRICS_LINES 150

long lyricTimes[MAX_LYRICS_LINES];
String lyricTexts[MAX_LYRICS_LINES];

int lyricCount = 0;

// =====================================================
// TEXT LAYOUT CONSTANTS (ASCII path)
// =====================================================
//
// Row 0-9   : header ("LYRIX" + play indicator + divider line)
// Row 12-63 : usable text area (~52px tall)
// =====================================================

const int TEXT_TOP = 12;
const int TEXT_BOTTOM = SCREEN_HEIGHT;              // 64 (exclusive)
const int TEXT_AREA_HEIGHT = TEXT_BOTTOM - TEXT_TOP; // ~52px

const int MAX_TEXT_WIDTH = SCREEN_WIDTH - 8; // 120px, 4px margin each side

const int LINE_HEIGHT_SIZE2 = 16; // 8px glyph * 2
const int LINE_HEIGHT_SIZE1 = 10; // 8px glyph * 1 + 2px gap

const int MAX_LINES = 8;

// =====================================================
// BITMAP PATH CONSTANTS
// =====================================================
//
// Matches the text area exactly: 128 wide x 52 tall, 1bpp,
// packed MSB-first per byte, row by row (Adafruit_GFX's
// drawBitmap format).
// =====================================================

#define BMP_WIDTH 128
#define BMP_HEIGHT 52
#define BMP_BYTES_PER_ROW ((BMP_WIDTH + 7) / 8)      // 16
#define BMP_TOTAL_BYTES (BMP_BYTES_PER_ROW * BMP_HEIGHT) // 832
#define BMP_HEX_LEN (BMP_TOTAL_BYTES * 2)                // 1664

uint8_t lyricBitmap[BMP_TOTAL_BYTES];

// =====================================================
// MEASURE TEXT WIDTH AT A GIVEN SIZE (ASCII path)
// =====================================================

uint16_t measureWidth(const String &s, uint8_t textSize) {
  int16_t x1, y1;
  uint16_t w, h;
  display.setTextSize(textSize);
  display.getTextBounds(s, 0, 0, &x1, &y1, &w, &h);
  return w;
}

// =====================================================
// WORD WRAP (with char-level fallback for long words)
// =====================================================

bool wrapWords(
  String words[], int wordCount,
  uint8_t textSize, int maxWidth,
  String outLines[], int &outLineCount, int maxLines
) {
  outLineCount = 0;
  String line = "";

  for (int i = 0; i < wordCount; i++) {
    String word = words[i];

    while (measureWidth(word, textSize) > maxWidth && word.length() > 1) {
      int cut = word.length();
      while (cut > 1) {
        String piece = word.substring(0, cut);
        if (measureWidth(piece, textSize) <= maxWidth) break;
        cut--;
      }

      String piece = word.substring(0, cut);
      String rest = word.substring(cut);

      String candidate = (line.length() == 0) ? piece : (line + " " + piece);
      if (measureWidth(candidate, textSize) <= maxWidth) {
        line = candidate;
      } else {
        if (outLineCount >= maxLines) return false;
        outLines[outLineCount++] = line;
        line = piece;
      }

      if (outLineCount >= maxLines) return false;
      outLines[outLineCount++] = line;
      line = "";
      word = rest;
    }

    String candidate = (line.length() == 0) ? word : (line + " " + word);
    if (measureWidth(candidate, textSize) <= maxWidth) {
      line = candidate;
    } else {
      if (outLineCount >= maxLines) return false;
      outLines[outLineCount++] = line;
      line = word;
    }
  }

  if (line.length() > 0) {
    if (outLineCount >= maxLines) return false;
    outLines[outLineCount++] = line;
  }

  return true;
}

// =====================================================
// HEADER (shared by both display paths)
// =====================================================

void drawHeader() {
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.print("LYRIX");

  display.setCursor(116, 0);
  display.print(isPlaying ? "*" : "-");

  display.drawLine(0, 10, 127, 10, SSD1306_WHITE);
}

// =====================================================
// DISPLAY TEXT (ASCII path)
// =====================================================

void showText(String text) {

  bitmapMode = false;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextWrap(false); // stop GFX's own auto-wrap from fighting our layout

  drawHeader();

  text.trim();
  if (text.length() == 0) text = "...";

  // ---- split into words ----
  String words[20];
  int wordCount = 0;
  String currentWord = "";

  for (int i = 0; i <= text.length(); i++) {
    char c = (i < text.length()) ? text[i] : ' ';

    if (c == ' ' || c == '\n') {
      if (currentWord.length() > 0) {
        if (wordCount < 20) words[wordCount++] = currentWord;
        currentWord = "";
      }
    } else {
      currentWord += c;
    }
  }

  // ---- choose font size based on how many lines actually fit ----
  int maxLinesSize2 = TEXT_AREA_HEIGHT / LINE_HEIGHT_SIZE2;
  int maxLinesSize1 = TEXT_AREA_HEIGHT / LINE_HEIGHT_SIZE1;

  if (maxLinesSize2 > MAX_LINES) maxLinesSize2 = MAX_LINES;
  if (maxLinesSize1 > MAX_LINES) maxLinesSize1 = MAX_LINES;

  String lines[MAX_LINES];
  int lineCount = 0;
  int textSize = 2;
  int lineHeight = LINE_HEIGHT_SIZE2;

  bool fits = wrapWords(words, wordCount, 2, MAX_TEXT_WIDTH, lines, lineCount, maxLinesSize2);

  if (!fits) {
    textSize = 1;
    lineHeight = LINE_HEIGHT_SIZE1;
    lineCount = 0;

    fits = wrapWords(words, wordCount, 1, MAX_TEXT_WIDTH, lines, lineCount, maxLinesSize1);

    if (!fits) {
      lineCount = 0;
      wrapWords(words, wordCount, 1, MAX_TEXT_WIDTH, lines, lineCount, maxLinesSize1);

      if (lineCount > 0) {
        String &last = lines[lineCount - 1];
        while (measureWidth(last + "...", 1) > MAX_TEXT_WIDTH && last.length() > 1) {
          last.remove(last.length() - 1);
        }
        last += "...";
      }
    }
  }

  // ---- vertical centering ----
  int totalHeight = lineCount * lineHeight;
  int startY = TEXT_TOP + ((TEXT_AREA_HEIGHT - totalHeight) / 2);
  if (startY < TEXT_TOP) startY = TEXT_TOP;

  // ---- draw ----
  display.setTextSize(textSize);

  for (int i = 0; i < lineCount; i++) {
    int16_t x1, y1;
    uint16_t w, h;

    display.getTextBounds(lines[i], 0, 0, &x1, &y1, &w, &h);

    int x = (SCREEN_WIDTH - w) / 2;
    if (x < 0) x = 0;

    int y = startY + (i * lineHeight);

    display.setCursor(x, y);
    display.print(lines[i]);
  }

  display.display();
}

// =====================================================
// DISPLAY BITMAP (phone-rendered Bangla path)
// =====================================================

void showBitmap() {
  bitmapMode = true;

  display.clearDisplay();
  drawHeader();
  display.drawBitmap(0, TEXT_TOP, lyricBitmap, BMP_WIDTH, BMP_HEIGHT, SSD1306_WHITE);
  display.display();
}

// =====================================================
// HEX DECODE HELPERS
// =====================================================

int hexNibble(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

// Returns true on success, false if the string wasn't valid hex
// of the expected length.
bool hexDecode(const String &hex, uint8_t *out, size_t outLen) {
  if ((size_t) hex.length() != outLen * 2) return false;

  for (size_t i = 0; i < outLen; i++) {
    int hi = hexNibble(hex[i * 2]);
    int lo = hexNibble(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    out[i] = (uint8_t)((hi << 4) | lo);
  }

  return true;
}

// =====================================================
// SORT LYRICS
// =====================================================

void sortLyrics() {
  for (int i = 0; i < lyricCount - 1; i++) {
    for (int j = i + 1; j < lyricCount; j++) {
      if (lyricTimes[j] < lyricTimes[i]) {
        long tempTime = lyricTimes[i];
        lyricTimes[i] = lyricTimes[j];
        lyricTimes[j] = tempTime;

        String tempText = lyricTexts[i];
        lyricTexts[i] = lyricTexts[j];
        lyricTexts[j] = tempText;
      }
    }
  }
}

// =====================================================
// ADD LRC LINE
// =====================================================

void addLyric(long timeMs, String text) {
  if (lyricCount >= MAX_LYRICS_LINES) return;

  text.trim();
  if (text.length() == 0) return;

  lyricTimes[lyricCount] = timeMs;
  lyricTexts[lyricCount] = text;
  lyricCount++;
}

// =====================================================
// PARSE LRC
// =====================================================

void parseLRC(String lrc) {
  lyricCount = 0;
  int start = 0;

  while (start < lrc.length()) {
    int end = lrc.indexOf('\n', start);
    if (end == -1) end = lrc.length();

    String line = lrc.substring(start, end);
    line.trim();

    int searchPos = 0;

    while (true) {
      int open = line.indexOf('[', searchPos);
      if (open == -1) break;

      int close = line.indexOf(']', open);
      if (close == -1) break;

      String timestamp = line.substring(open + 1, close);
      int colon = timestamp.indexOf(':');

      if (colon > 0) {
        String minutes = timestamp.substring(0, colon);
        String seconds = timestamp.substring(colon + 1);

        float sec = seconds.toFloat();
        int min = minutes.toInt();

        if (min >= 0 && sec >= 0.0) {
          long timeMs = ((long) min * 60000L) + (long)(sec * 1000.0);

          String lyricText = line.substring(close + 1);
          lyricText.trim();

          addLyric(timeMs, lyricText);
        }
      }

      searchPos = close + 1;
    }

    start = end + 1;
  }

  sortLyrics();

  Serial.print("Parsed lyric lines: ");
  Serial.println(lyricCount);

  lyricsLoaded = lyricCount > 0;
}

// =====================================================
// FIND CURRENT LYRIC
// =====================================================

String getCurrentLyric(long positionMs) {
  if (lyricCount == 0) return "...";

  int currentIndex = -1;

  for (int i = 0; i < lyricCount; i++) {
    if (lyricTimes[i] <= positionMs) {
      currentIndex = i;
    } else {
      break;
    }
  }

  if (currentIndex < 0) return "";

  return lyricTexts[currentIndex];
}

// =====================================================
// UPDATE CURRENT LYRIC (ASCII path only)
// =====================================================

void updateCurrentLyric() {
  if (!lyricsLoaded) return;

  String lyric = getCurrentLyric(currentPositionMs);

  if (lyric.length() == 0) {
    showText("...");
  } else {
    currentText = lyric;
    showText(currentText);
  }
}

// =====================================================
// HOME
// =====================================================

void handleRoot() {
  String html;

  html += "<!DOCTYPE html>";
  html += "<html>";
  html += "<head>";
  html += "<meta name='viewport' content='width=device-width,initial-scale=1'>";
  html += "<title>LYRIX</title>";
  html += "</head>";
  html += "<body style='font-family:Arial;text-align:center;padding:30px'>";
  html += "<h1>LYRIX</h1>";
  html += "<p>ESP32 OLED Receiver</p>";
  html += "<p><b>Status:</b> ONLINE</p>";
  html += "<p><b>IP:</b> ";
  html += WiFi.localIP().toString();
  html += "</p>";
  html += "<p><b>Mode:</b> ";
  html += bitmapMode ? "BITMAP" : "TEXT";
  html += "</p>";
  html += "<p><b>Lyrics:</b> ";
  html += lyricsLoaded ? "LOADED" : "NONE";
  html += "</p>";
  html += "<hr>";

  html += "<form action='/display' method='GET'>";
  html += "<input name='text' placeholder='Enter text' style='font-size:18px;padding:10px'>";
  html += "<br><br>";
  html += "<button type='submit' style='font-size:18px;padding:10px 25px'>";
  html += "DISPLAY";
  html += "</button>";
  html += "</form>";

  html += "</body>";
  html += "</html>";

  server.send(200, "text/html", html);
}

// =====================================================
// MANUAL DISPLAY ENDPOINT (ASCII)
// =====================================================

void handleDisplay() {
  if (!server.hasArg("text")) {
    server.send(400, "text/plain", "Missing text");
    return;
  }

  currentText = server.arg("text");
  showText(currentText);

  server.send(200, "text/plain", "Displayed: " + currentText);

  Serial.print("DISPLAY: ");
  Serial.println(currentText);
}

// =====================================================
// RECEIVE LRC (ASCII path)
// =====================================================

void handleLyrics() {
  if (server.method() != HTTP_POST) {
    server.send(405, "text/plain", "Use POST");
    return;
  }

  String lrc = server.arg("plain");

  if (lrc.length() == 0) {
    server.send(400, "text/plain", "Empty LRC");
    return;
  }

  Serial.println();
  Serial.println("======================");
  Serial.println("NEW LYRICS RECEIVED");
  Serial.print("LRC size: ");
  Serial.println(lrc.length());

  parseLRC(lrc);

  currentPositionMs = 0;
  isPlaying = false;

  if (lyricsLoaded) {
    showText("LYRICS READY");
    server.send(200, "text/plain", "Lyrics loaded: " + String(lyricCount) + " lines");
  } else {
    showText("NO LYRICS");
    server.send(400, "text/plain", "Could not parse LRC");
  }
}

// =====================================================
// RECEIVE BITMAP (Bangla / phone-rendered path)
// =====================================================
//
// Body: BMP_HEX_LEN (1664) hex characters, decoding to
// BMP_TOTAL_BYTES (832) bytes = a packed 128x52 1bpp bitmap.
// =====================================================

void handleBitmap() {
  if (server.method() != HTTP_POST) {
    server.send(405, "text/plain", "Use POST");
    return;
  }

  String hex = server.arg("plain");

  if (!hexDecode(hex, lyricBitmap, BMP_TOTAL_BYTES)) {
    server.send(
      400,
      "text/plain",
      "Expected " + String(BMP_HEX_LEN) + " hex chars (got " + String(hex.length()) + ")"
    );
    return;
  }

  showBitmap();

  server.send(200, "text/plain", "Bitmap displayed");

  Serial.println("BITMAP: displayed new frame");
}

// =====================================================
// RECEIVE PLAYBACK POSITION
// =====================================================
//
// Android calls:
// /position?ms=12345&playing=1
//
// =====================================================

void handlePosition() {
  if (!server.hasArg("ms")) {
    server.send(400, "text/plain", "Missing ms");
    return;
  }

  currentPositionMs = server.arg("ms").toInt();

  if (server.hasArg("playing")) {
    isPlaying = server.arg("playing") == "1";
  }

  // Only redraw from the LRC/text pipeline if we're not in bitmap
  // mode - a bitmap push is a fire-and-forget frame from the phone,
  // so just refresh the header (play indicator) over the last frame.
  if (bitmapMode) {
    showBitmap();
  } else {
    updateCurrentLyric();
  }

  server.send(200, "text/plain", "OK");
}

// =====================================================
// CLEAR
// =====================================================

void handleClear() {
  lyricCount = 0;
  lyricsLoaded = false;
  currentPositionMs = 0;
  isPlaying = false;
  bitmapMode = false;

  memset(lyricBitmap, 0, BMP_TOTAL_BYTES);

  currentText = "LYRIX READY";
  currentSong = "";
  currentArtist = "";

  showText(currentText);

  server.send(200, "text/plain", "Cleared");

  Serial.println("Lyrics cleared");
}

// =====================================================
// STATUS
// =====================================================

void handleStatus() {
  String json = "{";

  json += "\"device\":\"LYRIX\",";
  json += "\"status\":\"online\",";
  json += "\"ip\":\"";
  json += WiFi.localIP().toString();
  json += "\",";
  json += "\"mode\":\"";
  json += bitmapMode ? "bitmap" : "text";
  json += "\",";
  json += "\"lyricsLoaded\":";
  json += lyricsLoaded ? "true," : "false,";
  json += "\"lineCount\":";
  json += String(lyricCount);
  json += ",";
  json += "\"positionMs\":";
  json += String(currentPositionMs);
  json += ",";
  json += "\"playing\":";
  json += isPlaying ? "true," : "false,";
  json += "\"text\":\"";

  String safeText = currentText;
  safeText.replace("\\", "\\\\");
  safeText.replace("\"", "\\\"");
  safeText.replace("\n", "\\n");

  json += safeText;
  json += "\"";
  json += "}";

  server.send(200, "application/json", json);
}

// =====================================================
// SETUP
// =====================================================

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("======================");
  Serial.println("LYRIX ESP32 RECEIVER");
  Serial.println("======================");

  // ===================================================
  // OLED
  // ===================================================

  Wire.begin(OLED_SDA, OLED_SCL);

  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDRESS)) {
    Serial.println("OLED FAILED");
    while (true) {
      delay(1000);
    }
  }

  Serial.println("OLED OK");

  display.setTextWrap(false);

  showText("Starting...");

  // ===================================================
  // Wi-Fi
  // ===================================================

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to Wi-Fi");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("Wi-Fi connected!");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  // ===================================================
  // OLED IP SCREEN
  // ===================================================

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("LYRIX ONLINE");
  display.println();
  display.println("IP:");
  display.println(WiFi.localIP());
  display.display();

  delay(3000);

  showText("LYRIX READY");

  // ===================================================
  // ROUTES
  // ===================================================

  server.on("/", HTTP_GET, handleRoot);
  server.on("/display", HTTP_GET, handleDisplay);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/lyrics", HTTP_POST, handleLyrics);
  server.on("/bitmap", HTTP_POST, handleBitmap);
  server.on("/position", HTTP_GET, handlePosition);
  server.on("/clear", HTTP_GET, handleClear);

  server.begin();

  Serial.println("HTTP server started");
  Serial.println("LYRIX receiver ready!");
}

// =====================================================
// LOOP
// =====================================================

void loop() {
  server.handleClient();
}

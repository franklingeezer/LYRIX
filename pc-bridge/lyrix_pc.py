"""
LYRIX PC Bridge (Windows)
=========================

Watches whatever's currently playing on Windows via System Media
Transport Controls - Spotify desktop, a YouTube/YouTube Music tab
in Edge or Chrome, or anything else that registers with Windows'
media controls (the same API volume/media keys use - no API keys
needed). Fetches synced lyrics from lrclib.net, and pushes them to
your LYRIX ESP32 exactly like the Android app does - including the
same "push exactly on timestamp" scheduler and Bangla-bitmap
rendering.

SETUP
-----
1. pip install -r requirements.txt
2. Edit ESP32_IP below to match your device.
3. Play something (Spotify desktop, or a YouTube tab in Edge/Chrome).
4. python lyrix_pc.py

If Bangla lines come out blank/garbled, you likely don't have a
Bangla-capable font at one of the FONT_CANDIDATES paths - point
FONT_CANDIDATES at a Bangla TTF you have (e.g. Noto Sans Bengali).
"""

import asyncio
import os
import re
import time
from datetime import datetime, timezone

import requests
from PIL import Image, ImageDraw, ImageFont

from winsdk.windows.media.control import (
    GlobalSystemMediaTransportControlsSessionManager as MediaManager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as PlaybackStatus,
)


# =====================================================
# CONFIG
# =====================================================

# Your ESP32's current IP address (same one the Android app uses).
ESP32_IP = "10.224.45.70"
BASE_URL = f"http://{ESP32_IP}"

# If more than one app is playing at once, prefer these (checked
# against the app's AppUserModelId, case-insensitive) - otherwise
# whichever app is actually playing gets picked automatically,
# Spotify, a YouTube tab in Edge/Chrome, or anything else Windows
# tracks through its media controls.
PREFERRED_APP_HINTS = ["spotify"]

# Must match BMP_WIDTH / BMP_HEIGHT in the ESP32 sketch exactly.
BITMAP_WIDTH = 128
BITMAP_HEIGHT = 52

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\Nirmala.ttf",   # Nirmala UI - ships with Windows, covers Bengali
    r"C:\Windows\Fonts\NirmalaS.ttf",
    r"C:\Windows\Fonts\NirmalaB.ttf",
]
FONT_SIZE = 18
MAX_LINES = 2


# =====================================================
# BANGLA DETECTION + RENDERING
# =====================================================

def is_bangla(text: str) -> bool:
    return any(0x0980 <= ord(ch) <= 0x09FF for ch in text)


_font_cache = {}


def _load_font():
    if "font" in _font_cache:
        return _font_cache["font"]

    path = next((p for p in FONT_CANDIDATES if os.path.exists(p)), None)

    if path is None:
        raise RuntimeError(
            "No Bangla-capable font found at any of FONT_CANDIDATES. "
            "Edit FONT_CANDIDATES in this script to point at a Bangla "
            "TTF (e.g. Noto Sans Bengali)."
        )

    layout_engine = getattr(
        getattr(ImageFont, "Layout", None), "RAQM", None
    ) or getattr(ImageFont, "LAYOUT_RAQM", None)

    kwargs = {"layout_engine": layout_engine} if layout_engine is not None else {}

    font = ImageFont.truetype(path, FONT_SIZE, **kwargs)
    _font_cache["font"] = font
    return font


def _wrap_lines(draw, font, text, max_width, max_lines):
    words = text.split()
    lines = []
    line = ""

    for word in words:
        candidate = f"{line} {word}".strip()
        bbox = draw.textbbox((0, 0), candidate, font=font)
        w = bbox[2] - bbox[0]

        if w <= max_width or not line:
            line = candidate
        else:
            lines.append(line)
            if len(lines) >= max_lines:
                return lines[:max_lines]
            line = word

    if line:
        lines.append(line)

    return lines[:max_lines]


def render_to_hex(text: str) -> str:
    """Renders `text` to the packed 1bpp hex string the ESP32's
    /bitmap endpoint expects (MSB-first per byte, row by row)."""

    font = _load_font()

    img = Image.new("L", (BITMAP_WIDTH, BITMAP_HEIGHT), color=0)
    draw = ImageDraw.Draw(img)

    lines = _wrap_lines(draw, font, text, BITMAP_WIDTH - 4, MAX_LINES)

    line_bboxes = [draw.textbbox((0, 0), ln, font=font) for ln in lines]
    line_heights = [b[3] - b[1] for b in line_bboxes]
    total_height = sum(line_heights) + (len(lines) - 1) * 2  # 2px gap between lines

    y = max(0, (BITMAP_HEIGHT - total_height) // 2)

    for ln, bbox, h in zip(lines, line_bboxes, line_heights):
        w = bbox[2] - bbox[0]
        x = max(0, (BITMAP_WIDTH - w) // 2)
        draw.text((x, y - bbox[1]), ln, font=font, fill=255)
        y += h + 2

    bytes_per_row = (BITMAP_WIDTH + 7) // 8
    packed = bytearray(bytes_per_row * BITMAP_HEIGHT)
    pixels = img.load()

    for py in range(BITMAP_HEIGHT):
        for px in range(BITMAP_WIDTH):
            if pixels[px, py] > 127:
                byte_index = py * bytes_per_row + (px // 8)
                bit_index = 7 - (px % 8)
                packed[byte_index] |= (1 << bit_index)

    return packed.hex()


# =====================================================
# LRC PARSING
# =====================================================

_LRC_TIME_RE = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\]")


def parse_lrc(lrc_text: str):
    """Returns a sorted list of (time_ms, text) tuples."""

    entries = []

    for raw_line in lrc_text.splitlines():
        matches = list(_LRC_TIME_RE.finditer(raw_line))
        if not matches:
            continue

        text = _LRC_TIME_RE.sub("", raw_line).strip()
        if not text:
            continue

        for m in matches:
            minutes = int(m.group(1))
            seconds = float(m.group(2))
            time_ms = int(minutes * 60000 + seconds * 1000)
            entries.append((time_ms, text))

    entries.sort(key=lambda e: e[0])
    return entries


def fetch_lyrics(track_name: str, artist_name: str):
    try:
        r = requests.get(
            "https://lrclib.net/api/get",
            params={"track_name": track_name, "artist_name": artist_name},
            headers={"User-Agent": "LYRIX-PC/0.1"},
            timeout=4,
        )
        if r.status_code != 200:
            return None

        data = r.json()
        return (data.get("syncedLyrics") or "").strip() or None

    except requests.RequestException:
        return None


# =====================================================
# ESP32 HTTP CALLS
# =====================================================

def send_lyrics(lrc: str) -> bool:
    try:
        r = requests.post(
            f"{BASE_URL}/lyrics",
            data=lrc.encode("utf-8"),
            headers={"Content-Type": "text/plain; charset=UTF-8"},
            timeout=5,
        )
        return r.ok
    except requests.RequestException:
        return False


def send_bitmap(hex_str: str) -> bool:
    try:
        r = requests.post(
            f"{BASE_URL}/bitmap",
            data=hex_str.encode("ascii"),
            headers={"Content-Type": "text/plain; charset=UTF-8"},
            timeout=5,
        )
        return r.ok
    except requests.RequestException:
        return False


def send_display(text: str) -> bool:
    try:
        r = requests.get(f"{BASE_URL}/display", params={"text": text}, timeout=3)
        return r.ok
    except requests.RequestException:
        return False


def send_position(position_ms: int, playing: bool) -> bool:
    try:
        r = requests.get(
            f"{BASE_URL}/position",
            params={"ms": position_ms, "playing": "1" if playing else "0"},
            timeout=3,
        )
        return r.ok
    except requests.RequestException:
        return False


# =====================================================
# WINDOWS NOW-PLAYING (any app - Spotify, a browser tab, etc.)
# =====================================================

def _session_sort_key(session):
    aumid = (session.source_app_user_model_id or "").lower()

    try:
        playing = (
            session.get_playback_info().playback_status
            == PlaybackStatus.PLAYING
        )
    except Exception:
        playing = False

    preferred = any(hint in aumid for hint in PREFERRED_APP_HINTS)

    return (playing, preferred)


async def _get_active_session():
    manager = await MediaManager.request_async()
    sessions = list(manager.get_sessions())

    if not sessions:
        return None

    # Prefer an actually-playing session; among those, prefer apps in
    # PREFERRED_APP_HINTS if more than one happens to be playing at
    # once. Falls back to the first session found otherwise.
    sessions.sort(key=_session_sort_key, reverse=True)

    return sessions[0]


_TITLE_CLEAN_RE = re.compile(
    r"\s*[\(\[]\s*(official\s*(music\s*)?video|official\s*audio|"
    r"official\s*lyric\s*video|lyrics?|lyric\s*video|audio|hd|4k|"
    r"visualizer|explicit)\s*[\)\]]\s*",
    re.IGNORECASE,
)


def _guess_artist_title(title: str, artist: str):
    """YouTube tabs usually leave `artist` blank and put everything in
    the video title, often as "Artist - Song (Official Video)". Strip
    the common junk and split on " - " when we have to guess, so
    lyric lookups actually stand a chance of matching."""

    cleaned = _TITLE_CLEAN_RE.sub(" ", title)
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" -|")
    cleaned = cleaned or title

    if not artist and " - " in cleaned:
        guessed_artist, guessed_title = cleaned.split(" - ", 1)
        return guessed_artist.strip(), guessed_title.strip()

    return artist, cleaned


async def read_now_playing():
    try:
        session = await _get_active_session()
        if session is None:
            return None

        props = await session.try_get_media_properties_async()
        timeline = session.get_timeline_properties()
        playback_info = session.get_playback_info()

        title = (props.title or "").strip()
        artist = (props.artist or "").strip()

        playing = playback_info.playback_status == PlaybackStatus.PLAYING

        position_ms = int(timeline.position.total_seconds() * 1000)
        last_updated = timeline.last_updated_time

        try:
            rate = float(playback_info.playback_rate)
            if rate <= 0:
                rate = 1.0
        except (TypeError, ValueError):
            rate = 1.0

        return {
            "title": title,
            "artist": artist,
            "playing": playing,
            "position_ms": max(0, position_ms),
            "last_updated": last_updated,
            "rate": rate,
        }

    except Exception:
        return None


def estimate_position_ms(info: dict) -> int:
    """Live position, extrapolated from the last SMTC update instead
    of used as a stale snapshot - same idea as the Android app's
    PlaybackPositionEstimator."""

    if not info["playing"] or info["last_updated"] is None:
        return info["position_ms"]

    now = datetime.now(timezone.utc)
    elapsed_s = (now - info["last_updated"]).total_seconds()
    elapsed_s = max(0.0, elapsed_s)

    estimated = info["position_ms"] + elapsed_s * 1000 * info["rate"]
    return max(0, int(estimated))


# =====================================================
# MAIN LOOP
# =====================================================

async def main():
    print(f"LYRIX PC bridge - watching Spotify, sending to {BASE_URL}")

    last_track_key = None
    lyrics = []
    current_index = -1
    last_position_ping = 0.0

    while True:
        info = await read_now_playing()

        if info is None or not info["title"]:
            await asyncio.sleep(1.0)
            continue

        track_key = f"{info['title']}::{info['artist']}"

        if track_key != last_track_key:
            last_track_key = track_key
            current_index = -1

            print(f"Now playing: {info['title']} - {info['artist']}")

            search_artist, search_title = _guess_artist_title(
                info["title"], info["artist"]
            )

            if (search_artist, search_title) != (info["artist"], info["title"]):
                print(f"  Searching lyrics as: {search_title} - {search_artist}")

            lrc = fetch_lyrics(search_title, search_artist)

            if lrc:
                lyrics = parse_lrc(lrc)
                send_lyrics(lrc)
                print(f"  Loaded {len(lyrics)} synced lines")
            else:
                lyrics = []
                print("  No synced lyrics found")

        now_ms = estimate_position_ms(info)

        # Keepalive / play-pause indicator refresh, throttled to ~1s -
        # independent of the line-change scheduling below.
        loop_time = time.monotonic()
        if loop_time - last_position_ping >= 1.0:
            send_position(now_ms, info["playing"])
            last_position_ping = loop_time

        if lyrics:
            new_index = -1
            for i, (t, _) in enumerate(lyrics):
                if t <= now_ms:
                    new_index = i
                else:
                    break

            if new_index != current_index and new_index >= 0:
                current_index = new_index
                line_text = lyrics[current_index][1]

                if is_bangla(line_text):
                    hex_str = render_to_hex(line_text)
                    send_bitmap(hex_str)
                else:
                    send_display(line_text)

            next_time = (
                lyrics[current_index + 1][0]
                if current_index + 1 < len(lyrics)
                else None
            )

            if info["playing"] and next_time is not None:
                sleep_s = max(0.02, min(0.4, (next_time - now_ms) / 1000))
            else:
                sleep_s = 0.25
        else:
            sleep_s = 1.0

        await asyncio.sleep(sleep_s)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nStopped.")

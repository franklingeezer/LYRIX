# LYRIX PC Bridge

Windows-only. Watches whatever's playing (Spotify desktop, a YouTube
tab in Edge/Chrome, etc.) via Windows' System Media Transport
Controls, fetches synced lyrics from lrclib.net, and pushes them to
the same ESP32 the Android app talks to.

## Setup

```
py -3.12 -m pip install -r requirements.txt
```

`winsdk` (reads now-playing info from Windows) doesn't always have a
prebuilt wheel for the newest Python release yet - if install fails
trying to compile it, install Python 3.12 alongside whatever you have
(`py install 3.12`) and use `py -3.12` instead of `py`/`python`
throughout.

Edit `ESP32_IP` near the top of `lyrix_pc.py` to match your device,
then:

```
py -3.12 lyrix_pc.py
```

Play something and watch the console - it prints what it detects and
whether synced lyrics were found.

If Bangla lines come out blank, check the `FONT_CANDIDATES` paths at
the top of the script point at a font that's actually installed on
your machine (defaults to Windows' built-in Nirmala UI).

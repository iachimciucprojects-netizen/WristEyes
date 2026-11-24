# Contributing to WristEyes

Thanks for wanting to help! Small fixes are as welcome as big features.

## Easiest ways to help

- **Test on your watch.** Open an issue with your model and what worked. This alone helps a lot.
- **Add voice commands** or a new language for the assistant (see below).
- Pick a [good first issue](https://github.com/iachimciucprojects-netizen/WristEyes/labels/good%20first%20issue).

## Project layout

```
app/                    Wear OS app (Kotlin, no UI framework, no extra libraries except Vosk)
  HttpServer.kt         tiny HTTP server, token check
  WatchService.kt       all API routes: /info, /sensor, /vibrate, /show, /say...
  SensorReader.kt       reads one sensor value on demand
  Commands.kt           offline voice commands (keyword matching, Russian)
  AssistantActivity.kt  voice assistant screen (Vosk speech recognition)
mcp/server.py           MCP server that turns the HTTP API into tools
```

## Build and run

You need Android Studio (for the SDK and its bundled JDK) and a Wear OS watch or emulator.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The MCP server:

```bash
cd mcp && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
WRISTEYES_URL=http://<watch-ip>:8765 WRISTEYES_TOKEN=<token> .venv/bin/python server.py
```

## Adding an API command

1. Add a route in `WatchService.route()` and a small function for it.
2. Add a matching tool in `mcp/server.py`.
3. Add a row to the tables in `README.md`.

## Adding a voice command

1. Add a line to `Commands.handle()` with the keywords and the answer.
2. Add every new word to `Commands.VOCABULARY`, otherwise the recognizer won't hear it.

## Pull requests

- One topic per pull request, a short description of what and why.
- Say which watch you tested on.
- Keep the app free of heavy libraries; the whole point is that it stays tiny.

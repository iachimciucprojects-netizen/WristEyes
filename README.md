<p align="center"><img src="assets/logo.png" width="150" alt="WristEyes logo"></p>

<h1 align="center">WristEyes</h1>

<p align="center"><b>MCP server for Wear OS watches.</b><br>
Let Claude, Cursor or any AI assistant read your heart rate, buzz your wrist and talk to you.</p>

<p align="center">
  <a href="https://github.com/iachimciucprojects-netizen/WristEyes/releases/latest"><img src="https://img.shields.io/github/v/release/iachimciucprojects-netizen/WristEyes?label=APK" alt="Latest APK"></a>
  <img src="https://img.shields.io/badge/Wear%20OS-3%2B-4285F4?logo=wearos&logoColor=white" alt="Wear OS 3+">
  <img src="https://img.shields.io/badge/MCP-ready-6C63FF" alt="MCP ready">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license">
</p>

<p align="center">
  <img src="assets/face.gif" width="240" alt="The WristEyes face: tap it, ears pop out, it listens and answers">
  &nbsp;&nbsp;
  <img src="assets/demo.gif" width="240" alt="Claude greets you, reads your pulse and compass on the watch">
</p>

```
You:     what's my heart rate?
Claude:  (asks your watch)  Your heart rate is 68 bpm.
```

## Why

Your watch has a heart rate sensor, a barometer, a compass, a speaker and a vibration motor —
and it sits on your wrist all day. WristEyes opens all of that to your own scripts and AI tools,
over your home Wi-Fi, with nothing in the cloud.

- 🫀 **Read sensors**: heart rate, steps, pressure and altitude, compass, light, on-wrist, motion
- 📳 **Reach you**: vibrate, notify, show big text on screen, speak out loud
- 🤖 **Works with AI**: a ready MCP server for Claude Code, Claude Desktop, Cursor and others
- 👀 **A face that's alive**: eyes blink and look around, ears pop out when it listens, it talks back
- 🎤 **Offline voice assistant** on the watch, English and Russian: pulse, steps, timers, compass, weather…
- 🙋 **Knows you**: asks your name once, says good morning, good evening and good night at the right time
- 🔒 **Local only**: plain HTTP on your LAN with a token, no accounts, no tracking
- 🪶 **Tiny**: no dependencies in the server, a few small Kotlin files

## Try it in 2 minutes

**1. Install the watch app.** Turn on *Wireless debugging* on the watch
(Settings → Developer options), then:

```bash
adb connect <watch-ip>:<port>
adb install wristeyes-0.4.0.apk        # from the Releases page
```

Open **WristEyes** on the watch and allow the permissions. Tap the face: the first time it downloads
its voice (~45 MB over Wi-Fi) and asks your name. **Long-press the face** for settings: your name,
the address (`192.168.x.x:8765`) and the token.

**2. Check it answers:**

```bash
curl -H "Authorization: Bearer <token>" http://192.168.x.x:8765/info
```

**3. Connect your AI** (Python 3.10+):

```bash
git clone https://github.com/iachimciucprojects-netizen/WristEyes && cd WristEyes/mcp
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
claude mcp add wristeyes --scope user \
  -e WRISTEYES_URL=http://192.168.x.x:8765 \
  -e WRISTEYES_TOKEN=<token> \
  -- "$PWD/.venv/bin/python" "$PWD/server.py"
```

Now ask Claude *"what's my heart rate?"* or *"show 'time to sleep' on my watch"*.
Claude Desktop, Cursor and other clients take the same command and env in their MCP config.

<details>
<summary><b>Optional: full-screen text from the background</b></summary>

Android blocks apps from opening screens in the background. Allow it once and `show_text`
opens full screen even when WristEyes is closed:

```bash
adb shell appops set com.wristeyes SYSTEM_ALERT_WINDOW allow
```
</details>

<details>
<summary><b>The voice assistant</b></summary>

Tap the face and talk. Recognition runs fully offline with [Vosk](https://alphacephei.com/vosk/),
in English or Russian depending on the watch language. The model (~45 MB) downloads by itself on
the first tap.

Try: *"what's my heart rate"*, *"how many steps"*, *"what time is it"*, *"battery"*,
*"which way is north"*, *"timer for five minutes"*, *"what's the weather"*, *"weather tomorrow"*,
*"good night"*, *"my name is …"*.

No AI here: phrases are matched to commands, so it is instant, free and private.
The only things that go online are the model download and weather questions
([Open-Meteo](https://open-meteo.com), no account; if the watch has no location fix,
the city is guessed from your IP with [ipwho.is](https://ipwho.is)).
</details>

## MCP tools

| Tool | What happens |
|---|---|
| `watch_info` | model, Android version, battery, charging, screen on/off |
| `list_sensors` | which sensors this watch has |
| `read_sensor` | heart rate, steps, pressure + altitude, compass, light, on-wrist, accelerometer, gyroscope, magnetometer |
| `vibrate` | buzz the watch (length and count) |
| `notify` | post a notification |
| `show_text` | big text on the whole screen |
| `say` | speak text through the watch speaker |

## HTTP API

Use it from any language, shell script, Home Assistant or n8n.
Every call except `GET /` needs `Authorization: Bearer <token>`.

| Method | Path | Body / query |
|---|---|---|
| GET | `/info` | |
| GET | `/sensors` | |
| GET | `/sensor` | `?type=heart_rate&timeout=30` |
| POST | `/vibrate` | `{"ms":400,"times":1}` |
| POST | `/notify` | `{"title":"...","text":"..."}` |
| POST | `/show` | `{"text":"...","seconds":8}` |
| POST | `/say` | `{"text":"...","lang":"en"}` |

```bash
curl -H "Authorization: Bearer $TOKEN" "http://192.168.x.x:8765/sensor?type=heart_rate"
curl -H "Authorization: Bearer $TOKEN" -d '{"text":"Stand up! 🧍"}' http://192.168.x.x:8765/show
```

The watch also announces itself on the network as `_wristeyes._tcp`.

## Ideas for what to build

- "Buzz me when the build finishes" in your terminal
- Your AI coding agent asks for approval with a tap on the wrist
- Log your heart rate during meetings, games or workouts
- A stand-up or water reminder that actually reaches you
- Home Assistant: show who rang the doorbell on your wrist

## Compatibility

| Watch | Status |
|---|---|
| TicWatch Pro 5 (Wear OS 4) | ✅ tested |
| Galaxy Watch, Pixel Watch, other Wear OS 3+ | should work, [tell us](https://github.com/iachimciucprojects-netizen/WristEyes/issues) |

## Notes

- Meant for your home network. Anyone with the token can buzz your watch, so keep it private.
- While running, the app keeps Wi-Fi and a wake lock, which costs some battery. Stop it in the app when you don't need it.
- Heart rate needs the watch on your wrist and takes 5–30 seconds.

## Roadmap

- [x] Animated eyes that react to commands
- [x] Voice model downloads itself, no adb needed
- [x] Voice commands in English
- [x] Knows your name, greets you by the time of day, weather
- [ ] Voice commands in Romanian
- [ ] `pip install` / `uvx` package for the MCP server
- [ ] Music control, reminders, files
- [ ] Ask the user a question on the watch and wait for the tap

## Contributing

Ideas, bug reports and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md)
and the [good first issues](https://github.com/iachimciucprojects-netizen/WristEyes/labels/good%20first%20issue).
If WristEyes works on your watch, a ⭐ and a note about your model help a lot.

## License

MIT
